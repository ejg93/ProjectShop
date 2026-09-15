package com.projectshop.shop.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.projectshop.shop.audit.AuditLog;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

/**
 * 비밀번호를 잊은 사람이 1회용 토큰으로 다시 정한다(`5c-1`).
 *
 * <h2>계정이 있는지를 안 흘린다</h2>
 *
 * <p>요청은 <b>가입 여부와 무관하게 같은 것을 돌려준다</b>(`D14` 「응답 문구는 계정 존재 여부를
 * 안 흘린다」). 갈리면 이 입구가 <b>가입 여부를 물어보는 도구</b>가 된다 — 주소 목록을 넣어
 * 어느 것이 우리 회원인지 세는 데 쓴다.
 *
 * <h2>원문은 메일에만 있다</h2>
 *
 * <p>표에는 <b>해시만</b> 넣는다. 표가 새면 그 값으로 남의 비밀번호를 바꿀 수 있어서
 * 비밀번호와 같은 취급을 한다. 로그에도 안 남긴다(`D16` 의 `A09`).
 *
 * <h2>재사용은 DB 가 막는다</h2>
 *
 * <p>「쓴 토큰을 다시 못 쓴다」를 이 클래스에만 두면 <b>새 입구가 생길 때 빠뜨린다.</b>
 * 살아 있는 토큰이 사람마다 하나뿐이라는 것도 부분 유니크 인덱스가 든다(`V67`) —
 * 앱 검증(3위)이 아니라 제약(2위)이다.
 */
@Service
public class PasswordResetService {

    /**
     * 토큰 수명. <b>짧을수록 좋지만 메일이 늦게 도착하는 것도 사실이다</b> —
     * `D14` 가 「유효 시간이 짧다」만 정해서 그 안에서 고른 값이다.
     */
    static final Duration LIFETIME = Duration.ofMinutes(30);

    /** 32바이트. 추측으로 맞힐 수 있는 크기가 아니다 */
    private static final int TOKEN_BYTES = 32;

    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final PasswordResetMailer mailer;
    private final TransactionTemplate transactions;
    private final AuditLog auditLog;
    private final SecureRandom random = new SecureRandom();

    PasswordResetService(JdbcClient jdbc, PasswordEncoder passwordEncoder,
            PasswordResetMailer mailer, AuditLog auditLog, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.mailer = mailer;
        this.auditLog = auditLog;
        this.transactions = transactions;
    }

    /**
     * 재설정을 요청한다. <b>계정이 없어도 티가 안 난다.</b>
     *
     * <p>돌려주는 것이 없다 — 부르는 쪽이 결과로 분기하면 그 분기가 곧 존재 여부다.
     *
     * <p><b>메일은 트랜잭션 밖에서 보낸다.</b> 안에서 부르면 메일 서버가 느릴 때 그만큼
     * DB 잠금을 쥐고 있고, 보낸 뒤 롤백되면 <b>없는 토큰의 링크가 나간다</b> —
     * `ArchitectureTest` 의 「트랜잭션 안에서 PG·메일 안 부른다」가 그 규칙이다.
     */
    public void request(String email, String resetUrlTemplate) {
        Issued issued = transactions.execute(status -> issue(email));
        if (issued == null) {
            // 없는 계정이다. 아무것도 안 하고 같은 응답으로 돌아간다.
            return;
        }

        mailer.send(issued.userId(), resetUrlTemplate.replace("{token}", issued.token()),
                issued.expiresAt());

        // 토큰은 안 남긴다. 남길 것은 요청이 있었다는 사실뿐이다(D16 A09).
        auditLog.record(AuditLog.Kind.OUTCOME, "user.password_reset_requested", issued.userId(),
                AuditLog.Target.of("user", issued.userId()), Map.of());
    }

