package com.projectshop.shop.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.account.WithdrawalService;
import com.projectshop.shop.audit.AuditLog;
import com.projectshop.shop.error.ShopException;

/**
 * 2차 인증 등록과 검증이 비밀을 싸서 두고 같은 코드를 두 번 안 받는지 잰다(`Q451`, `D2` R45).
 *
 * <p><b>재사용 금지는 조건부 UPDATE 가 쥔다</b>(RFC 6238 §5.2 「MUST NOT accept the second attempt」).
 * 창이 앞뒤 한 스텝이라 같은 코드가 90초 동안 맞는다 — 저장소가 막지 않으면 엿본 코드를 그 안에 다시 낸다.
 */
@DisplayName("2차 인증 등록과 검증")
class SecondFactorServiceTest extends PostgresTestBase {

    private static final String PASSWORD = "second-factor-password";
    private static final byte[] SECRET = "test-second-factor-0".getBytes(StandardCharsets.US_ASCII);
    private static final Instant NOW = Instant.parse("2026-11-13T00:00:00Z");
    /** 시험 기반 클래스가 주는 판 1 의 키 */
    private static final String KEY_V1 = "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8=";
    /** 돌린 뒤의 키 */
    private static final String KEY_V2 = "QEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaW1xdXl8=";

    @Autowired
    SecondFactorService service;

    @Autowired
    WithdrawalService withdrawals;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    AuditLog auditLog;

    @Autowired
    PermissionEvaluator evaluator;

    long userId;

    @BeforeEach
    void setUp() {
        userId = jdbc.sql("""
                        insert into app_user (email, password_hash, display_name)
                        values ('second-factor@test.local', :hash, '이차')
                        returning user_id
                        """)
                .param("hash", passwordEncoder.encode(PASSWORD))
                .query(Long.class)
                .single();
        new AuthFixture(jdbc).grantGlobal(userId, "customer");
    }

    @Test
    @DisplayName("등록 뒤 맞는 코드는 통과한다")
    void acceptsCurrentCode() {
        service.enroll(userId, SECRET, 0);

        assertThat(service.enrolled(userId)).isTrue();
        assertThat(service.verify(userId, codeAt(NOW, 0), NOW)).isTrue();
    }

    @Test
    @DisplayName("같은 코드를 다시 내면 거부한다")
    void rejectsReplay() {
        service.enroll(userId, SECRET, 0);
        String code = codeAt(NOW, 0);

        assertThat(service.verify(userId, code, NOW)).isTrue();
        assertThat(service.verify(userId, code, NOW))
                .as("엿본 코드를 창 안에서 다시 내면 통과하면 안 된다")
                .isFalse();
    }

    @Test
    @DisplayName("뒤 스텝 코드가 통과한 뒤 앞 스텝 코드는 거부한다")
    void rejectsEarlierStepAfterLater() {
        service.enroll(userId, SECRET, 0);

        assertThat(service.verify(userId, codeAt(NOW, 1), NOW)).isTrue();
        assertThat(service.verify(userId, codeAt(NOW, 0), NOW)).isFalse();
    }

    @Test
    @DisplayName("틀린 코드는 거부한다")
    void rejectsWrongCode() {
        service.enroll(userId, SECRET, 0);
        String right = codeAt(NOW, 0);
        String wrong = right.equals("000000") ? "000001" : "000000";

        assertThat(service.verify(userId, wrong, NOW)).isFalse();
    }

    @Test
    @DisplayName("등록 안 한 계정은 거부한다")
    void rejectsUnenrolled() {
        assertThat(service.enrolled(userId)).isFalse();
        assertThat(service.verify(userId, codeAt(NOW, 0), NOW)).isFalse();
    }

    @Test
    @DisplayName("DB 에는 비밀 평문이 없다")
    void storesCiphertextOnly() {
        service.enroll(userId, SECRET, 0);

        byte[] stored = jdbc.sql("select secret_ciphertext from user_second_factor where user_id = :id")
                .param("id", userId)
                .query(byte[].class)
                .single();
        assertThat(new String(stored, StandardCharsets.ISO_8859_1))
                .as("인증정보는 암호화해 저장한다 — 고시 제7조①")
                .doesNotContain(new String(SECRET, StandardCharsets.ISO_8859_1));
    }

    @Test
    @DisplayName("탈퇴하면 등록 행이 지워진다")
    void withdrawalRemovesEnrollment() {
        service.enroll(userId, SECRET, 0);

        withdrawals.withdraw(userId, PASSWORD, "127.0.0.1");

        assertThat(jdbc.sql("select count(*) from user_second_factor where user_id = :id")
                .param("id", userId)
                .query(Integer.class)
                .single())
                .as("탈퇴한 계정의 인증정보가 남으면 안 된다")
                .isZero();
    }

