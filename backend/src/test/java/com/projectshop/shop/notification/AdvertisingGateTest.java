package com.projectshop.shop.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.order.OrderFixture;

/**
 * 광고가 관문을 지나는가, 그리고 <b>거래 통지가 그 관문을 안 지나는가</b>(청크 55, 정보통신망법 제50조).
 *
 * <p>뒤엣것이 이 테스트의 핵심이다. `D18` 이 강제 지점으로 넘긴 것이 바로 그 비대칭이라 —
 * 거래 통지가 동의를 보기 시작하면 <b>안 보낸 것이 우리 위반</b>이 되고,
 * 사용자가 껐다는 사실이 그 위반을 안 덮는다.
 */
@DisplayName("광고 관문")
class AdvertisingGateTest extends PostgresTestBase {

    /** KST 로 새벽 3시. 제50조제3항의 야간이다 */
    private static final OffsetDateTime NIGHT =
            OffsetDateTime.of(2026, 8, 22, 3, 0, 0, 0, ZoneOffset.ofHours(9));

    /** KST 로 낮 2시 */
    private static final OffsetDateTime DAY =
            OffsetDateTime.of(2026, 8, 22, 14, 0, 0, 0, ZoneOffset.ofHours(9));

    private static final String AD_CODE = "weekly_deal";

    /** 제50조제4항이 요구하는 자리를 다 갖춘 광고 문안 */
    private static final String LEGAL_AD_BODY =
            "이번 주 혜택. 보낸이 {{sender_name}} 문의 {{sender_contact}} 수신거부 {{unsubscribe}}";

    @Autowired
    private AdvertisingGate gate;

    @Autowired
    private AdvertisingNotifications advertisements;

    @Autowired
    private NotificationService notifications;

    @Autowired
    private JdbcClient jdbc;

    private long userId;

    @BeforeEach
    void setUp() {
        userId = new AuthFixture(jdbc).insertUser("ad-target@test.local", "받는이");
    }

    @Nested
    @DisplayName("동의를 본다")
    class Consent {

        @Test
        @DisplayName("동의한 적이 없으면 막힌다")
        void blocksWithoutConsent() {
            assertThat(gate.check(userId, DAY))
                    .describedAs("제50조제1항은 명시적인 사전 동의를 요구한다")
                    .isEqualTo(AdvertisingGate.Verdict.NO_CONSENT);
        }

        @Test
        @DisplayName("동의하면 낮에는 나간다")
        void allowsWithConsentDuringDay() {
            consent("marketing_email", true);

            assertThat(gate.check(userId, DAY)).isEqualTo(AdvertisingGate.Verdict.ALLOWED);
        }

        @Test
        @DisplayName("철회하면 그 순간부터 막힌다")
        void blocksAfterRevocation() {
            consent("marketing_email", true);
            consent("marketing_email", false);

            // 제50조제2항 — 철회 표시가 있으면 전송하여서는 아니 된다.
            assertThat(gate.check(userId, DAY)).isEqualTo(AdvertisingGate.Verdict.NO_CONSENT);
        }
    }

    @Nested
    @DisplayName("야간을 본다")
    class Night {

        @Test
        @DisplayName("야간 동의가 없으면 21~08시에 막힌다")
        void blocksAtNightWithoutNightConsent() {
            consent("marketing_email", true);

            assertThat(gate.check(userId, NIGHT))
                    .describedAs("제50조제3항은 야간에 별도의 사전 동의를 요구한다")
                    .isEqualTo(AdvertisingGate.Verdict.NO_NIGHT_CONSENT);
        }

        @Test
        @DisplayName("야간 동의가 있으면 야간에도 나간다")
        void allowsAtNightWithNightConsent() {
            consent("marketing_email", true);
            consent("marketing_night", true);

            assertThat(gate.check(userId, NIGHT)).isEqualTo(AdvertisingGate.Verdict.ALLOWED);
        }

        @Test
        @DisplayName("경계는 21시를 넣고 08시를 뺀다")
        void treatsBoundariesAsSpecified() {
            consent("marketing_email", true);

            assertThat(gate.check(userId, atKst(21, 0)))
                    .describedAs("「오후 9시부터」라 21시 정각이 야간이다")
                    .isEqualTo(AdvertisingGate.Verdict.NO_NIGHT_CONSENT);
            assertThat(gate.check(userId, atKst(8, 0)))
                    .describedAs("「오전 8시까지」라 08시 정각은 야간이 아니다")
                    .isEqualTo(AdvertisingGate.Verdict.ALLOWED);
        }
    }

    @Nested
    @DisplayName("거래 통지는 안 지난다")
    class TransactionalIsExempt {

