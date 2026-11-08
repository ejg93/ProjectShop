package com.projectshop.shop.metric;

import java.util.function.ToDoubleFunction;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;

/**
 * 우리가 내는 지표의 이름을 한 자리에 둔다(`Q388`, {@code observability-rules.md} 「이름과 태그」).
 *
 * <p><b>이름은 이 상수에만 있다.</b> 이름을 부르는 자리에서 글자로 적으면 이름 꼴({@code shop.<표>.<무엇>})을
 * 소스 글자로 재야 했다 — 그것이 {@code MetricNameTest} 의 옛 방법이다. 지금은 {@code ArchitectureTest} 가
 * 이 enum 밖의 날 {@code builder} 호출을 막고, {@code MetricNameTest} 가 {@link #values()} 를 돌며 꼴을 잰다.
 *
 * <p><b>빌더를 돌려준다.</b> 태그·설명·등록은 부르는 쪽이 잇는다 — 태그 값이 그 자리의 것이라서다.
 * 등록은 {@code MeterRegistry} 가 같은 이름·태그면 같은 미터를 돌려주므로 부를 때마다 이어도 된다.
 *
 * <p><b>{@code support} 가 아니라 {@code metric} 이다.</b> {@code ProblemFactory} 가 부르는데 {@code support} 는 이미
 * {@code error} 를 불러서 순환이 된다. 이 패키지는 우리 것을 아무것도 안 부르는 뿌리다({@code coding-rules.md}
 * 「자원이 아닌데 패키지를 파는 경우」).
 */
public enum MetricName {

    /** 지난 접속기록 점검이 표시한 열람자 수(`AuditLogReviewBatch`) */
    AUDIT_LOG_FLAGGED("shop.audit_log.flagged"),

    /** 권한 판정에 걸린 시간(`PermissionEvaluator`) */
    PERMISSION_DECIDE("shop.permission.decide"),

    /** 오류 코드별로 센 수(`ProblemFactory`) */
    ERROR_RAISED("shop.error.raised"),

    /** 기한을 넘긴 환급 대기 건수(`RefundSweeper`) */
    REFUND_OVERDUE("shop.refund.overdue"),

    /** 배치 회차 수(`BatchRuns`) */
    BATCH_RUN_FINISHED("shop.batch_run.finished");

    private final String value;

    MetricName(String value) {
        this.value = value;
    }

    /** 노출되는 이름. {@code shop.<표>.<무엇>} 꼴이다 */
    public String value() {
        return value;
    }

    /** 이 이름의 카운터 빌더 */
    public Counter.Builder counter() {
        return Counter.builder(value);
    }

    /** 이 이름의 타이머 빌더 */
    public Timer.Builder timer() {
        return Timer.builder(value);
    }

    /** 이 이름의 게이지 빌더. 값은 {@code obj} 에서 {@code read} 로 읽는다 */
    public <T> Gauge.Builder<T> gauge(T obj, ToDoubleFunction<T> read) {
        return Gauge.builder(value, obj, read);
    }
}
