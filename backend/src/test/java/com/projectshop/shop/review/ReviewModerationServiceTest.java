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
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.support.ListQuery.Paging;

/**
 * 답글·신고·내림·되살림의 입구(`Q167`).
 *
 * <p>{@code ReviewModerationTest} 는 표가 무엇을 막는지를 본다(`48`). 여기는 <b>그 표를 부르는 입구</b>가
 * 판정·상태·감사를 제대로 지나는지다 — `48` 은 입구 없이 닫혀서, 이 동작들을 부르는 코드가 시험뿐이었다.
 */
@DisplayName("후기 운영 입구")
class ReviewModerationServiceTest extends PostgresTestBase {

    private static final Paging FIRST = new Paging(0, 20);

    @Autowired
    private ReviewModerationService moderation;

    @Autowired
    private ReviewQuery query;

    @Autowired
    private JdbcClient jdbc;

    private ReviewFixture fixture;
    private AuthFixture auth;
    private long productId;
    private long reviewId;
    private long buyerId;
    private long staffId;
    private long adminId;
    private long reporterId;

    @BeforeEach
    void setUp() {
        fixture = new ReviewFixture(jdbc);
        auth = new AuthFixture(jdbc);

        productId = fixture.insertProduct("운영 상품");
        long orderItemId = fixture.placeOrder(productId, "delivered");
        buyerId = fixture.buyerId();
        fixture.insertReview(orderItemId, productId, buyerId, 2, "배송이 늦고 포장이 찢겨 왔어요");
        reviewId = jdbc.sql("select review_id from review where order_item_id = :id")
                .param("id", orderItemId)
                .query(Long.class)
                .single();

        // 픽스처의 셀러 사람은 소속만 있고 역할이 없다 — 답글 권한은 역할이 준다.
        staffId = fixture.sellerMemberId();
        auth.grantOrg(staffId, "seller_staff", fixture.sellerId());

        adminId = auth.insertUser("mod-admin@test.local", "운영자");
        auth.grantGlobal(adminId, "admin");

        reporterId = fixture.insertUser("reporter@test.local");
    }

    @Nested
    @DisplayName("답글")
    class Reply {

        @Test
        @DisplayName("그 셀러 사람이 답하면 상품 후기 목록에 실린다")
        void 그_셀러_사람이_답하면_상품_후기_목록에_실린다() {
            moderation.reply(staffId, reviewId, "불편을 드려 죄송합니다. 포장을 바꾸겠습니다.");

            assertThat(query.findByProduct(null, productId, FIRST).items())
                    .singleElement()
                    .extracting(ReviewQuery.Item::reply)
                    .isEqualTo("불편을 드려 죄송합니다. 포장을 바꾸겠습니다.");
        }

        @Test
        @DisplayName("남의 셀러 사람은 403 이다")
        void 남의_셀러_사람은_403_이다() {
            long other = auth.insertSeller("other-shop", "남의가게");
            long outsider = auth.insertUser("outsider@test.local", "남의담당");
            auth.joinSeller(other, outsider);
            auth.grantOrg(outsider, "seller_staff", other);

            assertThatThrownBy(() -> moderation.reply(outsider, reviewId, "저희 가게로 오세요"))
                    .isInstanceOf(ShopException.class)
                    .extracting(e -> ((ShopException) e).code())
                    .isEqualTo(ErrorCode.REVIEW_FORBIDDEN);
        }

        @Test
        @DisplayName("지운 답글도 다시 달면 되살아난다 — 후기 하나에 답글 하나다")
        void 지운_답글도_다시_달면_되살아난다() {
            moderation.reply(staffId, reviewId, "첫 답");
            moderation.deleteReply(staffId, reviewId);
            moderation.reply(staffId, reviewId, "고쳐 쓴 답");

            assertThat(jdbc.sql("select count(*) from review_reply where review_id = :id")
                    .param("id", reviewId).query(Long.class).single()).isEqualTo(1);
            assertThat(query.findForSeller(staffId, FIRST).items())
                    .singleElement()
                    .extracting(ReviewQuery.SellerItem::reply)
                    .isEqualTo("고쳐 쓴 답");
        }
    }

    @Nested
    @DisplayName("신고")
    class Report {

        @Test
        @DisplayName("신고하면 관리자의 접수 목록에 나온다")
        void 신고하면_관리자의_접수_목록에_나온다() {
            moderation.report(reporterId, reviewId, ReviewReason.ABUSE);

            assertThat(query.findReports(adminId, ReviewReportStatus.PENDING, FIRST).items())
                    .singleElement()
                    .satisfies(item -> {
                        assertThat(item.reviewId()).isEqualTo(reviewId);
                        assertThat(item.reason()).isEqualTo("ABUSE");
                        assertThat(item.status()).isEqualTo("PENDING");
                    });
        }

        @Test
        @DisplayName("같은 후기를 두 번 신고하면 409 다")
        void 같은_후기를_두_번_신고하면_409_다() {
            moderation.report(reporterId, reviewId, ReviewReason.ABUSE);

            assertThatThrownBy(() -> moderation.report(reporterId, reviewId, ReviewReason.UNRELATED))
                    .isInstanceOf(ShopException.class)
                    .extracting(e -> ((ShopException) e).code())
                    .isEqualTo(ErrorCode.REVIEW_ALREADY_REPORTED);
        }

