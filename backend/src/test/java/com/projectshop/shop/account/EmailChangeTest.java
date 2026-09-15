package com.projectshop.shop.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.error.ShopException;

/**
 * 이메일 변경 확인(`5e-1`).
 *
 * <p><b>가장 중요한 단언은 「안 바뀐다」다.</b> 요청만 했을 때 계정 주소가 그대로인 것이
 * 이 청크가 막으려는 사고(오타를 치면 알림을 영영 못 받는다)를 막는 자리다.
 */
@DisplayName("이메일 변경 확인")
class EmailChangeTest extends PostgresTestBase {

    private static final String URL = "http://localhost:3000/email-confirm?token={token}";

    @Autowired
    private EmailChangeService service;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private long userId;
    private String oldEmail;
    private String newEmail;

    @BeforeEach
    void setUp() {
        AuthFixture fixture = new AuthFixture(jdbc);
        long stamp = System.nanoTime();
        oldEmail = "old-" + stamp + "@test.local";
        newEmail = "new-" + stamp + "@test.local";
        userId = fixture.insertUser(oldEmail, "변경 시험");
    }

    @Test
    @DisplayName("요청만 하면 계정 주소가 안 바뀐다")
    void requestDoesNotChangeAccount() {
        service.request(userId, newEmail, URL);

        assertThat(currentEmail())
                .as("바로 바꾸면 오타를 친 순간 그 계정으로는 아무것도 못 받는다 (D2 R28)")
                .isEqualTo(oldEmail);
        assertThat(liveRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("확인하면 그때 계정 주소가 바뀐다")
    void confirmMovesTheAddress() {
        String token = issueToken(newEmail);

        String changed = service.confirm(userId, token);

        assertThat(changed).isEqualTo(newEmail);
        assertThat(currentEmail()).isEqualTo(newEmail);
        assertThat(liveRequestCount()).isZero();
    }

    @Test
    @DisplayName("다시 요청하면 앞 요청이 닫힌다")
    void secondRequestClosesTheFirst() {
        service.request(userId, newEmail, URL);
        service.request(userId, "other-" + System.nanoTime() + "@test.local", URL);

        assertThat(liveRequestCount())
                .as("여럿이 살아 있으면 어느 주소로 바뀔지를 사용자가 모른다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("쓴 토큰을 다시 쓰면 거절한다")
    void usedTokenIsRejected() {
        String token = issueToken(newEmail);
        service.confirm(userId, token);

        assertThatThrownBy(() -> service.confirm(userId, token))
                .isInstanceOf(ShopException.class)
                .hasMessageContaining("확인 토큰");
    }

    @Test
    @DisplayName("만료된 토큰은 거절한다")
    void expiredTokenIsRejected() {
        String token = issueToken(newEmail);
        jdbc.sql("""
                        update email_change_request
                           set issued_at  = now() - interval '2 days',
                               expires_at = now() - interval '1 day'
                         where user_id = :id
                        """)
                .param("id", userId)
                .update();

        assertThatThrownBy(() -> service.confirm(userId, token))
                .isInstanceOf(ShopException.class)
                .hasMessageContaining("확인 토큰");
    }

    /**
     * <b>남의 토큰으로는 못 바꾼다.</b> 링크를 주운 사람이 자기 계정으로 눌러도 안 통한다 —
     * 대조가 그 사람의 요청 안에서만 돌기 때문이다.
     */
    @Test
    @DisplayName("남의 토큰으로는 못 바꾼다")
    void otherUsersTokenIsRejected() {
        String token = issueToken(newEmail);
        long intruder = new AuthFixture(jdbc)
                .insertUser("intruder-" + System.nanoTime() + "@test.local", "남");

        assertThatThrownBy(() -> service.confirm(intruder, token))
                .isInstanceOf(ShopException.class)
                .hasMessageContaining("확인 토큰");
        assertThat(currentEmail()).isEqualTo(oldEmail);
    }

    @Test
    @DisplayName("이미 쓰는 주소로는 요청이 안 선다")
    void takenEmailIsRejected() {
        String taken = "taken-" + System.nanoTime() + "@test.local";
        new AuthFixture(jdbc).insertUser(taken, "먼저");

        assertThatThrownBy(() -> service.request(userId, taken, URL))
                .isInstanceOf(ShopException.class);
    }

    @Test
    @DisplayName("표에 토큰 원문이 없다")
    void tokenIsStoredHashed() {
        String token = issueToken(newEmail);

        String stored = jdbc.sql("select token_hash from email_change_request where user_id = :id")
                .param("id", userId)
                .query(String.class)
                .single();

        assertThat(stored).isNotEqualTo(token);
        assertThat(passwordEncoder.matches(token, stored)).isTrue();
    }

    /** 원문은 메일로만 나가므로 테스트가 발급 경로와 같은 모양으로 직접 넣는다 */
    private String issueToken(String email) {
        String token = "test-token-" + System.nanoTime();
        jdbc.sql("""
                        insert into email_change_request (user_id, new_email, token_hash, expires_at)
                        values (:id, :email, :hash, :expiresAt)
                        """)
                .param("id", userId)
                .param("email", email)
                .param("hash", passwordEncoder.encode(token))
                .param("expiresAt", OffsetDateTime.now().plus(EmailChangeService.LIFETIME))
                .update();
        return token;
    }

    private String currentEmail() {
        return jdbc.sql("select email from app_user where user_id = :id")
                .param("id", userId)
                .query(String.class)
                .single();
    }

    private int liveRequestCount() {
        return jdbc.sql("select count(*) from email_change_request"
                        + " where user_id = :id and used_at is null")
                .param("id", userId)
                .query(Integer.class)
                .single();
    }
}
