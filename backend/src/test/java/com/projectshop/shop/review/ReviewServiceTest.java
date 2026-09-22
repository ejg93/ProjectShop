package com.projectshop.shop.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.support.ListQuery.Paging;

/**
 * 후기를 쓰고 읽는 입구(`Q160`).
 *
 * <p><b>표가 이미 막는 것을 여기서 다시 막는 이유는 응답이다.</b> 제약에 맡기면 500 이 나가고
 * 부르는 쪽은 무엇을 고쳐야 하는지 모른다 — 같은 거절을 <b>고칠 수 있는 오류</b>로 답한다.
 *
 * <p><b>넷을 안 가른다.</b> 없는 주문 줄·남의 주문 줄·아직 안 받은 것·권한 없음이 같은
 * 응답이다 — 가르면 주문 줄 번호를 두드려 남이 무엇을 샀는지 셀 수 있다(`D14`).
 */
@DisplayName("후기 입구")
class ReviewServiceTest extends PostgresTestBase {

    @Autowired
    private ReviewService reviews;

    @Autowired
    private ReviewQuery query;

    @Autowired
    private JdbcClient jdbc;

    private ReviewFixture fixture;
    private long productId;
    private long orderItemId;
    private long buyerId;

    @BeforeEach
    void setUp() {
        fixture = new ReviewFixture(jdbc);
        productId = fixture.insertProduct("후기 상품");
        orderItemId = fixture.placeOrder(productId, "delivered");
        buyerId = fixture.buyerId();
    }

    @Nested
    @DisplayName("쓰기")
    class Create {

        @Test
        @DisplayName("산 사람이 쓴다")
        void 산_사람이_쓴다() {
            long reviewId = createdId(buyerId, new ReviewService.NewReview(
                    orderItemId, 5, "받아 보니 사진과 같습니다"));

            assertThat(reviewId).isPositive();
        }

        /** 주문 줄 번호를 두드려 남이 무엇을 샀는지 세는 길을 안 연다 */
        @Test
        @DisplayName("안 산 사람은 못 쓴다")
        void 안_산_사람은_못_쓴다() {
            long stranger = fixture.insertUser("stranger@example.com");

            assertThatThrownBy(() -> reviews.create(stranger, new ReviewService.NewReview(
                    orderItemId, 5, "안 샀지만 씁니다")))
                    .isInstanceOf(ShopException.class)
                    .extracting(error -> ((ShopException) error).code())
                    .isEqualTo(ErrorCode.REVIEW_NOT_ALLOWED);
        }

        @Test
        @DisplayName("받기 전에는 못 쓴다")
        void 받기_전에는_못_쓴다() {
            long preparing = fixture.placeOrder(productId, "preparing");

            assertThatThrownBy(() -> reviews.create(buyerId, new ReviewService.NewReview(
                    preparing, 5, "아직 안 받았지만 씁니다")))
                    .isInstanceOf(ShopException.class)
                    .extracting(error -> ((ShopException) error).code())
                    .isEqualTo(ErrorCode.REVIEW_NOT_ALLOWED);
        }

        /** 제약에 맡기면 500 이 나간다 — 고칠 수 있는 오류로 먼저 답한다 */
        @Test
        @DisplayName("한 줄에 둘째는 409 다")
        void 한_줄에_둘째는_409_다() {
            reviews.create(buyerId, new ReviewService.NewReview(orderItemId, 5, "첫 후기입니다"));

            assertThatThrownBy(() -> reviews.create(buyerId, new ReviewService.NewReview(
                    orderItemId, 1, "두 번째 후기입니다")))
                    .isInstanceOf(ShopException.class)
                    .extracting(error -> ((ShopException) error).code())
                    .isEqualTo(ErrorCode.REVIEW_ALREADY_WRITTEN);
        }
    }

    @Nested
    @DisplayName("고치기·지우기")
    class Edit {

