package com.projectshop.shop.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;

/**
 * 탈퇴 계정 파기(`D2` R9).
 *
 * <p>기준 시각을 넘겨서 시간을 통제한다. {@code now()} 에 기대면 30일과 3년을 기다려야 한다.
 */
@DisplayName("탈퇴 계정 파기")
class AccountPurgeServiceTest extends PostgresTestBase {

    /** 파기 대상이 되기에 충분히 지난 시각. 유예 30일보다 크면 값 자체는 상관없다. */
    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 8, 8, 0, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    private AccountPurgeService purgeService;

    @Autowired
    private JdbcClient jdbc;

    @Nested
    @DisplayName("유예가 지나면")
    class AfterGrace {

        @Test
        @DisplayName("이메일·이름·비밀번호 해시가 비워진다")
        void clearsIdentifyingFields() {
            long userId = withdrawnUser("purge-1@example.com", NOW.minusDays(31));

            purgeService.purge(NOW);

            Map<String, Object> row = accountRow(userId);
            assertThat(row.get("email"))
                    .as("탈퇴 후 유예가 지났는데 이메일이 남으면 파기가 안 된 것이다")
                    .isNull();
            assertThat(row.get("display_name")).isNull();
            assertThat(row.get("password_hash")).isNull();
        }

        @Test
        @DisplayName("행 자체는 남는다 — 주문이 이 id 를 가리킨다")
        void keepsRow() {
            long userId = withdrawnUser("purge-2@example.com", NOW.minusDays(31));

            purgeService.purge(NOW);

            assertThat(accountRow(userId))
                    .as("행을 지우면 5년 보존해야 할 주문이 가리킬 곳을 잃는다")
                    .isNotNull();
            assertThat(accountRow(userId).get("deleted_at"))
                    .as("수명은 파기와 다른 축이다. 지우면 탈퇴한 계정이라는 사실까지 사라진다")
                    .isNotNull();
        }

        @Test
        @DisplayName("동의 이력의 IP 가 비워지고 행은 남는다")
        void clearsConsentIpButKeepsRow() {
            long userId = withdrawnUser("purge-3@example.com", NOW.minusDays(31));
            insertConsent(userId, "203.0.113.7");

            purgeService.purge(NOW);

            assertThat(consentIps(userId))
                    .as("IP 는 입증에 안 쓰이면서 식별성이 높아 먼저 버린다")
                    .containsOnlyNulls();
            assertThat(consentIps(userId))
                    .as("무엇에 언제 동의했는지는 3년간 남아야 분쟁에 답한다")
                    .hasSize(1);
        }