        @Test
        @DisplayName("동의가 없어도 거래 통지는 나간다")
        void sendsNoticeWithoutAnyConsent() {
            insertTemplate("order_placed", "제목", "본문", "transactional");
            long orderId = insertOrder();

            Optional<Long> id = notifications.send("order_placed",
                    NotificationService.Target.order(orderId), userId, Map.of());

            // 안 보내면 우리가 위반이고, 사용자가 껐다는 사실이 그 위반을 안 덮는다(`D18`).
            assertThat(id).isPresent();
        }

        @Test
        @DisplayName("광고 수신을 철회해도 거래 통지는 나간다")
        void sendsNoticeAfterAdvertisingRevoked() {
            consent("marketing_email", false);
            insertTemplate("payment_completed", "제목", "본문", "transactional");
            long orderId = insertOrder();

            Optional<Long> id = notifications.send("payment_completed",
                    NotificationService.Target.order(orderId), userId, Map.of());

            assertThat(id).isPresent();
        }
    }

    @Nested
    @DisplayName("광고 입구는")
    class AdvertisingEntry {

        @Test
        @DisplayName("막히면 이력을 안 남긴다")
        void leavesNoTraceWhenBlocked() {
            insertTemplate(AD_CODE, "제목 {{sender_name}}", LEGAL_AD_BODY, "advertising");

            Optional<Long> id = advertisements.send(AD_CODE, userId, adValues());

            assertThat(id).isEmpty();
            // 발송 이력은 「보냈다」를 증명하는 자리라 안 보낸 것을 넣으면 뜻이 흐려진다(`D18`).
            assertThat(notificationCount()).isZero();
        }

        @Test
        @DisplayName("동의가 있으면 이력이 남는다")
        void recordsWhenAllowed() {
            consent("marketing_email", true);
            consent("marketing_night", true);
            insertTemplate(AD_CODE, "제목 {{sender_name}}", LEGAL_AD_BODY, "advertising");

            Optional<Long> id = advertisements.send(AD_CODE, userId, adValues());

            assertThat(id).isPresent();
            assertThat(notificationCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("수신거부 자리가 빠진 판은 거부한다")
        void refusesTemplateWithoutUnsubscribe() {
            consent("marketing_email", true);
            consent("marketing_night", true);
            insertTemplate(AD_CODE, "제목", "보낸이 {{sender_name}} 문의 {{sender_contact}}",
                    "advertising");

            assertThatThrownBy(() -> advertisements.send(AD_CODE, userId, adValues()))
                    .describedAs("제50조제4항이 수신거부 의사표시를 쉽게 할 조치를 명시하라고 한다")
                    .isInstanceOf(AdvertisingNotifications.MissingRequiredPlaceholderException.class);
        }
    }

    private Map<String, String> adValues() {
        return Map.of("sender_name", "프로젝트샵",
                "sender_contact", "help@test.local",
                "unsubscribe", "https://test.local/unsubscribe");
    }

    private OffsetDateTime atKst(int hour, int minute) {
        return OffsetDateTime.of(2026, 8, 22, hour, minute, 0, 0, ZoneOffset.ofHours(9));
    }

    private void consent(String code, boolean granted) {
        jdbc.sql("""
                        insert into user_consent (user_id, consent_item_id, granted, source)
                        select :id, consent_item_id, :granted, 'signup' from consent_item
                         where code = :code order by version desc limit 1
                        """)
                .param("id", userId)
                .param("code", code)
                .param("granted", granted)
                .update();
    }

    private void insertTemplate(String code, String subject, String body, String kind) {
        jdbc.sql("""
                        insert into notification_template (code, subject, body, kind)
                        values (:code, :subject, :body, :kind)
                        """)
                .param("code", code)
                .param("subject", subject)
                .param("body", body)
                .param("kind", kind)
                .update();
    }

    private int notificationCount() {
        return jdbc.sql("select count(*) from notification where user_id = :id")
                .param("id", userId)
                .query(Integer.class)
                .single();
    }

    private long insertOrder() {
        long id = jdbc.sql("""
                        insert into shop_order (order_number, user_id, total_amount,
                                                commission_total, shipping_fee_total, payable_amount)
                        values (:number, :userId, 10000, 1000, 0, 10000)
                        returning order_id
                        """)
                .param("number", OrderFixture.sellerOrderNumber().substring(2))
                .param("userId", userId)
                .query(Long.class)
                .single();

        // `V31` 이 서면 없는 주문을 막는다. 여기는 서면이 관심사가 아니라 껍데기만 채운다.
        OrderFixture.attachContractDocuments(jdbc, id);
        return id;
    }
}
