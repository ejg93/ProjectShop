package com.projectshop.shop.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.order.OrderFixture;

/**
 * 정산 스키마가 `money-invariants.md` 의 등식 다섯을 실제로 막는지 본다.
 *
 * <p>그 문서가 등식을 적어 두고 강제는 청크 17 로 미뤘다. 여기서 보는 것은 계산이 맞는지가
 * 아니라 <b>틀린 값이 DB 에 못 들어가는지</b>다 — 마감 배치(청크 19)도 {@code psql} 도
 * 같은 표를 쓰고, 정산은 1원만 어긋나도 사고다.
 *
 * <p><b>지연 트리거는 커밋 시점에 돈다.</b> 테스트는 {@code @Transactional} 이라 커밋을 안 하므로
 * {@link #flush()} 가 {@code set constraints all immediate} 로 밀린 검사를 그 자리에서 돌린다
 * ({@code OrderSchemaTest} 와 같은 수단).
 */
@DisplayName("정산 스키마")
class SettlementSchemaTest extends PostgresTestBase {

    private static final long PRICE = 10_000;
    private static final int COMMISSION_BP = 1000;
    private static final long COMMISSION = PRICE * COMMISSION_BP / 10_000;

    @Autowired
    private JdbcClient jdbc;

    private AuthFixture fixture;
    private long userId;
    private long sellerId;
    private long skuId;
    private long cycleId;
    private long previousCycleId;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);
        userId = fixture.insertUser("settle-buyer@test.local", "산사람");
        sellerId = fixture.insertSeller("s-settle", "정산셀러");
        skuId = insertSku();

        // 이월은 지난 주기에서 온다. 같은 주기에 정산서를 두 장 만들면
        // `settlement_cycle_seller_unique` 가 먼저 걸려서 이월을 볼 수가 없다.
        previousCycleId = insertCycle(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                LocalDate.of(2026, 7, 10));
        cycleId = insertCycle(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
                LocalDate.of(2026, 8, 10));
    }

    @Nested
    @DisplayName("지급액은")
    class PayoutAmount {

        @Test
        @DisplayName("항목 합과 같아야 커밋된다")
        void mustEqualTheItemSum() {
            long settlementId = insertSettlement(PRICE - COMMISSION, 0);
            long orderItemId = anOrderItem();
            insertItem(settlementId, "sale", PRICE, "order_item_id", orderItemId);
            insertItem(settlementId, "commission", -COMMISSION, "order_item_id", orderItemId);

            assertThatCode(SettlementSchemaTest.this::flush).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("항목 합과 다르면 커밋에서 막힌다")
        void isBlockedWhenItDivergesFromTheItemSum() {
            long settlementId = insertSettlement(PRICE, 0);
            insertItem(settlementId, "sale", PRICE, "order_item_id", anOrderItem());
            // 수수료 줄이 빠졌다. 저장된 지급액은 그 뺄셈을 이미 했다고 말하고 있다.
            jdbc.sql("update settlement set payout_amount = :amount where settlement_id = :id")
                    .param("amount", PRICE - COMMISSION)
                    .param("id", settlementId)
                    .update();

            assertThatThrownBy(SettlementSchemaTest.this::flush)
                    .as("합계는 원본과 어긋날 수 있는 유일한 종류의 값이다")
                    .isInstanceOf(DataAccessException.class);
        }

        /**
         * 대상이 없으면 정산서를 안 만든다. 빈 정산서가 서면
         * <b>「이 달에 거래가 없었다」와 「마감이 덜 돌았다」가 안 갈린다.</b>
         */
        @Test
        @DisplayName("줄이 하나도 없으면 막힌다")
        void rejectsAnEmptyStatement() {
            insertSettlement(0, 0);

            assertThatThrownBy(SettlementSchemaTest.this::flush)
                    .isInstanceOf(DataAccessException.class);
        }
    }

    @Nested
    @DisplayName("음수 잔액은")
    class CarriedOver {

        @Test
        @DisplayName("지급액이 음수면 그 값이 그대로 이월이다")
        void mirrorsANegativePayout() {
            long settlementId = insertSettlement(-COMMISSION, -COMMISSION);
            insertItem(settlementId, "commission", -COMMISSION, "order_item_id", anOrderItem());

            assertThatCode(SettlementSchemaTest.this::flush).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("지급액이 양수인데 이월이 있으면 안 들어간다")
        void rejectsCarryOverOnAPositivePayout() {
            assertThatThrownBy(() -> insertSettlement(PRICE, -100))
                    .as("`money-invariants` 가 테스트로 적어 둔 등식이 check 로 내려갔다")
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("이월이 지급액과 다른 음수면 안 들어간다")
        void rejectsAMismatchedCarryOver() {
            assertThatThrownBy(() -> insertSettlement(-COMMISSION, -1))
                    .isInstanceOf(DataAccessException.class);
        }
    }

    /**
     * 부가가치세법이 요구하는 공급자 분리다(`D2` R17).
     *
     * <p>상품 대금과 배송비는 셀러가 고객에게, 중개수수료는 플랫폼이 셀러에게 공급한 것이다.
     * <b>받아서 채우는 컬럼이 아니라 종류에서 만들어지는 값</b>이라 어긋날 방법이 없다.
     */
    @Nested
    @DisplayName("공급자는")
    class Supplier {

        @Test
        @DisplayName("종류에서 나온다")
        void isDerivedFromTheKind() {
            long settlementId = insertSettlement(PRICE - COMMISSION, 0);
            long orderItemId = anOrderItem();
            insertItem(settlementId, "sale", PRICE, "order_item_id", orderItemId);
            insertItem(settlementId, "commission", -COMMISSION, "order_item_id", orderItemId);

            assertThat(supplierOf(settlementId, "sale")).isEqualTo("seller");
            assertThat(supplierOf(settlementId, "commission"))
                    .as("중개수수료는 플랫폼이 셀러에게 공급한 것이다")
                    .isEqualTo("platform");
        }

        @Test
        @DisplayName("이월에는 없다")
        void isAbsentForCarryOver() {
            long previous = aCompleteSettlement(previousCycleId, -COMMISSION);
            long settlementId = insertSettlement(PRICE - COMMISSION * 2, 0);
            insertItem(settlementId, "sale", PRICE, "order_item_id", anOrderItem());
            insertItem(settlementId, "commission", -COMMISSION, "order_item_id", anOrderItem());
            insertItem(settlementId, "carryover", -COMMISSION, "carried_from_settlement_id", previous);

            assertThat(supplierIsNull(settlementId, "carryover"))
                    .as("이월은 공급이 아니라 정산끼리의 조정이다")
                    .isTrue();
        }

        @Test
        @DisplayName("직접 넣을 수 없다")
        void cannotBeWrittenByHand() {
            long settlementId = insertSettlement(PRICE, 0);

            assertThatThrownBy(() -> jdbc.sql("""
                            insert into settlement_item (settlement_id, kind, amount,
                                                         order_item_id, supplier)
                            values (:id, 'sale', :amount, :orderItemId, 'platform')
                            """)
                    .param("id", settlementId)
                    .param("amount", PRICE)
                    .param("orderItemId", anOrderItem())
                    .update())
                    .as("컬럼을 받아서 채우면 종류와 어긋난 행이 생긴다")
                    .isInstanceOf(DataAccessException.class);
        }
    }

    @Nested
    @DisplayName("항목은")
    class Items {

        @Test
        @DisplayName("종류가 부호를 정한다")
        void hasASignFixedByItsKind() {
            long settlementId = insertSettlement(PRICE, 0);

            assertThatThrownBy(() ->
                    insertItem(settlementId, "commission", COMMISSION, "order_item_id", anOrderItem()))
                    .as("수수료를 양수로 담고 뺄셈을 앱이 하면 「합이 곧 지급액」이 성립을 안 한다")
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("0원 줄은 안 받는다")
        void rejectsAZeroAmount() {
            long settlementId = insertSettlement(PRICE, 0);

            assertThatThrownBy(() ->
                    insertItem(settlementId, "sale", 0, "order_item_id", anOrderItem()))
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("종류가 근거를 정한다")
        void requiresTheSourceThatMatchesItsKind() {
            long settlementId = insertSettlement(PRICE, 0);

            assertThatThrownBy(() ->
                    insertItem(settlementId, "shipping_fee", PRICE, "order_item_id", anOrderItem()))
                    .as("배송비는 묶음 단위라 항목별로 가를 근거가 없다")
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("근거가 없으면 안 들어간다")
        void rejectsALineWithoutASource() {
            long settlementId = insertSettlement(PRICE, 0);

            assertThatThrownBy(() -> jdbc.sql("""
                            insert into settlement_item (settlement_id, kind, amount)
                            values (:id, 'sale', :amount)
                            """)
                    .param("id", settlementId)
                    .param("amount", PRICE)
                    .update())
                    .isInstanceOf(DataAccessException.class);
        }
    }

    /**
     * <b>같은 근거가 두 정산서에 실리면 셀러가 돈을 두 번 받는다.</b>
     *
     * <p>그 사고는 합계 불일치로 안 잡힌다 — 두 정산서 각각은 그것대로 합이 맞는다.
     * {@code (셀러, 주기)} 유니크도 못 잡는다: 그건 같은 주기를 두 번 마감하는 것만 막는다.
     */
    @Nested
    @DisplayName("같은 근거는")
    class SourceUniqueness {

        @Test
        @DisplayName("다른 주기의 정산서에도 두 번 못 실린다")
        void cannotBeBilledTwiceAcrossCycles() {
            long orderItemId = anOrderItem();

            long july = insertSettlement(PRICE, 0);
            insertItem(july, "sale", PRICE, "order_item_id", orderItemId);

            long augustCycle = insertCycle(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31),
                    LocalDate.of(2026, 9, 10));
            long august = insertSettlementIn(augustCycle, PRICE, 0);

            assertThatThrownBy(() ->
                    insertItem(august, "sale", PRICE, "order_item_id", orderItemId))
                    .as("유일성을 정산서 안이 아니라 전역으로 건다")
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("판매와 수수료는 같은 항목에서 한 줄씩 나온다")
        void allowsOneLinePerKind() {
            long settlementId = insertSettlement(PRICE - COMMISSION, 0);
            long orderItemId = anOrderItem();

            insertItem(settlementId, "sale", PRICE, "order_item_id", orderItemId);

            assertThatCode(() ->
                    insertItem(settlementId, "commission", -COMMISSION, "order_item_id", orderItemId))
                    .as("종류를 키에 넣은 이유다")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("한 정산서의 잔액은 한 번만 넘어간다")
        void carriesABalanceOverOnlyOnce() {
            long previous = aCompleteSettlement(previousCycleId, -COMMISSION);
            long settlementId = insertSettlement(PRICE, 0);
            insertItem(settlementId, "carryover", -COMMISSION, "carried_from_settlement_id", previous);

            assertThatThrownBy(() ->
                    insertItem(settlementId, "carryover", -COMMISSION,
                            "carried_from_settlement_id", previous))
                    .as("두 번 물리면 셀러가 같은 빚을 두 번 갚는다")
                    .isInstanceOf(DataAccessException.class);
        }
    }

    @Nested
    @DisplayName("주기는")
    class Cycles {

        @Test
        @DisplayName("같은 달을 두 번 못 연다")
        void isUniquePerPeriod() {
            assertThatThrownBy(() -> insertCycle(LocalDate.of(2026, 7, 1),
                    LocalDate.of(2026, 7, 31), LocalDate.of(2026, 8, 10)))
                    .as("같은 달을 두 번 만들면 그 달이 두 번 정산된다")
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("지급일이 대상 기간 안이면 안 들어간다")
        void rejectsAPayoutDateInsideThePeriod() {
            assertThatThrownBy(() -> insertCycle(LocalDate.of(2026, 9, 1),
                    LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 20)))
                    .as("아직 안 끝난 거래를 지급하게 된다")
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("셀러마다 한 장씩만 선다")
        void holdsOneStatementPerSeller() {
            aCompleteSettlement(cycleId, PRICE - COMMISSION);

            assertThatThrownBy(() -> insertSettlement(PRICE, 0))
                    .as("두 번 돌면 지급이 두 배가 되는 사고는 합계로는 안 잡힌다")
                    .isInstanceOf(DataAccessException.class);
        }
    }

    private void flush() {
        jdbc.sql("set constraints all immediate").update();
    }

    /** 줄까지 갖춘 정산서. 다음 정산서가 이월의 출처로 쓴다 */
    private long aCompleteSettlement(long cycle, long payout) {
        long settlementId = insertSettlementIn(cycle, payout, Math.min(0, payout));
        insertItem(settlementId, payout > 0 ? "sale" : "commission", payout,
                "order_item_id", anOrderItem());
        return settlementId;
    }

    private long insertSettlement(long payout, long carriedOver) {
        return insertSettlementIn(cycleId, payout, carriedOver);
    }

    private long insertSettlementIn(long cycle, long payout, long carriedOver) {
        return jdbc.sql("""
                        insert into settlement (settlement_cycle_id, seller_id,
                                                payout_amount, carried_over)
                        values (:cycleId, :sellerId, :payout, :carriedOver)
                        returning settlement_id
                        """)
                .param("cycleId", cycle)
                .param("sellerId", sellerId)
                .param("payout", payout)
                .param("carriedOver", carriedOver)
                .query(Long.class)
                .single();
    }

    /**
     * 근거 칸이 종류마다 달라서 컬럼 이름을 받는다.
     *
     * <p>SQL 텍스트에 이름을 끼우는 것이라 값이 아니다 — 부르는 자리가 전부 이 파일 안의
     * 리터럴이고 바깥에서 오는 값이 없다(`D23` 「SQL」).
     */
    private void insertItem(long settlementId, String kind, long amount,
            String sourceColumn, long sourceId) {
        jdbc.sql("""
                        insert into settlement_item (settlement_id, kind, amount, %s)
                        values (:id, :kind, :amount, :sourceId)
                        """.formatted(sourceColumn))
                .param("id", settlementId)
                .param("kind", kind)
                .param("amount", amount)
                .param("sourceId", sourceId)
                .update();
    }

    /** {@code query(String.class)} 는 null 을 못 받는다. 비어 있는지는 SQL 로 묻는다 */
    private boolean supplierIsNull(long settlementId, String kind) {
        return jdbc.sql("""
                        select supplier is null from settlement_item
                         where settlement_id = :id and kind = :kind
                        """)
                .param("id", settlementId)
                .param("kind", kind)
                .query(Boolean.class)
                .single();
    }

    private String supplierOf(long settlementId, String kind) {
        return jdbc.sql("""
                        select supplier from settlement_item
                         where settlement_id = :id and kind = :kind
                        """)
                .param("id", settlementId)
                .param("kind", kind)
                .query((rs, rowNum) -> rs.getString("supplier"))
                .single();
    }

    private long insertCycle(LocalDate start, LocalDate end, LocalDate payoutDate) {
        return jdbc.sql("""
                        insert into settlement_cycle (period_start, period_end, payout_date)
                        values (:start, :end, :payoutDate)
                        returning settlement_cycle_id
                        """)
                .param("start", start)
                .param("end", end)
                .param("payoutDate", payoutDate)
                .query(Long.class)
                .single();
    }

    /** 정산이 가리킬 실제 주문 항목. 부를 때마다 새 주문이 선다 */
    private long anOrderItem() {
        long orderId = jdbc.sql("""
                        insert into shop_order (order_number, user_id, total_amount,
                                                commission_total, shipping_fee_total, payable_amount)
                        values (:number, :userId, :price, :commission, 0, :price)
                        returning order_id
                        """)
                .param("number", OrderFixture.sellerOrderNumber().substring(2))
                .param("userId", userId)
                .param("price", PRICE)
                .param("commission", COMMISSION)
                .query(Long.class)
                .single();

        // `V31` 이 서면 없는 주문을 막는다. 여기는 서면이 관심사가 아니라 껍데기만 채운다.
        OrderFixture.attachContractDocuments(jdbc, orderId);

        long sellerOrderId = jdbc.sql("""
                        insert into seller_order (seller_order_number, order_id, seller_id)
                        values (:number, :orderId, :sellerId)
                        returning seller_order_id
                        """)
                .param("number", OrderFixture.sellerOrderNumber())
                .param("orderId", orderId)
                .param("sellerId", sellerId)
                .query(Long.class)
                .single();

        return jdbc.sql("""
                        insert into order_item (seller_order_id, sku_id, product_name,
                                                unit_price_incl_vat, quantity, line_amount,
                                                commission_bp, commission_amount)
                        values (:sellerOrderId, :skuId, '정산 테스트 상품',
                                :price, 1, :price, :bp, :commission)
                        returning order_item_id
                        """)
                .param("sellerOrderId", sellerOrderId)
                .param("skuId", skuId)
                .param("price", PRICE)
                .param("bp", COMMISSION_BP)
                .param("commission", COMMISSION)
                .query(Long.class)
                .single();
    }

    private long insertSku() {
        long productId = jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name)
                        values (:sellerId, :userId, '정산 테스트 상품')
                        returning product_id
                        """)
                .param("sellerId", sellerId)
                .param("userId", userId)
                .query(Long.class)
                .single();

        return jdbc.sql("""
                        with new_sku as (
                            insert into sku (product_id, price_incl_vat)
                            values (:productId, :price)
                            returning sku_id
                        )
                        insert into sku_stock (sku_id, on_hand)
                        select sku_id, 100 from new_sku
                        returning sku_id
                        """)
                .param("productId", productId)
                .param("price", PRICE)
                .query(Long.class)
                .single();
    }
}
