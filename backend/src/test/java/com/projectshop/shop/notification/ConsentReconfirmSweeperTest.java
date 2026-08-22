package com.projectshop.shop.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;

/**
 * 광고 수신동의를 2년마다 확인하는가(청크 55b, 시행령 제62조의3).
 *
 * <p><b>확인이 반복된다는 것이 이 청크의 함정이다.</b> 「통지가 나갔나」로 세면 두 번째 주기가
 * 영영 안 오고, 그때 안 깨지는 것이 문제다 — 2년 뒤에야 드러난다.
 * 그래서 여기서 <b>두 주기를 실제로 밟는다</b>.
 */
@DisplayName("수신동의 정기 확인")
class ConsentReconfirmSweeperTest extends PostgresTestBase {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 8, 22, 4, 30, 0, 0, ZoneOffset.ofHours(9));

    /** 관문은 야간도 같이 보므로 낮으로 묻는다. 야간 판정은 `55` 가 따로 밟는다 */
    private static final OffsetDateTime DAY =
            OffsetDateTime.of(2026, 8, 22, 14, 0, 0, 0, ZoneOffset.ofHours(9));

    @Autowired
    private ConsentReconfirmSweeper sweeper;

    @Autowired
    private AdvertisingGate gate;

    @Autowired
    private JdbcClient jdbc;

    private long userId;

    @BeforeEach
    void setUp() {
        userId = new AuthFixture(jdbc).insertUser("reconfirm@test.local", "받는이");
    }

    @Nested
    @DisplayName("2년이 지나면")
    class WhenDue {

        @Test
        @DisplayName("확인 통지가 나간다")
        void sendsReconfirmNotice() {
            consent("marketing_email", true, NOW.minusYears(3));

            assertThat(sweeper.sweep(NOW)).isEqualTo(1);
            assertThat(reconfirmEvents()).containsExactly("consent_reconfirm");
        }

        @Test
        @DisplayName("확인 시각이 남는다")
        void recordsReconfirmedAt() {
            consent("marketing_email", true, NOW.minusYears(3));

            sweeper.sweep(NOW);

            assertThat(reconfirmedAt()).isNotNull();
        }

        @Test
        @DisplayName("확인한 뒤에는 다시 안 나간다")
        void doesNotRepeatWithinCycle() {
            consent("marketing_email", true, NOW.minusYears(3));

            sweeper.sweep(NOW);
            int again = sweeper.sweep(NOW.plusDays(1));

            assertThat(again).isZero();
        }

        /**
         * <b>다음 주기가 실제로 온다.</b> `55a` 가 건 유니크를 그대로 두면 여기서 0 이 되고,
         * 그 사실은 2년 뒤에야 드러난다.
         */
        @Test
        @DisplayName("다음 2년이 지나면 또 나간다")
        void sendsAgainAfterNextCycle() {
            consent("marketing_email", true, NOW.minusYears(3));

            sweeper.sweep(NOW);
            int next = sweeper.sweep(NOW.plusYears(2).plusDays(1));

            assertThat(next).isEqualTo(1);
            assertThat(reconfirmEvents()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("대상이 아닌 것")
    class NotDue {

        @Test
        @DisplayName("2년이 안 지났으면 안 나간다")
        void ignoresFreshConsent() {
            consent("marketing_email", true, NOW.minusMonths(6));

            assertThat(sweeper.sweep(NOW)).isZero();
        }

        @Test
        @DisplayName("철회한 동의에는 안 나간다")
        void ignoresRevokedConsent() {
            consent("marketing_email", true, NOW.minusYears(3));
            consent("marketing_email", false, NOW.minusYears(3).plusDays(1));

            // 「동의하셨습니다」가 거짓이 되고, 그것 자체가 광고 재유치로 읽힌다.
            assertThat(sweeper.sweep(NOW)).isZero();
        }

        @Test
        @DisplayName("필수 약관에는 안 나간다")
        void ignoresRequiredTerms() {
            consent("terms_of_service", true, NOW.minusYears(3));

            assertThat(sweeper.sweep(NOW)).isZero();
        }
    }

    @Nested
    @DisplayName("관문이")
    class Gate {

        @Test
        @DisplayName("확인이 밀린 동의로는 광고를 안 보낸다")
        void blocksUnreconfirmedConsent() {
            consent("marketing_email", true, NOW.minusYears(3));

            // 배치가 못 돌면 확인 없는 동의가 쌓이는데, 관문이 안 보면 그 동안 광고가 나간다.
            assertThat(gate.check(userId, DAY))
                    .isEqualTo(AdvertisingGate.Verdict.NOT_RECONFIRMED);
        }

        @Test
        @DisplayName("확인하고 나면 다시 나간다")
        void allowsAfterReconfirm() {
            consent("marketing_email", true, NOW.minusYears(3));

            sweeper.sweep(NOW);

            assertThat(gate.check(userId, DAY)).isEqualTo(AdvertisingGate.Verdict.ALLOWED);
        }
    }

    private List<String> reconfirmEvents() {
        return jdbc.sql("""
                        select event_type from notification
                         where user_id = :id and event_type = 'consent_reconfirm'
                        """)
                .param("id", userId)
                .query(String.class)
                .list();
    }

    private OffsetDateTime reconfirmedAt() {
        return jdbc.sql("""
                        select uc.reconfirmed_at from user_consent uc
                          join consent_item ci on ci.consent_item_id = uc.consent_item_id
                         where uc.user_id = :id and ci.code = 'marketing_email'
                         order by uc.user_consent_id desc limit 1
                        """)
                .param("id", userId)
                .query(OffsetDateTime.class)
                .single();
    }

    private void consent(String code, boolean granted, OffsetDateTime actedAt) {
        jdbc.sql("""
                        insert into user_consent (user_id, consent_item_id, granted, source, acted_at)
                        select :id, consent_item_id, :granted, 'signup', :actedAt from consent_item
                         where code = :code order by version desc limit 1
                        """)
                .param("id", userId)
                .param("code", code)
                .param("granted", granted)
                .param("actedAt", actedAt)
                .update();
    }
}
