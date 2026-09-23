package com.projectshop.shop.auth;

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
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

/**
 * 비밀번호 재설정 토큰(`5c-1`).
 *
 * <p><b>재사용을 DB 가 막는지까지 본다.</b> 서비스가 그것을 지키는 것과 <b>DB 가 그것을 강제하는 것</b>은
 * 다르고, 뒤엣것이라야 새 입구가 생겨도 남는다.
 */
@DisplayName("비밀번호 재설정")
class PasswordResetTest extends PostgresTestBase {

    private static final String URL = "http://localhost:3000/password-reset?token={token}";

    @Autowired
    private PasswordResetService service;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private long userId;
    private String email;

    @BeforeEach
    void setUp() {
        AuthFixture fixture = new AuthFixture(jdbc);
        email = "reset-" + System.nanoTime() + "@test.local";
        userId = fixture.insertUser(email, "재설정 시험");
    }

    @Test
    @DisplayName("요청하면 살아 있는 토큰이 하나 생긴다")
    void requestIssuesOneLiveToken() {
        service.request(email, URL);

        assertThat(liveTokenCount())
                .as("사람마다 살아 있는 토큰은 하나다 (V67 의 부분 유니크 인덱스)")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("없는 주소로 요청해도 아무 일이 안 난다")
    void unknownEmailIsSilent() {
        assertThatThrownBy(() -> {
            service.request("nobody-" + System.nanoTime() + "@test.local", URL);
            throw new IllegalStateException("여기까지 왔다");
        })
                .as("예외로도 갈리면 안 된다. 없는 주소는 조용히 끝난다 (D14)")
                .hasMessage("여기까지 왔다");
    }

    @Test
    @DisplayName("다시 요청하면 앞 토큰이 닫힌다")
    void secondRequestClosesTheFirst() {
        service.request(email, URL);
        service.request(email, URL);

        assertThat(liveTokenCount())
                .as("옛 링크가 계속 통하면 메일을 여러 번 받은 사람의 앞 링크가 살아 있다")
                .isEqualTo(1);
        assertThat(tokenCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("토큰으로 비밀번호를 바꾸면 그 토큰이 죽는다")
    void resetConsumesTheToken() {
        String token = issueToken();

        service.reset(token, "NewPassword-123!");

        assertThat(liveTokenCount()).isZero();
        assertThat(currentHashMatches("NewPassword-123!"))
                .as("바뀐 비밀번호로 대조된다")
                .isTrue();
    }

    /**
     * 재설정도 비밀번호를 정하는 입구다(`D14-2`). <b>토큰은 살아 있어야 한다</b> — 거절한 뒤 토큰까지 닫히면
     * 사람은 메일을 다시 받아야 한다.
     */
    @Test
    @DisplayName("흔한 비밀번호로는 못 바꾸고 토큰은 살아 있다")
    void rejectsCommonPasswordAndKeepsToken() {
        String token = issueToken();

        assertThatThrownBy(() -> service.reset(token, "qwertyuiopasdfghjkl"))
                .isInstanceOf(ShopException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.PASSWORD_TOO_COMMON);
        assertThat(liveTokenCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("쓴 토큰을 다시 쓰면 거절한다")
    void usedTokenIsRejected() {
        String token = issueToken();
        service.reset(token, "NewPassword-123!");

        assertThatThrownBy(() -> service.reset(token, "Another-456!"))
                .isInstanceOf(ShopException.class)
                .hasMessageContaining("재설정 토큰");
    }

    @Test
    @DisplayName("만료된 토큰은 거절한다")
    void expiredTokenIsRejected() {
        String token = issueToken();
        // 발급 시각도 같이 당긴다. **제약이 `expires_at > issued_at` 을 요구해서**
        // 만료만 과거로 밀면 그 제약이 막는다 — 그것이 이 표가 뜻 없는 행을 안 받는다는 증거다.
        jdbc.sql("""
                        update password_reset_token
                           set issued_at  = now() - interval '2 minutes',
                               expires_at = now() - interval '1 minute'
                         where user_id = :id
                        """)
                .param("id", userId)
                .update();

        assertThatThrownBy(() -> service.reset(token, "Another-456!"))
                .isInstanceOf(ShopException.class)
                .hasMessageContaining("재설정 토큰");
    }

    @Test
    @DisplayName("모르는 토큰은 거절한다")
    void unknownTokenIsRejected() {
        assertThatThrownBy(() -> service.reset("아무거나", "Another-456!"))
                .isInstanceOf(ShopException.class)
                .hasMessageContaining("재설정 토큰");
    }

    /**
     * <b>표에 원문이 없다.</b> 저장된 값으로는 링크를 못 만든다 — 표가 새도 남의 비밀번호를
     * 못 바꾼다는 것이 이 단언의 뜻이다.
     */
    @Test
    @DisplayName("표에 토큰 원문이 없다")
    void tokenIsStoredHashed() {
        String token = issueToken();

        String stored = jdbc.sql("select token_hash from password_reset_token where user_id = :id")
                .param("id", userId)
                .query(String.class)
                .single();

        assertThat(stored)
                .as("원문을 그대로 담으면 표가 새는 순간 남의 계정이 같이 샌다 (D14)")
                .isNotEqualTo(token);
        assertThat(passwordEncoder.matches(token, stored)).isTrue();
    }

    /**
     * 토큰 원문은 메일로만 나가므로 테스트가 직접 만든다 — 서비스가 만든 것은 우리가 못 본다.
     *
     * <p>발급 경로와 같은 표에 같은 모양으로 넣는다.
     */
    private String issueToken() {
        String token = "test-token-" + System.nanoTime();
        jdbc.sql("""
                        insert into password_reset_token (user_id, token_hash, expires_at)
                        values (:id, :hash, :expiresAt)
                        """)
                .param("id", userId)
                .param("hash", passwordEncoder.encode(token))
                .param("expiresAt", OffsetDateTime.now().plus(PasswordResetService.LIFETIME))
                .update();
        return token;
    }

    private int liveTokenCount() {
        return jdbc.sql("select count(*) from password_reset_token"
                        + " where user_id = :id and used_at is null")
                .param("id", userId)
                .query(Integer.class)
                .single();
    }

    private int tokenCount() {
        return jdbc.sql("select count(*) from password_reset_token where user_id = :id")
                .param("id", userId)
                .query(Integer.class)
                .single();
    }

    private boolean currentHashMatches(String password) {
        String stored = jdbc.sql("select password_hash from app_user where user_id = :id")
                .param("id", userId)
                .query(String.class)
                .single();
        return passwordEncoder.matches(password, stored);
    }
}
