package com.projectshop.shop.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;

/**
 * 토큰 표 둘이 스스로 지키는 것(`Q62`).
 *
 * <p><b>셋 다 앱 검증에만 있던 것이다</b>(강제 지점 3위). 지금은 한 자리가 순서를 지키는데
 * <b>그 자리가 늘어나는 것을 아무도 안 막았다</b> — 마무리 15차 리뷰가 짚은 자리다.
 */
@DisplayName("토큰 표의 방벽")
class TokenGuardTest extends PostgresTestBase {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private AccountPurgeService purgeService;

    private long userId;

    @BeforeEach
    void setUp() {
        userId = jdbc.sql("""
                        insert into app_user (email, password_hash, display_name)
                        values ('token-guard@test.local', 'x', '토큰')
                        returning user_id
                        """)
                .query(Long.class)
                .single();
    }

    @Nested
    @DisplayName("쓴 토큰은")
    class UsedTokens {

        @Test
        @DisplayName("다시 못 쓴다")
        void cannotBeRevived() {
            insertReset(OffsetDateTime.now(KST));
            jdbc.sql("update password_reset_token set used_at = issued_at + interval ' 1 minute' where user_id = :id")
                    .param("id", userId)
                    .update();

            assertThatThrownBy(() -> jdbc.sql("""
                            update password_reset_token set used_at = null where user_id = :id
                            """)
                    .param("id", userId)
                    .update())
                    .as("비우는 것도 고치는 것이다. 되살아나면 그 열쇠로 계정이 넘어간다")
                    .isInstanceOf(Exception.class);
        }

        @Test
        @DisplayName("이메일 변경 쪽도 같다")
        void holdsForEmailChangeToo() {
            insertEmailChange(OffsetDateTime.now(KST), "new@test.local");
            jdbc.sql("update email_change_request set used_at = issued_at + interval ' 1 minute' where user_id = :id")
                    .param("id", userId)
                    .update();

            // **다른 값으로** 다시 쓴다. 같은 값이면 트리거가 안 돈다(`is distinct from`) —
            // 그것이 맞다: 다시 쓰는 것과 고치는 것은 다르다.
            assertThatThrownBy(() -> jdbc.sql("""
                            update email_change_request
                               set used_at = issued_at + interval ' 2 minutes'
                             where user_id = :id
                            """)
                    .param("id", userId)
                    .update())
                    .isInstanceOf(Exception.class);
        }
    }

    @Nested
    @DisplayName("이메일은")
    class EmailChange {

        @Test
        @DisplayName("확인된 요청 없이는 못 바꾼다")
        void needsAConfirmedRequest() {
            assertThatThrownBy(() -> jdbc.sql("""
                            update app_user set email = 'stolen@test.local' where user_id = :id
                            """)
                    .param("id", userId)
                    .update())
                    .as("그 주소로 비밀번호 재설정이 간다 — 확인이 성립 요건이다(`5e-1`)")
                    .isInstanceOf(Exception.class);
        }

        @Test
        @DisplayName("확인된 요청이 있으면 바뀐다")
        void passesWithAConfirmedRequest() {
            insertEmailChange(OffsetDateTime.now(KST), "confirmed@test.local");
            jdbc.sql("update email_change_request set used_at = issued_at + interval ' 1 minute' where user_id = :id")
                    .param("id", userId)
                    .update();

            int changed = jdbc.sql("""
                            update app_user set email = 'confirmed@test.local' where user_id = :id
                            """)
                    .param("id", userId)
                    .update();

            assertThat(changed).isOne();
        }

        /** 파기는 비우는 것이라 지나간다(`D13`). 막으면 탈퇴한 계정의 주소가 안 지워진다. */
        @Test
        @DisplayName("비우는 것은 지나간다")
        void allowsClearing() {
            // 파기는 수명 칸과 같이 온다 — `app_user_alive_fields_check` 가 「살아 있으면 이메일이 있다」를 든다.
            int cleared = jdbc.sql("""
                            update app_user set email = null, deleted_at = now()
                             where user_id = :id
                            """)
                    .param("id", userId)
                    .update();

            assertThat(cleared).isOne();
        }
    }

    @Nested
    @DisplayName("보관 기간이 지난 토큰은")
    class Retention {

        /**
         * <b>수집하는 코드가 파기하는 코드보다 먼저 나왔다.</b> 표는 {@code 5c-1}·{@code 5e-1} 이 세웠고
         * {@code data-lifecycle.md} 가 「30일 물리 삭제」로 적었는데 <b>지우는 자리가 없었다</b> —
         * 그 사이가 위반 구간이다(`D23` 「개인정보 컬럼은 셋을 같이 채운다」).
         */
        @Test
        @DisplayName("파기 배치가 지운다")
        void arePurged() {
            OffsetDateTime old = OffsetDateTime.now(KST).minusDays(31);
            insertReset(old);
            insertEmailChange(old, "old@test.local");

            AccountPurgeService.Purged purged =
                    purgeService.purge(OffsetDateTime.now(KST));

            assertThat(purged.tokens())
                    .as("발급일이 30일을 지난 토큰 둘이 지워져야 한다")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("아직 안 지난 것은 남는다")
        void areKeptUntilTheDeadline() {
            insertReset(OffsetDateTime.now(KST).minusDays(29));

            purgeService.purge(OffsetDateTime.now(KST));

            assertThat(countResets())
                    .as("기준은 발급일이다. 29일째는 아직 산다")
                    .isOne();
        }
    }

    private void insertReset(OffsetDateTime issuedAt) {
        jdbc.sql("""
                        insert into password_reset_token (user_id, token_hash, issued_at, expires_at)
                        values (:id, :hash, :issuedAt, :issuedAt + interval '1 hour')
                        """)
                .param("id", userId)
                .param("hash", "h" + System.nanoTime())
                .param("issuedAt", issuedAt)
                .update();
    }

    private void insertEmailChange(OffsetDateTime issuedAt, String newEmail) {
        jdbc.sql("""
                        insert into email_change_request
                               (user_id, new_email, token_hash, issued_at, expires_at)
                        values (:id, :email, :hash, :issuedAt, :issuedAt + interval '1 hour')
                        """)
                .param("id", userId)
                .param("email", newEmail)
                .param("hash", "h" + System.nanoTime())
                .param("issuedAt", issuedAt)
                .update();
    }

    private int countResets() {
        return jdbc.sql("select count(*) from password_reset_token where user_id = :id")
                .param("id", userId)
                .query(Integer.class)
                .single();
    }
}
