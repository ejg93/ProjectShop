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
import com.projectshop.shop.auth.Allowed;

/**
 * 후기를 쓸 자격(`47`).
 *
 * <p>고정하는 것이 셋이다 — <b>받아 본 뒤에만 쓰고</b>, <b>한 줄에 살아 있는 후기는 하나</b>고,
 * <b>쓰기는 자기 것만</b>이다. 앞 둘은 DB 가 들고 셋째는 판정이 든다.
 *
 * <p>넷째가 대조다. {@link ReviewStatusPolicy} 가 생 문자열로 상태를 드는데
 * ({@code order} 의 열거형이 그 패키지 밖으로 안 나온다), <b>그 값이 실제 배송 상태인지를
 * 여기서 잰다</b> — 오타나 이름 변경이 조용히 「아무 상태에서도 안 열리는 동작」을 만든다.
 */
@DisplayName("후기를 쓸 자격")
class ReviewEligibilityTest extends PostgresTestBase {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ReviewStatusPolicy statusPolicy;

    private ReviewFixture fixture;
    private long productId;
    private long buyerId;

    @BeforeEach
    void setUp() {
        fixture = new ReviewFixture(jdbc);
        productId = fixture.insertProduct("후기 상품");
        buyerId = fixture.buyerId();
    }

    @Nested
    @DisplayName("받아 본 뒤에만 쓴다")
    class OnlyAfterDelivery {

        @Test
        @DisplayName("배송 전에는 못 쓴다")
        void 배송_전에는_못_쓴다() {
            long item = fixture.placeOrder(productId, "preparing");

            assertThatThrownBy(() -> fixture.insertReview(item, productId, buyerId, 5, "잘 받았다"))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("받아 본 뒤에만");
        }

        /** 물건을 안 가진 사람의 후기라 「써 본 사람의 말」이 아니다 */
        @Test
        @DisplayName("반품으로 끝난 줄에는 못 쓴다")
        void 반품으로_끝난_줄에는_못_쓴다() {
            long item = fixture.placeOrder(productId, "returned");

            assertThatThrownBy(() -> fixture.insertReview(item, productId, buyerId, 5, "잘 받았다"))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("받아 본 뒤에만");
        }

        @Test
        @DisplayName("배송완료와 구매확정에서는 쓴다")
        void 배송완료와_구매확정에서는_쓴다() {
            fixture.insertReview(fixture.placeOrder(productId, "delivered"),
                    productId, buyerId, 5, "잘 받았다");
            fixture.insertReview(fixture.placeOrder(productId, "confirmed"),
                    productId, buyerId, 4, "쓸 만하다");

            assertThat(liveCount()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("한 줄에 하나")
    class OnePerOrderItem {

        @Test
        @DisplayName("같은 줄에 둘째 후기는 못 들어간다")
        void 같은_줄에_둘째_후기는_못_들어간다() {
            long item = fixture.placeOrder(productId, "delivered");
            fixture.insertReview(item, productId, buyerId, 5, "잘 받았다");

            assertThatThrownBy(() -> fixture.insertReview(item, productId, buyerId, 1, "다시 쓴다"))
                    .isInstanceOf(DataAccessException.class);
        }

        /** 전체 유니크로 두면 실수로 지운 사람이 영영 못 쓰고 그 구제가 운영 문의가 된다 */
        @Test
        @DisplayName("지운 뒤에는 다시 쓴다")
        void 지운_뒤에는_다시_쓴다() {
            long item = fixture.placeOrder(productId, "delivered");
            fixture.insertReview(item, productId, buyerId, 5, "잘 받았다");

            jdbc.sql("update review set deleted_at = now() where order_item_id = :id")
                    .param("id", item)
                    .update();
            fixture.insertReview(item, productId, buyerId, 3, "다시 쓴다");

            assertThat(liveCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("판정")
    class Evaluation {

        @Test
        @DisplayName("사는 사람의 쓰기 셋이 자기 것만이다")
        void 사는_사람의_쓰기_셋이_자기_것만이다() {
            assertThat(scopeOf("customer", "create")).isEqualTo("own");
            assertThat(scopeOf("customer", "update")).isEqualTo("own");
            assertThat(scopeOf("customer", "delete")).isEqualTo("own");
            assertThat(scopeOf("customer", "read")).isEqualTo("all");
        }

        /** 답글·신고는 `48` 이 연다. 여기서 같이 열면 그 청크가 무엇을 정하는지가 흐려진다 */
        @Test
        @DisplayName("셀러는 읽기만 받는다")
        void 셀러는_읽기만_받는다() {
            assertThat(actionsOf("seller_owner")).containsExactly("read");
            assertThat(actionsOf("seller_staff")).containsExactly("read");
        }

        /**
         * 정책이 드는 상태가 실제 배송 상태인지 잰다.
         *
         * <p><b>오타는 조용히 지나간다</b> — 모르는 상태를 적으면 그 동작이 아무 상태에서도
         * 안 열리는데, 판정은 그냥 거부로 보여서 사람이 권한 설정을 의심한다.
         */
        @Test
        @DisplayName("정책이 드는 상태가 배송 상태 목록 안에 있다")
        void 정책이_드는_상태가_배송_상태_목록_안에_있다() {
            String definition = jdbc.sql("""
                            select pg_get_constraintdef(oid) from pg_constraint
                             where conname = 'seller_order_status_check'
                            """)
                    .query(String.class)
                    .single();

            Allowed<String> create = statusPolicy.allowedStatuses("review", "create");
            assertThat(create.restricted()).isTrue();
            assertThat(create.values()).allSatisfy(status ->
                    assertThat(definition).contains("'" + status + "'"));
        }
    }

    private int liveCount() {
        return jdbc.sql("select count(*) from review where deleted_at is null")
                .query(Integer.class)
                .single();
    }

    private String scopeOf(String roleCode, String action) {
        return jdbc.sql("""
                        select rp.scope from role_permission rp
                          join role r on r.role_id = rp.role_id
                          join permission p on p.permission_id = rp.permission_id
                         where r.code = :code and p.resource = 'review' and p.action = :action
                           and rp.effect = 'allow'
                        """)
                .param("code", roleCode)
                .param("action", action)
                .query(String.class)
                .single();
    }

    private List<String> actionsOf(String roleCode) {
        return jdbc.sql("""
                        select p.action from role_permission rp
                          join role r on r.role_id = rp.role_id
                          join permission p on p.permission_id = rp.permission_id
                         where r.code = :code and p.resource = 'review' and rp.effect = 'allow'
                         order by p.action
                        """)
                .param("code", roleCode)
                .query(String.class)
                .list();
    }
}
