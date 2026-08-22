package com.projectshop.shop.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.order.OrderFixture;

/**
 * 알림 표 셋이 무엇을 거부하는가(청크 54a, `D18`·`D18-1`).
 *
 * <p><b>여기 있는 것은 전부 제약이다.</b> `D18` 이 「강제 지점」 표로 넘긴 것들을
 * 발송기가 생기기 전에 스키마로 세워 둔다 — 나중에 앱에 걸면 <b>새 입구가 생길 때 빠뜨리고</b>,
 * 빠뜨린 것은 같은 메일이 두 번 나간 날에야 드러난다.
 */
@DisplayName("알림 스키마")
class NotificationSchemaTest extends PostgresTestBase {

    @Autowired
    private JdbcClient jdbc;

    private long userId;
    private long templateId;
    private long orderId;
    private long sellerOrderId;

    @BeforeEach
    void setUp() {
        // `V44` 가 진짜 문안을 시드한다. 여기서는 검사할 판을 직접 세우므로 먼저 비운다 —
        // 코드와 판 번호가 유니크라 같은 코드로 1판을 또 넣으면 부딪친다.
        jdbc.sql("delete from notification_template").update();

        AuthFixture fixture = new AuthFixture(jdbc);
        userId = fixture.insertUser("notify-buyer@test.local", "받는이");
        long sellerId = fixture.insertSeller("notify-seller", "알림셀러");

        templateId = insertTemplate("order_placed", "transactional");
        orderId = insertOrder();
        sellerOrderId = insertSellerOrder(sellerId);
    }

    @Nested
    @DisplayName("같은 사건에 한 번만")
    class Once {

