package com.projectshop.shop.coupon;

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
 * 쿠폰을 만들고 받는 입구(`Q163`).
 *
 * <p><b>`49`·`50`·`51` 이 세운 표를 아무도 못 채우고 있었다.</b> 여기서 재는 것은 계산이 아니라
 * <b>누가 무엇을 할 수 있나</b>다 — 할인액은 쿠폰 적용 시험이 이미 든다.
 *
 * <p><b>셋을 안 가른다.</b> 없는 코드·내려간 쿠폰·발급 기간이 아닌 것이 같은 응답이다 —
 * 가르면 코드를 찍어 보며 어떤 쿠폰이 존재하는지를 알아낼 수 있다(`D14`).
 */
@DisplayName("쿠폰 입구")
class CouponIssueTest extends PostgresTestBase {

    @Autowired
    private CouponService coupons;

    @Autowired
    private CouponQuery query;

    @Autowired
    private JdbcClient jdbc;

    private AuthFixture auth;
    private long adminId;
    private long buyerId;

    @BeforeEach
    void setUp() {
        auth = new AuthFixture(jdbc);
        adminId = auth.insertUser("coupon-admin@example.com", "관리자");
        auth.grantGlobal(adminId, "admin");
        buyerId = auth.insertUser("coupon-buyer@example.com", "산사람");
        auth.grantGlobal(buyerId, "customer");
    }

    @Nested
    @DisplayName("만들기")
    class Define {

        @Test
        @DisplayName("관리자가 만든다")
        void 관리자가_만든다() {
            long couponId = coupons.define(adminId, mallCoupon("WELCOME-3000"));

            assertThat(couponId).isPositive();
        }

        /** 만드는 것이 곧 부담을 만드는 것이라 사는 사람이 지나면 안 된다 */
        @Test
        @DisplayName("사는 사람은 못 만든다")
        void 사는_사람은_못_만든다() {
            assertThatThrownBy(() -> coupons.define(buyerId, mallCoupon("STEAL-3000")))
                    .isInstanceOf(ShopException.class)
                    .extracting(error -> ((ShopException) error).code())
                    .isEqualTo(ErrorCode.ACCESS_DENIED);
        }

        /** 제약에 맡기면 500 이 나간다 — 고칠 수 있는 오류로 먼저 답한다 */
        @Test
        @DisplayName("셀러 없는 셀러 부담은 못 만든다")
        void 셀러_없는_셀러_부담은_못_만든다() {
            CouponService.NewCoupon broken = new CouponService.NewCoupon(
                    "ORPHAN-3000", "주인 없는 부담", "amount", 3000L, null, 0L, "seller", null, 30);

            assertThatThrownBy(() -> coupons.define(adminId, broken))
                    .isInstanceOf(ShopException.class)
                    .extracting(error -> ((ShopException) error).code())
                    .isEqualTo(ErrorCode.COUPON_NOT_APPLICABLE);
        }

        @Test
        @DisplayName("같은 코드는 409 다")
        void 같은_코드는_409_다() {
            coupons.define(adminId, mallCoupon("TWICE-3000"));

            assertThatThrownBy(() -> coupons.define(adminId, mallCoupon("TWICE-3000")))
                    .isInstanceOf(ShopException.class)
                    .extracting(error -> ((ShopException) error).code())
                    .isEqualTo(ErrorCode.COUPON_CODE_TAKEN);
        }
    }

    @Nested
    @DisplayName("받기")
    class Register {

        @Test
        @DisplayName("코드를 치면 쿠폰함에 담긴다")
        void 코드를_치면_쿠폰함에_담긴다() {
            coupons.define(adminId, mallCoupon("TAKE-3000"));

            long issueId = coupons.register(buyerId, "TAKE-3000");

            assertThat(issueId).isPositive();
            assertThat(query.findMine(buyerId, buyerId, new Paging(0, 20)).items())
                    .singleElement()
                    .satisfies(item -> {
                        assertThat(item.name()).isEqualTo("첫 구매 쿠폰");
                        assertThat(item.usedAt()).isNull();
                    });
        }

