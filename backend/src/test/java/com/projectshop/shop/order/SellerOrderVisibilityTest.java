package com.projectshop.shop.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.order.OrderStatusService.Actor;
import com.projectshop.shop.order.OrderTransitions.Payment;

/**
 * 셀러 묶음의 노출 번호와, 셀러에게 보이는 경계.
 *
 * <p>둘 다 <b>빠뜨려도 오류가 안 나는</b> 것이다. 번호가 없으면 전이 대상을 가리킬 방법이 없고,
 * 경계가 없으면 결제도 안 된 주문을 셀러가 준비한다.
 */
@DisplayName("셀러 주문의 번호와 노출 경계")
class SellerOrderVisibilityTest extends PostgresTestBase {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderStatusService statuses;

    @Autowired
    private JdbcClient jdbc;

    private AuthFixture fixture;
    private long buyer;
    private long alphaSku;
    private long betaSku;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);
        buyer = fixture.insertUser("visible-buyer@test.local", "산사람");

        long alpha = fixture.insertSeller("s-visible-a", "알파");
        fixture.verifySeller(alpha);
        alphaSku = insertSku(alpha, "알파 상품");

        long beta = fixture.insertSeller("s-visible-b", "베타");
        fixture.verifySeller(beta);
        betaSku = insertSku(beta, "베타 상품");
    }

    @Nested
    @DisplayName("노출 번호")
    class Number {

        /**
         * 형식이 주문번호와 같으면 전화로 번호를 받는 자리에서 어느 쪽인지 못 가른다.
         * 접두어가 그것을 가른다(`D9`).
         */
        @Test
        @DisplayName("S- 로 시작하는 번호가 발급된다")
        void hasPrefixedNumber() {
            long orderId = placeOrder(List.of(alphaSku));

            assertThat(numbersOf(orderId))
                    .singleElement()
                    .asString()
                    .matches("^S-[0-9]{8}-[2-9A-HJ-NP-Z]{6}$");
        }

        @Test
        @DisplayName("셀러가 둘이면 번호도 둘이고 서로 다르다")
        void oneNumberPerSeller() {
            long orderId = placeOrder(List.of(alphaSku, betaSku));

            assertThat(numbersOf(orderId))
                    .as("한 주문 안에서 번호가 겹치면 전이 대상을 가리킬 수 없다")
                    .hasSize(2)
                    .doesNotHaveDuplicates();
        }
    }

    @Nested
    @DisplayName("셀러에게 보이는 경계")
    class Visibility {

        /**
         * 셀러가 할 일이 생기는 시점은 결제 완료다. 결제 대기 건이 보이면
         * 아직 안 팔린 것을 준비하게 된다.
         */
        @Test
        @DisplayName("결제 전 주문은 뷰에 안 뜬다")
        void unpaidIsHidden() {
            long orderId = placeOrder(List.of(alphaSku));

            assertThat(visibleCountOf(orderId))
                    .as("미결제 건이 셀러 목록에 새면 화면에 오류로 안 드러난다")
                    .isZero();
        }

        @Test
        @DisplayName("결제되면 뜬다")
        void paidIsVisible() {
            long orderId = placeOrder(List.of(alphaSku));

            statuses.movePayment(orderId, Payment.PAID, Actor.system("테스트 결제"));

            assertThat(visibleCountOf(orderId)).isEqualTo(1);
        }

        @Test
        @DisplayName("결제가 만료된 주문도 안 뜬다")
        void expiredStaysHidden() {
            long orderId = placeOrder(List.of(alphaSku));

            statuses.movePayment(orderId, Payment.PAYMENT_EXPIRED, Actor.system("테스트 만료"));

            assertThat(visibleCountOf(orderId)).isZero();
        }
    }

    /**
     * 뷰가 표를 따라오나.
     *
     * <p><b>이 테스트가 없어서 같은 함정을 두 번 밟았다</b>(`Q2`). `11-6` 이 「뷰가 컬럼 목록을
     * 굳혀서 표에 컬럼이 늘어도 안 따라온다」를 이력에 적고 뷰를 다시 만들었는데,
     * 다음 날 `V29` 가 {@code return_reason} 을 더하면서 또 안 고쳤다.
     *
     * <p><b>뷰에는 {@code check} 를 못 건다.</b> 강제 지점이 테스트가 천장이고(`D23` 축 2)
     * 그 천장이 비어 있었다.
     *
     * <p>일부러 뺀 컬럼이 생기면 이 테스트가 먼저 깨진다 — 그때 <b>왜 뺐는지를 여기 적고</b>
     * 목록에서 덜어낸다. 조용히 빠지는 것과 밝히고 빼는 것을 가르는 것이 이 테스트의 일이다.
     */
    @Nested
    @DisplayName("뷰와 표의 컬럼")
    class ViewColumns {

        @Test
        @DisplayName("표에 있는 컬럼이 뷰에도 다 있다")
        void viewCarriesEveryColumn() {
            assertThat(columnsOf("seller_order_visible"))
                    .as("seller_order 에 컬럼을 더하면 seller_order_visible 도 같이 고친다")
                    .containsAll(columnsOf("seller_order"));
        }

        /**
         * 뷰에 있는 것이 계약까지 오나.
         *
         * <p><b>위 테스트가 못 잡는 한 칸이 더 있었다</b>(`11c-2c`). `V30` 이 뷰에
         * {@code return_reason} 을 실으면서 "`13g` 가 반품 사유를 그리려면 이 뷰로 와야 한다" 고
         * 적어 뒀는데, <b>{@link SellerOrderQuery.Detail} 에 칸이 없어서 못 나갔다.</b>
         * 표→뷰는 지켜졌고 뷰→계약이 비어 있었다.
         *
         * <p>못 나가는 것은 조용하다 — 응답에 없는 필드는 아무 데서도 안 깨지고,
         * 화면을 만들 때가 돼서야 드러난다. 그때는 계약과 화면을 같이 고친다.
         *
         * <p><b>뷰 컬럼이 전부 나가야 하는 것은 아니다.</b> 내부 식별자처럼 나가면 안 되는 것이
         * 있어서, 이 테스트가 요구하는 것은 "다 싣는 것" 이 아니라 <b>칸마다 답이 정해져 있는 것</b>이다.
         * 새 컬럼이 늘면 계약에 넣든 아래 목록에 넣든 <b>둘 중 하나를 고르게 만든다.</b>
         */
        @Test
        @DisplayName("뷰에 있는 컬럼은 계약에 있거나, 일부러 뺀 것이다")
        void everyViewColumnIsDecided() {
            List<String> carried = java.util.Arrays.stream(SellerOrderQuery.Detail.class
                            .getRecordComponents())
                    .map(component -> snakeCase(component.getName()))
                    .toList();

            assertThat(columnsOf("seller_order_visible"))
                    .as("뷰에 컬럼을 더하면 Detail 에 싣거나 NOT_IN_CONTRACT 에 근거를 적는다")
                    .allSatisfy(column -> assertThat(carried.contains(fieldNameOf(column))
                                    || NOT_IN_CONTRACT.contains(column))
                            .as("뷰 컬럼 %s 를 어디에도 안 정했다", column)
                            .isTrue());
        }

        /**
         * 컬럼 이름을 Java 필드 쪽 이름으로 맞춘다.
         *
         * <p><b>불리언의 {@code is_} 는 Java 에서 뗀다</b>(`D22`) — 게터가 {@code isShipOverdue()} 라
         * 접두사가 두 번 붙어서다. 그 규칙을 여기서도 써야 {@code is_ship_overdue} 와
         * {@code shipOverdue} 가 같은 것으로 잡힌다.
         */
        private String fieldNameOf(String column) {
            return column.startsWith("is_") ? column.substring("is_".length()) : column;
        }

        /**
         * 일부러 안 내보내는 뷰 컬럼. <b>왜 뺐는지가 여기 근거다.</b>
         *
         * <p>비우면 위 테스트가 "전부 실어라" 가 되고, 그러면 내부 식별자가 밖으로 나간다.
         */
        private static final List<String> NOT_IN_CONTRACT = List.of(
                // 내부 식별자. 밖으로는 노출 번호만 나간다(`D9`).
                "seller_order_id", "order_id",

                // 보는 사람이 그 셀러라 자명하다. 여러 셀러에 속한 사람은 목록을 셀러로 거른다.
                "seller_id",

                // 기한을 계산할 때 쓴 입력이다. 결과인 ship_due_at 이 이미 나가고,
                // 약정이 있었는지는 사는 사람 화면의 물음이다(`14c`).
                "supply_lead_days", "agreed_lead_days",

                // 거래가 끝난 시각. 셀러 화면이 묻는 것은 「지금 무엇을 하나」라 상태로 갈음된다.
                // 정산 축(`D10`)이 이 값을 쓰기 시작하면 그때 계약에 올린다.
                "closed_at",

                // 행을 마지막으로 건드린 시각. 도메인 사건이 아니라 기록이다.
                "updated_at");

        /** {@code shipDueAt} → {@code ship_due_at}. 이름이 바뀌는 자리는 여기 하나뿐이다(`D22`) */
        private String snakeCase(String camel) {
            return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
        }

        private List<String> columnsOf(String relation) {
            return jdbc.sql("""
                            select column_name from information_schema.columns
                             where table_schema = 'public' and table_name = :relation
                             order by column_name
                            """)
                    .param("relation", relation)
                    .query(String.class)
                    .list();
        }
    }

    private List<String> numbersOf(long orderId) {
        return jdbc.sql("""
                        select seller_order_number from seller_order
                         where order_id = :orderId
                         order by seller_order_id
                        """)
                .param("orderId", orderId)
                .query(String.class)
                .list();
    }

    private long visibleCountOf(long orderId) {
        return jdbc.sql("select count(*) from seller_order_visible where order_id = :orderId")
                .param("orderId", orderId)
                .query(Long.class)
                .single();
    }

    private long placeOrder(List<Long> skuIds) {
        long cartId = jdbc.sql("insert into cart (user_id) values (:userId) returning cart_id")
                .param("userId", buyer)
                .query(Long.class)
                .single();

        List<Long> cartItemIds = skuIds.stream()
                .map(skuId -> jdbc.sql("""
                                insert into cart_item (cart_id, sku_id, quantity)
                                values (:cartId, :skuId, 1)
                                returning cart_item_id
                                """)
                        .param("cartId", cartId)
                        .param("skuId", skuId)
                        .query(Long.class)
                        .single())
                .toList();

        return orderService.create(buyer, new OrderService.Command(cartItemIds,
                        new OrderService.Shipping("홍길동", "010-0000-0000", "06134",
                                "서울시 강남구", "101호", null)))
                .orderId();
    }

    private long insertSku(long sellerId, String productName) {
        long productId = jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name, status)
                        values (:sellerId, :userId, :name, 'on_sale')
                        returning product_id
                        """)
                .param("sellerId", sellerId)
                .param("userId", buyer)
                .param("name", productName)
                .query(Long.class)
                .single();

        return jdbc.sql("""
                        insert into sku (product_id, price_incl_vat, stock_count)
                        values (:productId, 10000, 10)
                        returning sku_id
                        """)
                .param("productId", productId)
                .query(Long.class)
                .single();
    }
}
