package com.projectshop.shop.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;

/**
 * 셀러 답글과 후기 신고(`48`).
 *
 * <p>고정하는 것이 넷이다 — <b>자기 상품에만 답하고</b>, <b>후기 하나에 답글 하나</b>고,
 * <b>한 사람이 같은 후기를 한 번만 신고</b>하고, <b>처리한 신고는 다시 못 연다</b>.
 *
 * <p>다섯째가 법이다. 전자상거래법 제21조의4 가 요구하는 넷(게시기간·등급평가·삭제 기준·
 * 이의제기 절차)이 <b>공개된 문서에 실제로 있는지</b>를 잰다(`D2` `R27`) —
 * 기준을 정하고 안 알리면 그 조문을 어긴다.
 */
@DisplayName("셀러 답글과 후기 신고")
class ReviewModerationTest extends PostgresTestBase {

    @Autowired
    private JdbcClient jdbc;

    private ReviewFixture fixture;
    private long reviewId;
    private long buyerId;

    @BeforeEach
    void setUp() {
        fixture = new ReviewFixture(jdbc);
        long productId = fixture.insertProduct("후기 상품");
        long orderItemId = fixture.placeOrder(productId, "delivered");
        buyerId = fixture.buyerId();

        fixture.insertReview(orderItemId, productId, buyerId, 5, "잘 받았다");
        reviewId = jdbc.sql("select review_id from review where order_item_id = :id")
                .param("id", orderItemId)
                .query(Long.class)
                .single();
    }

    @Nested
    @DisplayName("답글")
    class Reply {

        @Test
        @DisplayName("그 셀러 사람은 답한다")
        void 그_셀러_사람은_답한다() {
            insertReply(reviewId, fixture.sellerMemberId(), "감사합니다");

            assertThat(replyCount(reviewId)).isEqualTo(1);
        }

        /** 화면에서는 그 셀러의 말처럼 보인다 — 남의 상품 페이지에 끼어드는 자리다 */
        @Test
        @DisplayName("남의 셀러 사람은 못 단다")
        void 남의_셀러_사람은_못_단다() {
            long stranger = fixture.insertUser("stranger@example.com");

            assertThatThrownBy(() -> insertReply(reviewId, stranger, "끼어든다"))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("자기 상품의 후기에만");
        }

        /**
         * 트리거가 셀러 소속이 아닌 계정을 막으므로, 관리자에게 줘도 100% 실패한다.
         * <b>권한은 있는데 실행이 안 되는 부여</b>가 제일 나쁜 모양이라 아예 안 준다.
         */
        @Test
        @DisplayName("관리자는 답글 권한을 안 받는다")
        void 관리자는_답글_권한을_안_받는다() {
            int granted = jdbc.sql("""
                            select count(*) from role_permission rp
                              join role r on r.role_id = rp.role_id
                              join permission p on p.permission_id = rp.permission_id
                             where r.code = 'admin' and p.resource = 'review'
                               and p.action = 'reply' and rp.effect = 'allow'
                            """)
                    .query(Integer.class)
                    .single();

            assertThat(granted).isZero();
        }

        /** 여러 개를 허용하면 셀러가 같은 자리에 글을 쌓아 후기를 밀어낸다 */
        @Test
        @DisplayName("후기 하나에 답글 하나다")
        void 후기_하나에_답글_하나다() {
            long member = fixture.sellerMemberId();
            insertReply(reviewId, member, "감사합니다");

            assertThatThrownBy(() -> insertReply(reviewId, member, "한 번 더"))
                    .isInstanceOf(DataAccessException.class);
        }
    }

    @Nested
    @DisplayName("신고")
    class Report {

        /** 여러 번 허용하면 신고 수가 여론이 아니라 한 사람의 끈기를 센다 */
        @Test
        @DisplayName("한 사람이 같은 후기를 한 번만 신고한다")
        void 한_사람이_같은_후기를_한_번만_신고한다() {
            long reporter = fixture.insertUser("reporter@example.com");
            insertReport(reviewId, reporter, "abuse");

            assertThatThrownBy(() -> insertReport(reviewId, reporter, "advertisement"))
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("처리한 신고는 다시 못 연다")
        void 처리한_신고는_다시_못_연다() {
            long reporter = fixture.insertUser("reporter@example.com");
            insertReport(reviewId, reporter, "abuse");
            resolve(reviewId, reporter, "rejected");

            assertThatThrownBy(() -> resolve(reviewId, reporter, "accepted"))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("다시 못 연다");
        }

        /** 안 내렸는데 「광고라서 내렸다」가 붙어 있는 행을 막는다 */
        @Test
        @DisplayName("사유만 매달린 후기는 못 만든다")
        void 사유만_매달린_후기는_못_만든다() {
            assertThatThrownBy(() -> jdbc.sql("""
                            update review set blocked_reason = 'abuse' where review_id = :id
                            """)
                    .param("id", reviewId)
                    .update())
                    .isInstanceOf(DataAccessException.class);
        }

        /**
         * 배송 상태는 후기가 달린 뒤에도 움직인다({@code delivered} → {@code returned}).
         * 그때 트리거가 갱신까지 덮으면 <b>관리자가 신고를 받아들여도 못 내리고</b>
         * 작성자도 자기 글을 못 고친다 — 마무리 42차 독립 리뷰가 찾은 자리다.
         *
         * <p>{@code returned} 로 미는 것은 {@code seller_order_return_reason_required_check} 가
         * {@code return_requested} 에만 사유를 요구해서다 — 그 제약이 「returned 에도 걸면
         * CS 처리가 막힌다」고 적어 뒀다. 여기서 재는 것은 <b>배송 상태가 후기 밖에서
         * 움직였다</b>는 것뿐이라 어느 종착이든 같다.
         */
        @Test
        @DisplayName("주문이 반품으로 끝나도 내릴 수 있다")
        void 주문이_반품으로_끝나도_내릴_수_있다() {
            jdbc.sql("""
                            update seller_order set status = 'returned'
                             where seller_order_id in (
                                 select oi.seller_order_id from order_item oi
                                   join review r on r.order_item_id = oi.order_item_id
                                  where r.review_id = :id)
                            """)
                    .param("id", reviewId)
                    .update();

            jdbc.sql("""
                            update review set blocked_at = now(), blocked_reason = 'abuse'
                             where review_id = :id
                            """)
                    .param("id", reviewId)
                    .update();

            assertThat(visibleCount()).isZero();
        }

        @Test
        @DisplayName("시각과 사유를 같이 채우면 내려간다")
        void 시각과_사유를_같이_채우면_내려간다() {
            jdbc.sql("""
                            update review set blocked_at = now(), blocked_reason = 'abuse'
                             where review_id = :id
                            """)
                    .param("id", reviewId)
                    .update();

            assertThat(visibleCount()).isZero();
        }
    }