        @Test
        @DisplayName("파기 사실이 감사 로그에 남는다")
        void writesAuditLog() {
            long userId = withdrawnUser("purge-4@example.com", NOW.minusDays(31));

            purgeService.purge(NOW);

            assertThat(auditCount(userId))
                    .as("파기 사실의 증거가 이 로그뿐이다. 계정 정보는 이미 없다")
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("건드리지 않는 것")
    class LeavesAlone {

        @Test
        @DisplayName("살아 있는 계정")
        void aliveAccount() {
            long userId = insertUser("alive@example.com");

            purgeService.purge(NOW);

            assertThat(accountRow(userId).get("email")).isNotNull();
        }

        @Test
        @DisplayName("탈퇴했지만 유예가 안 지난 계정")
        void withinGrace() {
            long userId = withdrawnUser("purge-5@example.com", NOW.minusDays(29));

            purgeService.purge(NOW);

            assertThat(accountRow(userId).get("email"))
                    .as("유예 안에는 복구 요청이 온다. 미리 지우면 되돌릴 수 없다")
                    .isNotNull();
        }

        @Test
        @DisplayName("보존 3년이 안 지난 동의 이력")
        void consentWithinRetention() {
            long userId = withdrawnUser("purge-6@example.com", NOW.minusDays(31));
            insertConsent(userId, "203.0.113.7");

            purgeService.purge(NOW);

            assertThat(consentIps(userId)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("보존 3년이 지나면")
    class AfterRetention {

        @Test
        @DisplayName("동의 이력 행이 지워진다")
        void deletesConsentRows() {
            long userId = withdrawnUser("purge-7@example.com", NOW.minusYears(4));
            insertConsent(userId, "203.0.113.7");

            purgeService.purge(NOW);

            assertThat(consentIps(userId))
                    .as("cascade 는 탈퇴로 안 걸린다. 지우는 자리는 파기 배치뿐이다")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("비로그인 장바구니")
    class GuestCarts {

        @Test
        @DisplayName("30일 방치되면 지워진다")
        void deletesStaleGuestCart() {
            long cartId = insertGuestCart("stale-token", NOW.minusDays(31));

            purgeService.purge(NOW);

            assertThat(cartExists(cartId))
                    .as("계약이 안 오는 채로 오래 두면 제15조①4호 근거가 약해진다")
                    .isFalse();
        }

        @Test
        @DisplayName("아직 30일이 안 됐으면 남는다")
        void keepsRecentGuestCart() {
            long cartId = insertGuestCart("fresh-token", NOW.minusDays(29));

            purgeService.purge(NOW);

            assertThat(cartExists(cartId)).isTrue();
        }

        @Test
        @DisplayName("계정 장바구니는 안 건드린다")
        void keepsAccountCart() {
            long userId = insertUser("cart-owner@example.com");
            long cartId = jdbc.sql("""
                            insert into cart (user_id, updated_at) values (:id, :at)
                            returning cart_id
                            """)
                    .param("id", userId)
                    .param("at", NOW.minusDays(365))
                    .query(Long.class)
                    .single();

            purgeService.purge(NOW);

            assertThat(cartExists(cartId))
                    .as("계정 장바구니는 계정의 수명을 따라간다. 방치 기간과 축이 다르다")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("멱등키")
    class IdempotencyKeys {

        @Test
        @DisplayName("24시간이 지나면 지워진다")
        void deletesExpiredKey() {
            long userId = insertUser("idem-old@example.com");
            insertIdempotencyKey(userId, "old-key", NOW.minusHours(25));

            purgeService.purge(NOW);

            assertThat(keyExists("old-key"))
                    .as("안 지우면 요청 하나당 한 행이 영구히 쌓인다")
                    .isFalse();
        }

        @Test
        @DisplayName("아직 24시간이 안 됐으면 남는다")
        void keepsRecentKey() {
            long userId = insertUser("idem-fresh@example.com");
            insertIdempotencyKey(userId, "fresh-key", NOW.minusHours(23));

            purgeService.purge(NOW);

            assertThat(keyExists("fresh-key"))
                    .as("이 구간의 재전송은 저장된 응답을 받아야 한다")
                    .isTrue();
        }

        private void insertIdempotencyKey(long userId, String key, java.time.OffsetDateTime createdAt) {
            // created_at 을 직접 넣는다. 이 테이블엔 set_updated_at 트리거가 없어서 insert 로 과거를 만든다.
            jdbc.sql("""
                            insert into idempotency_key
                                (user_id, key_value, request_hash, response_body, created_at)
                            values (:userId, :key, repeat('a', 64), '{}'::jsonb, :createdAt)
                            """)
                    .param("userId", userId)
                    .param("key", key)
                    .param("createdAt", createdAt)
                    .update();
        }

        private boolean keyExists(String key) {
            return Boolean.TRUE.equals(jdbc.sql(
                            "select exists(select 1 from idempotency_key where key_value = :key)")
                    .param("key", key)
                    .query(Boolean.class)
                    .single());
        }
    }

    @Nested
    @DisplayName("두 번 돌아도")
    class Idempotent {

        @Test
        @DisplayName("두 번째는 대상이 없다")
        void secondRunFindsNothing() {
            withdrawnUser("purge-8@example.com", NOW.minusDays(31));

            AccountPurgeService.Purged first = purgeService.purge(NOW);
            AccountPurgeService.Purged second = purgeService.purge(NOW);

            assertThat(first.accounts()).isEqualTo(1);
            assertThat(second.accounts())
                    .as("재실행과 수동 실행이 겹친다. 두 번째가 뭔가 더 지우면 안 된다")
                    .isZero();
        }
    }

    /**
     * <b>{@code insert} 에서 시각을 넣는다.</b> {@code set_updated_at} 트리거가
     * {@code before update} 에 걸려 있어서, 넣은 뒤 {@code update} 로 되돌리면
     * 그 트리거가 다시 {@code now()} 로 덮어쓴다 — 방치된 상태를 못 만든다.
     */
    private long insertGuestCart(String token, OffsetDateTime lastTouched) {
        return jdbc.sql("""
                        insert into cart (cart_token, updated_at) values (:t, :at)
                        returning cart_id
                        """)
                .param("t", token)
                .param("at", lastTouched)
                .query(Long.class)
                .single();
    }

    private boolean cartExists(long cartId) {
        return Boolean.TRUE.equals(
                jdbc.sql("select exists(select 1 from cart where cart_id = :id)")
                        .param("id", cartId)
                        .query(Boolean.class)
                        .single());
    }

    private long insertUser(String email) {
        return jdbc.sql("""
                        insert into app_user (email, password_hash, display_name)
                        values (:email, 'not-a-real-hash', '테스트')
                        returning user_id
                        """)
                .param("email", email)
                .query(Long.class)
                .single();
    }

    private long withdrawnUser(String email, OffsetDateTime withdrawnAt) {
        long userId = insertUser(email);
        jdbc.sql("update app_user set deleted_at = :at where user_id = :id")
                .param("at", withdrawnAt)
                .param("id", userId)
                .update();
        return userId;
    }

    private void insertConsent(long userId, String ip) {
        jdbc.sql("""
                        insert into user_consent (user_id, consent_item_id, granted, source, acted_ip)
                        select :userId, consent_item_id, true, 'signup', cast(:ip as inet)
                          from consent_item
                         where code = 'terms_of_service'
                         order by version desc limit 1
                        """)
                .param("userId", userId)
                .param("ip", ip)
                .update();
    }

    private Map<String, Object> accountRow(long userId) {
        return jdbc.sql("""
                        select email, display_name, password_hash, deleted_at
                          from app_user where user_id = :id
                        """)
                .param("id", userId)
                .query()
                .singleRow();
    }

    /** IP 만 뽑는다. 행 수와 값이 둘 다 검증 대상이라 목록으로 받는다. */
    private java.util.List<String> consentIps(long userId) {
        return jdbc.sql("""
                        select cast(acted_ip as text) from user_consent
                         where user_id = :id
                        """)
                .param("id", userId)
                .query(String.class)
                .list();
    }

    private int auditCount(long userId) {
        return jdbc.sql("""
                        select count(*) from audit_log
                         where event_type = 'user.purged'
                           and target_type = 'user' and target_id = :id
                        """)
                .param("id", userId)
                .query(Integer.class)
                .single();
    }
}
