package com.projectshop.shop.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import com.projectshop.shop.StorageTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.support.ImagePipeline;

/**
 * 거래 축의 보존 기간이 실제로 지켜지는가.
 *
 * <p>여기서 보는 것은 <b>두 기간이 다르게 흐른다</b>는 것이다. 배송지는 6개월에 사라지고
 * 주문은 5년을 채운다 — R6(보존)과 R9(파기)가 부딪히는 자리를 테이블을 갈라서 푼 결과라,
 * 둘이 같이 사라지면 그 설계가 무의미해진다.
 */
@DisplayName("거래기록 파기")
class TransactionPurgeServiceTest extends StorageTestBase {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 8, 9, 0, 0, 0, 0, ZoneOffset.ofHours(9));

    @Autowired
    private TransactionPurgeService purgeService;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ImagePipeline images;

    @Autowired
    private S3Client s3;

    private long userId;
    private long sellerId;
    private long skuId;

    @BeforeEach
    void setUp() {
        AuthFixture fixture = new AuthFixture(jdbc);
        userId = fixture.insertUser("purge-buyer@test.local", "구매자");
        sellerId = fixture.insertSeller("purge-seller", "파기셀러");
        skuId = insertSku();
    }

    @Nested
    @DisplayName("배송지는")
    class ShippingAddress {

        @Test
        @DisplayName("거래가 끝나고 6개월이 지나면 사라진다")
        void isErasedAfterSixMonths() {
            long orderId = orderClosedAt(NOW.minusMonths(7));

            purgeService.purge(NOW);

            assertThat(shippingExists(orderId))
                    .as("제6조 제2항이 보존을 권리로만 주므로 쓸 일이 끝나면 버린다")
                    .isFalse();
        }

        @Test
        @DisplayName("아직 6개월이 안 됐으면 남는다")
        void survivesWithinSixMonths() {
            long orderId = orderClosedAt(NOW.minusMonths(5));

            purgeService.purge(NOW);

            assertThat(shippingExists(orderId))
                    .as("반품·교환이 늦게 오는 것까지 덮는 기간이다")
                    .isTrue();
        }

        @Test
        @DisplayName("지워져도 주문은 남는다")
        void leavesTheOrderBehind() {
            long orderId = orderClosedAt(NOW.minusMonths(7));

            purgeService.purge(NOW);

            assertThat(orderExists(orderId))
                    .as("거래 사실은 5년을 채운다. 테이블을 가른 이유가 이것이다")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("카드 정보는")
    class PaymentCard {

        @Test
        @DisplayName("거래가 끝나고 6개월이 지나면 사라진다")
        void isErasedAfterSixMonths() {
            long orderId = orderClosedAt(NOW.minusMonths(7));
            insertPaymentWithCard(orderId);

            purgeService.purge(NOW);

            assertThat(paymentCardExists(orderId))
                    .as("보존분 분리라 배송지와 같은 수명이다(`D2` R9, 제21조제3항)")
                    .isFalse();
        }

        @Test
        @DisplayName("아직 6개월이 안 됐으면 남는다")
        void survivesWithinSixMonths() {
            long orderId = orderClosedAt(NOW.minusMonths(5));
            insertPaymentWithCard(orderId);

            purgeService.purge(NOW);

            assertThat(paymentCardExists(orderId))
                    .as("「어느 카드로 냈나」는 분쟁과 함께 오는 물음이라 그 기간을 덮는다")
                    .isTrue();
        }

        @Test
        @DisplayName("지워져도 결제 기록은 남는다")
        void leavesThePaymentBehind() {
            long orderId = orderClosedAt(NOW.minusMonths(7));
            insertPaymentWithCard(orderId);

            purgeService.purge(NOW);

            assertThat(paymentExists(orderId))
                    .as("금액·승인번호·수단이 남아서 대금결제 기록은 5년을 채운다(제6조제1항)")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("주문은")
    class Orders {

        @Test
        @DisplayName("5년이 지나면 사라진다")
        void isErasedAfterFiveYears() {
            long orderId = orderClosedAt(NOW.minusYears(6));

            purgeService.purge(NOW);

            assertThat(orderExists(orderId)).isFalse();
        }

        /**
         * 후기 사진은 <b>저장소까지</b> 지운다(`Q159`). 행은 후기의 cascade 로도 사라지지만 그렇게 두면
         * 공개 버킷에 주인 없는 사진이 남는다 — cascade 를 파기 수단으로 안 쓴다(`D23`).
         */
        @Test
        @DisplayName("후기 사진은 저장소의 객체까지 같이 사라진다")
        void erasesReviewPhotosFromStorage() {
            long orderId = orderClosedAt(NOW.minusYears(6));
            long orderItemId = jdbc.sql("""
                            select oi.order_item_id from order_item oi
                              join seller_order so on so.seller_order_id = oi.seller_order_id
                             where so.order_id = :id
                            """)
                    .param("id", orderId).query(Long.class).single();
            long productId = jdbc.sql("select product_id from sku where sku_id = :id")
                    .param("id", skuId).query(Long.class).single();
            // 후기는 받아 본 뒤에만 쓴다(`check_review_target`). 이 fixture 의 묶음은 상태가 `preparing` 이다.
            jdbc.sql("update seller_order set status = 'confirmed' where order_id = :id")
                    .param("id", orderId).update();
            long reviewId = jdbc.sql("""
                            insert into review (order_item_id, product_id, user_id, rating, body)
                            values (:item, :product, :user, 5, '오래전에 산 물건의 후기')
                            returning review_id
                            """)
                    .param("item", orderItemId).param("product", productId).param("user", userId)
                    .query(Long.class).single();
            ImagePipeline.Stored stored = images.store("review", photo());
            jdbc.sql("""
                            insert into review_image (review_id, object_key, thumbnail_key, original_name,
                                                      content_type, byte_size)
                            values (:review, :key, :thumb, :name, :type, :size)
                            """)
                    .param("review", reviewId).param("key", stored.objectKey())
                    .param("thumb", stored.thumbnailKey()).param("name", stored.originalName())
                    .param("type", stored.contentType()).param("size", stored.byteSize())
                    .update();

            purgeService.purge(NOW);

            assertThat(countOf("select count(*) from review_image")).isZero();
            assertThatThrownBy(() -> s3.headObject(b -> b.bucket(PUBLIC_BUCKET).key(stored.objectKey())))
                    .isInstanceOf(NoSuchKeyException.class);
            assertThatThrownBy(() -> s3.headObject(b -> b.bucket(PUBLIC_BUCKET).key(stored.thumbnailKey())))
                    .isInstanceOf(NoSuchKeyException.class);
        }

        @Test
        @DisplayName("항목과 셀러 주문도 같이 사라진다")
        void takesItsChildrenWithIt() {
            orderClosedAt(NOW.minusYears(6));

            purgeService.purge(NOW);

            assertThat(countOf("select count(*) from order_item"))
                    .as("참조가 restrict 라 자식부터 지운다. cascade 를 파기 수단으로 안 쓴다(`D23`)")
                    .isZero();
            assertThat(countOf("select count(*) from seller_order")).isZero();
        }

        @Test
        @DisplayName("상태 이력도 같이 사라진다")
        void takesItsHistoryWithIt() {
            long orderId = orderClosedAt(NOW.minusYears(6));
            insertHistory(orderId);

            purgeService.purge(NOW);

            assertThat(countOf("select count(*) from order_status_history"))
                    .as("이력이 주문을 restrict 로 잡는다. 안 지우면 5년 파기가 통째로 실패한다(`V18`)")
                    .isZero();
            assertThat(orderExists(orderId)).isFalse();
        }

        @Test
        @DisplayName("아직 5년이 안 됐으면 남는다")
        void survivesWithinFiveYears() {
            long orderId = orderClosedAt(NOW.minusYears(4));

            purgeService.purge(NOW);

            assertThat(orderExists(orderId))
                    .as("계약·청약철회 기록은 5년이다(시행령 제6조)")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("안 끝난 거래는")
    class OpenOrders {

        @Test
        @DisplayName("아무리 오래돼도 대상이 아니다")
        void areNeverPurged() {
            long orderId = insertOrder("20260809-7QX4P2");
            insertSellerOrder(orderId, null);
            insertShipping(orderId);

            purgeService.purge(NOW);

            assertThat(shippingExists(orderId))
                    .as("`closed_at` 이 비어 있으면 기산점이 없다. 청크 11 이 채운다")
                    .isTrue();
        }

        @Test
        @DisplayName("셀러 하나만 안 끝나도 대상이 아니다")
        void needEverySellerOrderClosed() {
            long orderId = insertOrder("20260809-7QX4P3");
            insertSellerOrder(orderId, NOW.minusYears(6));
            insertSellerOrder(orderId, null, secondSeller());
            insertShipping(orderId);

            purgeService.purge(NOW);

            assertThat(shippingExists(orderId))
                    .as("하나가 반품 중인데 배송지를 지우면 그 반품을 처리할 수 없다")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("감사 로그는")
    class AuditLogs {

        @Test
        @DisplayName("3년이 지나면 사라진다")
        void isErasedAfterThreeYears() {
            insertAuditLog(NOW.minusYears(4));
            insertAuditLog(NOW.minusYears(2));

            purgeService.purge(NOW);

            assertThat(countOf("select count(*) from audit_log"))
                    .as("분쟁이 늦게 터져도 닿는 기간이다(`D13`)")
                    .isEqualTo(1);
        }
    }

    /** 끝난 주문 하나. 배송지와 항목까지 갖춘다 */
    /**
     * 손해배상 판정의 수명(`43a-4b`, `D13`).
     *
     * <p><b>순서가 이 묶음의 요점이다.</b> {@code compensation.seller_order_id} 가 restrict 라
     * 배상을 안 지우면 <b>5년째 주문 파기가 외래키에 걸려서 통째로 멈춘다</b> —
     * 그 사고는 배상 표가 비어 있는 동안에는 안 나고, 판정이 실제로 쌓인 뒤에 처음 난다.
     */
    @Nested
    @DisplayName("손해배상 사유 글은")
    class CompensationNotes {

        @Test
        @DisplayName("3년이 지나면 사라진다")
        void isErasedAfterThreeYears() {
            insertCompensation(orderClosedAt(NOW.minusYears(1)), NOW.minusYears(4));

            purgeService.purge(NOW);

            assertThat(countOf("select count(*) from compensation_note"))
                    .as("분쟁처리 기록은 3년이다(시행령 제6조 4호)")
                    .isZero();
        }

        @Test
        @DisplayName("지워져도 판정은 남는다")
        void leavesTheDecisionBehind() {
            insertCompensation(orderClosedAt(NOW.minusYears(1)), NOW.minusYears(4));

            purgeService.purge(NOW);

            assertThat(countOf("select count(*) from compensation"))
                    .as("판정은 정산의 근거라 장부와 같이 산다 — 사라지는 것은 사람이 쓴 글뿐이다")
                    .isOne();
        }

        @Test
        @DisplayName("아직 3년이 안 됐으면 남는다")
        void survivesWithinThreeYears() {
            insertCompensation(orderClosedAt(NOW.minusYears(1)), NOW.minusYears(2));

            purgeService.purge(NOW);

            assertThat(countOf("select count(*) from compensation_note")).isOne();
        }
    }


    /**
     * 주문을 {@code restrict} 로 잡는 표가 남아 있으면 <b>파기 회차 전체가 롤백된다</b>(`43a-28b`).
     *
     * <p>그래서 보는 것이 「지워졌나」만이 아니다 — <b>예외 없이 끝났나</b>가 같이 걸린다.
     * 고치기 전에는 첫 테스트가 restrict 위반으로 죽었다.
     */
    @Nested
    @DisplayName("주문을 잡는 것이 있으면")
    class Blockers {

        @Test
        @DisplayName("정산이 살아 있는 동안은 주문을 안 지운다")
        void keepsTheOrderWhileSettlementLives() {
            long orderId = orderClosedAt(NOW.minusYears(6));
            // 2026년 2기 거래라 보존이 2032년까지다. 아직 한참 남았다.
            insertSettlement(orderId, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

            purgeService.purge(NOW);

            assertThat(orderExists(orderId))
                    .as("정산이 장부라 5년이 지나도 못 지운다(개인정보법 제21조① 단서, `R41`)."
                            + " 억지로 지우면 restrict 가 거부하고 이 회차 전체가 롤백된다")
                    .isTrue();
            assertThat(countOf("select count(*) from settlement_cycle")).isOne();
        }

        @Test
        @DisplayName("정산 보존이 끝나면 주기를 통째로 지우고 주문도 따라 지운다")
        void purgesTheCycleThenTheOrder() {
            long orderId = orderClosedAt(NOW.minusYears(6));
            // 2019년 1기 거래 — 신고기한 2019-07-25 다음날부터 5년이라 2024-07-26 에 끝났다.
            insertSettlement(orderId, LocalDate.of(2019, 6, 1), LocalDate.of(2019, 6, 30));

            purgeService.purge(NOW);

            assertThat(countOf("select count(*) from settlement_cycle"))
                    .as("줄만 지우면 지연 트리거가 합계 불일치로 막는다. 주기 통째로 지운다")
                    .isZero();
            assertThat(countOf("select count(*) from settlement_item")).isZero();
            assertThat(orderExists(orderId))
                    .as("같은 회차 안에서 정산이 먼저 사라지므로 주문이 이어서 지워진다")
                    .isFalse();
        }

        @Test
        @DisplayName("배상 판정도 보존이 끝나야 주문이 지워진다")
        void waitsForTheCompensation() {
            long stillHeld = orderClosedAt(NOW.minusYears(6));
            insertCompensation(stillHeld, NOW.minusYears(1));

            long released = orderClosedAt(NOW.minusYears(6));
            insertCompensation(released, OffsetDateTime.of(2019, 6, 30, 0, 0, 0, 0, ZoneOffset.ofHours(9)));

            purgeService.purge(NOW);

            assertThat(orderExists(stillHeld))
                    .as("정산의 근거라 장부와 같이 산다(`data-lifecycle.md` 「손해배상 판정」)")
                    .isTrue();
            assertThat(orderExists(released)).isFalse();
            assertThat(countOf("select count(*) from compensation")).isOne();
        }

        @Test
        @DisplayName("문의가 달린 주문은 문의가 사라진 뒤에 지워진다")
        void waitsForTheInquiry() {
            long stillHeld = orderClosedAt(NOW.minusYears(6));
            // 주문이 닫히고 한참 뒤에 달린 문의다. 문의는 3년인데 기산이 작성일이라
            // 주문(5년, 거래 종료 기산)보다 늦게 만료된다 — 그 조합이 파기를 막던 자리다.
            insertOrderInquiry(stillHeld, NOW.minusYears(1));

            long released = orderClosedAt(NOW.minusYears(6));
            insertOrderInquiry(released, NOW.minusYears(4));

            purgeService.purge(NOW);

            assertThat(orderExists(stillHeld))
                    .as("문의는 시행령 제6조 4호의 3년이라 아직 살아 있고, 주문을 restrict 로 잡는다")
                    .isTrue();
            assertThat(orderExists(released)).isFalse();
            assertThat(countOf("select count(*) from inquiry")).isOne();
        }
    }

    /** 그 주문의 항목 하나를 근거로 정산서 한 장을 세운다. 지급액은 줄 합과 같아야 한다 */
    private void insertSettlement(long orderId, LocalDate periodStart, LocalDate periodEnd) {
        long cycleId = jdbc.sql("""
                        insert into settlement_cycle (period_start, period_end, payout_date)
                        values (:start, :end, :payout)
                        returning settlement_cycle_id
                        """)
                .param("start", periodStart)
                .param("end", periodEnd)
                .param("payout", periodEnd.plusMonths(1).withDayOfMonth(10))
                .query(Long.class)
                .single();

        long settlementId = jdbc.sql("""
                        insert into settlement (settlement_number, settlement_cycle_id, seller_id, payout_amount)
                        values (:number, :cycleId, :sellerId, 10000)
                        returning settlement_id
                        """)
                .param("number", "T-20260809-K3M9P" + (char) ('2' + counter++))
                .param("cycleId", cycleId)
                .param("sellerId", sellerId)
                .query(Long.class)
                .single();

        jdbc.sql("""
                        insert into settlement_item (settlement_id, kind, order_item_id, amount)
                        select :settlementId, 'sale', oi.order_item_id, 10000
                          from order_item oi
                          join seller_order so on so.seller_order_id = oi.seller_order_id
                         where so.order_id = :orderId
                         limit 1
                        """)
                .param("settlementId", settlementId)
                .param("orderId", orderId)
                .update();
    }

    /** 그 주문의 묶음에 달린 문의 하나 */
    private void insertOrderInquiry(long orderId, OffsetDateTime createdAt) {
        jdbc.sql("""
                        insert into inquiry (inquiry_number, kind, user_id, question,
                                             seller_order_id, created_at)
                        select :number, 'order', :userId, '언제 처리되나',
                               so.seller_order_id, :createdAt
                          from seller_order so
                         where so.order_id = :orderId
                         limit 1
                        """)
                .param("number", "Q-20260809-K3M9P" + (char) ('2' + counter++))
                .param("userId", userId)
                .param("createdAt", createdAt)
                .param("orderId", orderId)
                .update();
    }
    private void insertCompensation(long orderId, OffsetDateTime decidedAt) {
        long sellerOrderId = jdbc.sql(
                        "select seller_order_id from seller_order where order_id = :id")
                .param("id", orderId)
                .query(Long.class)
                .single();

        long compensationId = jdbc.sql("""
                        insert into compensation (seller_order_id, kind, bearer, amount,
                                                  decided_by_user_id, decided_at)
                        values (:sellerOrderId, 'late_delivery', 'seller', 10000, :userId, :decidedAt)
                        returning compensation_id
                        """)
                .param("sellerOrderId", sellerOrderId)
                .param("userId", userId)
                .param("decidedAt", decidedAt)
                .query(Long.class)
                .single();

        jdbc.sql("""
                        insert into compensation_note (compensation_id, reason)
                        values (:id, '발송이 늦어 구매목적을 달성하지 못했다')
                        """)
                .param("id", compensationId)
                .update();
    }

    private static ImagePipeline.Incoming photo() {
        BufferedImage image = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "jpeg", out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return new ImagePipeline.Incoming("old.jpg", out.toByteArray());
    }

    private long orderClosedAt(OffsetDateTime closedAt) {
        long orderId = insertOrder("20260809-7QX4P" + (char) ('4' + counter++));
        insertSellerOrder(orderId, closedAt);
        insertShipping(orderId);
        return orderId;
    }

    private int counter;

    @Nested
    @DisplayName("알림은")
    class Notifications {

        @Test
        @DisplayName("광고 이력이 여섯 달을 넘기면 사라진다")
        void purgesOldAdvertisements() {
            long id = insertNotification("advertising", "advertisement", NOW.minusMonths(7));

            purgeService.purge(NOW);

            // 시행령 제6조제1항 1호 「표시·광고에 관한 기록」이 여섯 달이다(`D18-1`).
            assertThat(notificationExists(id)).isFalse();
        }

        @Test
        @DisplayName("거래 통지는 여섯 달로는 안 사라진다")
        void keepsNoticesForFiveYears() {
            long id = insertNotification("transactional", "order_placed", NOW.minusMonths(7));

            purgeService.purge(NOW);

            // 같은 표인데 칸이 다르다 — 2·3호라 5년이다. 짧게 잡으면 4~5년차 분쟁에서
            // 「보냈다」를 증명할 것이 사라진다.
            assertThat(notificationExists(id)).isTrue();
        }

        @Test
        @DisplayName("거래 통지도 5년을 넘기면 사라진다")
        void purgesNoticesAfterFiveYears() {
            long id = insertNotification("transactional", "order_placed", NOW.minusYears(6));

            purgeService.purge(NOW);

            assertThat(notificationExists(id)).isFalse();
        }

        @Test
        @DisplayName("본문은 먼저 사라지고 메타는 남는다")
        void purgesBodyBeforeMeta() {
            long id = insertNotification("transactional", "order_placed", NOW.minusMonths(7));
            jdbc.sql("""
                            insert into notification_body (notification_id, subject, body)
                            values (:id, '제목', '완성된 본문')
                            """)
                    .param("id", id)
                    .update();

            purgeService.purge(NOW);

            // 표를 갈라 둔 것이 여기서 값을 한다. 한 표면 파기가 컬럼을 비우는 일이 되고,
            // 안 비운 행이 섞여도 아무 제약이 안 걸린다(`D18-1`).
            assertThat(countOf("select count(*) from notification_body where notification_id = " + id))
                    .isZero();
            assertThat(notificationExists(id)).isTrue();
        }

        private long insertNotification(String kind, String eventType, OffsetDateTime createdAt) {
            long templateId = jdbc.sql("""
                            insert into notification_template (code, subject, body, kind)
                            values (:code, '제목', '본문', :kind)
                            returning notification_template_id
                            """)
                    .param("code", eventType + "-" + kind)
                    .param("kind", kind)
                    .query(Long.class)
                    .single();

            return jdbc.sql("""
                            insert into notification (user_id, event_type, kind,
                                                      notification_template_id, channel, status,
                                                      created_at)
                            values (:userId, :eventType, :kind, :templateId, 'email', 'pending',
                                    :createdAt)
                            returning notification_id
                            """)
                    .param("userId", userId)
                    .param("eventType", eventType)
                    .param("kind", kind)
                    .param("templateId", templateId)
                    .param("createdAt", createdAt)
                    .query(Long.class)
                    .single();
        }

        private boolean notificationExists(long id) {
            return exists("select 1 from notification where notification_id = " + id);
        }
    }

    private long insertOrder(String number) {
        long orderId = jdbc.sql("""
                        insert into shop_order (order_number, user_id, total_amount,
                                                commission_total, shipping_fee_total, payable_amount)
                        values (:number, :userId, 10000, 1000, 0, 10000)
                        returning order_id
                        """)
                .param("number", number)
                .param("userId", userId)
                .query(Long.class)
                .single();

        // `V31` 이 서면 없는 주문을 막는다. 여기는 서면이 관심사가 아니라 껍데기만 채운다.
        OrderFixture.attachContractDocuments(jdbc, orderId);
        return orderId;
    }

    private void insertSellerOrder(long orderId, OffsetDateTime closedAt) {
        insertSellerOrder(orderId, closedAt, sellerId);
    }

    private void insertSellerOrder(long orderId, OffsetDateTime closedAt, long seller) {
        long sellerOrderId = jdbc.sql("""
                        insert into seller_order (seller_order_number, order_id, seller_id,
                                                  shipping_fee, closed_at)
                        values (:number, :orderId, :sellerId, 0, :closedAt)
                        returning seller_order_id
                        """)
                .param("number", OrderFixture.sellerOrderNumber())
                .param("orderId", orderId)
                .param("sellerId", seller)
                .param("closedAt", closedAt)
                .query(Long.class)
                .single();

        jdbc.sql("""
                        insert into order_item (seller_order_id, sku_id, product_name,
                                                unit_price_incl_vat, quantity, line_amount,
                                                commission_bp, commission_amount)
                        values (:sellerOrderId, :skuId, '파기 상품', 10000, 1, 10000, 1000, 1000)
                        """)
                .param("sellerOrderId", sellerOrderId)
                .param("skuId", skuId)
                .update();
    }

    /** 두 층 모두 이력을 남긴다. 파기는 둘 다 걷어야 한다 */
    private void insertHistory(long orderId) {
        jdbc.sql("""
                        insert into order_status_history (order_id, from_status, to_status, actor_type)
                        values (:orderId, 'payment_pending', 'paid', 'system')
                        """)
                .param("orderId", orderId)
                .update();

        jdbc.sql("""
                        insert into order_status_history (seller_order_id, from_status, to_status, actor_type)
                        select seller_order_id, 'shipping', 'delivered', 'system'
                          from seller_order where order_id = :orderId
                        """)
                .param("orderId", orderId)
                .update();
    }

    private void insertShipping(long orderId) {
        jdbc.sql("""
                        insert into order_shipping (order_id, receiver_name, receiver_phone,
                                                    postal_code, address1)
                        values (:orderId, '홍길동', '010-0000-0000', '06134', '서울시 강남구')
                        """)
                .param("orderId", orderId)
                .update();
    }

    private void insertAuditLog(OffsetDateTime createdAt) {
        jdbc.sql("""
                        insert into audit_log (event_type, actor_user_id, detail, created_at)
                        values ('test.event', :userId, '{}'::jsonb, :createdAt)
                        """)
                .param("userId", userId)
                .param("createdAt", createdAt)
                .update();
    }

    private long secondSeller() {
        return new AuthFixture(jdbc).insertSeller("purge-seller-2", "둘째셀러");
    }

    private long insertSku() {
        long productId = jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name)
                        values (:sellerId, :userId, '파기 상품')
                        returning product_id
                        """)
                .param("sellerId", sellerId)
                .param("userId", userId)
                .query(Long.class)
                .single();

        return jdbc.sql("""
                        with new_sku as (
                            insert into sku (product_id, price_incl_vat)
                            values (:productId, 10000)
                            returning sku_id
                        )
                        insert into sku_stock (sku_id, on_hand)
                        select sku_id, 10 from new_sku
                        returning sku_id
                        """)
                .param("productId", productId)
                .query(Long.class)
                .single();
    }

    private boolean shippingExists(long orderId) {
        return exists("select 1 from order_shipping where order_id = " + orderId);
    }

    /** 카드 결제 하나를 만든다. 카드 정보는 갈라진 표에 들어간다(`D2` R9) */
    private void insertPaymentWithCard(long orderId) {
        long paymentId = jdbc.sql("""
                        insert into payment (order_id, status, method, amount, approval_number)
                        values (:orderId, 'approved', 'card', 10000, 'APPROVAL-1')
                        returning payment_id
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .single();

        jdbc.sql("""
                        insert into payment_card (payment_id, card_issuer, card_last4)
                        values (:paymentId, '비자', '4242')
                        """)
                .param("paymentId", paymentId)
                .update();
    }

    private boolean paymentCardExists(long orderId) {
        return exists("select 1 from payment_card c"
                + " join payment p on p.payment_id = c.payment_id where p.order_id = " + orderId);
    }

    private boolean paymentExists(long orderId) {
        return exists("select 1 from payment where order_id = " + orderId);
    }

    private boolean orderExists(long orderId) {
        return exists("select 1 from shop_order where order_id = " + orderId);
    }

    private boolean exists(String sql) {
        return Boolean.TRUE.equals(
                jdbc.sql("select exists(" + sql + ")").query(Boolean.class).single());
    }

    private int countOf(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }
}
