package com.projectshop.shop.order;

import java.util.Arrays;

/**
 * 반품 요청이 어디까지 왔나. 저장값은 {@code return_request.status} 고
 * 목록은 {@code return_request_status_check} 다(`V63`).
 *
 * <p><b>여섯이 한 줄로 흐르지 않는다.</b> {@link #PICKED_UP} 은 수거를 셀러가 아니라
 * 택배가 하는 경우에만 지나고, {@link #INSPECTED} 는 검수를 별도로 기록할 때만 선다 —
 * 둘 다 건너뛸 수 있다. 끝은 {@link #APPROVED} 아니면 {@link #REJECTED} 둘뿐이다.
 *
 * <p><b>끝 둘이 제약 셋을 동시에 건다</b>(`V63`) — 판정 시각이 차고
 * ({@code return_request_decision_check}), 반환 비용 부담 주체가 정해지고
 * ({@code return_request_bearer_decided_check}), 하자면 소비자에게 못 물린다
 * ({@code return_request_defect_bearer_check}, 제18조제10항). 그래서 이 둘은
 * <b>값 하나가 아니라 판정 사건</b>이다.
 *
 * <p>열거형으로 두는 근거는 `D23` 「가르는 물음」이다 — <b>값이 늘면 코드를 고쳐야 한다.</b>
 * {@link ReturnShippingFeeBearer#of} 가 이 값으로 갈리고, 새 상태가 끝인지 중간인지에 따라
 * 위 제약 셋에 걸릴지가 달라진다.
 */
enum ReturnStatus {

    /** 소비자가 냈다. 아직 아무도 안 봤다 */
    REQUESTED,

    /** 택배가 걷어 갔다. 셀러가 직접 받으면 이 칸을 안 지난다 */
    PICKED_UP,

    /** 셀러에게 도착했다 */
    RECEIVED,

    /** 검수했다. 따로 기록하지 않으면 이 칸을 안 지난다 */
    INSPECTED,

    /** 인정했다. <b>부담 주체가 같이 정해진다</b> */
    APPROVED,

    /** 안 인정했다. <b>부담 주체가 같이 정해진다</b> */
    REJECTED;

    /** 저장값. DB 는 소문자다(`D5` 「형식」) */
    String code() {
        return name().toLowerCase();
    }

    /** 판정이 끝났나. 이 둘만 부담 주체와 판정 시각을 요구한다(`V63`) */
    boolean isDecided() {
        return this == APPROVED || this == REJECTED;
    }

    /**
     * 저장값을 상태로 되돌린다.
     *
     * <p><b>아직 부르는 데가 없다</b>(마무리의 독립 리뷰가 짚었다). 읽는 쪽이 상태를 타입으로
     * 받기 시작하면 그때 쓴다 — {@code ReturnRequestQuery} 가 아직 문자열을 그대로 내보낸다.
     * <b>대전제의 「세어 보고 하나뿐이면 안 만든다」에 걸리는 자리</b>지만,
     * {@link #code} 와 짝이라 한쪽만 두면 <b>되돌리는 길이 없는 타입</b>이 된다.
     *
     * <p><b>모르는 값이면 터진다.</b> {@code check} 가 이미 막고 있으므로 여기 오는 모르는 값은
     * <b>마이그레이션과 이 enum 이 어긋났다</b>는 뜻이다.
     */
    static ReturnStatus of(String code) {
        return Arrays.stream(values())
                .filter(status -> status.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("모르는 반품 상태다: " + code));
    }
}
