package com.projectshop.shop.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;

/**
 * 상태 목록이 DB 와 코드 두 군데에 있다. <b>어긋나는 것을 여기서 잡는다</b>(`D23` 「열거값을 어디에 두나」).
 *
 * <p>어긋나면 무슨 일이 나는지가 방향마다 다르다.
 * <ul>
 *   <li><b>DB 에만 있다</b> — 그 값을 읽는 순간 {@code of()} 가 터진다. 조회 하나가 통째로 500 이 된다</li>
 *   <li><b>코드에만 있다</b> — 그 상태로 옮기려는 {@code update} 가 {@code check} 에 걸린다.
 *       전이표에는 있는데 <b>절대 성공하지 않는 전이</b>가 된다</li>
 * </ul>
 *
 * <p>둘 다 <b>그 경로를 실제로 밟아 봐야</b> 드러난다. 마이그레이션을 고친 사람은 대개 안 밟는다.
 */
@DisplayName("상품 상태 목록")
class ProductStatusTest extends PostgresTestBase {

    @Autowired
    private JdbcClient jdbc;

    @Test
    @DisplayName("상품 상태가 DB 제약과 같다")
    void productStatusMatchesConstraint() {
        assertThat(valuesIn("product_status_check"))
                .as("`check` 와 `ProductStatus` 가 갈리면 한쪽에만 있는 상태가 생긴다")
                .containsExactlyInAnyOrderElementsOf(codesOf(ProductStatus.values()));
    }

    @Test
    @DisplayName("조합 상태가 DB 제약과 같다")
    void skuStatusMatchesConstraint() {
        assertThat(valuesIn("sku_status_check"))
                .containsExactlyInAnyOrderElementsOf(codesOf(SkuStatus.values()));
    }

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

    /**
     * {@code check (status in ('draft', 'pending_review', ...))} 에서 따옴표 안을 뽑는다.
     *
     * <p>제약 정의를 문자열로 읽는 것이 거칠지만, 대안이 <b>목록을 여기 손으로 또 적는 것</b>이라
     * 그러면 세 번째 사본이 생긴다 — 이 테스트가 막으려는 것이 정확히 그거다.
     */
    private List<String> valuesIn(String constraintName) {
        String definition = jdbc.sql("""
                        select pg_get_constraintdef(oid) from pg_constraint
                         where conname = :name
                        """)
                .param("name", constraintName)
                .query(String.class)
                .single();

        return Arrays.stream(definition.split("'"))
                .filter(part -> part.matches("[a-z_]+"))
                .toList();
    }

    private static List<String> codesOf(ProductStatus[] values) {
        return Arrays.stream(values).map(ProductStatus::code).toList();
    }

    private static List<String> codesOf(SkuStatus[] values) {
        return Arrays.stream(values).map(SkuStatus::code).toList();
    }
}
