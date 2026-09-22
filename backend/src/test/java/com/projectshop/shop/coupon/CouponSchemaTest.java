package com.projectshop.shop.coupon;

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
import com.projectshop.shop.auth.AuthFixture;

/**
 * 쿠폰 표가 무엇을 막나(`49`).
 *
 * <p>쓰는 입구는 `50` 이고 정산은 `51` 이다. 여기서 재는 것은 <b>표 스스로 막는 것</b>뿐이다.
 *
 * <p><b>제일 값이 큰 것이 마지막 시험이다.</b> 이 쿠폰이 전자금융거래법의 선불전자지급수단이
 * 아닌 근거가 <b>「그런 칸이 없다」</b>인데, 그 근거는 컬럼이 생기는 순간 조용히 무너진다 —
 * 잔액·충전·이전 칸이 안 생겼는지를 시험이 센다(`D2` 「안 걸리는 것도 근거를 남긴다」).
 */
@DisplayName("쿠폰 표가 막는 것")
class CouponSchemaTest extends PostgresTestBase {

    @Autowired
    private JdbcClient jdbc;

    private AuthFixture auth;
    private long sellerId;
    private long userId;

    @BeforeEach
    void setUp() {
        auth = new AuthFixture(jdbc);
        sellerId = auth.insertSeller("coupon-seller", "쿠폰셀러");
        userId = auth.insertUser("coupon@example.com", "받는이");
    }

    @Nested
    @DisplayName("부담 주체")
    class Bearer {

        /** 셀러 없는 셀러 부담은 정산에서 갈 곳이 없다 */
        @Test
        @DisplayName("셀러 부담에는 셀러가 있어야 한다")
        void 셀러_부담에는_셀러가_있어야_한다() {
            assertThatThrownBy(() -> insertCoupon("c1", "amount", 1000, null, "seller", null))
                    .isInstanceOf(DataAccessException.class);
        }

        /** 셀러가 붙은 몰 부담은 정산에서 두 번 빠진다 */
        @Test
        @DisplayName("몰 부담에는 셀러가 없어야 한다")
        void 몰_부담에는_셀러가_없어야_한다() {
            assertThatThrownBy(() -> insertCoupon("c2", "amount", 1000, null, "mall", sellerId))
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("짝이 맞으면 들어간다")
        void 짝이_맞으면_들어간다() {
            insertCoupon("c3", "amount", 1000, null, "mall", null);
            insertCoupon("c4", "percent", 1000, 5000L, "seller", sellerId);

            assertThat(couponCount()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("할인 방식")
    class Discount {

        @Test
        @DisplayName("정률은 1bp~10000bp 밖으로 못 나간다")
        void 정률은_범위_밖으로_못_나간다() {
            assertThatThrownBy(() -> insertCoupon("c5", "percent", 10001, null, "mall", null))
                    .isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> insertCoupon("c6", "percent", 0, null, "mall", null))
                    .isInstanceOf(DataAccessException.class);
        }

        /** 정액에 상한을 두면 그 값이 곧 할인액이라 뜻이 없다 */
        @Test
        @DisplayName("정액에는 상한을 못 붙인다")
        void 정액에는_상한을_못_붙인다() {
            assertThatThrownBy(() -> insertCoupon("c7", "amount", 1000, 500L, "mall", null))
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("모르는 할인 방식은 못 들어간다")
        void 모르는_할인_방식은_못_들어간다() {
            assertThatThrownBy(() -> insertCoupon("c8", "buy_one_get_one", 1, null, "mall", null))
                    .isInstanceOf(DataAccessException.class);
        }
    }

    @Nested
    @DisplayName("발급")
    class Issue {

        /** 앱에서만 세면 두 번 눌러 두 장이 된다 */
        @Test
        @DisplayName("한 사람이 같은 쿠폰을 한 번만 받는다")
        void 한_사람이_같은_쿠폰을_한_번만_받는다() {
            long coupon = insertCoupon("c9", "amount", 1000, null, "mall", null);
            issue(coupon, userId);

            assertThatThrownBy(() -> issue(coupon, userId))
                    .isInstanceOf(DataAccessException.class);
        }

        /** 「어디에 썼는지 모르는 사용」은 정산이 못 따라간다 */
        @Test
        @DisplayName("쓴 시각만 있고 주문이 없으면 못 들어간다")
        void 쓴_시각만_있고_주문이_없으면_못_들어간다() {
            long coupon = insertCoupon("c10", "amount", 1000, null, "mall", null);
            issue(coupon, userId);

            assertThatThrownBy(() -> jdbc.sql("""
                            update coupon_issue set used_at = now() where coupon_id = :id
                            """)
                    .param("id", coupon)
                    .update())
                    .isInstanceOf(DataAccessException.class);
        }
    }

    @Nested
    @DisplayName("법의 경계")
    class LegalBoundary {

        /**
         * 선불전자지급수단이 아닌 근거가 <b>「그런 칸이 없다」</b>다(`D2`).
         * 칸이 생기면 근거가 조용히 무너지므로 <b>칸이 안 생겼는지를 센다</b>.
         *
         * <p>이름을 통째로 막지 않고 <b>뜻이 있는 낱말</b>만 본다 — 정확한 이름을 못 맞히면
         * 우회가 쉽고, 여기서 잡으려는 것은 우회가 아니라 <b>무심코 더하는 것</b>이다.
         */
        @Test
        @DisplayName("잔액·충전·이전 칸이 없다")
        void 잔액_충전_이전_칸이_없다() {
            List<String> columns = jdbc.sql("""
                            select column_name from information_schema.columns
                             where table_schema = 'public'
                               and table_name in ('coupon', 'coupon_issue')
                            """)
                    .query(String.class)
                    .list();

            assertThat(columns).noneSatisfy(name ->
                    assertThat(name).containsAnyOf("balance", "charge", "transfer", "remain"));
        }
    }

    private long insertCoupon(String code, String kind, long value, Long maxDiscount,
            String bearer, Long seller) {
        return jdbc.sql("""
                        insert into coupon (code, name, discount_kind, discount_value,
                                            max_discount_amount, bearer, seller_id)
                        values (:code, :code, :kind, :value, :max, :bearer, :seller)
                        returning coupon_id
                        """)
                .param("code", code)
                .param("kind", kind)
                .param("value", value)
                .param("max", maxDiscount)
                .param("bearer", bearer)
                .param("seller", seller)
                .query(Long.class)
                .single();
    }

    private void issue(long couponId, long toUserId) {
        jdbc.sql("""
                        insert into coupon_issue (coupon_id, user_id, expires_at)
                        values (:coupon, :user, now() + interval '30 days')
                        """)
                .param("coupon", couponId)
                .param("user", toUserId)
                .update();
    }

    private int couponCount() {
        return jdbc.sql("select count(*) from coupon where deleted_at is null")
                .query(Integer.class)
                .single();
    }
}
