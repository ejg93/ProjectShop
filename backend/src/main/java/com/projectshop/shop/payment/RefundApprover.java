package com.projectshop.shop.payment;

import java.util.Arrays;
import java.util.Locale;

/**
 * 환불을 승인·반려한 주체(`Q124`, {@code refund.approved_by_type}).
 *
 * <h2>{@code ActorType} 을 안 쓴다</h2>
 *
 * <p>요청자({@code requested_by_type})는 그쪽이 값을 대는데(`43a-17`) <b>승인자는 목록이 좁다</b> —
 * {@code admin}·{@code system} 둘뿐이고 요청자는 넷이다(`V51`). 넓은 쪽을 쓰면
 * <b>{@code customer} 를 넘기는 코드가 컴파일을 지나고</b> DB 제약이 실행 때 막는다 —
 * 타입이 지키는 자리가 사라진다(`D23` 축 2: 타입 1위).
 *
 * <p>값이 겹치는 열거형이 둘이 되는 것이 그 대가다. <b>제약이 이미 둘로 갈려 있으므로</b>
 * 모양을 따라가는 것이고, {@code EnumConstraintTest} 가 둘을 각자 제 제약과 맞춘다.
 *
 * <h2>바깥에서 오지 않는다</h2>
 *
 * <p>승인자는 요청 본문이 정하는 값이 아니라 <b>우리가 어느 경로로 들어왔는지</b>가 정한다 —
 * 관리자 승인이면 {@link #ADMIN}, 배치가 돌린 것이면 {@link #SYSTEM} 이다. 그래서 `Q121` 이 둔
 * {@code ofRequest} 짝이 여기엔 없다.
 */
enum RefundApprover {

    /** 사람이 승인·반려했다. {@code V24} 의 do 블록이 그 권한을 관리자 밖으로 못 나가게 지킨다(`D2` R5) */
    ADMIN,

    /** 배치가 돌렸다. 결제가 실패해 자동으로 되돌리는 경로다 */
    SYSTEM;

    /**
     * DB 에 들어가는 값. {@code EnumConstraintTest} 가 이 이름의 메서드를 리플렉션으로 읽어
     * 제약 목록과 대조한다 — 그래서 이름을 바꾸지 않는다.
     */
    String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    // **`of(String code)` 를 안 둔다**(마무리 35차 독립 리뷰). 다른 열거형은 DB 에서 읽은 값을
    // 되돌리려고 그것을 두는데, 이 값을 열거형으로 읽는 자리가 아직 없다 — 두면 죽은 코드다.
    // `Q120` 이 `ImageContentType` 에서 같은 판단을 했다. 읽는 자리가 생기는 날 그때 만든다.
}
