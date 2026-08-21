package com.projectshop.shop.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

/**
 * 같은 재고를 동시에 살 때 무슨 일이 나는가(`D11`).
 *
 * <h2>롤백이 없다</h2>
 *
 * <p>겹치려면 각 주문이 <b>진짜로 커밋돼야 한다.</b> 테스트가 트랜잭션을 열고 있으면
 * 다른 스레드가 그 안의 재고 차감을 못 보고, 잠금도 안 걸려서 경쟁 자체가 안 일어난다.
 * 그래서 {@code NOT_SUPPORTED} 로 테스트 트랜잭션을 끄고 만든 것을 직접 지운다 —
 * {@code HttpTestBase} 가 같은 이유로 같은 방식을 쓴다(`D15`).
 *
 * <h2>여기서 보는 것</h2>
 *
 * <p>재고가 음수로 안 가는 것과 엇갈린 순서로 담아도 데드락이 안 나는 것 둘이다.
 * <b>둘 다 코드가 없으면 조용히 틀린다</b> — 순차로 부르면 전부 통과하고, 겹칠 때만 갈린다.
 */
@DisplayName("동시에 주문하면")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OrderConcurrencyTest extends PostgresTestBase {

    /** 이 테스트가 만든 것임을 알아보는 표시. 정리가 이것만 지운다 */
    private static final String EMAIL_PREFIX = "order-race-";
    private static final String SELLER_PREFIX = "race-";

    @Autowired
    private OrderService orderService;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private AuthFixture fixture;
    private long merchantUserId;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);

        // 앞 테스트의 정리가 실패하면 그 데이터가 컨테이너에 남는다.
        // 이메일이 겹쳐서 시작도 못 하므로 만들기 전에 한 번 걷어낸다.
        cleanUp();

        merchantUserId = fixture.insertUser(EMAIL_PREFIX + "merchant@test.local", "판매 담당");
    }

    /**
     * 만든 것을 자식부터 지운다.
     *
     * <p>참조가 전부 {@code restrict} 라 순서가 틀리면 정리가 실패한다.
     * cascade 를 켜서 편하게 지우지 않는다(`D23`) — 파기 수단이 되면 지울 생각이 없던 것도 같이 사라진다.
     *
     * <p><b>한 트랜잭션으로 묶는다.</b> 테스트 트랜잭션이 없어서 문장마다 커밋되는데,
     * 금액 등식은 커밋 시점에 보는 지연 트리거라 항목만 지운 상태에서 한 번 터진다
     * ({@code 항목이 없는 셀러 주문}).
     */
    @AfterEach
    void cleanUp() {
        String buyers = "select user_id from app_user where email like '" + EMAIL_PREFIX + "%'";
        String orders = "select order_id from shop_order where user_id in (" + buyers + ")";
        String sellerOrders = "select seller_order_id from seller_order where order_id in (" + orders + ")";
        String sellers = "select seller_id from seller where code like '" + SELLER_PREFIX + "%'";
        String products = "select product_id from product where seller_id in (" + sellers + ")";

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            execute("delete from order_item where seller_order_id in (" + sellerOrders + ")");
            execute("delete from seller_order where order_id in (" + orders + ")");
            execute("delete from order_shipping where order_id in (" + orders + ")");
            execute("delete from shop_order where user_id in (" + buyers + ")");
            execute("delete from cart_item where cart_id in (select cart_id from cart where user_id in ("
                    + buyers + "))");
            execute("delete from cart where user_id in (" + buyers + ")");
            execute("delete from sku where product_id in (" + products + ")");
            execute("delete from product where seller_id in (" + sellers + ")");
            execute("delete from seller where code like '" + SELLER_PREFIX + "%'");
            execute("delete from app_user where email like '" + EMAIL_PREFIX + "%'");
        });
    }

    @Nested
    @DisplayName("재고보다 많이는")
    class NeverOversells {

        /** 재고보다 사려는 사람이 많아야 경쟁이 된다 */
        private static final int STOCK = 10;
        private static final int BUYERS = 20;

        @Test
        @DisplayName("안 팔린다")
        void sellsExactlyTheStock() {
            long skuId = skuOf(sellerWith("one"), "하나뿐인 물건", 10_000L, STOCK);
            List<Runnable> buys = buyEachOne(skuId, BUYERS);

            List<Throwable> failures = race(buys);

            assertThat(BUYERS - failures.size())
                    .as("조건부 UPDATE 가 곧 검사다(`D11`). 읽고 검사하고 쓰면 둘이 같은 값을 읽고 둘 다 통과한다")
                    .isEqualTo(STOCK);
            assertThat(stockOf(skuId)).isZero();
        }

        @Test
        @DisplayName("못 산 사람은 재고 부족을 받는다")
        void tellsWhyItFailed() {
            long skuId = skuOf(sellerWith("two"), "하나뿐인 물건", 10_000L, STOCK);

            List<Throwable> failures = race(buyEachOne(skuId, BUYERS));

            assertThat(failures)
                    .as("0행의 이유를 UPDATE 결과만으로는 모른다. 실패 경로에서 한 번 조회해 가른다(`D11`)")
                    .isNotEmpty()
                    .allSatisfy(failure -> assertThat(failure)
                            .isInstanceOfSatisfying(ShopException.class,
                                    e -> assertThat(e.code()).isEqualTo(ErrorCode.OUT_OF_STOCK)));
        }

        @Test
        @DisplayName("판 만큼만 주문 항목이 남는다")
        void leavesOneItemPerSale() {
            long skuId = skuOf(sellerWith("three"), "하나뿐인 물건", 10_000L, STOCK);

            race(buyEachOne(skuId, BUYERS));

            assertThat(countOf("select count(*) from order_item where sku_id = " + skuId))
                    .as("차감은 됐는데 주문이 안 남으면 재고만 사라진다")
                    .isEqualTo(STOCK);
        }
    }

    @Nested
    @DisplayName("서로 다른 `sku` 를 엇갈린 순서로 담아도")
    class NoDeadlock {

        private static final int BUYERS = 10;

        @Test
        @DisplayName("데드락이 안 난다")
        void survivesCrossedOrders() {
            long sellerId = sellerWith("cross");
            long first = skuOf(sellerId, "먼저 만든 것", 10_000L, BUYERS);
            long second = skuOf(sellerId, "나중 만든 것", 20_000L, BUYERS);

            List<Runnable> buys = new ArrayList<>();
            for (int i = 0; i < BUYERS; i++) {
                long buyerId = buyer(i);
                // 절반은 반대로 담는다. 담은 순서가 곧 잠그는 순서면 서로 기다린다(`D11`).
                List<Long> skuIds = i % 2 == 0 ? List.of(first, second) : List.of(second, first);
                List<Long> cartItemIds = skuIds.stream().map(skuId -> addToCart(buyerId, skuId)).toList();

                buys.add(() -> orderService.create(buyerId, command(cartItemIds)));
            }

            assertThat(race(buys))
                    .as("`decreaseStock` 이 `sku_id` 오름차순으로 도는 것이 이걸 막는 유일한 장치다")
                    .isEmpty();
            assertThat(stockOf(first)).isZero();
            assertThat(stockOf(second)).isZero();
        }

        /**
         * 정렬을 뺀 것을 직접 돌려서 위험이 진짜인지 확인한다.
         *
         * <p>{@code readLines} 의 쿼리에 {@code order by} 가 없어서 <b>담은 순서를 뒤집어도
         * 플래너가 같은 순서로 돌려줄 수 있다.</b> 그러면 위 테스트는 정렬을 지워도 통과한다.
         * 여기서 잠그는 순서를 손으로 엇갈리게 만들어, 정렬이 없을 때 무엇이 나는지 못박는다.
         */
        @Test
        @DisplayName("순서를 안 맞추면 데드락이 난다")
        void deadlocksWithoutTheSort() {
            long sellerId = sellerWith("proof");
            long first = skuOf(sellerId, "먼저 만든 것", 10_000L, 100);
            long second = skuOf(sellerId, "나중 만든 것", 20_000L, 100);

            // 둘 다 첫 행을 잡은 뒤에 두 번째로 넘어가게 만든다. 안 맞추면 한쪽이 먼저 끝나 버린다.
            CyclicBarrier bothLocked = new CyclicBarrier(2);

            assertThatThrownBy(() -> raceOrThrow(List.of(
                    () -> decreaseInOrder(bothLocked, first, second),
                    () -> decreaseInOrder(bothLocked, second, first))))
                    .as("Postgres 가 순환을 찾아 한쪽을 죽인다")
                    // 예외 이름이 아니라 SQLSTATE 를 본다. 40P01 은 `DeadlockLoserDataAccessException`
                    // 이 아니라 상위인 `PessimisticLockingFailureException` 으로 올라온다 —
                    // Postgres 는 오류 코드표가 아니라 SQLSTATE 앞 두 자리로 번역된다.
                    // 재시도를 붙이는 청크(`D11` 「재시도」)가 이름으로 잡으면 데드락을 놓친다.
                    .isInstanceOfSatisfying(PessimisticLockingFailureException.class,
                            e -> assertThat(((SQLException) e.getCause()).getSQLState()).isEqualTo("40P01"));
        }

        /** 한 트랜잭션이 두 `sku` 를 준 순서대로 깎는다. 첫 행을 잡은 채로 상대를 기다린다 */
        private void decreaseInOrder(CyclicBarrier bothLocked, long firstSkuId, long secondSkuId) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                decreaseOne(firstSkuId);
                await(bothLocked);
                decreaseOne(secondSkuId);
            });
        }

        private void decreaseOne(long skuId) {
            jdbc.sql("update sku_stock set on_hand = on_hand - 1 where sku_id = :id")
                    .param("id", skuId)
                    .update();
        }

        private void await(CyclicBarrier barrier) {
            try {
                barrier.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
                throw new IllegalStateException("두 스레드를 못 맞췄다", e);
            }
        }
    }

    /** 사람마다 자기 장바구니에 하나씩 담아 두고, 신호가 떨어지면 동시에 산다 */
    private List<Runnable> buyEachOne(long skuId, int buyers) {
        List<Runnable> buys = new ArrayList<>();
        for (int i = 0; i < buyers; i++) {
            long buyerId = buyer(i);
            long cartItemId = addToCart(buyerId, skuId);

            buys.add(() -> orderService.create(buyerId, command(List.of(cartItemId))));
        }
        return buys;
    }

    /**
     * 다 같이 출발시키고 터진 것을 모은다.
     *
     * <p><b>출발선을 안 맞추면 경쟁이 안 난다.</b> 스레드를 만드는 데 드는 시간이
     * 주문 하나보다 길어서, 먼저 만들어진 스레드가 혼자 끝내고 만다.
     */
    private List<Throwable> race(List<Runnable> tasks) {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<?>> running = new ArrayList<>();

        try {
            for (Runnable task : tasks) {
                running.add(pool.submit(() -> {
                    startGun.await();
                    task.run();
                    return null;
                }));
            }
            startGun.countDown();

            List<Throwable> failures = new ArrayList<>();
            for (Future<?> future : running) {
                try {
                    future.get(60, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    failures.add(e.getCause());
                } catch (InterruptedException | TimeoutException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("주문이 안 끝났다", e);
                }
            }
            return failures;
        } finally {
            pool.shutdownNow();
        }
    }

    /** 실패를 모으는 대신 첫 실패를 그대로 던진다. 무엇으로 터졌는지가 검증 대상일 때 쓴다 */
    private void raceOrThrow(List<Runnable> tasks) {
        List<Throwable> failures = race(tasks);
        if (!failures.isEmpty()) {
            throw failures.getFirst() instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalStateException(failures.getFirst());
        }
    }

    private OrderService.Command command(List<Long> cartItemIds) {
        return new OrderService.Command(cartItemIds,
                new OrderService.Shipping("홍길동", "010-0000-0000", "06134", "서울시 강남구", "101호", null));
    }

    private long buyer(int index) {
        return fixture.insertUser(EMAIL_PREFIX + "buyer-" + index + "@test.local", "구매자 " + index);
    }

    private long sellerWith(String suffix) {
        long sellerId = fixture.insertSeller(SELLER_PREFIX + suffix, "경쟁 셀러 " + suffix);
        fixture.verifySeller(sellerId);
        jdbc.sql("update seller set commission_bp = 1000, default_shipping_fee = 3000 where seller_id = :id")
                .param("id", sellerId)
                .update();
        return sellerId;
    }

    private long skuOf(long sellerId, String name, long priceInclVat, int stock) {
        long productId = jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name, status)
                        values (:sellerId, :userId, :name, 'on_sale')
                        returning product_id
                        """)
                .param("sellerId", sellerId)
                .param("userId", merchantUserId)
                .param("name", name)
                .query(Long.class)
                .single();

        return jdbc.sql("""
                        with new_sku as (
                            insert into sku (product_id, price_incl_vat)
                            values (:productId, :priceInclVat)
                            returning sku_id
                        )
                        insert into sku_stock (sku_id, on_hand)
                        select sku_id, :stock from new_sku
                        returning sku_id
                        """)
                .param("productId", productId)
                .param("priceInclVat", priceInclVat)
                .param("stock", stock)
                .query(Long.class)
                .single();
    }

    private long addToCart(long buyerId, long skuId) {
        long cartId = jdbc.sql("""
                        insert into cart (user_id) values (:userId)
                        on conflict (user_id) where user_id is not null do update set updated_at = now()
                        returning cart_id
                        """)
                .param("userId", buyerId)
                .query(Long.class)
                .single();

        return jdbc.sql("""
                        insert into cart_item (cart_id, sku_id, quantity)
                        values (:cartId, :skuId, 1)
                        returning cart_item_id
                        """)
                .param("cartId", cartId)
                .param("skuId", skuId)
                .query(Long.class)
                .single();
    }

    private int stockOf(long skuId) {
        return jdbc.sql("select on_hand from sku_stock where sku_id = :id")
                .param("id", skuId)
                .query(Integer.class)
                .single();
    }

    private int countOf(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }

    private void execute(String sql) {
        jdbc.sql(sql).update();
    }
}
