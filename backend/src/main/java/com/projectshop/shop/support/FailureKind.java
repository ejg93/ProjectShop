package com.projectshop.shop.support;

import java.util.Arrays;

/**
 * 배치 회차가 실패한 종류. 저장값은 {@code batch_run.failure_kind} 고
 * 목록은 {@code batch_run_failure_kind_values_check} 다(`43a-23`).
 *
 * <p><b>재시도가 이 값으로 갈린다</b>(`D19` 2층). {@link BatchRuns#shouldRetry} 가
 * 마지막 회차의 이 값이 {@code transient} 일 때만 다시 시도한다.
 *
 * <p><b>판단은 실패한 순간에 한다.</b> 이력만 보고 나중에 다시 가르면 판단이 두 벌이 되고,
 * 그 둘이 갈리면 어느 쪽이 맞는지 아무도 못 정한다({@link BatchRuns#failureKindOf}).
 */
enum FailureKind {

    /** 10분 뒤에 다시 해 볼 실패 */
    TRANSIENT,

    /** 다시 해도 같은 자리에서 죽는 실패 */
    PERMANENT;

    /** 저장값. DB 는 소문자다 */
    String code() {
        return name().toLowerCase();
    }

    /**
     * 저장값을 종류로 되돌린다.
     *
     * <p><b>모르는 값이면 터진다.</b> {@code batch_run_failure_kind_values_check} 가 이미 막고
     * 있으므로 여기 오는 모르는 값은 <b>마이그레이션과 이 enum 이 어긋났다</b>는 뜻이다.
     */
    static FailureKind of(String code) {
        return Arrays.stream(values())
                .filter(kind -> kind.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("모르는 배치 실패 종류다: " + code));
    }
}
