package com.projectshop.shop.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.order.OrderStatusService.Actor;
import com.projectshop.shop.order.OrderStatusService.ReturnReason;
import com.projectshop.shop.order.OrderTransitions.Payment;
import com.projectshop.shop.order.OrderTransitions.Shipment;
import com.projectshop.shop.payment.PaymentService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 응답에 실리는 열거값의 표기(`D5` 「값의 형식」 — 대문자 스네이크).
 *
 * <p><b>방향이 다른 테스트다.</b> 다른 테스트는 「이 칸에 이 값이 실렸나」를 묻는데
 * 여기는 <b>「저장값이 그대로 새 나간 칸이 있나」</b>를 묻는다. 칸 이름을 안 적으므로
 * <b>새로 생긴 칸도 같이 걸린다</b> — 등록하는 목록이면 등록을 빠뜨리는 자리가 또 생긴다.
 *
 * <p>이것이 필요해진 이유가 실물에 있다(`43a-6`). {@code enumValue} 를 손으로 부르는 구조라
 * {@code SellerOrderQuery} 의 {@code return_reason} <b>한 칸만 그 호출을 빠뜨렸고</b>,
 * 저장값 {@code defect} 가 응답으로 새서 화면이 소문자로 읽고 있었다.
 * 테스트도 빌드도 초록이었다 — 아무도 응답 전체를 안 훑었다.
 *
 * <p>강제 지점으로는 4순위(돌릴 때만)다. 그 칸 자체는 타입으로 내렸고(`Detail.returnReason`)
 * 이 테스트는 <b>아직 문자열인 나머지 칸</b>을 받는다.
 */
@DisplayName("응답 열거값 표기")
class ResponseEnumCaseTest extends PostgresTestBase {

    private static final long PRICE = 12_000;
    private static final String GOOD_CARD = "4242-4242-4242-4242";

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderStatusService statuses;

    @Autowired
    private PaymentService payments;

    @Autowired
    private OrderQuery orders;

    @Autowired
    private SellerOrderQuery sellerOrders;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbc;

    private AuthFixture fixture;
    private long buyer;
    private long sellerOwner;
    private long seller;
    private String orderNumber;
    private String sellerOrderNumber;

