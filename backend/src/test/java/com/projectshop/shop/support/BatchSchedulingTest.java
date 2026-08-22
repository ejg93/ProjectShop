package com.projectshop.shop.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import com.projectshop.shop.PostgresTestBase;

/**
 * 배치 여섯이 스레드를 나눠 쓰다 서로 미는가(청크 36b).
 *
 * <p><b>값을 설정에 박는 것으로는 안 막힌다.</b> {@code spring.task.scheduling.pool.size} 를 지우면
 * Spring 기본값 1 로 돌아가는데 <b>아무 테스트도 안 깨지고</b>, 깨지는 것은 운영에서 배치가 밀릴 때다.
 * 밀리는 것 중에 환불 스위퍼가 있고, 그것이 미뤄지면 3영업일 기한이 그냥 흐른다
 * (시행령 제21조의3, 지연배상금 연 15%).
 *
 * <p>그래서 여기서 <b>둘을 본다</b> — 자리가 배치 수만큼 있는가(반사)와,
 * 실제로 다 같이 도는가(붙잡아 두고 확인). 앞엣것은 배치를 더했을 때 고칠 곳을 말해 주고,
 * 뒤엣것은 그 수가 진짜로 동시에 도는 것인지를 밟는다.
 */
@DisplayName("배치 스케줄러")
class BatchSchedulingTest extends PostgresTestBase {

    /** 붙잡아 둔 작업이 다 시작될 때까지 기다리는 한도. 넘으면 스레드가 모자란 것이다. */
    private static final int START_TIMEOUT_SECONDS = 5;

    @Autowired
    private ThreadPoolTaskScheduler scheduler;

    @Autowired
    private ScheduledTaskHolder scheduledTasks;

    @Test
    @DisplayName("배치마다 스레드가 하나씩 있다")
    void poolHasAThreadPerBatch() {
        int batches = scheduledTasks.getScheduledTasks().size();

        assertThat(corePoolSize())
                .describedAs("배치가 %d 인데 스레드가 모자란다. `application.yml` 의 "
                        + "`spring.task.scheduling.pool.size` 를 배치 수에 맞춘다", batches)
                .isGreaterThanOrEqualTo(batches);
    }

    /**
     * 배치 수만큼 붙잡아 두고 <b>전부 시작되는지</b> 본다.
     *
     * <p>스레드가 하나면 첫 작업이 붙잡힌 자리에서 나머지가 큐에 쌓여서 시작조차 못 한다 —
     * 그것이 긴 배치가 짧은 배치를 미는 모습 그대로다.
     *
     * <p>붙잡는 것을 <b>반드시 푼다.</b> 안 풀면 스케줄러 스레드가 물린 채로 남아서
     * 뒤에 오는 테스트 클래스가 배치를 못 돌린다 — 컨텍스트가 캐시라 같은 풀을 쓴다.
     */
    @Test
    @DisplayName("긴 배치가 도는 동안 나머지도 돈다")
    void longRunningBatchDoesNotBlockOthers() throws InterruptedException {
        int batches = scheduledTasks.getScheduledTasks().size();
        CountDownLatch started = new CountDownLatch(batches);
        CountDownLatch release = new CountDownLatch(1);

        try {
            for (int i = 0; i < batches; i++) {
                scheduler.execute(() -> {
                    started.countDown();
                    await(release);
                });
            }

            assertThat(started.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .describedAs("배치 %d 중 %d 개가 시작도 못 했다. 스레드가 모자라면 "
                            + "거래기록 파기가 도는 동안 환불 스위퍼가 멈춘다",
                            batches, started.getCount())
                    .isTrue();
        } finally {
            release.countDown();
        }
    }

    private int corePoolSize() {
        return scheduler.getScheduledThreadPoolExecutor().getCorePoolSize();
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
