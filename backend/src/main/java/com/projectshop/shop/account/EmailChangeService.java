package com.projectshop.shop.account;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.projectshop.shop.audit.AuditLog;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

/**
 * 이메일을 바꾸기 전에 <b>그 주소를 받을 수 있는지</b> 확인한다(`5e-1`, `D2` R28).
 *
 * <h2>왜 바로 안 바꾸나</h2>
 *
 * <p>지금까지는 치는 즉시 반영됐다. <b>오타를 넣으면 그 계정으로는 아무것도 못 받는다</b> —
 * 바뀌었다는 알림조차 없는 주소로 간다. 대기 주소를 따로 두면 <b>쓰던 주소가 살아 있어서</b>
 * 링크가 안 와도 잃는 것이 없다.
 *
 * <h2>비밀번호 재확인과 다른 물음이다</h2>
 *
 * <p>{@link AccountService#changeEmail} 이 이미 현재 비밀번호를 받는다 — 그것은 「본인이 맞나」다.
 * 여기서 더하는 것은 <b>「그 주소가 그 사람 것인가」</b>고, 둘 다 있어야 한다.
 *
 * <h2>토큰 다루는 방식은 `5c-1` 과 같다</h2>
 *
 * <p>해시만 담고, 살아 있는 것은 사람마다 하나고, 재사용은 DB 가 막는다({@code V68}).
 * <b>다른 점 하나</b> — 재설정은 계정 존재 여부를 숨기지만 여기는 <b>이미 로그인한 사람</b>이라
 * 숨길 것이 없다. 그래서 이미 쓰는 주소면 그대로 거절한다.
 */
@Service
public class EmailChangeService {

    /** 확인 링크 수명. 비밀번호 재설정보다 길다 — 급히 눌러야 할 이유가 없다 */
    static final Duration LIFETIME = Duration.ofHours(24);

    private static final int TOKEN_BYTES = 32;

    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final EmailChangeMailer mailer;
    private final AuditLog auditLog;
    private final TransactionTemplate transactions;
    private final SecureRandom random = new SecureRandom();

    EmailChangeService(JdbcClient jdbc, PasswordEncoder passwordEncoder, EmailChangeMailer mailer,
            AuditLog auditLog, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.mailer = mailer;
        this.auditLog = auditLog;
        this.transactions = transactions;
    }

    /**
     * 새 주소로 확인 링크를 보낸다. <b>계정은 아직 안 바뀐다.</b>
     *
     * <p>메일은 트랜잭션 밖에서 보낸다 — 안에서 보내면 롤백된 뒤에도 <b>없는 요청의 링크</b>가 나간다.
     */
    public void request(long userId, String newEmail, String confirmUrlTemplate) {
        String token = transactions.execute(status -> issue(userId, newEmail));

        mailer.send(userId, newEmail, confirmUrlTemplate.replace("{token}", token),
                OffsetDateTime.now().plus(LIFETIME));

        // 어느 주소로 바꾸려 했는지는 안 남긴다. 아직 그 사람 것이 아닌 개인정보다(D16).
        auditLog.record(AuditLog.Kind.OUTCOME, "user.email_change_requested", userId,
                AuditLog.Target.of("user", userId), Map.of());
    }

    private String issue(long userId, String newEmail) {
        boolean taken = jdbc.sql("""
                        select exists(
                            select 1 from app_user
                             where lower(email) = lower(:email) and user_id <> :id)
                        """)
                .param("email", newEmail)
                .param("id", userId)
                .query(Boolean.class)
                .single();
        if (taken) {
            throw new ShopException(ErrorCode.EMAIL_TAKEN);
        }

        // 앞 요청을 닫는다. 여럿이 살아 있으면 마지막에 누른 링크가 이기는데,
        // 그 순서를 사용자가 몰라서 어느 주소로 바뀌었는지도 모르게 된다.
        jdbc.sql("""
                        update email_change_request set used_at = now()
                         where user_id = :id and used_at is null
                        """)
                .param("id", userId)
                .update();

        String token = newToken();
        jdbc.sql("""
                        insert into email_change_request (user_id, new_email, token_hash, expires_at)
                        values (:id, :email, :hash, :expiresAt)
                        """)
                .param("id", userId)
                .param("email", newEmail)
                .param("hash", passwordEncoder.encode(token))
                .param("expiresAt", OffsetDateTime.now().plus(LIFETIME))
                .update();
        return token;
    }

    /**
     * 링크를 눌렀다. <b>여기서 계정 주소가 바뀐다.</b>
     *
     * @return 바뀐 주소
     */
    public String confirm(long userId, String token) {
        Request found = liveRequests(userId).stream()
                .filter(candidate -> passwordEncoder.matches(token, candidate.hash()))
                .findFirst()
                .orElseThrow(() -> new ShopException(ErrorCode.EMAIL_CHANGE_TOKEN_INVALID,
                        "쓸 수 없는 확인 토큰이다"));

        int closed = jdbc.sql("""
                        update email_change_request set used_at = now()
                         where email_change_request_id = :id and used_at is null
                        """)
                .param("id", found.id())
                .update();
        if (closed == 0) {
            throw new ShopException(ErrorCode.EMAIL_CHANGE_TOKEN_INVALID, "이미 쓴 확인 토큰이다");
        }

        // 대기하는 사이에 남이 그 주소로 가입했을 수 있다. 유니크 인덱스가 최종 판단이지만
        // 사람이 읽을 문구를 주려고 여기서 먼저 본다.
        jdbc.sql("update app_user set email = :email where user_id = :id and deleted_at is null")
                .param("email", found.newEmail())
                .param("id", userId)
                .update();

        auditLog.record(AuditLog.Kind.OUTCOME, "user.email_changed", userId,
                AuditLog.Target.of("user", userId), Map.of());
        return found.newEmail();
    }

    /**
     * 그 사람의 살아 있는 요청.
     *
     * <p>해시로는 검색이 안 된다 — 인코더가 매번 다른 해시를 만든다. 사람마다 하나뿐이라
     * 이 목록은 길어야 하나다.
     */
    private List<Request> liveRequests(long userId) {
        return jdbc.sql("""
                        select email_change_request_id, new_email, token_hash
                          from email_change_request
                         where user_id = :id and used_at is null and expires_at > now()
                        """)
                .param("id", userId)
                .query((rs, rowNum) -> new Request(rs.getLong("email_change_request_id"),
                        rs.getString("new_email"), rs.getString("token_hash")))
                .list();
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private record Request(long id, String newEmail, String hash) {}
}