        /** 표가 든다(`coupon_issue_once`). 앱에서만 세면 두 번 눌러 두 장이 된다 */
        @Test
        @DisplayName("같은 쿠폰을 두 번 받으면 409 다")
        void 같은_쿠폰을_두_번_받으면_409_다() {
            coupons.define(adminId, mallCoupon("ONCE-3000"));
            coupons.register(buyerId, "ONCE-3000");

            assertThatThrownBy(() -> coupons.register(buyerId, "ONCE-3000"))
                    .isInstanceOf(ShopException.class)
                    .extracting(error -> ((ShopException) error).code())
                    .isEqualTo(ErrorCode.COUPON_ALREADY_ISSUED);
        }

        @Test
        @DisplayName("없는 코드는 422 다")
        void 없는_코드는_422_다() {
            assertThatThrownBy(() -> coupons.register(buyerId, "NOSUCH-3000"))
                    .isInstanceOf(ShopException.class)
                    .extracting(error -> ((ShopException) error).code())
                    .isEqualTo(ErrorCode.COUPON_CODE_NOT_ISSUABLE);
        }

        /**
         * 발급 창이 닫힌 쿠폰과 없는 쿠폰이 같은 응답이다.
         *
         * <p><b>창을 뒤로 못 당긴다</b>({@code coupon_issue_window_check} 가
         * {@code issue_end_at > issue_start_at} 을 요구한다) — 그래서 시작도 같이 민다.
         * 그 제약이 {@link CouponService#withdraw} 를 세운 이유이기도 하다.
         */
        @Test
        @DisplayName("발급 기간이 지나면 없는 것과 같은 응답이다")
        void 발급_기간이_지나면_없는_것과_같은_응답이다() {
            long couponId = coupons.define(adminId, mallCoupon("CLOSED-3000"));
            jdbc.sql("""
                            update coupon
                               set issue_start_at = now() - interval '2 days',
                                   issue_end_at   = now() - interval '1 day'
                             where coupon_id = :id
                            """)
                    .param("id", couponId)
                    .update();

            assertThatThrownBy(() -> coupons.register(buyerId, "CLOSED-3000"))
                    .isInstanceOf(ShopException.class)
                    .extracting(error -> ((ShopException) error).code())
                    .isEqualTo(ErrorCode.COUPON_CODE_NOT_ISSUABLE);
        }

        /**
         * <b>기본 창으로 만든 쿠폰은 이것 말고 멈출 길이 없다.</b> 위 시험이 창을 뒤로 못
         * 당긴다는 것을 보였고, {@code issue_start_at} 이 만든 시각이면 과거를 가리킬 수가 없다.
         */
        @Test
        @DisplayName("내린 쿠폰은 아무도 못 받는다")
        void 내린_쿠폰은_아무도_못_받는다() {
            long couponId = coupons.define(adminId, mallCoupon("DOWN-3000"));

            coupons.withdraw(adminId, couponId);

            assertThatThrownBy(() -> coupons.register(buyerId, "DOWN-3000"))
                    .isInstanceOf(ShopException.class)
                    .extracting(error -> ((ShopException) error).code())
                    .isEqualTo(ErrorCode.COUPON_CODE_NOT_ISSUABLE);
        }

        @Test
        @DisplayName("사는 사람은 못 내린다")
        void 사는_사람은_못_내린다() {
            long couponId = coupons.define(adminId, mallCoupon("KEEP-3000"));

            assertThatThrownBy(() -> coupons.withdraw(buyerId, couponId))
                    .isInstanceOf(ShopException.class)
                    .extracting(error -> ((ShopException) error).code())
                    .isEqualTo(ErrorCode.ACCESS_DENIED);
        }