        @Test
        @DisplayName("한 주문에 같은 사건은 두 번 못 남는다")
        void rejectsSecondNotificationForSameOrder() {
            insertNotification("order_placed", orderId, null);

            assertThatThrownBy(() -> insertNotification("order_placed", orderId, null))
                    .describedAs("앱이 「이미 보냈나」를 조회해서 판단하면 그 사이에 끼어들 틈이 생긴다(`D18`)")
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("사건이 다르면 같은 주문에 또 남는다")
        void allowsAnotherEventForSameOrder() {
            insertNotification("order_placed", orderId, null);

            assertThatCode(() -> insertNotification("payment_completed", orderId, null))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("셀러 주문은 주문과 따로 센다")
        void countsSellerOrderSeparately() {
            insertNotification("supply_delayed", null, sellerOrderId);

            assertThatThrownBy(() -> insertNotification("supply_delayed", null, sellerOrderId))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        /**
         * <b>유니크를 대상마다 따로 건 이유가 이것이다.</b> 셋을 한 유니크에 묶으면
         * 대상이 아예 없는 알림은 {@code event_type} 하나로 유니크가 돼서 평생 한 번밖에 못 나간다.
         */
        @Test
        @DisplayName("대상이 없는 알림은 여러 번 나간다")
        void allowsRepeatedNotificationsWithoutTarget() {
            long adTemplate = insertTemplate("weekly_deal", "advertising");

            assertThatCode(() -> {
                insertAdvertisement(adTemplate);
                insertAdvertisement(adTemplate);
            }).describedAs("광고는 같은 사람에게 여러 번 나간다").doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("막는 것")
    class Guards {

        @Test
        @DisplayName("대상 둘을 동시에 못 가리킨다")
        void rejectsTwoTargets() {
            assertThatThrownBy(() -> insertNotification("order_placed", orderId, sellerOrderId))
                    .describedAs("어느 사건 때문에 보냈나가 둘이면 이력이 답을 못 준다")
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("모르는 사건은 못 남긴다")
        void rejectsUnknownEventType() {
            assertThatThrownBy(() -> insertNotification("무슨일인지모름", orderId, null))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("성공인데 보낸 시각이 없으면 거부한다")
        void rejectsSucceededWithoutSentAt() {
            assertThatThrownBy(() -> insertWithStatus("succeeded", null, null))
                    .describedAs("「보냈다」의 증거가 시각인데 비어 있으면 증명이 안 된다")
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("실패인데 이유가 없으면 거부한다")
        void rejectsFailedWithoutReason() {
            assertThatThrownBy(() -> insertWithStatus("failed", null, null))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("이력이 가리키는 판은 못 지운다")
        void rejectsDeletingReferencedTemplate() {
            insertNotification("order_placed", orderId, null);

            assertThatThrownBy(() -> jdbc.sql(
                            "delete from notification_template where notification_template_id = :id")
                    .param("id", templateId)
                    .update())
                    .describedAs("판을 지우면 그때 무슨 문안으로 보냈나가 사라진다(`D18`)")
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Nested
    @DisplayName("본문은 따로 산다")
    class Body {

        @Test
        @DisplayName("발송 하나에 본문 하나뿐이다")
        void rejectsSecondBody() {
            long notificationId = insertNotification("order_placed", orderId, null);
            insertBody(notificationId);

            assertThatThrownBy(() -> insertBody(notificationId))
                    .describedAs("기본키를 본체와 공유해서 1:1 이 구조로 강제된다(`D22`)")
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("메타를 지우면 본문도 사라진다")
        void cascadesFromNotification() {
            long notificationId = insertNotification("order_placed", orderId, null);
            insertBody(notificationId);

            jdbc.sql("delete from notification where notification_id = :id")
                    .param("id", notificationId)
                    .update();

            assertThat(bodyCount(notificationId))
                    .describedAs("메타가 없는 본문은 누구에게 보낸 것인지 알 길이 없다")
                    .isZero();
        }

        @Test
        @DisplayName("본문을 지워도 메타는 남는다")
        void keepsNotificationWhenBodyIsPurged() {
            long notificationId = insertNotification("order_placed", orderId, null);
            insertBody(notificationId);

            jdbc.sql("delete from notification_body where notification_id = :id")
                    .param("id", notificationId)
                    .update();

            assertThat(notificationExists(notificationId))
                    .describedAs("본문이 여섯 달 먼저 사라져도 「보냈다」는 5년 남는다(`D18-1`)")
                    .isTrue();
        }
    }

    private long insertTemplate(String code, String kind) {
        return jdbc.sql("""
                        insert into notification_template (code, subject, body, kind)
                        values (:code, '제목', '본문', :kind)
                        returning notification_template_id
                        """)
                .param("code", code)
                .param("kind", kind)
                .query(Long.class)
                .single();
    }

    private long insertNotification(String eventType, Long order, Long sellerOrder) {
        return jdbc.sql("""
                        insert into notification (user_id, event_type, kind,
                                                  notification_template_id, channel, status,
                                                  order_id, seller_order_id)
                        values (:userId, :eventType, 'transactional', :templateId, 'email', 'pending',
                                :orderId, :sellerOrderId)
                        returning notification_id
                        """)
                .param("userId", userId)
                .param("eventType", eventType)
                .param("templateId", templateId)
                .param("orderId", order)
                .param("sellerOrderId", sellerOrder)
                .query(Long.class)
                .single();
    }

    private void insertAdvertisement(long adTemplateId) {
        jdbc.sql("""
                        insert into notification (user_id, event_type, kind,
                                                  notification_template_id, channel, status)
                        values (:userId, 'advertisement', 'advertising', :templateId, 'email', 'pending')
                        """)
                .param("userId", userId)
                .param("templateId", adTemplateId)
                .update();
    }

    private void insertWithStatus(String status, OffsetDateTime sentAt, String failureReason) {
        jdbc.sql("""
                        insert into notification (user_id, event_type, kind,
                                                  notification_template_id, channel, status,
                                                  order_id, sent_at, failure_reason)
                        values (:userId, 'order_placed', 'transactional', :templateId, 'email', :status,
                                :orderId, :sentAt, :failureReason)
                        """)
                .param("userId", userId)
                .param("templateId", templateId)
                .param("status", status)
                .param("orderId", orderId)
                .param("sentAt", sentAt)
                .param("failureReason", failureReason)
                .update();
    }

    private void insertBody(long notificationId) {
        jdbc.sql("""
                        insert into notification_body (notification_id, subject, body)
                        values (:id, '제목', '완성된 본문')
                        """)
                .param("id", notificationId)
                .update();
    }

    private int bodyCount(long notificationId) {
        return jdbc.sql("select count(*) from notification_body where notification_id = :id")
                .param("id", notificationId)
                .query(Integer.class)
                .single();
    }

    private boolean notificationExists(long notificationId) {
        return Boolean.TRUE.equals(jdbc.sql(
                        "select exists(select 1 from notification where notification_id = :id)")
                .param("id", notificationId)
                .query(Boolean.class)
                .single());
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

    private long insertSellerOrder(long sellerId) {
        long id = jdbc.sql("""
                        insert into seller_order (seller_order_number, order_id, seller_id, shipping_fee)
                        values (:number, :orderId, :sellerId, 0)
                        returning seller_order_id
                        """)
                .param("number", OrderFixture.sellerOrderNumber())
                .param("orderId", orderId)
                .param("sellerId", sellerId)
                .query(Long.class)
                .single();

        jdbc.sql("""
                        insert into order_item (seller_order_id, sku_id, product_name,
                                                unit_price_incl_vat, quantity, line_amount,
                                                commission_bp, commission_amount)
                        values (:sellerOrderId, :skuId, '알림 상품', 10000, 1, 10000, 1000, 1000)
                        """)
                .param("sellerOrderId", id)
                .param("skuId", insertSku(sellerId))
                .update();

        return id;
    }

    private long insertSku(long sellerId) {
        long productId = jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name)
                        values (:sellerId, :userId, '알림 상품')
                        returning product_id
                        """)
                .param("sellerId", sellerId)
                .param("userId", userId)
                .query(Long.class)
                .single();

        return jdbc.sql("""
                        insert into sku (product_id, price_incl_vat)
                        values (:productId, 10000)
                        returning sku_id
                        """)
                .param("productId", productId)
                .query(Long.class)
                .single();
    }
}
