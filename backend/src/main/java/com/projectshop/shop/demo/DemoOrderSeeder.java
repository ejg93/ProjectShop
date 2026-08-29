package com.projectshop.shop.demo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.projectshop.shop.cart.CartService;
import com.projectshop.shop.order.OrderActionService;
import com.projectshop.shop.order.OrderService;
import com.projectshop.shop.order.OrderStatusService;
import com.projectshop.shop.payment.PaymentMethod;
import com.projectshop.shop.payment.PaymentService;

/**
 * 데모 주문을 기동할 때 만든다(`6b-2`). {@code local} 프로필에서만 돈다.
 *
 * <h2>왜 SQL 이 아닌가</h2>
 *
 * <p>계정·셀러·상품은 {@code db/seed} 의 SQL 이 넣는데(`6b-1`) <b>주문은 그 방법으로 못 넣는다.</b>
 * 주문 하나가 서려면 통과할 자리가 여덟이고 — 노출 번호 발급(`D9`), 발송 기한의 영업일 계산
 * ({@code BusinessCalendar}), 계약내용 서면 네 조항(`V31`), 청약철회 제한 박제(`V32`),
 * 재고 차감, 셀러별 배송비, 상태 이력(`V18`), 결제 행(`V22`) — 이것을 SQL 로 다시 쓰면
 * <b>같은 규칙의 사본이 둘</b>이 된다. 운영 경로가 바뀌어도 시드는 안 따라오고 아무것도 안 깨진다.
 *
 * <p>그래서 여기서는 <b>운영 코드를 그대로 부른다.</b> 시드가 만드는 데이터가 사람이
 * 화면으로 만든 것과 같다는 것이 호출로 보장되고, 서비스 시그니처가 바뀌면
 * <b>이 파일이 안 컴파일된다</b> — 강제 지점이 테스트가 아니라 타입이다(`D23` 축 2의 1위).
 *
 * <h2>무엇을 넣나</h2>
 *
 * <p>고른 기준은 `6b-1` 과 같다 — <b>화면에서 갈리는 것을 데이터로 밟는다.</b>
 * 주문 축에서 갈리는 것은 상태와 사유고, 하나라도 빠지면 그 갈래는 손으로 만들게 된다.
 *
 * <h2>여러 번 돌아도 결과가 같다</h2>
 *
 * <p>주문이 하나라도 있으면 안 만들고 나간다. Flyway 와 달리 이 코드는 기동할 때마다 도는데,
 * 막지 않으면 <b>띄운 횟수만큼 주문이 쌓여서</b> 목록 화면이 매일 달라진다.
 */