        @Test
        @DisplayName("자기 후기는 신고하지 않는다")
        void 자기_후기는_신고하지_않는다() {
            assertThatThrownBy(() -> moderation.report(buyerId, reviewId, ReviewReason.ABUSE))
                    .isInstanceOf(ShopException.class)
                    .extracting(e -> ((ShopException) e).code())
                    .isEqualTo(ErrorCode.REVIEW_FORBIDDEN);
        }

        @Test
        @DisplayName("관리자가 아니면 신고 목록을 못 본다")
        void 관리자가_아니면_신고_목록을_못_본다() {
            assertThatThrownBy(() -> query.findReports(staffId, ReviewReportStatus.PENDING, FIRST))
                    .isInstanceOf(ShopException.class)
                    .extracting(e -> ((ShopException) e).code())
                    .isEqualTo(ErrorCode.REVIEW_FORBIDDEN);
        }
    }

    @Nested
    @DisplayName("내림과 되살림")
    class Moderate {

        private long reportId;

        @BeforeEach
        void reported() {
            moderation.report(reporterId, reviewId, ReviewReason.ABUSE);
            reportId = jdbc.sql("select review_report_id from review_report where review_id = :id")
                    .param("id", reviewId).query(Long.class).single();
        }

        /**
         * 공개 목록에서는 빠지고 <b>쓴 사람에게는 사유와 같이 보인다</b> — 공개한 운영정책이
         * 「그 사실과 사유를 확인할 수 있다」고 알리는 자리다(`D2` `R27`).
         */
        @Test
        @DisplayName("받아들이면 그 사유로 내려가고 쓴 사람은 사유를 본다")
        void 받아들이면_그_사유로_내려가고_쓴_사람은_사유를_본다() {
            moderation.acceptReport(adminId, reportId);

            assertThat(query.findByProduct(null, productId, FIRST).items()).isEmpty();
            assertThat(query.findMine(buyerId, FIRST).items())
                    .singleElement()
                    .satisfies(item -> {
                        assertThat(item.blockedAt()).isNotNull();
                        assertThat(item.blockedReason()).isEqualTo("ABUSE");
                    });
        }

        @Test
        @DisplayName("처리한 신고를 다시 처리하면 409 다")
        void 처리한_신고를_다시_처리하면_409_다() {
            moderation.rejectReport(adminId, reportId);

            assertThatThrownBy(() -> moderation.acceptReport(adminId, reportId))
                    .isInstanceOf(ShopException.class)
                    .extracting(e -> ((ShopException) e).code())
                    .isEqualTo(ErrorCode.REVIEW_REPORT_ALREADY_RESOLVED);
        }

        @Test
        @DisplayName("셀러는 신고를 처리하지 못한다 — 불리한 후기를 내리는 자리가 된다")
        void 셀러는_신고를_처리하지_못한다() {
            assertThatThrownBy(() -> moderation.acceptReport(staffId, reportId))
                    .isInstanceOf(ShopException.class)
                    .extracting(e -> ((ShopException) e).code())
                    .isEqualTo(ErrorCode.REVIEW_FORBIDDEN);
        }

        /**
         * 되살림이 <b>감사를 남기는 입구로만</b> 있는 것이 이 청크의 핵심이다(마무리 42차 독립 리뷰) —
         * 전에는 맨 UPDATE 뿐이라 「누가 왜 되살렸나」에 답할 수 없었다.
         */
        @Test
        @DisplayName("되살리면 공개 목록에 돌아오고 이전 사유와 이유가 감사에 남는다")
        void 되살리면_공개_목록에_돌아오고_이전_사유와_이유가_감사에_남는다() {
            moderation.acceptReport(adminId, reportId);

            moderation.restore(adminId, reviewId, "이의제기 문의 확인 — 비방이 아니라 배송 불만");

            assertThat(query.findByProduct(null, productId, FIRST).items()).hasSize(1);
            assertThat(jdbc.sql("""
                            select detail ->> 'previous_reason' from audit_log
                             where event_type = 'review.restored' and actor_user_id = :admin
                            """)
                    .param("admin", adminId).query(String.class).single())
                    .isEqualTo("abuse");
        }

        @Test
        @DisplayName("안 내려간 후기는 되살릴 것이 없다")
        void 안_내려간_후기는_되살릴_것이_없다() {
            assertThatThrownBy(() -> moderation.restore(adminId, reviewId, "착오"))
                    .isInstanceOf(ShopException.class)
                    .extracting(e -> ((ShopException) e).code())
                    .isEqualTo(ErrorCode.REVIEW_NOT_BLOCKED);
        }
    }

    @Nested
    @DisplayName("셀러 목록")
    class SellerList {

        @Test
        @DisplayName("소속된 셀러가 없으면 0건이 아니라 거부다")
        void 소속된_셀러가_없으면_0건이_아니라_거부다() {
            assertThatThrownBy(() -> query.findForSeller(reporterId, FIRST))
                    .isInstanceOf(ShopException.class)
                    .extracting(e -> ((ShopException) e).code())
                    .isEqualTo(ErrorCode.REVIEW_FORBIDDEN);
        }
    }
}
