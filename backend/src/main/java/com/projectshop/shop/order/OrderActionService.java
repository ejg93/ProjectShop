package com.projectshop.shop.order;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectshop.shop.auth.PermissionEvaluator;
import com.projectshop.shop.auth.PermissionEvaluator.Target;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.order.OrderStatusService.Actor;
import com.projectshop.shop.order.OrderTransitions.Shipment;

/**
 * 사람이 셀러 묶음의 상태를 옮기는 자리. 판정과 전이 사이를 잇는다.
 *
 * <p><b>상태를 조회한 행에서 그대로 실어 보낸다</b>(사용자 지시). 손으로 쓴 문자열을
 * {@link Target#inStatus} 에 넘기면 상태 축의 유일한 약점이 열린다 — 오타를 컴파일러가 못 잡고,
 * 틀린 상태는 <b>거부가 아니라 엉뚱한 허용</b>으로 떨어질 수 있다.
 *
 * <p><b>{@code seller_order_visible} 뷰를 읽는다.</b> 결제가 안 끝난 묶음은 조회에서와 마찬가지로
 * 여기서도 없는 것이다 — 결제 만료 건을 닫는 것은 사람이 아니라 결제 전이가 한다
 * ({@link OrderStatusService#movePayment}).
 *
 * <p>전이의 곁가지(기한 박제·재고 복구·{@code closed_at})는 여기서 안 만진다.
 * {@link OrderStatusService} 가 데리고 다닌다 — 입구마다 흩어 두면 한 군데서 빠뜨린다.
 */
@Service
public class OrderActionService {

    /**
     * 사람이 부를 수 있는 전이.
     *
     * <p><b>동작 이름이 넷이다</b>(`V20`). 상태 축의 단위가 {@code resource:action} 이라
     * 하나로 두면 고객과 셀러의 허용 상태를 못 가른다 — 표는 {@link OrderStatusPolicy} 에 있다.
     *
     * @param permission 판정에 넘길 {@code action}. 여러 전이가 한 권한을 공유한다
     * @param to         이 동작이 옮겨 놓는 상태
     */
    public enum Action {

        SHIP("update_status", Shipment.SHIPPING),
        DELIVER("update_status", Shipment.DELIVERED),
        COMPLETE_RETURN("update_status", Shipment.RETURNED),

        /**
         * 자기 주문을 스스로 무른다.
         *
         * <p><b>미성년자 취소권(민법 제5조, `D2` R13)은 여기 없다.</b> 안 빠뜨린 것이고
         * 일부러 안 넣었다 — 그 취소는 <b>주문한 사람이 아닌 제3자(법정대리인)가 부른다</b>.
         * {@code scope=own} 으로는 표현할 방법이 없어서 계정에 생년월일과 대리인 관계가 먼저 있어야 한다.
         *
         * <p>그 축이 서는 것은 청크 `11b` 다. <b>그때까지 이 경로는 본인 취소만 받는다.</b>
         * 근거를 여기 적는 이유는, `D2` 에만 두면 이 코드를 고치는 사람이 문서를 안 열고 지나서다.
         */
        CANCEL("cancel", Shipment.CANCELLED),
        CONFIRM("confirm", Shipment.CONFIRMED),
        REQUEST_RETURN("request_return", Shipment.RETURN_REQUESTED);

        private final String permission;
        private final Shipment to;

        Action(String permission, Shipment to) {
            this.permission = permission;
            this.to = to;
        }

        public String permission() {
            return permission;
        }

        Shipment to() {
            return to;
        }
    }

    /** 판정에 필요한 것까지 같이 읽은 행. 상태는 여기서 나와 그대로 판정으로 간다 */
    private record Row(long sellerOrderId, long buyerUserId, long sellerId, String status) {
    }

    private final JdbcClient jdbc;
    private final PermissionEvaluator evaluator;
    private final OrderStatusService statuses;

    OrderActionService(JdbcClient jdbc, PermissionEvaluator evaluator, OrderStatusService statuses) {
        this.jdbc = jdbc;
        this.evaluator = evaluator;
        this.statuses = statuses;
    }

    /**
     * 이 사람이 이 묶음을 이 동작으로 옮긴다.
     *
     * <p><b>읽기와 옮기기가 한 트랜잭션이다.</b> 갈라 두면 판정에 쓴 상태와 실제로 옮길 때의 상태가
     * 달라질 수 있고, 그 틈으로 닫힌 상태의 전이가 통과한다.
     *
     * <p><b>거부는 404 다</b>(`D5` 의 자원별 표). 403 을 주면 노출 번호를 훑어서 실재하는 묶음의
     * 지도를 그릴 수 있고, 그게 곧 셀러별 거래 건수다.
     *
     * @param reason 관리자가 옮길 때의 근거. 고객·셀러는 안 쓴다(`D7`)
     */
    @Transactional
    public void run(long userId, String sellerOrderNumber, Action action, String reason) {
        Row row = find(sellerOrderNumber);

        Target target = Target.of(row.buyerUserId(), row.sellerId()).inStatus(row.status());
        if (!evaluator.decide(userId, "order", action.permission(), target).allowed()) {
            throw notFound(sellerOrderNumber);
        }

        statuses.moveShipment(row.sellerOrderId(), action.to(), actorOf(userId, row, reason));
    }