    /**
     * 반품이 접수된 묶음 하나를 만든다. <b>사유가 있어야 이 테스트가 값을 한다</b> —
     * 반품 전이면 {@code return_reason} 이 비어서 새 나갈 값 자체가 없다.
     */
    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);

        buyer = fixture.insertUser("enum-buyer@test.local", "산사람");
        fixture.grantGlobal(buyer, "customer");

        seller = fixture.insertSeller("s-enum", "표기셀러");
        fixture.verifySeller(seller);

        sellerOwner = fixture.insertUser("enum-owner@test.local", "대표");
        fixture.joinSeller(seller, sellerOwner);
        fixture.grantOrg(sellerOwner, "seller_owner", seller);

        long skuId = insertSku();
        long cartId = jdbc.sql("insert into cart (user_id) values (:userId) returning cart_id")
                .param("userId", buyer)
                .query(Long.class)
                .single();
        long cartItemId = jdbc.sql("""
                        insert into cart_item (cart_id, sku_id, quantity)
                        values (:cartId, :skuId, 1)
                        returning cart_item_id
                        """)
                .param("cartId", cartId)
                .param("skuId", skuId)
                .query(Long.class)
                .single();

        OrderService.Created created = orderService.create(buyer,
                new OrderService.Command(List.of(cartItemId),
                        new OrderService.Shipping("홍길동", "010-0000-0000", "06134",
                                "서울시 강남구", "101호", null)));
        orderNumber = created.orderNumber();

        payments.pay(buyer, UUID.randomUUID().toString(),
                new PaymentService.Command(orderNumber, "card", GOOD_CARD));

        long bundleId = jdbc.sql("select seller_order_id from seller_order where order_id = :id")
                .param("id", created.orderId())
                .query(Long.class)
                .single();
        sellerOrderNumber = jdbc.sql("""
                        select seller_order_number from seller_order where seller_order_id = :id
                        """)
                .param("id", bundleId)
                .query(String.class)
                .single();

        statuses.moveShipment(bundleId, Shipment.SHIPPING, Actor.person("seller", sellerOwner));
        statuses.moveShipment(bundleId, Shipment.DELIVERED, Actor.person("seller", sellerOwner));
        statuses.moveShipment(bundleId, Shipment.RETURN_REQUESTED,
                Actor.person("customer", buyer), ReturnReason.DEFECT);
    }

    /**
     * <b>이 칸이 이 청크가 고친 것이다.</b> 그전에는 {@code "defect"} 가 나갔다.
     */
    @Test
    @DisplayName("반품 사유가 대문자로 나간다")
    void returnReasonIsUpperCase() {
        JsonNode detail = json(sellerOrders.findByNumber(sellerOwner, sellerOrderNumber));

        assertThat(detail.get("return_reason").stringValue())
                .as("`D5` 「값의 형식」 — 열거값은 대문자 스네이크다")
                .isEqualTo("DEFECT");
    }

    /**
     * 칸 이름을 안 적는다. <b>저장값 집합을 들고 응답 전체를 훑는다</b> —
     * 새 칸이 생겨서 표기를 빠뜨려도 등록 없이 걸린다.
     */
    @Test
    @DisplayName("셀러 응답에 저장값이 그대로 실린 칸이 없다")
    void sellerDetailLeaksNothing() {
        assertThat(leakedValues(json(sellerOrders.findByNumber(sellerOwner, sellerOrderNumber))))
                .as("저장값이 그대로 나가면 화면이 소문자로 읽게 되고, 표기를 고치는 날 둘 다 고쳐야 한다")
                .isEmpty();
    }

    @Test
    @DisplayName("고객 응답에 저장값이 그대로 실린 칸이 없다")
    void buyerDetailLeaksNothing() {
        assertThat(leakedValues(json(orders.findByNumber(buyer, orderNumber)))).isEmpty();
    }

    /**
     * 위 훑기가 <b>빈 응답을 훑고 통과한 것이 아님</b>을 못박는다(`43a-8`).
     *
     * <p>「걸려 있다」와 「돈다」는 다르다. 값 집합에 {@link ActorType}·{@link ContractClause} 를
     * 더해도 <b>그 칸이 응답에 없으면</b> 훑기는 아무것도 안 보고 초록이 된다 —
     * 이 프로젝트가 이미 한 번 밟은 함정이다(`V63` 의 지연 트리거가 걸린 채 0회 돌았다).
     */
    @Test
    @DisplayName("이력과 계약 조항이 실제로 응답에 실린다")
    void detailActuallyCarriesScannedFields() {
        JsonNode detail = json(orders.findByNumber(buyer, orderNumber));

        assertThat(detail.get("history").isEmpty())
                .as("이력이 비면 actor_type 표기를 훑을 것이 없다")
                .isFalse();
        assertThat(detail.get("history").valueStream()
                .map(entry -> entry.get("actor_type").stringValue()))
                .as("`D5` 「값의 형식」 — 열거값은 대문자 스네이크다")
                .isNotEmpty()
                .allMatch(type -> type.equals(type.toUpperCase(Locale.ROOT)));

        assertThat(detail.get("contract_documents").valueStream()
                .map(document -> document.get("clause").stringValue()))
                .as("제13조제2항의 호. 비면 clause 표기를 훑을 것이 없다")
                .isNotEmpty()
                .allMatch(clause -> clause.equals(clause.toUpperCase(Locale.ROOT)));
    }

    /**
     * 표기를 바꾸는 자리가 <b>열거형을 지나간다</b>(`43a-7`).
     *
     * <p>그전에는 {@code toUpperCase()} 뿐이라 DB 에 모르는 값이 들어와 있으면 그대로 올려서
     * 내보냈다 — 화면이 처음 보는 값을 받고 서버는 아무 말도 안 한다.
     * <b>마이그레이션이 값을 늘렸는데 코드가 안 따라온 순간</b>이 그 자리다.
     *
     * <p>제약을 잠깐 떼고 넣는다. {@code check} 가 평소에 막는 것이 맞고,
     * 여기서 보는 것은 <b>그 제약이 뚫렸을 때 응답이 어떻게 되나</b>다.
     *
     * <p><b>지연 검사를 먼저 흘려보낸다.</b> {@code V63} 의 지연 트리거가 걸려 있는 동안에는
     * {@code alter table} 이 {@code pending trigger events} 로 거부된다.
     */
    @Test
    @DisplayName("모르는 저장 상태는 응답으로 안 나가고 터진다")
    void unknownStoredStatusThrows() {
        jdbc.sql("set constraints all immediate").update();
        jdbc.sql("alter table seller_order drop constraint seller_order_status_check").update();
        jdbc.sql("""
                        update seller_order set status = 'teleported'
                         where seller_order_number = :number
                        """)
                .param("number", sellerOrderNumber)
                .update();

        assertThatThrownBy(() -> sellerOrders.findByNumber(sellerOwner, sellerOrderNumber))
                .as("모르는 값을 조용히 대문자로 올려 보내면 화면이 처음 보는 값을 받는다")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("teleported");
    }

    /**
     * {@link OrderTransitions#statusName} 이 서 있는 전제를 지킨다(1차 마무리).
     *
     * <p>그 함수는 상태 이력의 한 줄이 어느 층인지 모르는 채로 <b>결제 층부터 찾아보고</b>
     * 없으면 배송 층으로 간다. <b>두 층의 값이 안 겹친다는 것이 그 순서의 근거다</b> —
     * 겹치는 값이 생기면 조용히 결제 층으로 판정되고, 터지지도 않아서 아무도 모른다.
     *
     * <p>전제가 코드 어디에도 안 걸려 있어서 여기에 건다. 상태를 하나 늘렸을 때
     * <b>이름이 겹치면 이 테스트가 먼저 깨진다.</b>
     */
    @Test
    @DisplayName("결제 층과 배송 층의 상태 코드는 안 겹친다")
    void statusLayersDoNotOverlap() {
        Set<String> paymentCodes = Arrays.stream(Payment.values())
                .map(Payment::code)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertThat(Arrays.stream(Shipment.values()).map(Shipment::code))
                .as("겹치면 statusName 이 이력 한 줄을 잘못된 층으로 읽는다")
                .noneMatch(paymentCodes::contains);
    }

    /**
     * 이 테스트가 진짜로 잡는지 확인하는 자리다. <b>저장값을 일부러 실어 보고 걸리는지 본다</b> —
     * 안 그러면 「훑었는데 아무것도 안 나왔다」와 「훑는 코드가 죽어 있다」가 안 갈린다.
     */
    @Test
    @DisplayName("저장값이 섞이면 실제로 잡아낸다")
    void detectsLeakWhenItHappens() {
        JsonNode leaking = objectMapper.readTree(
                "{\"status\":\"return_requested\",\"return_reason\":\"defect\"}");

        assertThat(leakedValues(leaking))
                .containsExactlyInAnyOrder("return_requested", "defect");
    }

    /**
     * 응답 어딘가에 저장값이 문자열로 실렸나. <b>칸 이름을 안 적고 값으로 찾는다</b> —
     * 목록으로 두면 새 칸을 등록하는 것을 빠뜨리는 자리가 또 생긴다.
     *
     * <p>저장값 집합도 열거형에서 뽑는다. 손으로 적으면 열거형이 늘어날 때 빠뜨린다.
     */
    private List<String> leakedValues(JsonNode node) {
        Set<String> storedCodes = new LinkedHashSet<>();
        Arrays.stream(Shipment.values()).map(Shipment::code).forEach(storedCodes::add);
        Arrays.stream(Payment.values()).map(Payment::code).forEach(storedCodes::add);
        Arrays.stream(ReturnReason.values()).map(ReturnReason::code).forEach(storedCodes::add);
        Arrays.stream(ActorType.values()).map(ActorType::code).forEach(storedCodes::add);
        Arrays.stream(ContractClause.values()).map(ContractClause::code).forEach(storedCodes::add);

        List<String> found = new ArrayList<>();
        collectStrings(node, found);
        return found.stream().filter(storedCodes::contains).collect(Collectors.toList());
    }

    /**
     * <b>메타 필드는 건너뛴다.</b> 밑줄로 시작하는 이름은 자원의 속성이 아니라는 표시고
     * (`D5` 「밑줄로 시작하는 이름은 메타 필드라는 표시다」), 거기 실리는 것은 열거값이 아니라
     * <b>필드 그룹 이름</b>이다 — `D5` 가 그 예를 {@code ["basic", "shipping"]} 소문자로 적어 놨다.
     *
     * <p>안 가르면 {@code _visible_field_groups} 의 {@code "shipping"} 이
     * {@link Shipment#SHIPPING} 의 저장값과 글자가 같아서 걸린다. <b>뜻이 다른데 글자가 같다.</b>
     */
    private void collectStrings(JsonNode node, List<String> into) {
        if (node.isString()) {
            into.add(node.stringValue());
            return;
        }

        if (node.isObject()) {
            node.propertyNames().forEach(name -> {
                if (!name.startsWith("_")) {
                    collectStrings(node.get(name), into);
                }
            });
            return;
        }

        node.forEach(child -> collectStrings(child, into));
    }

    private JsonNode json(Object response) {
        return objectMapper.valueToTree(response);
    }

    private long insertSku() {
        long productId = jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name, status)
                        values (:sellerId, :userId, '표기 상품', 'on_sale')
                        returning product_id
                        """)
                .param("sellerId", seller)
                .param("userId", sellerOwner)
                .query(Long.class)
                .single();

        return jdbc.sql("""
                        with new_sku as (
                            insert into sku (product_id, price_incl_vat)
                            values (:productId, :price)
                            returning sku_id
                        )
                        insert into sku_stock (sku_id, on_hand)
                        select sku_id, 10 from new_sku
                        returning sku_id
                        """)
                .param("productId", productId)
                .param("price", PRICE)
                .query(Long.class)
                .single();
    }
}
