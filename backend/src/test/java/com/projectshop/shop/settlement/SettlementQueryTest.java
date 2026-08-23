package com.projectshop.shop.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.order.OrderFixture;
import com.projectshop.shop.support.BusinessCalendar;

/**
 * 정산서를 누가 어디까지 보나(청크 20).
 *
 * <p><b>정산액은 곧 그 셀러의 월 거래액이다.</b> 셀러가 남의 정산서를 보면 경쟁사 매출을
 * 그대로 읽는 것이고, 건수만 세도 「몇 달째 거래하나」가 나온다 — 그래서 <b>못 봄이
 * 0건이 아니다.</b>
 *
 * <p>범위를 <b>판정 엔진에게 물어서</b> 조건으로 옮기는지가 관심사다. 쿼리에 셀러 번호를
 * 손으로 끼우면 그 자리마다 새는 자리가 생긴다.
 */
@DisplayName("정산서 조회")
class SettlementQueryTest extends PostgresTestBase {

    private static final long PRICE = 10_000;
    private static final int COMMISSION_BP = 1000;
    private static final long COMMISSION = PRICE * COMMISSION_BP / 10_000;
    private static final long SHIPPING_FEE = 3_000;

    private static final LocalDate PERIOD_END = LocalDate.of(2026, 7, 31);

    @Autowired
    private SettlementCloseBatch batch;

    @Autowired
    private SettlementQuery query;

    @Autowired
    private JdbcClient jdbc;

