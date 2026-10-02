package com.projectshop.shop.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.projectshop.shop.auth.Allowed;
import com.projectshop.shop.order.SellerOrderQuery.Branch;
import com.projectshop.shop.order.SellerOrderQuery.Scope;

/**
 * 셀러 주문 목록이 범위 조건의 갈래를 고르는 자리(`Q243a`).
 *
 * <p><b>계획 단언은 갈래를 직접 받는다</b>({@code SellerOrderQueryTest}) — 그래서 셀러 하나만 보는 사람이 여럿 갈래
 * ({@code = any(배열)})로 잘못 가도 거기서는 안 빨갛다. 결과도 같아서 스코프 시험도 초록이다. 고르는 것을 여기서 본다
 * (마무리 57차 독립 리뷰).
 */
@DisplayName("셀러 주문 목록의 갈래")
class SellerOrderScopeTest {

    private static final long ALPHA = 11L;
    private static final long BETA = 22L;

    @Test
    @DisplayName("볼 수 있는 셀러가 하나면 셀러를 안 골라도 하나 갈래다 — 배열 조건으로 안 간다")
    void soleSellerIsOne() {
        Scope scope = Scope.of(Allowed.only(Set.of(ALPHA)), null);

        assertThat(scope.branch()).isEqualTo(Branch.ONE);
        assertThat(scope.sellerId()).isEqualTo(ALPHA);
    }

    @Test
    @DisplayName("여러 셀러에 속하면 고른 셀러는 하나 갈래, 안 고르면 여럿 갈래다")
    void memberOfManyPicksOneOrMany() {
        Allowed<Long> both = Allowed.only(Set.of(ALPHA, BETA));

        assertThat(Scope.of(both, BETA).branch()).isEqualTo(Branch.ONE);
        assertThat(Scope.of(both, BETA).sellerId()).isEqualTo(BETA);
        assertThat(Scope.of(both, null).branch()).isEqualTo(Branch.MANY);
        assertThat(Scope.of(both, null).sellers()).containsExactlyInAnyOrder(ALPHA, BETA);
    }

    @Test
    @DisplayName("범위 밖 셀러를 고르면 없음 갈래다")
    void outsideSellerIsNone() {
        assertThat(Scope.of(Allowed.only(Set.of(ALPHA)), BETA).branch()).isEqualTo(Branch.NONE);
    }

    @Test
    @DisplayName("전부 보는 사람은 안 고르면 전부, 고르면 하나 갈래다")
    void everythingPicksAllOrOne() {
        assertThat(Scope.of(Allowed.everything(), null).branch()).isEqualTo(Branch.ALL);
        assertThat(Scope.of(Allowed.everything(), ALPHA).branch()).isEqualTo(Branch.ONE);
    }
}
