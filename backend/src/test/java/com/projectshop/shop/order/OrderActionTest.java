package com.projectshop.shop.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.order.OrderActionService.Action;
import com.projectshop.shop.order.OrderStatusService.Actor;
import com.projectshop.shop.order.OrderTransitions.Payment;

/**
 * 사람이 주문을 옮기는 경로.
 *
 * <p>여기서 지키는 것 셋이다. <b>동작마다 열리는 상태가 다르고</b>(`V20` 이 동작을 가른 이유),
 * <b>고객과 셀러가 서로의 동작을 못 부르고</b>, <b>법이 정한 두 조건이 반품을 막는다</b>(`D2` R3·R4).
 *
 * <p>셋 다 틀려도 화면은 정상으로 보인다 — 셀러가 대신 확정을 누르면 정산이 당겨질 뿐이고,
 * 기한 지난 반품은 그냥 접수된다.
 */
@DisplayName("주문 처리")
class OrderActionTest extends PostgresTestBase {

    @Autowired
    private OrderActionService actions;

    @Autowired
    private SellerOrderQuery sellerOrders;

    @Autowired
    private OrderQuery orders;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderStatusService statuses;

    @Autowired
    private JdbcClient jdbc;

    private AuthFixture fixture;
    private long buyer;
    private long alphaOwner;
    private long alpha;
    private long alphaSku;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);

        buyer = fixture.insertUser("oa-buyer@test.local", "산사람");
        fixture.grantGlobal(buyer, "customer");

        alpha = fixture.insertSeller("s-oa-alpha", "알파");
        fixture.verifySeller(alpha);
        alphaSku = insertSku(alpha, "알파 상품", null);

        alphaOwner = fixture.insertUser("oa-alpha-owner@test.local", "알파대표");
        fixture.joinSeller(alpha, alphaOwner);
        fixture.grantOrg(alphaOwner, "seller_owner", alpha);
    }

    @Nested
    @DisplayName("동작과 상태")
    class ActionAndStatus {

        @Test
        @DisplayName("셀러가 발송하면 배송중이 된다")
        void sellerShips() {
            String number = paidShipment();

            actions.run(alphaOwner, number, Action.SHIP, null);

            assertThat(statusOf(number)).isEqualTo("shipping");
        }

        /**
         * `V20` 이 동작을 가른 첫 번째 이유다. 한 동작으로 두면 고객이 자기 주문을
         * 발송·배송완료까지 밀어서, 셀러가 물건을 안 보낸 주문이 확정까지 간다.
         */
        @Test
        @DisplayName("고객은 발송을 못 부른다")
        void buyerCannotShip() {
            String number = paidShipment();

            assertThatThrownBy(() -> actions.run(buyer, number, Action.SHIP, null))
                    .as("고객에게 update_status 가 열리면 셀러 없이 주문이 진행된다")
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.SELLER_ORDER_NOT_FOUND));
        }

        /**
         * 두 번째 이유다. 확정은 정산 대상이 되는 사건이라 셀러가 누를 수 있으면
         * 자기 정산 시점을 당긴다(`D7`).
         */
        @Test
        @DisplayName("셀러는 구매확정을 못 부른다")
        void sellerCannotConfirm() {
            String number = deliveredShipment();

            assertThatThrownBy(() -> actions.run(alphaOwner, number, Action.CONFIRM, null))
                    .as("셀러가 확정을 누르면 청약철회가 살아 있는 주문이 정산으로 넘어간다")
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.SELLER_ORDER_NOT_FOUND));
        }

        @Test
        @DisplayName("고객이 배송받은 것을 확정한다")
        void buyerConfirms() {
            String number = deliveredShipment();

            actions.run(buyer, number, Action.CONFIRM, null);

            assertThat(statusOf(number)).isEqualTo("confirmed");
        }

        /** 상태 축이 막는다. 전이표에도 없지만 권한에서 먼저 걸려야 시도가 감사에 남는다 */
        @Test
        @DisplayName("배송 전에는 구매확정이 안 열린다")
        void confirmClosedBeforeDelivery() {
            String number = paidShipment();

            assertThatThrownBy(() -> actions.run(buyer, number, Action.CONFIRM, null))
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.SELLER_ORDER_NOT_FOUND));
        }

        /** `D7` 이 `preparing → cancelled` 를 "고객 또는 셀러" 로 정했다 */
        @Test
        @DisplayName("취소는 고객도 셀러도 부른다")
        void bothCanCancel() {
            actions.run(buyer, paidShipment(), Action.CANCEL, null);

            assertThatCode(() -> actions.run(alphaOwner, paidShipment(), Action.CANCEL, null))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("보이는 경계")
    class Visibility {

        /** 결제가 안 끝난 묶음은 조회에서와 마찬가지로 없는 것이다 */
        @Test
        @DisplayName("결제 전 묶음은 없는 것과 같다")
        void unpaidLooksMissing() {
            placeOrder(List.of(alphaSku));
            String number = numberOf(alpha);

            assertThatThrownBy(() -> actions.run(alphaOwner, number, Action.SHIP, null))
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.SELLER_ORDER_NOT_FOUND));
        }

        /** 403 이면 번호를 훑어 실재하는 묶음의 지도가 그려진다(`D5`) */
        @Test
        @DisplayName("남의 묶음도 없는 것과 같은 답이다")
        void othersLookMissing() {
            long stranger = fixture.insertUser("oa-stranger@test.local", "남");
            fixture.grantGlobal(stranger, "customer");
            String number = paidShipment();

            assertThatThrownBy(() -> actions.run(stranger, number, Action.CANCEL, null))
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.SELLER_ORDER_NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("반품 접수")
    class ReturnRequest {

        @Test
        @DisplayName("배송받은 것은 반품 접수가 된다")
        void plainReturnPasses() {
            String number = deliveredShipment();

            actions.run(buyer, number, Action.REQUEST_RETURN, null);

            assertThat(statusOf(number)).isEqualTo("return_requested");
        }

        /**
         * <b>`43` 이 표를 세우고 넣는 코드를 안 만들어서 반품이 통째로 막혀 있었다.</b>
         * 묶음만 옮기면 `V63` 의 지연 트리거가 커밋에서 거부한다 — 접수가 되는 것처럼 보이다가
         * 트랜잭션이 끝날 때 통째로 사라진다.
         *
         * <p><b>{@code set constraints all immediate} 를 지나야 이 테스트가 값을 한다.</b>
         * 테스트는 롤백이라 그냥 두면 지연 검사가 한 번도 안 돈다(`ReturnRequestSchemaTest`).
         */
        @Test
        @DisplayName("접수하면 반품 행이 생겨서 커밋 검사를 지난다")
        void opensReturnRequestRow() {
            String number = deliveredShipment();

            actions.run(buyer, number, Action.REQUEST_RETURN, null);

            assertThatCode(() -> jdbc.sql("set constraints all immediate").update())
                    .as("묶음만 return_requested 로 가면 V63 의 지연 트리거가 커밋에서 거부한다")
                    .doesNotThrowAnyException();

            assertThat(returnStatusOf(number)).isEqualTo("requested");
            assertThat(returnItemCountOf(number))
                    .as("반품은 묶음 통째라 주문 항목이 그대로 담겨야 한다(`D7`)")
                    .isEqualTo(1);
        }

        /** 사유가 이 행에도 남는다. 묶음의 사유는 거절되면 비워진다(`V63`) */
        @Test
        @DisplayName("하자 반품은 사유가 반품 행에 남는다")
        void keepsDefectReasonOnRow() {
            String number = deliveredShipment();

            actions.run(buyer, number, Action.REQUEST_RETURN, null,
                    OrderStatusService.ReturnReason.DEFECT);

            assertThat(returnReasonOf(number)).isEqualTo("defect");
        }

        /**
         * 전자상거래법 제17조제2항(`D2` R4).
         *
         * <p><b>주문 시점에 성립한 제한만 막는다</b>(`Q5`·`Q6`). 디지털콘텐츠는 제공 개시가 요건인데
         * 반품 접수가 배송완료에서만 열려서 공급이 전제고, 그래서 동의 없이도 성립한다.
         */
        @Test
        @DisplayName("청약철회 제한이 성립한 항목은 반품이 막힌다")
        void restrictedProductBlocked() {
            long restrictedSku = insertSku(alpha, "디지털 콘텐츠", "digital_content");
            String number = deliveredShipment(restrictedSku);

            assertThatThrownBy(() -> actions.run(buyer, number, Action.REQUEST_RETURN, null))
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.WITHDRAWAL_RESTRICTED));
        }

        /** 묶음 하나에 섞여 있어도 막는다. 취소·반품의 최소 단위가 셀러 묶음이다(`D7`) */
        @Test
        @DisplayName("한 항목만 제한이어도 묶음 전체가 막힌다")
        void oneRestrictedItemBlocksBundle() {
            long restrictedSku = insertSku(alpha, "디지털 콘텐츠", "digital_content");
            String number = deliveredShipment(alphaSku, restrictedSku);

            assertThatThrownBy(() -> actions.run(buyer, number, Action.REQUEST_RETURN, null))
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.WITHDRAWAL_RESTRICTED));
        }

        /**
         * 전자상거래법 제17조(`D2` R3). 배송완료 때 박제한 값을 읽는다 —
         * 여기서 다시 계산하면 달력이 바뀌었을 때 지나간 주문의 기한까지 흔들린다.
         */
        @Test
        @DisplayName("청약철회 기간이 지나면 반품이 막힌다")
        void expiredPeriodBlocked() {
            String number = deliveredShipment();
            expireWithdrawal(number);

            assertThatThrownBy(() -> actions.run(buyer, number, Action.REQUEST_RETURN, null))
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.WITHDRAWAL_PERIOD_EXPIRED));
        }

        /**
         * 배치와 관리자도 같은 검사를 지난다. 서비스가 아니라 경로마다 걸면
         * 새 입구가 생겼을 때 빠뜨린다(`D23` 「축 2 — 강제 지점」).
         */
        @Test
        @DisplayName("전이 서비스를 직접 불러도 제한이 걸린다")
        void checkLivesInTransition() {
            long restrictedSku = insertSku(alpha, "디지털 콘텐츠", "digital_content");
            String number = deliveredShipment(restrictedSku);
            long sellerOrderId = idOf(number);

            assertThatThrownBy(() -> statuses.moveShipment(sellerOrderId,
                    OrderTransitions.Shipment.RETURN_REQUESTED, Actor.system("직접 호출")))
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.WITHDRAWAL_RESTRICTED));
        }

        /**
         * 복제 가능 매체(제17조제2항4호)는 <b>포장을 훼손한 경우</b>가 요건이다.
         *
         * <p>물건이 돌아와야 아는 사실이고 제17조제5항이 그 입증을 우리에게 지웠다.
         * 상품에 그 표시가 붙어 있다는 이유로 접수를 막으면 <b>뜯지 않고 돌려보내는 사람까지 막는</b>
         * 부당한 제한이 된다. 판단은 반품 검수 축(43·44)이 한다.
         */
        @Test
        @DisplayName("복제 가능 매체는 접수를 안 막는다")
        void copyableMediaDoesNotBlock() {
            long restrictedSku = insertSku(alpha, "복제 가능 재화", "copyable_media");
            String number = deliveredShipment(restrictedSku);

            assertThatCode(() -> actions.run(buyer, number, Action.REQUEST_RETURN, null))
                    .as("포장 훼손은 접수 시점에 알 수 없다")
                    .doesNotThrowAnyException();
        }
    }

    /**
     * 구매확정 뒤의 반품(`43a-3`).
     *
     * <p>{@link OrderStatusPolicy} 가 {@code confirmed} 를 열고 사유 판정은
     * {@code requireWithdrawable} 이 한다. <b>강제 지점이 앱 검증(3위)이라 경계를 여기서 못박는다</b> —
     * 상태 축으로 못 내린 이유는 사는 사람이 7일 안에 손으로 확정할 수 있어서다.
     * 그때 제17조제1항의 권리는 아직 살아 있고, 축으로 닫으면 우리가 만든 장치로 법이 준 기간을 자른다.
     *
     * <p>넷이 두 축으로 갈린다 — <b>사유</b>(변심/하자) × <b>기한</b>(안/밖).
     */
    @Nested
    @DisplayName("구매확정 뒤 반품")
    class ReturnAfterConfirm {

        /**
         * <b>이 줄이 상태 축으로 안 내린 이유다.</b> 자동확정은 8일이라 7일이 확실히 지났지만,
         * 손으로 확정하면 3일차에도 {@code confirmed} 가 된다 — 제17조제1항의 권리가 5일 남아 있다.
         */
        @Test
        @DisplayName("기한 안이면 손으로 확정했어도 단순 변심이 열린다")
        void changeOfMindWithinPeriodPasses() {
            String number = confirmedShipment();

            actions.run(buyer, number, Action.REQUEST_RETURN, null);

            assertThat(statusOf(number))
                    .as("구매확정은 우리가 만든 장치라 제17조제1항의 7일을 못 자른다")
                    .isEqualTo("return_requested");
        }

        /** 확정이 아니라 <b>기한</b>이 막는다. 상태 축이 아니라 박제된 값이 판단 근거다 */
        @Test
        @DisplayName("기한이 지나면 단순 변심이 막힌다")
        void changeOfMindAfterPeriodBlocked() {
            String number = confirmedShipment();
            expireWithdrawal(number);

            assertThatThrownBy(() -> actions.run(buyer, number, Action.REQUEST_RETURN, null))
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.WITHDRAWAL_PERIOD_EXPIRED));
        }

        /**
         * <b>이것이 이 청크가 연 구멍이다.</b> 그전에는 상태 축이 {@code delivered} 만 들고 있어서
         * 확정한 사람의 하자 신고가 통째로 거부됐다 — 제17조제3항이 준 3개월이 남아 있는데도.
         */
        @Test
        @DisplayName("확정 두 달 뒤여도 하자는 접수된다")
        void defectWithinThreeMonthsPasses() {
            String number = confirmedShipment();
            deliverBackDated(number, 2);

            actions.run(buyer, number, Action.REQUEST_RETURN, null,
                    OrderStatusService.ReturnReason.DEFECT);

            assertThat(statusOf(number))
                    .as("제17조제3항 — 공급받은 날부터 3개월이라 구매확정으로 안 끝난다")
                    .isEqualTo("return_requested");

            assertThatCode(() -> jdbc.sql("set constraints all immediate").update())
                    .as("확정 경로도 `V63` 의 지연 트리거를 지난다. 테스트는 롤백이라 안 부르면 한 번도 안 돈다")
                    .doesNotThrowAnyException();
            assertThat(returnStatusOf(number)).isEqualTo("requested");
        }

        /** 3개월은 역일이라 {@code delivered_at} 에서 세면 언제 계산해도 같다 */
        @Test
        @DisplayName("확정 넉 달 뒤면 하자도 막힌다")
        void defectAfterThreeMonthsBlocked() {
            String number = confirmedShipment();
            deliverBackDated(number, 4);

            assertThatThrownBy(() -> actions.run(buyer, number, Action.REQUEST_RETURN, null,
                    OrderStatusService.ReturnReason.DEFECT))
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.WITHDRAWAL_PERIOD_EXPIRED));
        }

        /**
         * 접수까지만 열린 것이지 확정이 되돌아간 것이 아니다. 승인되면 {@code returned} 로 가고
         * 거절되면 {@code delivered} 로 돌아간다(`OrderTransitions`) — 확정으로는 안 돌아간다.
         */
        @Test
        @DisplayName("확정된 것을 또 확정하지는 못한다")
        void confirmStaysClosed() {
            String number = confirmedShipment();

            assertThatThrownBy(() -> actions.run(buyer, number, Action.CONFIRM, null))
                    .isInstanceOf(ShopException.class);
        }
    }

    /**
     * 상세가 내리는 `allowed_actions`.
     *
     * <p><b>목록이 실제 판정과 어긋나면 안 된다.</b> 어긋나는 쪽이 어느 쪽이든 나쁘다 —
     * 없는데 뜨면 눌렀을 때 404 고, 있는데 안 뜨면 할 수 있는 일을 못 한다.
     */
    @Nested
    @DisplayName("할 수 있는 것 목록")
    class AllowedActions {

        @Test
        @DisplayName("셀러는 배송 전에 발송과 취소가 열린다")
        void sellerAtPreparing() {
            String number = paidShipment();

            assertThat(allowedFor(alphaOwner, number))
                    .containsExactlyInAnyOrder("SHIP", "CANCEL");
        }

        @Test
        @DisplayName("고객은 배송 전에 취소만 열린다")
        void buyerAtPreparing() {
            String number = paidShipment();

            assertThat(allowedForBuyer(number)).containsExactly("CANCEL");
        }

        @Test
        @DisplayName("고객은 배송받으면 확정과 반품접수가 열린다")
        void buyerAtDelivered() {
            String number = deliveredShipment();

            assertThat(allowedForBuyer(number))
                    .containsExactlyInAnyOrder("CONFIRM", "REQUEST_RETURN");
        }

        /** 이것이 없으면 화면이 `/api/shipments/{번호}/confirm` 을 못 부른다 */
        @Test
        @DisplayName("구매자 상세가 셀러 묶음의 노출 번호를 같이 내린다")
        void buyerSeesSellerOrderNumber() {
            String number = paidShipment();

            assertThat(bundleOf(number).sellerOrderNumber()).isEqualTo(number);
        }

        /** `D7` — 배송완료를 지나면 셀러의 전이 권한이 닫힌다. 안 닫으면 기산점을 조작한다 */
        @Test
        @DisplayName("셀러는 배송완료 뒤에 할 것이 없다")
        void sellerAtDelivered() {
            String number = deliveredShipment();

            assertThat(allowedFor(alphaOwner, number))
                    .as("셀러에게 확정이 뜨면 정산 시점을 셀러가 당길 수 있다")
                    .isEmpty();
        }

        /**
         * 권한만 보면 둘(`SHIP`·`DELIVER`)이 다 열린다 — 셀러가 `update_status` 하나로 쓴다.
         * 전이표가 갈라야 「배송 전인데 배송완료」가 안 뜬다.
         *
         * <p>반품완료는 `43a-2` 가 `approve_return` 으로 떼어 냈다.
         */
        @Test
        @DisplayName("전이표에 없는 화살표는 안 뜬다")
        void transitionTableFilters() {
            String number = paidShipment();

            assertThat(allowedFor(alphaOwner, number))
                    .as("권한만 보면 배송완료까지 열린다. 전이표가 그것을 자른다")
                    .doesNotContain("DELIVER", "APPROVE_RETURN");
        }

        /**
         * <b>구매확정은 종착이 아니다</b>(`43a-3`). 제17조제3항의 하자 반품이 공급받은 날부터
         * 3개월이라 확정으로 안 끝난다 — 여기서 목록이 비면 화면에 반품 버튼이 안 뜬다.
         *
         * <p>기한이 남았는지는 이 목록이 안 본다. 그 판단은 사유를 알아야 갈리는데
         * 목록은 사유를 안 받는다 — 청약철회 제한 상품을 여기서 안 보는 것과 같은 이유다.
         */
        @Test
        @DisplayName("구매확정 뒤에는 반품 접수만 열린다")
        void buyerAtConfirmed() {
            String number = deliveredShipment();
            actions.run(buyer, number, Action.CONFIRM, null);

            assertThat(allowedForBuyer(number)).containsExactly("REQUEST_RETURN");
            assertThat(allowedFor(alphaOwner, number))
                    .as("확정 뒤 반품은 사는 사람이 여는 것이다")
                    .isEmpty();
        }

        @Test
        @DisplayName("종착 상태에서는 아무것도 안 열린다")
        void terminalIsEmpty() {
            String number = paidShipment();
            actions.run(buyer, number, Action.CANCEL, null);

            assertThat(allowedForBuyer(number)).isEmpty();
            assertThat(allowedFor(alphaOwner, number)).isEmpty();
        }

        /**
         * 목록을 만들려고 판정을 여섯 번 부르는데, 그 거부가 감사에 쌓이면
         * <b>상세를 열 때마다 네 줄</b>이다. 진짜 시도가 그 잡음에 묻힌다(`4b`).
         */
        @Test
        @DisplayName("목록을 묻는 것은 감사 로그에 안 남는다")
        void doesNotPolluteAuditLog() {
            String number = deliveredShipment();
            long before = denialCount();

            allowedFor(alphaOwner, number);

            assertThat(denialCount())
                    .as("버튼 모양을 물어본 것이 침입 시도와 같은 모양으로 쌓이면 안 된다")
                    .isEqualTo(before);
        }

        /** 셀러가 보는 경로(`/api/seller/orders/{번호}`) */
        private List<String> allowedFor(long userId, String sellerOrderNumber) {
            return sellerOrders.findByNumber(userId, sellerOrderNumber).allowedActions();
        }

        /** 구매자가 보는 경로(`/api/orders/{주문번호}`). 묶음마다 따로 붙는다 */
        private List<String> allowedForBuyer(String sellerOrderNumber) {
            return bundleOf(sellerOrderNumber).allowedActions();
        }

        private OrderQuery.SellerOrder bundleOf(String sellerOrderNumber) {
            return orders.findByNumber(buyer, orderNumberOf(sellerOrderNumber))
                    .sellerOrders().stream()
                    .filter(bundle -> sellerOrderNumber.equals(bundle.sellerOrderNumber()))
                    .findFirst()
                    .orElseThrow();
        }
    }

    @Nested
    @DisplayName("이력의 행위자")
    class ActorType {

        /** 역할 이름이 아니라 대상 행과의 관계로 정한다. 같은 사람이 자기 가게에서 살 수 있다 */
        @Test
        @DisplayName("주문자가 옮기면 customer 로 남는다")
        void buyerIsCustomer() {
            String number = deliveredShipment();

            actions.run(buyer, number, Action.CONFIRM, null);

            assertThat(lastActorType(number)).isEqualTo("customer");
        }

        @Test
        @DisplayName("셀러 소속이 옮기면 seller 로 남는다")
        void memberIsSeller() {
            String number = paidShipment();

            actions.run(alphaOwner, number, Action.SHIP, null);

            assertThat(lastActorType(number)).isEqualTo("seller");
        }

        /**
         * 주문자도 셀러 소속도 아닌데 판정을 통과했으면 `all` 스코프다. 정상 경로가 아니라서
         * 사유가 없으면 나중에 데이터가 왜 이 모양인지 아무도 모른다(`D7`).
         */
        @Test
        @DisplayName("관리자는 사유가 없으면 못 옮긴다")
        void adminNeedsReason() {
            long admin = fixture.insertUser("oa-admin@test.local", "관리자");
            fixture.grantGlobal(admin, "admin");
            String number = paidShipment();

            assertThatThrownBy(() -> actions.run(admin, number, Action.CANCEL, null))
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.TRANSITION_REASON_REQUIRED));

            actions.run(admin, number, Action.CANCEL, "고객 전화 요청");

            assertThat(lastActorType(number)).isEqualTo("admin");
        }
    }

    /** 결제까지 끝난 묶음. 인자가 없으면 기본 상품 하나짜리다 */
    private String paidShipment(long... skuIds) {
        List<Long> ids = skuIds.length == 0
                ? List.of(alphaSku)
                : Arrays.stream(skuIds).boxed().toList();

        statuses.movePayment(placeOrder(ids), Payment.PAID, Actor.system("테스트 결제"));
        return numberOf(alpha);
    }

    /** 셀러가 발송하고 배송을 끝낸 상태. 기한 둘이 여기서 박제된다 */
    private String deliveredShipment(long... skuIds) {
        String number = paidShipment(skuIds);
        actions.run(alphaOwner, number, Action.SHIP, null);
        actions.run(alphaOwner, number, Action.DELIVER, null);
        return number;
    }

    /** 사는 사람이 손으로 확정한 묶음. 기한은 아직 남아 있다 */
    private String confirmedShipment(long... skuIds) {
        String number = deliveredShipment(skuIds);
        actions.run(buyer, number, Action.CONFIRM, null);
        return number;
    }

    /**
     * 배송완료 시각을 과거로 민다. 3개월을 실제로 기다릴 수 없어서다.
     *
     * <p>청약철회 기한도 같이 민다 — 안 밀면 단순 변심 기한이 미래로 남아서
     * 하자 기한만 보는 것인지 둘 다 보는 것인지가 안 갈린다.
     */
    private void deliverBackDated(String sellerOrderNumber, int monthsAgo) {
        jdbc.sql("""
                        update seller_order
                           set delivered_at = now() - make_interval(months => :months),
                               withdrawal_expire_at = now() - make_interval(months => :months)
                         where seller_order_number = :number
                        """)
                .param("months", monthsAgo)
                .param("number", sellerOrderNumber)
                .update();
    }

    /** 기한을 손으로 넘긴다. 7일을 실제로 기다릴 수 없어서다 */
    private void expireWithdrawal(String sellerOrderNumber) {
        jdbc.sql("""
                        update seller_order
                           set withdrawal_expire_at = now() - interval '1 day'
                         where seller_order_number = :number
                        """)
                .param("number", sellerOrderNumber)
                .update();
    }

    private String statusOf(String sellerOrderNumber) {
        return jdbc.sql("select status from seller_order where seller_order_number = :number")
                .param("number", sellerOrderNumber)
                .query(String.class)
                .single();
    }

    private String returnStatusOf(String sellerOrderNumber) {
        return jdbc.sql("""
                        select rr.status from return_request rr
                          join seller_order so on so.seller_order_id = rr.seller_order_id
                         where so.seller_order_number = :number
                        """)
                .param("number", sellerOrderNumber)
                .query(String.class)
                .single();
    }

    private String returnReasonOf(String sellerOrderNumber) {
        return jdbc.sql("""
                        select rr.reason_code from return_request rr
                          join seller_order so on so.seller_order_id = rr.seller_order_id
                         where so.seller_order_number = :number
                        """)
                .param("number", sellerOrderNumber)
                .query(String.class)
                .single();
    }

    private int returnItemCountOf(String sellerOrderNumber) {
        return jdbc.sql("""
                        select count(*) from return_request_item rri
                          join return_request rr
                            on rr.return_request_id = rri.return_request_id
                          join seller_order so on so.seller_order_id = rr.seller_order_id
                         where so.seller_order_number = :number
                        """)
                .param("number", sellerOrderNumber)
                .query(Integer.class)
                .single();
    }

    private long idOf(String sellerOrderNumber) {
        return jdbc.sql("""
                        select seller_order_id from seller_order
                         where seller_order_number = :number
                        """)
                .param("number", sellerOrderNumber)
                .query(Long.class)
                .single();
    }

    private String orderNumberOf(String sellerOrderNumber) {
        return jdbc.sql("""
                        select o.order_number
                          from shop_order o
                          join seller_order so on so.order_id = o.order_id
                         where so.seller_order_number = :number
                        """)
                .param("number", sellerOrderNumber)
                .query(String.class)
                .single();
    }

    /** 판정이 남긴 거부의 수. 목록 계산이 여기를 안 건드려야 한다 */
    private long denialCount() {
        return jdbc.sql("select count(*) from audit_log where event_type = 'permission.denied'")
                .query(Long.class)
                .single();
    }

    private String lastActorType(String sellerOrderNumber) {
        return jdbc.sql("""
                        select h.actor_type
                          from order_status_history h
                          join seller_order so on so.seller_order_id = h.seller_order_id
                         where so.seller_order_number = :number
                         order by h.order_status_history_id desc
                         limit 1
                        """)
                .param("number", sellerOrderNumber)
                .query(String.class)
                .single();
    }

    private String numberOf(long sellerId) {
        return jdbc.sql("""
                        select seller_order_number from seller_order
                         where seller_id = :sellerId
                         order by seller_order_id desc
                         limit 1
                        """)
                .param("sellerId", sellerId)
                .query(String.class)
                .single();
    }

    private long placeOrder(List<Long> skuIds) {
        long cartId = cartOfBuyer();

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

    /**
     * 산 사람의 장바구니. <b>사람당 하나다</b>({@code cart_user_id_key}) —
     * 한 테스트가 주문을 둘 이상 만들므로 매번 새로 넣으면 두 번째에서 깨진다.
     */
    private long cartOfBuyer() {
        return jdbc.sql("select cart_id from cart where user_id = :userId")
                .param("userId", buyer)
                .query(Long.class)
                .optional()
                .orElseGet(() -> jdbc
                        .sql("insert into cart (user_id) values (:userId) returning cart_id")
                        .param("userId", buyer)
                        .query(Long.class)
                        .single());
    }

    /** @param restrictionReason 청약철회 제한 사유. 제한이 없으면 null 이다(`V13` 의 check 가 짝을 강제한다) */
    private long insertSku(long sellerId, String productName, String restrictionReason) {
        long productId = jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name, status,
                                             is_withdrawal_restricted, withdrawal_restriction_reason)
                        values (:sellerId, :userId, :name, 'on_sale',
                                :restricted, :reason)
                        returning product_id
                        """)
                .param("sellerId", sellerId)
                .param("userId", buyer)
                .param("name", productName)
                .param("restricted", restrictionReason != null)
                .param("reason", restrictionReason)
                .query(Long.class)
                .single();

        return jdbc.sql("""
                        with new_sku as (
                            insert into sku (product_id, price_incl_vat)
                            values (:productId, 10000)
                            returning sku_id
                        )
                        insert into sku_stock (sku_id, on_hand)
                        select sku_id, 10 from new_sku
                        returning sku_id
                        """)
                .param("productId", productId)
                .query(Long.class)
                .single();
    }
}