    private AuthFixture fixture;
    private long buyerId;
    private long ownerId;
    private long otherOwnerId;
    private long adminId;
    private long auditorId;
    private long sellerId;
    private long otherSellerId;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);

        buyerId = fixture.insertUser("settle-buyer@test.local", "산사람");
        fixture.grantGlobal(buyerId, "customer");

        adminId = fixture.insertUser("settle-admin@test.local", "관리자");
        fixture.grantGlobal(adminId, "admin");

        auditorId = fixture.insertUser("settle-auditor@test.local", "감사자");
        fixture.grantGlobal(auditorId, "auditor");

        sellerId = fixture.insertSeller("s-mine", "내셀러");
        fixture.verifySeller(sellerId);
        ownerId = fixture.insertUser("mine-owner@test.local", "내대표");
        fixture.joinSeller(sellerId, ownerId);
        fixture.grantOrg(ownerId, "seller_owner", sellerId);

        otherSellerId = fixture.insertSeller("s-other", "남의셀러");
        fixture.verifySeller(otherSellerId);
        otherOwnerId = fixture.insertUser("other-owner@test.local", "남의대표");
        fixture.joinSeller(otherSellerId, otherOwnerId);
        fixture.grantOrg(otherOwnerId, "seller_owner", otherSellerId);
    }

    @Nested
    @DisplayName("범위는")
    class Scope {

        @Test
        @DisplayName("셀러가 자기 것만 본다")
        void showsOnlyOwnSettlementsToASeller() {
            closeWith(sellerId, otherSellerId);

            assertThat(query.find(ownerId, 0, 20).items())
                    .as("정산액은 곧 그 셀러의 월 거래액이다")
                    .singleElement()
                    .extracting(SettlementQuery.Summary::sellerCode)
                    .isEqualTo("s-mine");
        }

        @Test
        @DisplayName("관리자가 전부 본다")
        void showsEverythingToAnAdmin() {
            closeWith(sellerId, otherSellerId);

            assertThat(query.find(adminId, 0, 20).total()).isEqualTo(2);
        }

        /** 돈이 어디로 갔나를 못 보면 감사가 성립을 안 한다 */
        @Test
        @DisplayName("감사자가 전부 본다")
        void showsEverythingToAnAuditor() {
            closeWith(sellerId, otherSellerId);

            assertThat(query.find(auditorId, 0, 20).total()).isEqualTo(2);
        }

        @Test
        @DisplayName("고객은 못 본다")
        void refusesACustomer() {
            closeWith(sellerId);

            assertThatThrownBy(() -> query.find(buyerId, 0, 20))
                    .as("0건이 아니다 — 0건과 못 봄이 갈려야 개수로 정보가 안 샌다")
                    .isInstanceOf(ShopException.class);
        }

        @Test
        @DisplayName("남의 정산서는 없는 것으로 답한다")
        void answersNotFoundForAnotherSellerStatement() {
            closeWith(sellerId, otherSellerId);
            String mine = numberOf("s-other");

            assertThatThrownBy(() -> query.findOne(ownerId, mine))
                    .as("403 을 주면 번호를 훑어서 실재하는 정산서를 셀 수 있다")
                    .isInstanceOf(ShopException.class)
                    .hasMessageContaining("그런 정산서가 없다");
        }
    }

    @Nested
    @DisplayName("상세는")
    class Details {

        @Test
        @DisplayName("줄마다 근거를 내린다")
        void carriesTheBasisOnEveryLine() {
            closeWith(sellerId);

            SettlementQuery.Detail detail = query.findOne(ownerId, numberOf("s-mine"));

            assertThat(detail.lines()).hasSize(3);
            assertThat(detail.summary().payoutAmount())
                    .isEqualTo(PRICE - COMMISSION + SHIPPING_FEE);
        }

        @Test
        @DisplayName("수수료 줄이 요율과 기준 금액을 들고 있다")
        void showsTheCommissionRateAndBase() {
            closeWith(sellerId);

            SettlementQuery.Line commission = query.findOne(ownerId, numberOf("s-mine")).lines()
                    .stream()
                    .filter(line -> "COMMISSION".equals(line.kind()))
                    .findFirst()
                    .orElseThrow();

            assertThat(commission.commissionBp())
                    .as("셀러가 정산서만으로 대사할 수 있어야 한다(청크 18)")
                    .isEqualTo(COMMISSION_BP);
            assertThat(commission.commissionBaseAmount()).isEqualTo(PRICE);
            assertThat(commission.amount()).isEqualTo(-COMMISSION);
        }

        /** 부가가치세법이 요구하는 공급자 분리다(`D2` R17) */
        @Test
        @DisplayName("공급자가 종류마다 다르다")
        void splitsTheSupplierByKind() {
            closeWith(sellerId);

            assertThat(query.findOne(ownerId, numberOf("s-mine")).lines())
                    .filteredOn(line -> "SALE".equals(line.kind()))
                    .singleElement()
                    .extracting(SettlementQuery.Line::supplier)
                    .isEqualTo("SELLER");

            assertThat(query.findOne(ownerId, numberOf("s-mine")).lines())
                    .filteredOn(line -> "COMMISSION".equals(line.kind()))
                    .singleElement()
                    .extracting(SettlementQuery.Line::supplier)
                    .isEqualTo("PLATFORM");
        }

        @Test
        @DisplayName("배송비 줄에는 상품이 없다")
        void leavesTheProductEmptyOnShipping() {
            closeWith(sellerId);

            assertThat(query.findOne(ownerId, numberOf("s-mine")).lines())
                    .filteredOn(line -> "SHIPPING_FEE".equals(line.kind()))
                    .singleElement()
                    .satisfies(line -> {
                        assertThat(line.productName())
                                .as("묶음 단위라 항목별로 가를 근거가 없다")
                                .isNull();
                        assertThat(line.sellerOrderNumber()).isNotNull();
                    });
        }
    }

    /** 셀러마다 구매확정 주문을 하나씩 만들고 그 달을 마감한다 */
    private void closeWith(long... sellers) {
        for (long seller : sellers) {
            confirmedOrder(seller);
        }
        jdbc.sql("""
                        insert into batch_run (batch_name, baseline_date, started_at, finished_at,
                                               target_count, processed_count, status)
                        values ('auto_confirm', :baselineDate, now(), now(), 0, 0, 'succeeded')
                        """)
                .param("baselineDate", PERIOD_END)
                .update();

        batch.close(PERIOD_END);
    }

    private String numberOf(String sellerCode) {
        return jdbc.sql("""
                        select s.settlement_number from settlement s
                          join seller sel on sel.seller_id = s.seller_id
                         where sel.code = :code
                        """)
                .param("code", sellerCode)
                .query(String.class)
                .single();
    }

    private void confirmedOrder(long seller) {
        long orderId = jdbc.sql("""
                        insert into shop_order (order_number, user_id, total_amount,
                                                commission_total, shipping_fee_total, payable_amount)
                        values (:number, :userId, :price, :commission, :shipping, :payable)
                        returning order_id
                        """)
                .param("number", OrderFixture.sellerOrderNumber().substring(2))
                .param("userId", buyerId)
                .param("price", PRICE)
                .param("commission", COMMISSION)
                .param("shipping", SHIPPING_FEE)
                .param("payable", PRICE + SHIPPING_FEE)
                .query(Long.class)
                .single();

        OrderFixture.attachContractDocuments(jdbc, orderId);

        long sellerOrderId = jdbc.sql("""
                        insert into seller_order (seller_order_number, order_id, seller_id,
                                                  shipping_fee, status, closed_at)
                        values (:number, :orderId, :sellerId, :fee, 'confirmed', :closedAt)
                        returning seller_order_id
                        """)
                .param("number", OrderFixture.sellerOrderNumber())
                .param("orderId", orderId)
                .param("sellerId", seller)
                .param("fee", SHIPPING_FEE)
                .param("closedAt", PERIOD_END.atTime(12, 0)
                        .atZone(BusinessCalendar.ZONE).toOffsetDateTime())
                .query(Long.class)
                .single();

        jdbc.sql("""
                        insert into order_item (seller_order_id, sku_id, product_name,
                                                unit_price_incl_vat, quantity, line_amount,
                                                commission_bp, commission_amount)
                        values (:sellerOrderId, :skuId, '정산 조회 상품',
                                :price, 1, :price, :bp, :commission)
                        """)
                .param("sellerOrderId", sellerOrderId)
                .param("skuId", insertSku(seller))
                .param("price", PRICE)
                .param("bp", COMMISSION_BP)
                .param("commission", COMMISSION)
                .update();
    }

    private long insertSku(long seller) {
        long productId = jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name)
                        values (:sellerId, :userId, '정산 조회 상품')
                        returning product_id
                        """)
                .param("sellerId", seller)
                .param("userId", buyerId)
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
