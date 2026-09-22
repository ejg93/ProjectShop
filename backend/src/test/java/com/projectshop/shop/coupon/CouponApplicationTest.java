package com.projectshop.shop.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.coupon.CouponService.CouponLine;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

/**
 * 쿠폰을 얼마나 깎고 어떻게 나누나(`50`).
 *
 * <p><b>배분이 이 청크의 핵심이다.</b> 주문 하나에 한 장이지만 할인액은 항목마다 나뉜다 —
 * 정산이 셀러별로 갈라야 하고(`51`) 부분 환불이 항목 단위다. 나누면 <b>반드시 잔차가 남고</b>,
 * 그 잔차가 결제액 등식을 깨뜨리는 것이 제일 잡기 어려운 자리다.
 *
 * <p><b>서비스를 실제로 부른다.</b> 계산을 시험이 다시 짜면 사본이 둘이 되고, 둘이 같이
 * 틀려도 초록이다 — 재는 것은 결과의 <b>성질</b>이다(합이 정확한가, 내림인가, 상한을 지키나).
 */
@DisplayName("쿠폰 계산과 배분")
class CouponApplicationTest extends PostgresTestBase {

    @Autowired
    private CouponService coupons;

    @Autowired
    private JdbcClient jdbc;

    private AuthFixture auth;
    private long userId;
    private long sellerA;
    private long sellerB;

    @BeforeEach
    void setUp() {
        auth = new AuthFixture(jdbc);
        userId = auth.insertUser("coupon@example.com", "받는이");
        sellerA = auth.insertSeller("coupon-a", "A셀러");
        sellerB = auth.insertSeller("coupon-b", "B셀러");
    }

    @Nested
    @DisplayName("배분")
    class Allocation {

        @Test
        @DisplayName("배분액의 합이 총 할인액과 정확히 같다")
        void 배분액의_합이_총_할인액과_정확히_같다() {
            long issue = issueAmountCoupon("a1", 1000);

            var applied = coupons.apply(userId, issue,
                    List.of(new CouponLine(sellerA, 3000), new CouponLine(sellerA, 3000), new CouponLine(sellerA, 4000)));

            assertThat(applied.total()).isEqualTo(1000);
            assertThat(sum(applied.perLine())).isEqualTo(1000);
        }

        /** 앞엣것은 내림이라 조금씩 작고, 마지막이 그 차이를 먹는다 */
        @Test
        @DisplayName("잔차는 마지막 대상이 먹는다")
        void 잔차는_마지막_대상이_먹는다() {
            long issue = issueAmountCoupon("a2", 100);

            var applied = coupons.apply(userId, issue,
                    List.of(new CouponLine(sellerA, 333), new CouponLine(sellerA, 333), new CouponLine(sellerA, 334)));

            assertThat(applied.perLine()).containsExactly(33L, 33L, 34L);
        }

        /** 항목값보다 큰 할인은 음수 결제액을 만든다. 거스름을 줄 방법이 없다 */
        @Test
        @DisplayName("할인이 대상 금액을 못 넘는다")
        void 할인이_대상_금액을_못_넘는다() {
            long issue = issueAmountCoupon("a3", 50_000);

            var applied = coupons.apply(userId, issue,
                    List.of(new CouponLine(sellerA, 1000), new CouponLine(sellerA, 2000)));

            assertThat(applied.total()).isEqualTo(3000);
            assertThat(sum(applied.perLine())).isEqualTo(3000);
        }
    }

    @Nested
    @DisplayName("부담 주체가 대상을 정한다")
    class Bearer {

        /** 셀러 부담 쿠폰이 남의 상품까지 깎으면 그 셀러가 남의 할인을 문다 */
        @Test
        @DisplayName("셀러 부담은 그 셀러의 줄만 깎는다")
        void 셀러_부담은_그_셀러의_줄만_깎는다() {
            long issue = issueSellerCoupon("b1", 1000, sellerA);

            var applied = coupons.apply(userId, issue,
                    List.of(new CouponLine(sellerA, 5000), new CouponLine(sellerB, 5000)));

            assertThat(applied.perLine().get(0)).isEqualTo(1000);
            assertThat(applied.perLine().get(1)).isZero();
        }

        @Test
        @DisplayName("대상 셀러의 줄이 없으면 못 쓴다")
        void 대상_셀러의_줄이_없으면_못_쓴다() {
            long issue = issueSellerCoupon("b2", 1000, sellerA);

            assertThatThrownBy(() -> coupons.apply(userId, issue, List.of(new CouponLine(sellerB, 5000))))
                    .isInstanceOf(ShopException.class)
                    .extracting(error -> ((ShopException) error).code())
                    .isEqualTo(ErrorCode.COUPON_NOT_APPLICABLE);
        }
    }