    /**
     * 이 사람이 지금 이 묶음에 할 수 있는 것. 상세 응답의 {@code allowed_actions} 다.
     *
     * <p><b>거르는 것이 둘이다.</b> 전이표에 그 화살표가 있어야 하고(도메인), 권한과 상태 축이
     * 열려 있어야 한다. 앞을 빼면 {@code preparing} 인 묶음에 「배송완료」가 뜨고 —
     * 셀러는 {@code update_status} 를 셋에 다 쓰므로 권한만으로는 안 갈린다.
     *
     * <p><b>청약철회 제한 상품은 여기서 안 본다.</b> 이 목록의 물음이 "이 사람이 이 상태에서 무엇을
     * 할 권한이 있나" 고, 제한은 상품 속성이라 축이 아니다(`permission-rules.md`).
     * 화면은 그 사실을 상품에서 이미 받는다 — <b>미리 알리는 것이 제한의 성립 요건</b>이라
     * (전자상거래법 제17조제2항 단서, `D2` R4) 주문 화면에 오기 전에 표시돼 있어야 한다.
     *
     * <p>이름은 {@link Action} 그대로다. <b>소문자·하이픈으로 바꾸면 경로가 된다</b> —
     * {@code REQUEST_RETURN} 이 {@code /api/shipments/{번호}/request-return} 이다.
     * 화면이 동작마다 경로를 표로 들고 있지 않게 하려는 것이다.
     */
    public List<String> allowedActions(long userId, long buyerUserId, long sellerId, String status) {
        Shipment from = Shipment.of(status);
        Target target = Target.of(buyerUserId, sellerId).inStatus(status);

        Set<String> permitted = evaluator.allowedActions(userId, "order",
                Arrays.stream(Action.values())
                        .map(Action::permission)
                        .collect(Collectors.toUnmodifiableSet()),
                target);

        return Arrays.stream(Action.values())
                .filter(action -> permitted.contains(action.permission()))
                .filter(action -> OrderTransitions.allows(from, action.to()))
                .map(Enum::name)
                .toList();
    }

    private Row find(String sellerOrderNumber) {
        return jdbc.sql("""
                        select so.seller_order_id, so.seller_id, so.status,
                               o.user_id as buyer_user_id
                          from seller_order_visible so
                          join shop_order o on o.order_id = so.order_id
                         where so.seller_order_number = :number
                        """)
                .param("number", sellerOrderNumber)
                .query((rs, rowNum) -> new Row(
                        rs.getLong("seller_order_id"),
                        rs.getLong("buyer_user_id"),
                        rs.getLong("seller_id"),
                        rs.getString("status")))
                .optional()
                .orElseThrow(() -> notFound(sellerOrderNumber));
    }

    /**
     * 이력에 남길 행위자(`V18` 의 {@code actor_type}).
     *
     * <p><b>역할 이름을 안 쓴다.</b> 같은 사람이 자기 가게에서 살 수도 있어서 역할만으로는
     * 이 전이에서 무엇이었는지가 안 갈린다. 대상 행과의 관계로 정한다 —
     * 주문자면 {@code customer}, 그 셀러 소속이면 {@code seller}, 둘 다 아니면 {@code admin} 이다.
     *
     * <p>둘 다 아닌데 판정을 통과했다는 것은 {@code all} 스코프를 가졌다는 뜻이다.
     * <b>그때는 사유가 필수다</b>(`D7`) — 정상 경로가 아니라서 왜 그랬는지가 없으면
     * 나중에 데이터가 왜 이 모양인지 아무도 모른다. DB 도 같은 것을 막고 있다
     * ({@code order_status_history_admin_reason_check}).
     */
    private Actor actorOf(long userId, Row row, String reason) {
        if (userId == row.buyerUserId()) {
            return Actor.person("customer", userId);
        }
        if (isMemberOf(userId, row.sellerId())) {
            return Actor.person("seller", userId);
        }
        if (reason == null || reason.isBlank()) {
            throw new ShopException(ErrorCode.TRANSITION_REASON_REQUIRED);
        }
        return Actor.admin(userId, reason);
    }

    private boolean isMemberOf(long userId, long sellerId) {
        return jdbc.sql("""
                        select count(*) from seller_member
                         where user_id = :userId and seller_id = :sellerId
                        """)
                .param("userId", userId)
                .param("sellerId", sellerId)
                .query(Integer.class)
                .single() > 0;
    }

    private static ShopException notFound(String sellerOrderNumber) {
        return new ShopException(ErrorCode.SELLER_ORDER_NOT_FOUND,
                "그런 셀러 주문이 없다: " + sellerOrderNumber);
    }
}
