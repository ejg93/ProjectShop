package com.projectshop.shop.settlement;

import java.util.Arrays;

/**
 * 정산 한 회차의 지급이 어디까지 왔나. 저장값은 {@code settlement.payout_status} 고
 * 목록은 {@code settlement_payout_status_check} 다(`V57`).
 *
 * <p><b>시각 컬럼과 짝이 강제돼 있다</b>(`V57`) — {@link #PENDING} 이면 요청 시각이 비어 있고,
 * {@link #PAID}·{@link #REJECTED} 면 결정 시각이 차 있다. 그 짝은 {@code check} 가 들고 있고
 * <b>여기 안 싣는다</b>(`43a-13`) — 옮기면 규칙이 두 벌이 되고 강제 지점이 내려간다.
 *
 * <p><b>{@link #REJECTED} 는 종착이 아니다.</b> 반려된 회차는 다시 요청할 수 있어서
 * {@code PENDING} 과 함께 요청 가능 상태다 — 그래야 셀러가 서류를 고쳐 다시 낼 수 있다.
 *
 * <p>열거형으로 두는 근거는 `D23` 「가르는 물음」이다 — <b>값이 하나 늘면 코드를 고쳐야 한다.</b>
 * 요청이 열리는 상태와 결정이 열리는 상태가 값으로 갈린다.
 */
enum PayoutStatus {

    /** 아직 아무도 안 건드렸다. 지급액이 0 이어도 이 상태다 */
    PENDING,

    /** 셀러가 지급을 요청했다. <b>결정이 열리는 유일한 상태다</b> */
    REQUESTED,

    /** 지급했다 */
    PAID,

    /** 반려했다. <b>다시 요청할 수 있다</b> */
    REJECTED;

    /** 저장값. DB 는 소문자고 응답은 대문자다(`D5` 「형식」) */
    String code() {
        return name().toLowerCase();
    }

    /**
     * 저장값을 상태로 되돌린다.
     *
     * <p><b>모르는 값이면 터진다.</b> {@code settlement_payout_status_check} 가 이미 막고
     * 있으므로 여기 오는 모르는 값은 <b>마이그레이션과 이 enum 이 어긋났다</b>는 뜻이다.
     */
    static PayoutStatus of(String code) {
        return Arrays.stream(values())
                .filter(status -> status.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("모르는 지급 상태다: " + code));
    }
}
