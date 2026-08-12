package com.projectshop.shop.product;

import java.util.List;
import java.util.Optional;

/**
 * 상품 상태 전이표(`D7`, `ADR 0009`).
 *
 * <p><b>표에 없는 전이는 못 한다.</b> 상태를 여기저기서 갈아 끼우면 반드시 깨져서,
 * 허용된 전이를 한 곳에 선언하고 그 표를 거치지 않는 변경을 막기로 했다.
 *
 * <p>표가 <b>권한까지 들고 있다.</b> 그래야 "누가 할 수 있나" 가 코드 분기로 흩어지지 않는다 —
 * {@code blocked → on_sale} 이 {@code product:review} 라는 사실이 표에 적혀 있으면
 * 셀러는 그 권한이 없어서 자동으로 막힌다. 조건문을 따로 쓸 필요가 없다.
 *
 * <p><b>{@code SOLD_OUT} 은 여기 없다.</b> 사람이 옮기는 상태가 아니라 재고에서 파생된다 —
 * 주문(청크 10)이 재고를 깎을 때 바뀐다.
 *
 * <p><b>상태를 {@link ProductStatus} 로 든다</b>(`7e`). 문자열이면 표의 오타를 컴파일러가 못 잡고,
 * 그 줄은 <b>영원히 안 걸리는 전이</b>가 되면서 아무 오류도 안 난다.
 */
final class ProductTransitions {

    /**
     * @param permission 이 전이를 할 수 있는 권한. {@code resource:action} 형태다
     */
    record Transition(ProductStatus from, ProductStatus to, String permission) {
    }

    private static final String UPDATE = "update";
    private static final String REVIEW = "review";

    private static final List<Transition> ALLOWED = List.of(
            // 검수 (7c)
            new Transition(ProductStatus.DRAFT, ProductStatus.PENDING_REVIEW, UPDATE),
            new Transition(ProductStatus.PENDING_REVIEW, ProductStatus.ON_SALE, REVIEW),
            new Transition(ProductStatus.PENDING_REVIEW, ProductStatus.DRAFT, REVIEW),

            // 셀러가 쉰다 (7d). 품절·단종처럼 자기 사정으로 내리는 것이다.
            new Transition(ProductStatus.ON_SALE, ProductStatus.SUSPENDED, UPDATE),
            new Transition(ProductStatus.SUSPENDED, ProductStatus.ON_SALE, UPDATE),

            // 관리자가 막는다 (7d). 승인 뒤에 문제가 드러나는 경우다 —
            // 위법 표시, 위조품 신고, 리콜. 알고도 방치하면 중개자가 연대책임을 진다(제20조의2).
            new Transition(ProductStatus.ON_SALE, ProductStatus.BLOCKED, REVIEW),
            new Transition(ProductStatus.SUSPENDED, ProductStatus.BLOCKED, REVIEW),

            // 푸는 것도 관리자만이다. 셀러가 풀 수 있으면 제재가 무의미해진다.
            new Transition(ProductStatus.BLOCKED, ProductStatus.ON_SALE, REVIEW),
            // 고쳐서 다시 검수받으라는 뜻. 오인이 아니라 실제로 문제가 있었던 경우다.
            new Transition(ProductStatus.BLOCKED, ProductStatus.DRAFT, REVIEW));

    private ProductTransitions() {
    }

    /**
     * 이 전이에 필요한 동작 이름. 표에 없으면 비어 있다.
     *
     * <p>돌려주는 것이 {@code product:update} 의 {@code update} 부분이다 —
     * 자원은 언제나 {@code product} 라 호출자가 붙인다.
     */
    static Optional<String> actionFor(ProductStatus from, ProductStatus to) {
        return ALLOWED.stream()
                .filter(t -> t.from() == from && t.to() == to)
                .map(Transition::permission)
                .findFirst();
    }

    /** 테스트가 표 전체를 훑을 때 쓴다. 표에 줄이 늘면 그 테스트가 알려 준다 */
    static List<Transition> all() {
        return ALLOWED;
    }
}
