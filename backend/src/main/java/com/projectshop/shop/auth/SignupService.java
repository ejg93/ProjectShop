package com.projectshop.shop.auth;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.projectshop.shop.audit.AuditLog;

/**
 * 계정을 만들고 그 자리에서 동의 사건을 남긴다.
 *
 * <p>둘이 한 트랜잭션인 이유가 있다. 계정만 생기고 동의가 빠지면
 * <b>동의 없이 개인정보를 들고 있는 계정</b>이 되고, 그 상태를 나중에 알아볼 방법이 없다.
 * 반대로 동의만 남고 계정이 없으면 주인 없는 기록이 된다.
 */
@Service
public class SignupService {

    /** 가입하면 자동으로 붙는 역할. 아무 역할도 없으면 로그인해도 할 수 있는 것이 없다. */
    private static final String DEFAULT_ROLE = "customer";

    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final AuditLog auditLog;

    public SignupService(JdbcClient jdbc, PasswordEncoder passwordEncoder, AuditLog auditLog) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.auditLog = auditLog;
    }

    /**
     * @param consents 항목 코드 → 동의 여부. <b>담긴 것만</b> 기록한다.
     *                 안 담긴 선택 항목은 행이 안 생긴다 — 거부와 갈라 두려는 것이다(5-0).
     */
    public record Command(
            String email,
            String password,
            String displayName,
            Map<String, Boolean> consents,
            String actorIp) {
    }

    @Transactional
    public long signUp(Command command) {
        List<ConsentItem> items = currentConsentItems();
        verifyConsents(command.consents(), items);

        long userId = insertUser(command);
        grantDefaultRole(userId);
        recordConsents(userId, command, items);

        auditLog.record("user.signed_up", userId, AuditLog.Target.of("user", userId),
                Map.of("role_code", DEFAULT_ROLE, "consent_count", command.consents().size()));

        return userId;
    }

    /**
     * 지금 효력이 있는 판만 코드별로 하나씩 고른다.
     *
     * <p>개정판을 미리 넣어 둘 수 있어서 {@code effective_at} 을 본다.
     * 이 조건이 없으면 아직 시작 안 한 약관에 동의를 받게 된다.
     */
    private List<ConsentItem> currentConsentItems() {
        return jdbc.sql("""
                        select distinct on (c.code)
                               c.consent_item_id, c.code, c.is_required as required,
                               p.code as depends_on_code
                          from consent_item c
                          left join consent_item p on p.consent_item_id = c.depends_on_id
                         where c.effective_at <= now()
                         order by c.code, c.effective_at desc, c.version desc
                        """)
                .query(ConsentItem.class)
                .list();
    }

    private void verifyConsents(Map<String, Boolean> given, List<ConsentItem> items) {
        Map<String, ConsentItem> byCode = new LinkedHashMap<>();
        items.forEach(item -> byCode.put(item.code(), item));

        for (String code : given.keySet()) {
            if (!byCode.containsKey(code)) {
                throw unprocessable("모르는 동의 항목이다: " + code);
            }
        }

        for (ConsentItem item : items) {
            if (item.required() && !Boolean.TRUE.equals(given.get(item.code()))) {
                throw unprocessable("필수 동의 항목이다: " + item.code());
            }

            // 야간 수신은 채널 수신에 걸린다(R14). 채널을 거부한 사람에게 야간만 켜 주면
            // 보낼 수 없는 동의가 남고, 나중에 채널만 켜지는 순간 야간까지 열린다.
            boolean wants = Boolean.TRUE.equals(given.get(item.code()));
            if (wants && item.dependsOnCode() != null
                    && !Boolean.TRUE.equals(given.get(item.dependsOnCode()))) {
                throw unprocessable(
                        item.code() + " 는 " + item.dependsOnCode() + " 에 동의해야 받을 수 있다");
            }
        }
    }

    private long insertUser(Command command) {
        // 이메일 중복을 미리 조회해서 막지 않는다. 조회와 삽입 사이에 남이 끼어들 수 있어서
        // 어차피 유니크 인덱스가 최종 판단이다. 둘 다 두면 같은 규칙이 두 군데가 된다.
        try {
            return jdbc.sql("""
                            insert into app_user (email, password_hash, display_name)
                            values (:email, :passwordHash, :displayName)
                            returning user_id
                            """)
                    .param("email", command.email())
                    .param("passwordHash", passwordEncoder.encode(command.password()))
                    .param("displayName", command.displayName())
                    .query(Long.class)
                    .single();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 가입된 이메일이다");
        }
    }

    private void grantDefaultRole(long userId) {
        jdbc.sql("""
                        insert into user_role (user_id, role_id)
                        select :userId, role_id from role where code = :code
                        """)
                .param("userId", userId)
                .param("code", DEFAULT_ROLE)
                .update();
    }

    private void recordConsents(long userId, Command command, List<ConsentItem> items) {
        Map<String, Long> idByCode = new LinkedHashMap<>();
        items.forEach(item -> idByCode.put(item.code(), item.consentItemId()));

        command.consents().forEach((code, granted) -> jdbc.sql("""
                        insert into user_consent (user_id, consent_item_id, granted, source, acted_ip)
                        values (:userId, :itemId, :granted, 'signup', cast(:actorIp as inet))
                        """)
                .param("userId", userId)
                .param("itemId", idByCode.get(code))
                .param("granted", granted)
                .param("actorIp", command.actorIp())
                .update());
    }

    private ResponseStatusException unprocessable(String detail) {
        // 형식은 맞는데 값이 규칙에 안 맞는 자리라 422 다(D5).
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, detail);
    }

    /**
     * 불리언의 {@code is_} 는 Java 에서 뗀다(`D22`). 게터가 {@code isRequired()} 라 두 번 붙는다.
     *
     * <p>그래서 조회 SQL 이 {@code is_required as required} 로 별칭을 준다 —
     * {@code query(ConsentItem.class)} 가 컬럼명으로 생성자 인자를 맞추기 때문이다.
     *
     * @param dependsOnCode 먼저 동의해야 하는 항목의 코드. 걸린 것이 없으면 null.
     */
    public record ConsentItem(long consentItemId, String code, boolean required, String dependsOnCode) {
    }
}