    /** 표를 고치는 부분. 앞 토큰을 닫고 새것을 넣는 둘이 한 덩이여야 한다 */
    private Issued issue(String email) {
        Optional<Long> userId = jdbc.sql("""
                        select user_id from app_user
                         where lower(email) = lower(:email) and deleted_at is null
                        """)
                .param("email", email)
                .query(Long.class)
                .optional();

        if (userId.isEmpty()) {
            return null;
        }

        // 살아 있는 앞 토큰을 닫는다. 안 닫으면 부분 유니크 인덱스가 새 발급을 막고,
        // 그 실패가 곧 「이 주소는 이미 요청했다」를 흘린다.
        jdbc.sql("""
                        update password_reset_token set used_at = now()
                         where user_id = :id and used_at is null
                        """)
                .param("id", userId.get())
                .update();

        String token = newToken();
        OffsetDateTime expiresAt = OffsetDateTime.now().plus(LIFETIME);

        jdbc.sql("""
                        insert into password_reset_token (user_id, token_hash, expires_at)
                        values (:id, :hash, :expiresAt)
                        """)
                .param("id", userId.get())
                .param("hash", passwordEncoder.encode(token))
                .param("expiresAt", expiresAt)
                .update();

        return new Issued(userId.get(), token, expiresAt);
    }

    /** 트랜잭션 밖으로 나가는 것. <b>원문이 여기 잠깐 실린다</b> — 저장은 안 한다 */
    private record Issued(long userId, String token, OffsetDateTime expiresAt) {}

    /**
     * 토큰으로 비밀번호를 바꾼다.
     *
     * <p><b>현재 비밀번호를 안 묻는다.</b> 그것을 아는 사람은 이 입구가 필요 없다 —
     * 대신 토큰을 가진 것이 본인 확인이라, 토큰을 <b>메일로만</b> 보낸 것이 그 근거다.
     */
    @Transactional
    public void reset(String token, String newPassword) {
        Token found = liveTokens().stream()
                .filter(candidate -> passwordEncoder.matches(token, candidate.hash()))
                .findFirst()
                .orElseThrow(() -> new ShopException(ErrorCode.PASSWORD_RESET_TOKEN_INVALID,
                        "쓸 수 없는 재설정 토큰이다"));

        // 쓴 표시를 먼저 한다. 같은 토큰으로 두 요청이 겹쳐 들어와도 한 번만 통과한다 —
        // 부분 유니크 인덱스가 아니라 이 갱신의 조건이 그것을 막는다.
        int closed = jdbc.sql("""
                        update password_reset_token set used_at = now()
                         where password_reset_token_id = :id and used_at is null
                        """)
                .param("id", found.id())
                .update();
        if (closed == 0) {
            throw new ShopException(ErrorCode.PASSWORD_RESET_TOKEN_INVALID, "이미 쓴 재설정 토큰이다");
        }

        jdbc.sql("update app_user set password_hash = :hash where user_id = :userId")
                .param("hash", passwordEncoder.encode(newPassword))
                .param("userId", found.userId())
                .update();

        auditLog.record(AuditLog.Kind.OUTCOME, "user.password_reset", found.userId(),
                AuditLog.Target.of("user", found.userId()), Map.of());
    }

    /**
     * 아직 살아 있는 토큰들.
     *
     * <p><b>해시로는 검색이 안 된다.</b> 같은 원문이라도 인코더가 매번 다른 해시를 만들어서
     * {@code where token_hash = ?} 로 못 찾는다 — 살아 있는 것만 꺼내 하나씩 맞춰 본다.
     * 사람마다 하나뿐이라 이 목록은 짧다.
     */
    private java.util.List<Token> liveTokens() {
        return jdbc.sql("""
                        select password_reset_token_id, user_id, token_hash
                          from password_reset_token
                         where used_at is null and expires_at > now()
                        """)
                .query((rs, rowNum) -> new Token(rs.getLong("password_reset_token_id"),
                        rs.getLong("user_id"), rs.getString("token_hash")))
                .list();
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private record Token(long id, long userId, String hash) {}
}
