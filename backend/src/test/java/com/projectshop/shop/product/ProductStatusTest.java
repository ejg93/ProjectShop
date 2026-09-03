package com.projectshop.shop.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 상품 축의 두 상태 목록이 <b>서로 어떤 관계인가</b>. 이 관계는 DB 가 안 든다 —
 * 제약 둘이 따로 서 있을 뿐이라 <b>겹침이 무너지는 것은 여기서만 걸린다.</b>
 *
 * <p><b>제약과의 대조 셋은 {@code EnumConstraintTest} 로 옮겼다</b>(`43a-19`) —
 * {@code product_status_check}·{@code sku_status_check}·{@code product_withdrawal_reason_check}.
 * 열거형마다 옆에 두면 새 열거형이 대조를 안 받는 것을 막는 것이 아무것도 없다.
 *
 * <p>DB 를 안 쓰게 됐다. 남은 둘이 값 목록끼리의 비교라 {@code PostgresTestBase} 를 뗐다
 * (`D15` — 아래층에서 되는 것을 위층에 두지 않는다).
 */
@DisplayName("상품 상태 목록")
class ProductStatusTest {

    /**
     * 두 목록이 겹치지만 같지 않다. <b>그래서 타입을 갈랐다</b> —
     * 문자열이면 {@code sku} 자리에 {@code draft} 를 넣어도 컴파일이 통과한다.
     */
    @Test
    @DisplayName("상품 상태와 조합 상태는 다른 목록이다")
    void twoListsDiffer() {
        assertThat(codesOf(SkuStatus.values()))
                .as("조합은 검수를 안 받고 제재도 상품 단위라 목록이 좁다")
                .isSubsetOf(codesOf(ProductStatus.values()))
                .hasSizeLessThan(ProductStatus.values().length);
    }

    @Test
    @DisplayName("모르는 값은 조용히 통과하지 않는다")
    void unknownCodeThrows() {
        assertThatThrownBy(() -> ProductStatus.of("on_sail"))
                .as("모르는 값을 통과시키면 그 뒤의 전이·판정이 전부 엉뚱한 답을 낸다")
                .isInstanceOf(IllegalStateException.class);
    }

    private static List<String> codesOf(ProductStatus[] values) {
        return Arrays.stream(values).map(ProductStatus::code).toList();
    }

    private static List<String> codesOf(SkuStatus[] values) {
        return Arrays.stream(values).map(SkuStatus::code).toList();
    }
}