    @Test
    @DisplayName("스텝이 음수인 행은 check 가 막는다")
    void rejectsNegativeStep() {
        assertThatThrownBy(() -> jdbc.sql("""
                        insert into user_second_factor (user_id, secret_ciphertext, secret_key_version, last_used_step)
                        values (:id, '\\x00'::bytea, 1, -1)
                        """)
                .param("id", userId)
                .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("user_second_factor_last_used_step_check");
    }

    @Test
    @DisplayName("키 판이 1 미만인 행은 check 가 막는다")
    void rejectsZeroKeyVersion() {
        assertThatThrownBy(() -> jdbc.sql("""
                        insert into user_second_factor (user_id, secret_ciphertext, secret_key_version, last_used_step)
                        values (:id, '\\x00'::bytea, 0, 0)
                        """)
                .param("id", userId)
                .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("user_second_factor_secret_key_version_check");
    }

    @Test
    @DisplayName("관리자 초기화가 등록 행을 지우고 감사 한 줄을 남긴다")
    void adminResetRemovesEnrollment() {
        service.enroll(userId, SECRET, 0);

        service.reset(account("sf-reset-admin@test.local", "admin"), userId, "admin");

        assertThat(service.enrolled(userId)).isFalse();
        assertThat(resetAudits()).isEqualTo(1);
    }

    @Test
    @DisplayName("등록이 없어도 초기화는 예외 없이 지나가고 감사를 안 남긴다")
    void resetWithoutEnrollmentIsQuiet() {
        service.reset(account("sf-reset-empty@test.local", "admin"), userId, "admin");

        assertThat(resetAudits()).isZero();
    }

    @Test
    @DisplayName("감사자는 초기화를 못 한다")
    void auditorCannotReset() {
        service.enroll(userId, SECRET, 0);
        long auditor = account("sf-reset-auditor@test.local", "auditor");

        assertThatThrownBy(() -> service.reset(auditor, userId, "admin")).isInstanceOf(ShopException.class);
        assertThat(service.enrolled(userId)).isTrue();
    }

    @Test
    @DisplayName("이전 키로 싼 행이 검증을 지나고 현재 키로 다시 싸인다")
    void resealsWithCurrentKey() {
        service.enroll(userId, SECRET, 0);
        byte[] before = ciphertext();
        SecondFactorService rotated = serviceWith(new SecondFactorSecrets(KEY_V2, 2, KEY_V1, 1));

        assertThat(rotated.state(userId)).isEqualTo(SecondFactorService.State.ENROLLED);
        assertThat(rotated.verify(userId, codeAt(NOW, 0), NOW)).isTrue();

        assertThat(keyVersion()).isEqualTo(2);
        assertThat(ciphertext()).isNotEqualTo(before);
        SecondFactorService previousDropped = serviceWith(new SecondFactorSecrets(KEY_V2, 2, "", 0));
        assertThat(previousDropped.verify(userId, codeAt(NOW, 1), NOW))
                .as("다시 싼 행은 옛 키를 지운 뒤에도 풀린다")
                .isTrue();
    }

    @Test
    @DisplayName("현재 판도 이전 판도 아닌 행은 못 읽는다")
    void unknownVersionIsUnreadable() {
        service.enroll(userId, SECRET, 0);

        assertThat(serviceWith(new SecondFactorSecrets(KEY_V2, 3, KEY_V2, 2)).state(userId))
                .isEqualTo(SecondFactorService.State.UNREADABLE);
        assertThat(service.state(userId)).isEqualTo(SecondFactorService.State.ENROLLED);
    }

    private SecondFactorService serviceWith(SecondFactorSecrets secrets) {
        return new SecondFactorService(jdbc, secrets, auditLog, evaluator);
    }

    private long account(String email, String role) {
        AuthFixture fixture = new AuthFixture(jdbc);
        long id = fixture.insertUser(email, "초기화");
        fixture.grantGlobal(id, role);
        return id;
    }

    private int resetAudits() {
        return jdbc.sql("""
                        select count(*) from audit_log
                         where event_type = 'user.second_factor_reset' and target_type = 'user' and target_id = :id
                        """)
                .param("id", userId)
                .query(Integer.class)
                .single();
    }

    private byte[] ciphertext() {
        return jdbc.sql("select secret_ciphertext from user_second_factor where user_id = :id")
                .param("id", userId)
                .query(byte[].class)
                .single();
    }

    private int keyVersion() {
        return jdbc.sql("select secret_key_version from user_second_factor where user_id = :id")
                .param("id", userId)
                .query(Integer.class)
                .single();
    }

    private static String codeAt(Instant now, long offset) {
        return Totp.codeAt(SECRET, Totp.stepAt(now) + offset);
    }
}
