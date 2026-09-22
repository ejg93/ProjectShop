package com.projectshop.shop.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;

/**
 * 후기 표가 무엇을 막나(`46`).
 *
 * <p>입구가 아직 없다 — 쓰는 자격은 `47`, 셀러 답글·신고는 `48` 이다. 그래서 이 시험이
 * 재는 것은 <b>표 스스로 막는 것</b>뿐이고, 그것이 이 청크의 닫힘이다.
 *
 * <p>막는 것이 셋이다 — <b>안 산 줄에 못 붙고</b>(외래키), <b>주문 줄과 어긋난 상품·사람이
 * 못 들어오고</b>(트리거), <b>별이 1~5 밖으로 못 나간다</b>(check).
 * 가운데가 제일 값이 크다: 어긋나도 화면에서는 그럴듯해 보인다.
 */
@DisplayName("후기 표가 막는 것")
class ReviewSchemaTest extends PostgresTestBase {

    @Autowired
    private JdbcClient jdbc;

    private ReviewFixture fixture;
    private long orderItemId;
    private long productId;
    private long buyerId;

    @BeforeEach
    void setUp() {
        fixture = new ReviewFixture(jdbc);
        productId = fixture.insertProduct("후기 상품");
        // 받아 본 뒤에만 쓴다(`47`). 그 조건은 `ReviewEligibilityTest` 가 잰다.
        orderItemId = fixture.placeOrder(productId, "delivered");
        buyerId = fixture.buyerId();
    }

    @Nested
    @DisplayName("들어가는 것")
    class Accepted {

        @Test
        @DisplayName("산 줄에 붙은 후기는 들어간다")
        void 산_줄에_붙은_후기는_들어간다() {
            fixture.insertReview(orderItemId, productId, buyerId, 5, "잘 받았다");

            assertThat(count()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("막는 것")
    class Rejected {

        @Test
        @DisplayName("주문 줄과 다른 상품에는 못 붙는다")
        void 주문_줄과_다른_상품에는_못_붙는다() {
            long other = fixture.insertProduct("다른 상품");

            assertThatThrownBy(() -> fixture.insertReview(orderItemId, other, buyerId, 5, "잘 받았다"))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("주문 줄과 다르다");
        }

        @Test
        @DisplayName("주문자가 아닌 사람은 못 쓴다")
        void 주문자가_아닌_사람은_못_쓴다() {
            long stranger = fixture.insertUser("stranger@example.com");

            assertThatThrownBy(
                    () -> fixture.insertReview(orderItemId, productId, stranger, 5, "잘 받았다"))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("주문자가 아니다");
        }

        @Test
        @DisplayName("별은 1~5 밖으로 못 나간다")
        void 별은_1에서_5_밖으로_못_나간다() {
            assertThatThrownBy(() -> fixture.insertReview(orderItemId, productId, buyerId, 6, "잘"))
                    .isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> fixture.insertReview(orderItemId, productId, buyerId, 0, "잘"))
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("빈 본문은 못 들어간다")
        void 빈_본문은_못_들어간다() {
            assertThatThrownBy(() -> fixture.insertReview(orderItemId, productId, buyerId, 5, ""))
                    .isInstanceOf(DataAccessException.class);
        }
    }

    private int count() {
        return jdbc.sql("select count(*) from review where deleted_at is null")
                .query(Integer.class)
                .single();
    }
}