    @Nested
    @DisplayName("쓸 수 없는 것")
    class NotUsable {

        /** 넷을 안 가른다 — 가르면 번호를 두드려 남이 무슨 쿠폰을 받았는지 알 수 있다 */
        @Test
        @DisplayName("남의 발급은 못 쓴다")
        void 남의_발급은_못_쓴다() {
            long stranger = auth.insertUser("stranger@example.com", "남");
            long issue = issueAmountCoupon("c1", 1000);

            assertThatThrownBy(() -> coupons.apply(stranger, issue, List.of(new CouponLine(sellerA, 5000))))
                    .isInstanceOf(ShopException.class)
                    .extracting(error -> ((ShopException) error).code())
                    .isEqualTo(ErrorCode.COUPON_NOT_USABLE);
        }

        /**
         * <b>「쓴 것」으로 재려다 못 했다.</b> {@code coupon_issue_used_check} 가 시각과 주문을
         * 사슬로 묶어서 주문 없이 쓴 표시를 할 수가 없다 — 그것이 `49` 가 의도한 것이라
         * 여기서는 같은 「못 쓰는 것」의 다른 얼굴인 기한으로 잰다.
         * 쓴 것을 두 번 못 쓰는 것은 {@link CouponService#markUsed} 의 조건이 든다.
         */
        @Test
        @DisplayName("기한이 지난 것은 못 쓴다")
        void 기한이_지난_것은_못_쓴다() {
            long issue = issueAmountCoupon("c2", 1000);
            jdbc.sql("""
                            update coupon_issue
                               set issued_at = now() - interval '60 days',
                                   expires_at = now() - interval '1 day'
                             where coupon_issue_id = :id
                            """)
                    .param("id", issue)
                    .update();

            assertThatThrownBy(() -> coupons.apply(userId, issue, List.of(new CouponLine(sellerA, 5000))))
                    .isInstanceOf(ShopException.class);
        }

        @Test
        @DisplayName("최소 주문 금액에 못 미치면 못 쓴다")
        void 최소_주문_금액에_못_미치면_못_쓴다() {
            long coupon = insertCoupon("c3", "amount", 1000, null, "mall", null, 10_000);
            long issue = issue(coupon);

            assertThatThrownBy(() -> coupons.apply(userId, issue, List.of(new CouponLine(sellerA, 5000))))
                    .isInstanceOf(ShopException.class)
                    .extracting(error -> ((ShopException) error).code())
                    .isEqualTo(ErrorCode.COUPON_NOT_APPLICABLE);
        }
    }

    @Nested
    @DisplayName("정률")
    class Percent {

        @Test
        @DisplayName("bp 로 재고 상한에서 자른다")
        void bp_로_재고_상한에서_자른다() {
            long coupon = insertCoupon("p1", "percent", 1000, 700L, "mall", null, 0);
            long issue = issue(coupon);

            // 10000 의 10% 는 1000 인데 상한이 700 이다.
            var applied = coupons.apply(userId, issue, List.of(new CouponLine(sellerA, 10_000)));

            assertThat(applied.total()).isEqualTo(700);
        }
    }

    private long issueAmountCoupon(String code, long value) {
        return issue(insertCoupon(code, "amount", value, null, "mall", null, 0));
    }

    private long issueSellerCoupon(String code, long value, long sellerId) {
        return issue(insertCoupon(code, "amount", value, null, "seller", sellerId, 0));
    }

    private long insertCoupon(String code, String kind, long value, Long maxDiscount,
            String bearer, Long seller, long minOrder) {
        return jdbc.sql("""
                        insert into coupon (code, name, discount_kind, discount_value,
                                            max_discount_amount, bearer, seller_id, min_order_amount)
                        values (:code, :code, :kind, :value, :max, :bearer, :seller, :min)
                        returning coupon_id
                        """)
                .param("code", code)
                .param("kind", kind)
                .param("value", value)
                .param("max", maxDiscount)
                .param("bearer", bearer)
                .param("seller", seller)
                .param("min", minOrder)
                .query(Long.class)
                .single();
    }

    private long issue(long couponId) {
        return jdbc.sql("""
                        insert into coupon_issue (coupon_id, user_id, expires_at)
                        values (:coupon, :user, now() + interval '30 days')
                        returning coupon_issue_id
                        """)
                .param("coupon", couponId)
                .param("user", userId)
                .query(Long.class)
                .single();
    }

    private static long sum(List<Long> values) {
        return values.stream().mapToLong(Long::longValue).sum();
    }
}
