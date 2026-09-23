package com.projectshop.shop.seller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;

/**
 * 대표 둘이 <b>동시에</b> 서로를 내보낼 때 셀러가 대표 0으로 남지 않는가(`Q169`).
 *
 * <p>`Q165` 의 앱 검사는 잠금 없는 {@code exists} 라 순차로 부르면 막히고 겹치면 둘 다 통과했다
 * (마무리 44차 독립 리뷰). <b>순차 시험으로는 이 구멍이 안 보인다</b> — 겹쳐야 갈린다.
 *
 * <p><b>롤백이 없다.</b> 지연 트리거는 커밋할 때 돌고, 두 트랜잭션이 서로의 삭제를 못 보는 것은
 * 둘 다 커밋 직전일 때다. 그래서 테스트 트랜잭션을 끄고 만든 것을 직접 지운다({@code OrderConcurrencyTest} 와 같다).
 *
 * <p><b>앱을 안 거친다.</b> 앱 검사도 셀러 행을 잠그지만 여기서 재는 것은 그 뒤의 방벽이다 —
 * 두 삭제가 다 끝난 뒤에 커밋하도록 장벽을 세워 <b>가장 나쁜 순서</b>를 매번 만든다.
 */
@DisplayName("대표 둘이 동시에 빠지면")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SellerOwnerRaceTest extends PostgresTestBase {

    /** 이 테스트가 만든 것임을 알아보는 표시. 정리가 이것만 지운다 */
    private static final String EMAIL_PREFIX = "owner-race-";
    private static final String SELLER_PREFIX = "owner-race-";

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private long seller;
    private long ownerA;
    private long ownerB;

    @BeforeEach
    void setUp() {
        // 앞 테스트의 정리가 실패하면 그 데이터가 남아 코드가 겹친다. 만들기 전에 걷어낸다.
        cleanUp();

        AuthFixture fixture = new AuthFixture(jdbc);
        seller = fixture.insertSeller(SELLER_PREFIX + "twin", "쌍대표가게");
        ownerA = fixture.insertUser(EMAIL_PREFIX + "a@test.local", "대표갑");
        ownerB = fixture.insertUser(EMAIL_PREFIX + "b@test.local", "대표을");
        for (long owner : List.of(ownerA, ownerB)) {
            fixture.joinSeller(seller, owner);
            fixture.grantOrg(owner, "seller_owner", seller);
        }
    }

    @AfterEach
    void cleanUp() {
        String sellers = "select seller_id from seller where code like '" + SELLER_PREFIX + "%'";
        String users = "select user_id from app_user where email like '" + EMAIL_PREFIX + "%'";

        // **셀러와 사람을 같은 트랜잭션에서 지운다.** 역할만 먼저 지우고 커밋하면 이 청크의 트리거가
        // 그 삭제를 막는다 — 커밋 시점에 셀러가 없으면 트리거가 안 센다.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.sql("delete from user_role where seller_id in (" + sellers + ")").update();
            jdbc.sql("delete from seller_member where seller_id in (" + sellers + ")").update();
            jdbc.sql("delete from seller where code like '" + SELLER_PREFIX + "%'").update();
            jdbc.sql("delete from user_role where user_id in (" + users + ")").update();
            jdbc.sql("delete from app_user where email like '" + EMAIL_PREFIX + "%'").update();
        });
    }

    @Test
    @DisplayName("하나는 실패하고 대표가 하나 남는다")
    void oneFailsAndOneOwnerRemains() throws Exception {
        CyclicBarrier bothDeleted = new CyclicBarrier(2);

        List<Throwable> failures = race(List.of(
                dropOwnerRole(ownerA, bothDeleted),
                dropOwnerRole(ownerB, bothDeleted)));

        assertThat(failures)
                .as("둘 다 통과하면 대표가 0이 된다 — 잠금 없는 검사가 그랬다")
                .hasSize(1);
        assertThat(liveOwnerCount())
                .as("실패한 쪽은 롤백되어 자기 역할이 남는다")
                .isEqualTo(1);
    }

    /**
     * 역할 하나를 지우고 <b>상대도 지울 때까지 기다렸다가</b> 커밋한다. 둘 다 삭제를 마친 채로
     * 커밋에 들어가야 「서로의 삭제를 못 본다」가 매번 성립한다.
     */
    private Callable<Void> dropOwnerRole(long owner, CyclicBarrier bothDeleted) {
        return () -> {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.sql("delete from user_role where user_id = :owner and seller_id = :seller")
                        .param("owner", owner)
                        .param("seller", seller)
                        .update();
                try {
                    bothDeleted.await(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException("상대 삭제를 기다리다 끊겼다", e);
                }
            });
            return null;
        };
    }

    private static List<Throwable> race(List<Callable<Void>> tasks) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Throwable> failures = new ArrayList<>();
            for (Future<Void> future : pool.invokeAll(tasks, 30, TimeUnit.SECONDS)) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    failures.add(e.getCause());
                }
            }
            return failures;
        } finally {
            pool.shutdownNow();
        }
    }

    private long liveOwnerCount() {
        return jdbc.sql("""
                        select count(*)
                          from user_role ur
                          join role r on r.role_id = ur.role_id
                          join app_user u on u.user_id = ur.user_id
                         where ur.seller_id = :seller
                           and r.code = 'seller_owner'
                           and u.deleted_at is null
                        """)
                .param("seller", seller)
                .query(Long.class)
                .single();
    }
}
