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

    @Autowired
    SecondFactorService service;

    @Autowired
    WithdrawalService withdrawals;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

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

    private static String codeAt(Instant now, long offset) {
        return Totp.codeAt(SECRET, Totp.stepAt(now) + offset);
    }
}