        /** 정의가 바뀌어도 이미 나간 것은 안 바뀐다(`V26` 과 같은 이유) */
        @Test
        @DisplayName("기한을 발급 시점에 박제한다")
        void 기한을_발급_시점에_박제한다() {
            long couponId = coupons.define(adminId, mallCoupon("PINNED-3000"));
            long issueId = coupons.register(buyerId, "PINNED-3000");

            jdbc.sql("update coupon set valid_days = 1 where coupon_id = :id")
                    .param("id", couponId)
                    .update();

            int days = jdbc.sql("""
                            select extract(day from expires_at - issued_at)::int as span
                              from coupon_issue where coupon_issue_id = :id
                            """)
                    .param("id", issueId)
                    .query(Integer.class)
                    .single();

            assertThat(days).isEqualTo(30);
        }
    }

    @Nested
    @DisplayName("읽기")
    class Read {

        /**
         * <b>코드를 아는 것이 곧 받을 수 있다는 뜻이라</b> 조회가 사실상 발급이다(`Q163`).
         * 열어 두면 아직 안 알린 쿠폰의 코드가 통째로 샌다.
         */
        @Test
        @DisplayName("사는 사람은 정의 목록을 못 읽는다")
        void 사는_사람은_정의_목록을_못_읽는다() {
            coupons.define(adminId, mallCoupon("SECRET-3000"));

            assertThatThrownBy(() -> query.findAll(buyerId, new Paging(0, 20)))
                    .isInstanceOf(ShopException.class)
                    .extracting(error -> ((ShopException) error).code())
                    .isEqualTo(ErrorCode.ACCESS_DENIED);
        }

        @Test
        @DisplayName("관리자는 코드까지 읽는다")
        void 관리자는_코드까지_읽는다() {
            coupons.define(adminId, mallCoupon("LISTED-3000"));

            assertThat(query.findAll(adminId, new Paging(0, 20)).items())
                    .anySatisfy(item -> assertThat(item.code()).isEqualTo("LISTED-3000"));
        }

        /**
         * <b>부여가 실제로 막는다</b>(마무리 44차 독립 리뷰). `user_id` 로 좁히는 것만으로는
         * `coupon_issue:read` 가 아무것도 안 막고, `V91` 이 「감사자에게는 안 준다」고 적어 둔
         * 근거가 글로만 남는다.
         */
        @Test
        @DisplayName("감사자는 쿠폰함을 못 읽는다")
        void 감사자는_쿠폰함을_못_읽는다() {
            long auditor = auth.insertUser("coupon-auditor@example.com", "감사자");
            auth.grantGlobal(auditor, "auditor");

            assertThatThrownBy(() -> query.findMine(auditor, auditor, new Paging(0, 20)))
                    .isInstanceOf(ShopException.class)
                    .extracting(error -> ((ShopException) error).code())
                    .isEqualTo(ErrorCode.ACCESS_DENIED);
        }

        /** 남의 쿠폰함이 내 것에 안 섞인다 — 경계가 판정의 {@code own} 과 SQL 둘이다 */
        @Test
        @DisplayName("쿠폰함은 자기 것만 나온다")
        void 쿠폰함은_자기_것만_나온다() {
            long other = auth.insertUser("coupon-other@example.com", "남");
            auth.grantGlobal(other, "customer");
            coupons.define(adminId, mallCoupon("MINE-3000"));
            coupons.register(other, "MINE-3000");

            assertThat(query.findMine(buyerId, buyerId, new Paging(0, 20)).items()).isEmpty();
            assertThat(query.findMine(other, other, new Paging(0, 20)).total()).isEqualTo(1);
        }
    }

    private static CouponService.NewCoupon mallCoupon(String code) {
        return new CouponService.NewCoupon(
                code, "첫 구매 쿠폰", "amount", 3000L, null, 10_000L, "mall", null, 30);
    }
}
