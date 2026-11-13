package com.projectshop.shop;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.order.OrderFixture;

/**
 * 상품 묶음의 회계 밖 {@code check} 를 하나씩 부순다(`Q426d`). <b>사례마다 그 제약 하나만 어기는 쓰기를 내고 이름으로 거부를 단언한다.</b>
 *
 * <p>{@code CheckCoverageTest} 의 {@code COVERED_CATALOG} 가 이 파일을 부수는 시험으로 든다. 정상 행은 상품·SKU·재고 하나와
 * 배송 완료 주문 항목에 붙은 후기 하나다. 사진·신고·문의는 사례가 직접 넣는다.
 *
 * <p>한 쓰기가 여러 제약을 어기면 Postgres 는 이름 알파벳 순으로 앞의 것을 낸다 — 사례는 다른 칸을 맞춰 그 제약 하나만 어긴다.
 */
@DisplayName("상품 묶음 check 위반")
class CheckViolationCatalogTest extends PostgresTestBase {

    /** 문의 번호 꼴({@code Q-날짜-여섯 글자})에 맞는 값. 사례마다 한 행이라 겹치지 않는다 */
    private static final String INQUIRY_NUMBER = "Q-20260101-222222";

    @Autowired
    private JdbcClient jdbc;

    private Map<String, Object> ids;

    @BeforeEach
    void setUp() {
        AuthFixture fixture = new AuthFixture(jdbc);
        long buyerId = fixture.insertUser("q426d-buyer@test.local", "산사람");
        long sellerId = fixture.insertSeller("s-q426d", "상품셀러");

        OrderFixture.Line line = OrderFixture.deliveredLine(jdbc, buyerId, sellerId, "상품 시험 상품", 0);
        long bundleId = line.bundleId();
        long productId = line.productId();
        long skuId = line.skuId();
        long itemId = line.itemId();
        jdbc.sql("insert into sku_stock (sku_id, on_hand) values (:skuId, 10)")
                .param("skuId", skuId)
                .update();

        long reviewId = jdbc.sql("""
                        insert into review (order_item_id, product_id, user_id, rating, body)
                        values (:itemId, :productId, :userId, 5, '잘 받았다')
                        returning review_id
                        """)
                .param("itemId", itemId)
                .param("productId", productId)
                .param("userId", buyerId)
                .query(Long.class)
                .single();

        ids = Map.of("product", productId, "sku", skuId, "bundle", bundleId, "review", reviewId,
                "user", buyerId, "inquiryNumber", INQUIRY_NUMBER);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("violations")
    @DisplayName("제약 하나만 어기는 쓰기는 그 이름으로 거부된다")
    void rejects(String constraint, String sql) {
        assertThatThrownBy(() -> jdbc.sql(sql).params(ids).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    private static Stream<Arguments> violations() {
        return Stream.of(
                Arguments.of("product_commission_bp_check",
                        "update product set commission_bp = 10001 where product_id = :product"),
                Arguments.of("product_supply_lead_days_check",
                        "update product set supply_lead_days = 61 where product_id = :product"),
                Arguments.of("product_image_byte_size_check", """
                        insert into product_image (product_id, object_key, thumbnail_key, original_name,
                                                   content_type, byte_size)
                        values (:product, 'k/q426d.jpg', 'k/q426d-thumb.jpg', 'a.jpg', 'image/jpeg', 0)
                        """),
                Arguments.of("sku_price_incl_vat_check",
                        "update sku set price_incl_vat = -1 where sku_id = :sku"),
                Arguments.of("sku_stock_on_hand_check",
                        "update sku_stock set on_hand = -1 where sku_id = :sku"),
                Arguments.of("sku_stock_safety_stock_check",
                        "update sku_stock set safety_stock = -1 where sku_id = :sku"),
                Arguments.of("sku_stock_movement_quantity_check",
                        "insert into sku_stock_movement (sku_id, quantity, reason) values (:sku, 0, 'adjustment')"),
                Arguments.of("review_block_check",
                        "update review set blocked_at = now() where review_id = :review"),
                Arguments.of("review_rating_check",
                        "update review set rating = 6 where review_id = :review"),
                Arguments.of("review_image_byte_size_check", """
                        insert into review_image (review_id, object_key, thumbnail_key, original_name,
                                                  content_type, byte_size)
                        values (:review, 'r/q426d.jpg', 'r/q426d-thumb.jpg', 'a.jpg', 'image/jpeg', 0)
                        """),
                Arguments.of("review_report_resolution_check", """
                        insert into review_report (review_id, reporter_user_id, reason, resolved_at)
                        values (:review, :user, 'abuse', now())
                        """),
                Arguments.of("inquiry_answer_check", """
                        insert into inquiry (inquiry_number, kind, user_id, question, product_id, answered_at)
                        values (:inquiryNumber, 'product', :user, '문의', :product, now())
                        """),
                Arguments.of("inquiry_block_check", """
                        insert into inquiry (inquiry_number, kind, user_id, question, product_id, status)
                        values (:inquiryNumber, 'product', :user, '문의', :product, 'blocked')
                        """),
                Arguments.of("inquiry_due_at_check", """
                        insert into inquiry (inquiry_number, kind, user_id, question, product_id, due_at)
                        values (:inquiryNumber, 'product', :user, '문의', :product, now())
                        """),
                Arguments.of("inquiry_number_format_check", """
                        insert into inquiry (inquiry_number, kind, user_id, question, product_id)
                        values ('Q-bad', 'product', :user, '문의', :product)
                        """),
                Arguments.of("inquiry_target_check", """
                        insert into inquiry (inquiry_number, kind, user_id, question)
                        values (:inquiryNumber, 'product', :user, '문의')
                        """),
                Arguments.of("inquiry_visibility_check", """
                        insert into inquiry (inquiry_number, kind, user_id, question, seller_order_id, is_public)
                        values (:inquiryNumber, 'order', :user, '문의', :bundle, true)
                        """),
                Arguments.of("copyright_report_decision_complete_check", """
                        insert into copyright_report (product_id, reporter_name, reporter_email, claimed_work,
                                                      target, decision)
                        values (:product, '신고인', 'reporter@test.local', '원작', 'product_image', 'rejected')
                        """),
                Arguments.of("copyright_report_target_reference_check", """
                        with new_image as (
                            insert into product_image (product_id, object_key, thumbnail_key, original_name,
                                                       content_type, byte_size)
                            values (:product, 'k/q426d.jpg', 'k/q426d-thumb.jpg', 'a.jpg', 'image/jpeg', 1)
                            returning product_image_id
                        )
                        insert into copyright_report (product_id, reporter_name, reporter_email, claimed_work,
                                                      target, product_image_id)
                        select :product, '신고인', 'reporter@test.local', '원작', 'review_image', product_image_id
                          from new_image
                        """));
    }
}