        @Test
        @DisplayName("자기 후기는 고친다")
        void 자기_후기는_고친다() {
            long reviewId = createdId(buyerId,
                    new ReviewService.NewReview(orderItemId, 5, "처음 쓴 후기입니다"));

            reviews.update(buyerId, reviewId, 3, "다시 써 본 후기입니다");

            assertThat(ratingOf(reviewId)).isEqualTo(3);
        }

        @Test
        @DisplayName("남의 후기는 못 고친다")
        void 남의_후기는_못_고친다() {
            long reviewId = createdId(buyerId,
                    new ReviewService.NewReview(orderItemId, 5, "처음 쓴 후기입니다"));
            long stranger = fixture.insertUser("stranger@example.com");

            assertThatThrownBy(() -> reviews.update(stranger, reviewId, 1, "남의 글을 고칩니다"))
                    .isInstanceOf(ShopException.class);
        }

        /** 지운 뒤에는 다시 쓸 수 있다(`47` 의 부분 유니크) */
        @Test
        @DisplayName("지우면 목록에서 빠지고 다시 쓸 수 있다")
        void 지우면_목록에서_빠지고_다시_쓸_수_있다() {
            long reviewId = createdId(buyerId,
                    new ReviewService.NewReview(orderItemId, 5, "처음 쓴 후기입니다"));

            reviews.delete(buyerId, reviewId);
            reviews.create(buyerId, new ReviewService.NewReview(orderItemId, 4, "다시 쓴 후기입니다"));

            assertThat(query.findByProduct(buyerId, productId, new Paging(0, 20)).items())
                    .singleElement()
                    .satisfies(item -> assertThat(item.rating()).isEqualTo(4));
        }
    }

    @Nested
    @DisplayName("읽기")
    class Read {

        @Test
        @DisplayName("로그인 안 해도 읽고 mine 은 전부 거짓이다")
        void 로그인_안_해도_읽는다() {
            reviews.create(buyerId, new ReviewService.NewReview(orderItemId, 5, "읽히는 후기입니다"));

            var result = query.findByProduct(null, productId, new Paging(0, 20));

            assertThat(result.items()).singleElement()
                    .satisfies(item -> assertThat(item.mine()).isFalse());
        }

        @Test
        @DisplayName("쓴 사람에게는 mine 이 참이다")
        void 쓴_사람에게는_mine_이_참이다() {
            reviews.create(buyerId, new ReviewService.NewReview(orderItemId, 5, "내가 쓴 후기입니다"));

            var result = query.findByProduct(buyerId, productId, new Paging(0, 20));

            assertThat(result.items()).singleElement()
                    .satisfies(item -> assertThat(item.mine()).isTrue());
        }

        /** 0 으로 두면 「별 0점」과 「후기 없음」이 같은 값이 된다 */
        @Test
        @DisplayName("후기가 없으면 평균이 없다")
        void 후기가_없으면_평균이_없다() {
            var result = query.findByProduct(null, productId, new Paging(0, 20));

            assertThat(result.summary().count()).isZero();
            assertThat(result.summary().average()).isNull();
        }

        @Test
        @DisplayName("내려간 후기는 목록에 안 든다")
        void 내려간_후기는_목록에_안_든다() {
            long reviewId = createdId(buyerId,
                    new ReviewService.NewReview(orderItemId, 5, "곧 내려갈 후기입니다"));
            jdbc.sql("""
                            update review set blocked_at = now(), blocked_reason = 'abuse'
                             where review_id = :id
                            """)
                    .param("id", reviewId)
                    .update();

            var result = query.findByProduct(null, productId, new Paging(0, 20));

            assertThat(result.items()).isEmpty();
            assertThat(result.summary().count()).isZero();
        }
    }

    /** 반환이 record 라 번호만 꺼내 쓴다 — 시험이 재는 것은 그 번호뿐이다 */
    private long createdId(long actorUserId, ReviewService.NewReview command) {
        return reviews.create(actorUserId, command).reviewId();
    }

    private int ratingOf(long reviewId) {
        return jdbc.sql("select rating from review where review_id = :id")
                .param("id", reviewId)
                .query(Integer.class)
                .single();
    }
}
