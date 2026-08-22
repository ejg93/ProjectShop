package com.projectshop.shop.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
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
 * 거래 통지가 나가고 나간 것이 남는가(청크 54b, `D18`).
 *
 * <p>여기서 보는 것이 셋이다 — <b>시행 중인 판을 고르는가</b>, <b>안 채워진 자리를 막는가</b>,
 * <b>두 번 불러도 한 번만 나가는가</b>. 뒤엣것은 앱이 아니라 스키마가 막으므로
 * 이 테스트가 확인하는 것은 <b>그 예외를 서비스가 「할 일 없음」으로 받는가</b>다.
 */
@DisplayName("거래 통지 발송")
class NotificationSendTest extends PostgresTestBase {

    private static final String EVENT = "order_placed";

    @Autowired
    private NotificationService notifications;

    @Autowired
    private JdbcClient jdbc;

    private long userId;
    private long orderId;

    @BeforeEach
    void setUp() {
        // `V44` 가 진짜 문안을 시드한다. 여기서는 검사할 판을 직접 세우므로 먼저 비운다 —
        // 코드와 판 번호가 유니크라 같은 코드로 1판을 또 넣으면 부딪친다.
        jdbc.sql("delete from notification_template").update();

        AuthFixture fixture = new AuthFixture(jdbc);
        userId = fixture.insertUser("send-buyer@test.local", "받는이");
        orderId = insertOrder();
    }

    @Nested
    @DisplayName("보내면")
    class Sending {

        @Test
        @DisplayName("이력과 본문이 완성된 글자로 남는다")
        void recordsRenderedBody() {
            insertTemplate("주문 {{order_number}} 접수", "{{order_number}} 를 받았다", OffsetDateTime.now());

            Optional<Long> id = notifications.send(EVENT, NotificationService.Target.order(orderId),
                    userId, Map.of("order_number", "20260822-ABCDEF"));

            assertThat(id).isPresent();
            assertThat(bodyOf(id.get()))
                    .describedAs("판이 바뀌어도 그때 보낸 글자가 남아야 한다(`D18`)")
                    .containsEntry("subject", "주문 20260822-ABCDEF 접수")
                    .containsEntry("body", "20260822-ABCDEF 를 받았다");
        }

        @Test
        @DisplayName("성공하면 보낸 시각이 채워진다")
        void marksSucceeded() {
            insertTemplate("제목", "본문", OffsetDateTime.now());

            long id = notifications.send(EVENT, NotificationService.Target.order(orderId),
                    userId, Map.of()).orElseThrow();

            assertThat(rowOf(id))
                    .containsEntry("status", "succeeded")
                    .containsEntry("failure_reason", null);
            assertThat(rowOf(id).get("sent_at")).isNotNull();
        }

        @Test
        @DisplayName("주소가 없으면 실패로 남는다")
        void recordsFailureWhenAddressIsGone() {
            insertTemplate("제목", "본문", OffsetDateTime.now());
            // 파기된 계정이다. 보낼 곳이 없어도 이력은 남아야 왜 안 갔는지에 답할 수 있다.
            jdbc.sql("update app_user set email = null, deleted_at = now() where user_id = :id")
                    .param("id", userId)
                    .update();

            long id = notifications.send(EVENT, NotificationService.Target.order(orderId),
                    userId, Map.of()).orElseThrow();

            assertThat(rowOf(id))
                    .containsEntry("status", "failed")
                    .containsEntry("failure_reason", "no_address");
        }
    }

    @Nested
    @DisplayName("판을 고를 때")
    class ChoosingVersion {

        @Test
        @DisplayName("시행일이 지난 판 중 가장 최근 것을 쓴다")
        void usesLatestEffectiveVersion() {
            insertTemplate("옛 제목", "옛 본문", OffsetDateTime.now().minusDays(2));
            insertTemplate("새 제목", "새 본문", OffsetDateTime.now().minusDays(1));

            long id = notifications.send(EVENT, NotificationService.Target.order(orderId),
                    userId, Map.of()).orElseThrow();

            assertThat(bodyOf(id)).containsEntry("subject", "새 제목");
        }

        @Test
        @DisplayName("시행 전 판은 안 쓴다")
        void ignoresFutureVersion() {
            insertTemplate("지금 제목", "지금 본문", OffsetDateTime.now().minusDays(1));
            insertTemplate("나중 제목", "나중 본문", OffsetDateTime.now().plusDays(7));

            long id = notifications.send(EVENT, NotificationService.Target.order(orderId),
                    userId, Map.of()).orElseThrow();

            // 판 번호로 고르면 개정판을 미리 넣어 둔 순간 시행 전 문안이 나간다(`D2-7`).
            assertThat(bodyOf(id)).containsEntry("subject", "지금 제목");
        }
    }

    @Nested
    @DisplayName("막는 것")
    class Guards {

        @Test
        @DisplayName("안 채워진 자리가 남으면 안 보낸다")
        void refusesToSendWithMissingValue() {
            insertTemplate("환급 안내", "환급 {{amount}}원을 보냈다", OffsetDateTime.now());

            assertThatThrownBy(() -> notifications.send(EVENT,
                    NotificationService.Target.order(orderId), userId, Map.of()))
                    .describedAs("법이 요구하는 항목이 빈 채로 나가면 제18조제3항 단서를 안 지킨 것이 된다")
                    .isInstanceOf(NotificationTemplates.MissingTemplateValueException.class);

            assertThat(notificationCount())
                    .describedAs("보낼 수 없는 것을 「보내는 중」으로 남기면 재시도가 그것을 집는다")
                    .isZero();
        }

        @Test
        @DisplayName("같은 사건을 두 번 불러도 한 번만 나간다")
        void sendsOnlyOncePerEvent() {
            insertTemplate("제목", "본문", OffsetDateTime.now());

            Optional<Long> first = notifications.send(EVENT,
                    NotificationService.Target.order(orderId), userId, Map.of());
            Optional<Long> second = notifications.send(EVENT,
                    NotificationService.Target.order(orderId), userId, Map.of());

            assertThat(first).isPresent();
            assertThat(second)
                    .describedAs("두 번째는 실패가 아니라 할 일이 없는 것이다")
                    .isEmpty();
            assertThat(notificationCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("시행 중인 판이 없으면 던진다")
        void refusesWithoutEffectiveTemplate() {
            insertTemplate("나중 제목", "나중 본문", OffsetDateTime.now().plusDays(1));

            assertThatThrownBy(() -> notifications.send(EVENT,
                    NotificationService.Target.order(orderId), userId, Map.of()))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    private void insertTemplate(String subject, String body, OffsetDateTime effectiveAt) {
        jdbc.sql("""
                        insert into notification_template (code, version, subject, body, kind,
                                                           effective_at)
                        values (:code,
                                (select coalesce(max(version), 0) + 1 from notification_template
                                  where code = :code),
                                :subject, :body, 'transactional', :effectiveAt)
                        """)
                .param("code", EVENT)
                .param("subject", subject)
                .param("body", body)
                .param("effectiveAt", effectiveAt)
                .update();
    }

    private Map<String, Object> rowOf(long notificationId) {
        return jdbc.sql("""
                        select status, sent_at, failure_reason from notification
                         where notification_id = :id
                        """)
                .param("id", notificationId)
                .query()
                .singleRow();
    }

    private Map<String, Object> bodyOf(long notificationId) {
        return jdbc.sql("""
                        select subject, body from notification_body where notification_id = :id
                        """)
                .param("id", notificationId)
                .query()
                .singleRow();
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
