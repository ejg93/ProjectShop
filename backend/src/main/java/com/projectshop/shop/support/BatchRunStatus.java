package com.projectshop.shop.support;

import java.util.Arrays;

/**
 * 배치 회차 하나가 어떻게 끝났나. 저장값은 {@code batch_run.status} 고
 * 목록은 {@code batch_run_status_check} 다(`43a-23`).
 *
 * <p><b>{@link FailureKind} 와 짝이다.</b> {@code V42} 가 「{@code failure_kind} 가 있는 것과
 * {@code failed} 인 것은 같다」고 걸어 놨다 — 한쪽만 채우면 제약이 막는다.
 *
 * <p><b>{@code SKIPPED} 가 둘을 겸한다</b>(`D19`). 이미 성공한 회차를 다시 안 돌린 것과
 * 선행이 막아서 안 돈 것이 같은 값인데, 앞은 행을 아예 안 남기고 뒤만 남긴다 —
 * 아무것도 안 남기면 「선행이 막았다」와 「스케줄이 안 걸렸다」가 이력에서 안 갈린다.
 */
enum BatchRunStatus {

    SUCCEEDED,
    FAILED,
    SKIPPED;

    /** 저장값. DB 는 소문자다 */
    String code() {
        return name().toLowerCase();
    }

    /**
     * 저장값을 상태로 되돌린다.
     *
     * <p><b>모르는 값이면 터진다.</b> {@code batch_run_status_check} 가 이미 막고 있으므로
     * 여기 오는 모르는 값은 <b>마이그레이션과 이 enum 이 어긋났다</b>는 뜻이다.
     */
    static BatchRunStatus of(String code) {
        return Arrays.stream(values())
                .filter(status -> status.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("모르는 배치 회차 상태다: " + code));
    }
}
