package com.projectshop.shop.product;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 상품 전이표(`D7`, `ADR 0009`).
 *
 * <p>DB 를 안 쓴다. 표는 순수 데이터라 컨테이너를 띄울 이유가 없다.
 */
@DisplayName("상품 전이표")
class ProductTransitionsTest {

    @Test
    @DisplayName("제재를 푸는 것은 검수 권한이다 — 셀러 권한이 아니다")
    void unblockingNeedsReviewPermission() {
        assertThat(ProductTransitions.actionFor("blocked", "on_sale"))
                .as("셀러가 풀 수 있으면 제재가 무의미해진다")
                .contains("review");
        assertThat(ProductTransitions.actionFor("blocked", "draft")).contains("review");
    }

    @Test
    @DisplayName("셀러가 쉬었다 다시 파는 것은 셀러 권한이다")
    void sellerCanSuspendAndResume() {
        assertThat(ProductTransitions.actionFor("on_sale", "suspended")).contains("update");
        assertThat(ProductTransitions.actionFor("suspended", "on_sale")).contains("update");
    }

    @Test
    @DisplayName("표에 없는 전이는 비어 있다")
    void unknownTransitionIsEmpty() {
        // 팔던 것을 준비 중으로 되돌리면 그 사이 주문이 무엇을 가리키는지 애매해진다.
        assertThat(ProductTransitions.actionFor("on_sale", "draft")).isEmpty();
        assertThat(ProductTransitions.actionFor("draft", "on_sale")).isEmpty();
        assertThat(ProductTransitions.actionFor("draft", "blocked")).isEmpty();
    }

    @Test
    @DisplayName("sold_out 은 표에 없다 — 사람이 옮기는 상태가 아니다")
    void soldOutIsNotInTheTable() {
        assertThat(ProductTransitions.all())
                .as("재고에서 파생된다. 주문이 재고를 깎을 때 바뀐다(청크 10)")
                .noneMatch(t -> t.from().equals("sold_out") || t.to().equals("sold_out"));
    }

    @Test
    @DisplayName("파는 상태로 가는 전이는 전부 셀러 확인을 지난다")
    void everyPathToSaleIsGuarded() {
        // 트리거가 최후에 막지만, 새 전이를 표에 더할 때 앱 검사를 빠뜨리면
        // 이유가 500 으로 뭉개진다. 그 자리가 몇 개인지 여기서 드러난다.
        assertThat(ProductTransitions.all())
                .filteredOn(t -> t.to().equals("on_sale"))
                .hasSize(3);
    }
}
