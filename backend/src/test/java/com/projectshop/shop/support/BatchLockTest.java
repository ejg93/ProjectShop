package com.projectshop.shop.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.projectshop.shop.PostgresTestBase;

/**
 * 같은 배치가 두 곳에서 동시에 돌지 않는가(`Q50`).
 *
 * <p><b>배포 창이 곧 2대 창이다.</b> 인스턴스를 늘리지 않아도 새 것이 뜨고 옛 것이 내려가는
 * 사이가 있고, 04:00 에 배포하면 크론 셋이 그 창에 걸린다. {@code batch_run} 의 부분 유니크는
 * <b>본체가 다 돈 뒤</b>에야 걸려서 정산 마감이 두 번 계산되는 구간을 못 막는다.
 *
 * <p><b>트랜잭션을 끈다</b>({@code NOT_SUPPORTED}, `D15` 「경쟁을 보려면 롤백을 끈다」).
 * 테스트가 트랜잭션을 쥐고 있으면 다른 스레드가 이력을 못 보고 <b>경쟁 자체가 안 일어난다.</b>
 * 대가로 만든 행을 직접 지운다 — 배치 이름에 접두어를 붙이고 그것만 지운다.
 */
@DisplayName("배치 잠금")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BatchLockTest extends PostgresTestBase {

    /** 이 접두어가 붙은 이력만 지운다. 남의 층이 만든 행을 지우면 그쪽이 조용히 깨진다 */
    private static final String PREFIX = "lock_probe_";
    private static final LocalDate BASELINE = LocalDate.of(2026, 9, 15);

    @Autowired
    private BatchRuns runs;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private BatchLockConnections lockConnections;

    /** 요청용 웅덩이 크기. Hikari 기본이 열이다 */
    @Value("${spring.datasource.hikari.maximum-pool-size:10}")
    private int requestPoolSize;

    @BeforeEach
    @AfterEach
    void purgeProbeRuns() {
        // 시작할 때도 지운다. 앞선 실행이 죽으면 재사용 컨테이너에 행이 남는다(`D15`).
        jdbc.sql("delete from batch_run where starts_with(batch_name, :prefix)")
                .param("prefix", PREFIX)
                .update();
    }

    @Test
    @DisplayName("같은 배치가 동시에 돌면 한쪽은 건너뛴다")
    void concurrentRunsOfTheSameBatchSkipOne() throws Exception {
        String batch = PREFIX + "concurrent";
        var bodyStarted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var bodyRuns = new AtomicInteger();

        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            // 앞선 쪽이 본체 안에서 붙들려 있는 동안 뒤엣것이 들어온다.
            // 래치가 없으면 하나가 혼자 끝내서 겹치지 않는다(`D15` 「출발선을 맞춘다」).
            Future<Optional<BatchRuns.Counts>> first = threads.submit(() ->
                    runs.record(batch, BASELINE, () -> {
                        bodyRuns.incrementAndGet();
                        bodyStarted.countDown();
                        await(release);
                        return BatchRuns.Counts.of(1);
                    }));

            assertThat(bodyStarted.await(10, TimeUnit.SECONDS))
                    .as("앞선 회차가 본체에 들어가야 경쟁이 성립한다")
                    .isTrue();

            Future<Optional<BatchRuns.Counts>> second = threads.submit(() ->
                    runs.record(batch, BASELINE, () -> {
                        bodyRuns.incrementAndGet();
                        return BatchRuns.Counts.of(1);
                    }));

            assertThat(second.get(10, TimeUnit.SECONDS))
                    .as("잠금을 못 잡았으니 본체를 안 부르고 빈 값이다")
                    .isEmpty();

            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS))
                    .as("잠금을 잡은 쪽은 정상으로 돈다")
                    .isPresent();
        } finally {
            release.countDown();
            threads.shutdownNow();
        }

        assertThat(bodyRuns)
                .as("**본체가 한 번만 돈다.** 정산 마감이 두 번 계산되면 안 되는 자리다")
                .hasValue(1);

        assertThat(statusesOf(batch))
                .as("건너뛴 쪽도 이력에 남는다 — 안 남기면 「스케줄이 안 걸린 것」과 안 갈린다")
                .containsExactlyInAnyOrder("succeeded", "skipped");
    }

    @Test
    @DisplayName("본체가 던져도 잠금이 풀려서 다음 회차가 돈다")
    void lockIsReleasedWhenBodyThrows() {
        String batch = PREFIX + "throwing";

        runs.record(batch, BASELINE, () -> {
            throw new IllegalStateException("본체가 터졌다");
        });

        // **다른 연결에서 묻는다.** 다시 `record` 를 불러서 되는지 보는 방식은
        // **잠금이 안 풀려도 통과한다** — advisory lock 은 같은 세션 안에서 재진입이라,
        // 웅덩이가 아까 그 연결을 도로 내주면 이미 쥔 잠금을 또 잡는다.
        // 처음에 그렇게 썼다가 일부러 어긴 코드가 초록으로 지나가는 것을 봤다(`Q50`).
        assertThat(lockIsFree(batch))
                .as("앞 회차가 던졌어도 잠금이 남아 있으면 안 된다. "
                        + "남으면 그 배치는 인스턴스를 재시작할 때까지 회차를 통째로 건너뛴다")
                .isTrue();

        assertThat(runs.record(batch, BASELINE, () -> BatchRuns.Counts.of(1)))
                .as("그래서 다음 회차가 정상으로 돈다")
                .isPresent();

        assertThat(statusesOf(batch))
                .containsExactlyInAnyOrder("failed", "succeeded");
    }

    /**
     * 그 배치 이름의 잠금이 비어 있나. <b>잡아 보고 바로 푼다.</b>
     *
     * <p>{@code JdbcClient} 는 요청용 웅덩이를 쓰므로 {@link BatchLockConnections} 과 <b>연결이 다르다</b> —
     * 재진입으로 통과할 자리가 없다. 그것이 이 방법을 쓰는 이유다.
     */
    private boolean lockIsFree(String batchName) {
        Boolean taken = jdbc.sql("select pg_try_advisory_lock(hashtext(:name))")
                .param("name", batchName)
                .query(Boolean.class)
                .single();
        if (Boolean.TRUE.equals(taken)) {
            jdbc.sql("select pg_advisory_unlock(hashtext(:name))")
                    .param("name", batchName)
                    .query(Boolean.class)
                    .single();
        }
        return Boolean.TRUE.equals(taken);
    }

    @Test
    @DisplayName("잠금 연결은 요청용 웅덩이에서 안 빌려 온다")
    void lockDoesNotBorrowFromTheRequestPool() {
        // 요청용 웅덩이를 통째로 쥔 채로 잠금 연결을 연다. 같은 웅덩이였으면 빈 자리가 없어
        // 연결 타임아웃까지 막히고, 갈라 뒀으면 곧바로 열린다(`Q50`, 사용자 선택 ②).
        // **배치가 손님 요청을 굶기는 것이 같은 사실의 뒷면이다** — 갈려 있어야 둘 다 성립한다.
        List<Connection> held = new ArrayList<>();
        try {
            for (int i = 0; i < requestPoolSize; i++) {
                held.add(dataSource.getConnection());
            }

            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                try (Connection lock = lockConnections.open()) {
                    assertThat(lock.isClosed())
                            .as("요청용 연결이 하나도 안 남아 있어도 잠금 연결은 열린다")
                            .isFalse();
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException("요청용 연결을 쥐다 실패했다", e);
        } finally {
            held.forEach(BatchLockTest::closeQuietly);
        }
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new IllegalStateException("쥐고 있던 연결을 못 놓았다", e);
        }
    }

    private List<String> statusesOf(String batchName) {
        return jdbc.sql("select status from batch_run where batch_name = :name")
                .param("name", batchName)
                .query(String.class)
                .list();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("붙들린 본체가 안 풀렸다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("기다리다 끊겼다", e);
        }
    }
}