    @Nested
    @DisplayName("R27 — 규칙을 공개한다")
    class PublicPolicy {

        /**
         * 제21조의4 는 후기를 어떻게 다루는지가 아니라 <b>그 규칙을 알리는 것</b>을 요구한다.
         * 넷 중 하나라도 빠지면 공개한 것이 아니다.
         */
        @Test
        @DisplayName("고지 문서에 넷이 다 있다")
        void 고지_문서에_넷이_다_있다() {
            String body = jdbc.sql("""
                            select body from policy_document
                             where code = 'review_policy' and effective_at <= now()
                             order by version desc limit 1
                            """)
                    .query(String.class)
                    .single();

            assertThat(body).contains("게시기간", "등급평가", "삭제 기준", "이의제기");
        }

        /** 내리는 사유가 고지된 목록과 갈리면 공개한 기준으로 안 내린 것이 된다 */
        @Test
        @DisplayName("내리는 사유가 고지 문서에 다 적혀 있다")
        void 내리는_사유가_고지_문서에_다_적혀_있다() {
            String body = jdbc.sql("""
                            select body from policy_document where code = 'review_policy'
                             order by version desc limit 1
                            """)
                    .query(String.class)
                    .single();

            List<String> reasons = List.of("광고", "욕설", "관련이 없는", "개인정보");
            assertThat(reasons).allSatisfy(reason -> assertThat(body).contains(reason));
        }

        /**
         * <b>가리키는 자리가 실제로 있어야 알린 것이다</b>(`Q171`). 제2판은 「고객센터」로 보냈는데
         * 계약내용 서면(`V27`)은 고객센터를 운영하지 않는다고 적었다 — 두 공개 문서가 서로 다른 말을 했다.
         * 실제로 받는 자리는 내 문의의 「불만·분쟁 접수」(`/me/inquiries` 의 `DISPUTE`)고,
         * 내려간 사유는 내 후기(`/me/reviews`)에서 본다. 그 두 이름이 화면과 같아야 한다.
         */
        @Test
        @DisplayName("이의제기는 실제로 받는 자리를 가리킨다")
        void 이의제기는_실제로_받는_자리를_가리킨다() {
            String body = jdbc.sql("""
                            select body from policy_document
                             where code = 'review_policy' and effective_at <= now()
                             order by version desc limit 1
                            """)
                    .query(String.class)
                    .single();

            // 이름은 화면 쪽 시험과 같은 상수다 — 화면의 글자가 바뀌면 그 시험이 빨개진다(마무리 45차).
            assertThat(body)
                    .contains(ReviewPolicyScreenTest.DISPUTE_LABEL, ReviewPolicyScreenTest.MY_REVIEWS_LABEL)
                    .doesNotContain("고객센터");
        }
    }

    private void insertReply(long review, long user, String body) {
        jdbc.sql("""
                        insert into review_reply (review_id, user_id, body)
                        values (:review, :user, :body)
                        """)
                .param("review", review)
                .param("user", user)
                .param("body", body)
                .update();
    }

    private void insertReport(long review, long reporter, String reason) {
        jdbc.sql("""
                        insert into review_report (review_id, reporter_user_id, reason)
                        values (:review, :reporter, :reason)
                        """)
                .param("review", review)
                .param("reporter", reporter)
                .param("reason", reason)
                .update();
    }

    private void resolve(long review, long reporter, String status) {
        jdbc.sql("""
                        update review_report
                           set status = :status, resolved_at = now(), resolved_by_user_id = :by
                         where review_id = :review and reporter_user_id = :reporter
                        """)
                .param("status", status)
                .param("by", buyerId)
                .param("review", review)
                .param("reporter", reporter)
                .update();
    }

    private int replyCount(long review) {
        return jdbc.sql("select count(*) from review_reply where review_id = :id")
                .param("id", review)
                .query(Integer.class)
                .single();
    }

    private int visibleCount() {
        return jdbc.sql("""
                        select count(*) from review
                         where deleted_at is null and blocked_at is null
                        """)
                .query(Integer.class)
                .single();
    }
}