@Component
@Profile("local")
public class DemoOrderSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoOrderSeeder.class);

    /** 승인되는 번호. 끝 네 자리가 {@code 0000} 이면 거절, {@code 0002} 면 타임아웃이다 */
    private static final String GOOD_CARD = "4242-4242-4242-4242";

    private static final String CUSTOMER = "customer@example.com";
    private static final String FASHION_OWNER = "fashion-owner@example.com";
    private static final String CRAFT_OWNER = "craft-owner@example.com";

    private static final String FASHION = "demo-fashion";
    private static final String CRAFT = "demo-craft";

    private static final String TSHIRT = "데모 티셔츠";
    private static final String BAG = "데모 에코백";
    private static final String CUTTING_BOARD = "데모 원목 도마";
    private static final String PATTERN_FILE = "데모 도안 파일";

    /**
     * 배송지. 다섯 주문이 같은 값을 쓴다.
     *
     * <p>번호와 우편번호에 형식 제약이 걸려 있다(`V33`). 주소는 실재하지 않는 것이라야 한다 —
     * `V900` 이 메일 도메인에 {@code example.com} 을 쓴 것과 같은 이유다.
     */
    private static final OrderService.Shipping SHIPPING = new OrderService.Shipping(
            "데모고객", "010-0000-0000", "06134", "서울특별시 강남구 테헤란로 1", "101호", null);

    private final JdbcClient jdbc;
    private final CartService carts;
    private final OrderService orders;
    private final PaymentService payments;
    private final OrderActionService actions;

    /** 한 번 돌면서 같은 계정을 여러 번 찾는다. 이메일로 매번 조회할 이유가 없다 */
    private final Map<String, Long> userIds = new HashMap<>();

    DemoOrderSeeder(JdbcClient jdbc, CartService carts, OrderService orders,
            PaymentService payments, OrderActionService actions) {
        this.jdbc = jdbc;
        this.carts = carts;
        this.orders = orders;
        this.payments = payments;
        this.actions = actions;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (hasOrders()) {
            log.info("데모 주문 시드를 건너뛴다. 이미 주문이 있다");
            return;
        }
        if (!hasDemoData()) {
            log.warn("데모 주문 시드를 건너뛴다. 데모 계정·상품이 없다 — db/seed 가 안 들어간 DB 다");
            return;
        }

        long buyer = userId(CUSTOMER);
        List<String> made = new ArrayList<>();

        // 1. 한 주문이 셀러 둘로 갈린다.
        //
        // 배송비가 셀러마다 붙는 것(패션 3,000원 / 공방 무료)과 발송 기한이 묶음마다 다른 것을
        // 여기서 밟는다 — 도마는 약정 7영업일이고 티셔츠는 약정이 없어 법정 3영업일이다(`14c`).
        //
        // **동의를 안 받는다.** 도마의 제한은 시행령 제21조가 거래마다 동의를 요구해서
        // 여기서는 성립하지 않는다(`Q5`) — 아래 5번과 갈리는 자리다.
        OrderService.Created split = place(buyer, false, sku(TSHIRT), sku(CUTTING_BOARD));
        made.add(split.orderNumber());

        // 묶음 둘의 상태를 다르게 둔다. 같으면 「주문 하나에 진행이 다른 묶음이 섞인 화면」이 안 나온다.
        ship(FASHION_OWNER, sellerOrderNumber(split.orderId(), FASHION));

        // 2. 배송완료. 청약철회 기간과 자동 구매확정 시각이 여기서 박제된다(`D7`·`D10`).
        OrderService.Created delivered = place(buyer, false, sku(BAG));
        made.add(delivered.orderNumber());
        deliver(FASHION_OWNER, sellerOrderNumber(delivered.orderId(), FASHION));

        // 3. 단순 변심 반품. 환불 화면(`12a`)이 볼 것이 이것이다.
        OrderService.Created returned = place(buyer, false, sku(BAG));
        made.add(returned.orderNumber());
        String returnedNumber = sellerOrderNumber(returned.orderId(), FASHION);
        deliver(FASHION_OWNER, returnedNumber);
        requestReturn(buyer, returnedNumber, OrderStatusService.ReturnReason.CHANGE_OF_MIND);

        // 4. 하자 반품. **3번과 사유만 다른데 기한도 배송비 부담도 갈린다**(제17조제3항, `D2` R3).
        // 둘 다 있어야 화면이 사유를 왜 물어보는지가 데이터로 드러난다.
        OrderService.Created defect = place(buyer, false, sku(BAG));
        made.add(defect.orderNumber());
        String defectNumber = sellerOrderNumber(defect.orderId(), FASHION);
        deliver(FASHION_OWNER, defectNumber);
        requestReturn(buyer, defectNumber, OrderStatusService.ReturnReason.DEFECT);

        // 5. 청약철회 제한이 **실제로 성립한** 주문. 배송완료인데 반품이 안 열린다.
        //
        // 한 묶음에 제한 둘이 다 든다 — 도마는 동의를 받아서(made_to_order),
        // 도안 파일은 공급이 개시돼서(digital_content) 성립한다. 성립 조건이 다른 둘을
        // 한 묶음에 두면 `V32` 의 제약 셋을 한 번에 밟는다.
        OrderService.Created restricted = place(buyer, true, sku(CUTTING_BOARD), sku(PATTERN_FILE));
        made.add(restricted.orderNumber());
        deliver(CRAFT_OWNER, sellerOrderNumber(restricted.orderId(), CRAFT));

        log.info("데모 주문 {}건을 만들었다: {}", made.size(), made);
    }

    /**
     * 담고, 주문하고, 결제한다. <b>셋 다 운영 경로다.</b>
     *
     * @param restrictionAgreed 주문제작 상품의 청약철회 제한에 동의했나. 주문 단위다(`Q5`)
     */
    private OrderService.Created place(long buyerId, boolean restrictionAgreed, long... skuIds) {
        CartService.Owner owner = CartService.Owner.of(buyerId, null);
        for (long skuId : skuIds) {
            carts.add(owner, skuId, 1);
        }

        // 담긴 것을 다시 읽어서 담긴 것의 식별자를 얻는다. 주문이 끝나면 산 것은 장바구니에서 빠져서
        // 다음 주문을 시작할 때 이 장바구니는 다시 비어 있다.
        List<Long> cartItemIds = carts.read(owner).items().stream()
                .map(CartService.Item::cartItemId)
                .toList();

        OrderService.Created created = orders.create(buyerId,
                new OrderService.Command(cartItemIds, SHIPPING, restrictionAgreed));

        payments.pay(buyerId, UUID.randomUUID().toString(),
                new PaymentService.Command(created.orderNumber(), PaymentMethod.CARD, GOOD_CARD));

        return created;
    }

    /** 셀러가 보낸다. 권한 판정까지 운영과 같은 자리를 탄다 */
    private void ship(String sellerOwnerEmail, String sellerOrderNumber) {
        actions.run(userId(sellerOwnerEmail), sellerOrderNumber, OrderActionService.Action.SHIP, null);
    }

    /** 배송중을 거쳐야 배송완료로 간다(`OrderTransitions`). 한 걸음씩 밟는다 */
    private void deliver(String sellerOwnerEmail, String sellerOrderNumber) {
        ship(sellerOwnerEmail, sellerOrderNumber);
        actions.run(userId(sellerOwnerEmail), sellerOrderNumber,
                OrderActionService.Action.DELIVER, null);
    }

    private void requestReturn(long buyerId, String sellerOrderNumber,
            OrderStatusService.ReturnReason reason) {
        actions.run(buyerId, sellerOrderNumber, OrderActionService.Action.REQUEST_RETURN,
                null, reason);
    }

    private boolean hasOrders() {
        return Boolean.TRUE.equals(
                jdbc.sql("select exists (select 1 from shop_order)").query(Boolean.class).single());
    }

    private boolean hasDemoData() {
        return Boolean.TRUE.equals(jdbc.sql("""
                        select exists (select 1 from app_user where email = :email)
                           and exists (select 1 from product where name = :product)
                        """)
                .param("email", CUSTOMER)
                .param("product", BAG)
                .query(Boolean.class)
                .single());
    }

    private long userId(String email) {
        return userIds.computeIfAbsent(email, key -> jdbc
                .sql("select user_id from app_user where email = :email")
                .param("email", key)
                .query(Long.class)
                .single());
    }

    /**
     * 그 상품의 살 수 있는 조합 하나.
     *
     * <p>조합을 이름으로 안 고른다 — 여기서 필요한 것은 <b>재고가 있는 것 아무거나</b>고,
     * 조합을 지목하기 시작하면 `V902` 의 값이 바뀔 때마다 이 파일이 같이 깨진다.
     * 티셔츠는 검정 L 만 재고가 0 이라 나머지 셋 중 하나가 온다.
     */
    private long sku(String productName) {
        return jdbc.sql("""
                        select s.sku_id
                          from sku s
                          join product p on p.product_id = s.product_id
                          join sku_stock st on st.sku_id = s.sku_id
                         where p.name = :name
                           and st.available_count > 0
                         order by s.sku_id
                         limit 1
                        """)
                .param("name", productName)
                .query(Long.class)
                .single();
    }

    /** 그 주문에서 그 셀러가 받은 묶음의 노출 번호. 동작은 전부 이 번호로 부른다(`D9`) */
    private String sellerOrderNumber(long orderId, String sellerCode) {
        return jdbc.sql("""
                        select so.seller_order_number
                          from seller_order so
                          join seller sel on sel.seller_id = so.seller_id
                         where so.order_id = :orderId
                           and sel.code = :sellerCode
                        """)
                .param("orderId", orderId)
                .param("sellerCode", sellerCode)
                .query(String.class)
                .single();
    }
}
