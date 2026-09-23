package com.projectshop.shop.order;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectshop.shop.audit.AuditLog;
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

        SHIP("update_status", Shipment.SHIPPING, Party.SELLER),
        DELIVER("update_status", Shipment.DELIVERED, Party.SELLER),

        /**
         * 반품을 인정한다. 묶음이 닫힌다.
         *
         * <p><b>{@code update_status} 에서 떼어 냈다</b>(`43a-2`). 그전에는 셀러의 그 권한이
         * {@code return_requested} 에서 열려 있었는데, 같은 권한으로 {@link #DELIVER} 를 부르면
         * <b>반품 거절이 된다</b> — `D7` 이 관리자만이라고 적어 둔 전이다.
         *
         * <p>승인까지 관리자인 근거는 제17조제5항이다(`D2` R37) — 훼손 책임의 입증이
         * 우리에게 있으므로 셀러의 소견이 곧 결론이 되면 안 된다.
         */
        APPROVE_RETURN("approve_return", Shipment.RETURNED, Party.ADMIN),

        /**
         * 반품을 인정하지 않는다. 물건이 소비자에게 돌아가고 묶음은 배송완료로 되돌아간다.
         *
         * <p><b>기산점은 안 움직인다</b>(`D7`) — {@code delivered} 로 갈 때 박제한 값이라
         * 다시 안 센다. {@link OrderStatusService} 가 이 복귀에서 기한을 다시 안 박는다.
         */
        REJECT_RETURN("reject_return", Shipment.DELIVERED, Party.ADMIN),

        /**
         * 자기 주문을 스스로 무른다.
         *
         * <p><b>미성년자 취소권(민법 제5조, `D2` R13)은 여기 없다</b> — 가입 입구가 생년월일을 받아 만 19세 미만을 막아서(`11b`)
         * 법정대리인이 부를 거래가 안 생긴다. 생년월일이 빈 옛 계정·시드는 그 전에 만든 것이다. 그래서 이 경로는 본인 취소만 받는다.
         * 미성년자 가입을 여는 날 이 동작의 주체가 는다.
         * 근거를 여기 적는 이유는, `D2` 에만 두면 이 코드를 고치는 사람이 문서를 안 열고 지나서다.
         */
        CANCEL("cancel", Shipment.CANCELLED, Party.BUYER_OR_SELLER),
        CONFIRM("confirm", Shipment.CONFIRMED, Party.BUYER),
        REQUEST_RETURN("request_return", Shipment.RETURN_REQUESTED, Party.BUYER);

        private final String permission;
        private final Shipment to;
        private final Party party;

        Action(String permission, Shipment to, Party party) {
            this.permission = permission;
            this.to = to;
            this.party = party;
        }

        public String permission() {
            return permission;
        }

        Shipment to() {
            return to;
        }
    }

    /**
     * 동작이 누구 몫인가(`Q202`). <b>판정과 다른 물음이다</b> — 관리자는 모든 권한을 {@code all} 로 가져서
     * 판정만으로는 구매확정·발송까지 관리자에게 열린다. 버튼을 고르는 {@link #allowedActions} 가 이것으로 한 번 더 거른다.
     * 입구 판정은 그대로다 — {@code Q198} 이 상품에서 한 것과 같은 모양이다.
     */
    enum Party {
        /** 주문한 사람 몫 */
        BUYER,
        /** 그 셀러 소속 몫 */
        SELLER,
        /** 둘 다 부를 수 있다 */
        BUYER_OR_SELLER,
        /** 주문자도 소속도 아닌 사람(관리자) 몫 — 반품 판정 */
        ADMIN;

        boolean offeredTo(boolean buyer, boolean member) {
            return switch (this) {
                case BUYER -> buyer;
                case SELLER -> member;
                case BUYER_OR_SELLER -> buyer || member;
                case ADMIN -> !buyer && !member;
            };
        }
    }

    /** 판정에 필요한 것까지 같이 읽은 행. 상태는 여기서 나와 그대로 판정으로 간다 */
    private record Row(long sellerOrderId, long buyerUserId, long sellerId, String status) {
    }

    private final JdbcClient jdbc;
    private final PermissionEvaluator evaluator;
    private final OrderStatusService statuses;
    private final ReturnRequestService returns;
    private final AuditLog auditLog;

    OrderActionService(JdbcClient jdbc, PermissionEvaluator evaluator, OrderStatusService statuses,
            ReturnRequestService returns, AuditLog auditLog) {

        this.jdbc = jdbc;
        this.evaluator = evaluator;
        this.statuses = statuses;
        this.returns = returns;
        this.auditLog = auditLog;
    }

    /**
     * 관리자가 전이표와 상태 축 밖으로 옮긴다(`16c`, {@code order:force_status}).
     *
     * <p><b>갈 곳은 {@link OrderTransitions#forcible} 이 닫는다</b> — 취소·배송중·배송완료. 곁가지(재고·발송 시각·기한)는
     * {@link OrderStatusService} 가 출발지를 보고 맞춘다. <b>배송중으로 옮긴 묶음은 송장이 비어 있다</b>(`57`) —
     * 송장 없는 직접 배송이 이 동작의 이유 중 하나라 DB 가 일부러 안 막는다(`V104`).
     *
     * <p><b>감사 로그에 결과를 남긴다</b>(`4b`). 이력 행에도 사유가 남지만 그것은 주문의 생애고,
     * 감사 로그는 「누가 이 권한을 썼나」를 셀 자리다 — 권한 하나를 따로 세운 이유가 그것이다({@code V106}).
     *
     * @param to     {@code CANCELLED}·{@code SHIPPING}·{@code DELIVERED}
     * @param reason 필수다. 비면 400 이다 — DB 도 같은 것을 막는다({@code order_status_history_admin_reason_check})
     */
    @Transactional
    public void force(long userId, String sellerOrderNumber, String to, String reason) {
        Row row = find(sellerOrderNumber);

        Target target = Target.of(row.buyerUserId(), row.sellerId()).inStatus(row.status());
        if (!evaluator.decide(userId, "order", "force_status", target).allowed()) {
            throw notFound(sellerOrderNumber);
        }
        if (reason == null || reason.isBlank()) {
            throw new ShopException(ErrorCode.TRANSITION_REASON_REQUIRED);
        }

        Shipment from = Shipment.of(row.status());
        Shipment destination = Arrays.stream(Shipment.values())
                .filter(status -> status.name().equals(to.toUpperCase(Locale.ROOT)))
                .findFirst()
                .orElseThrow(() -> new ShopException(ErrorCode.VALIDATION_FAILED, "그런 배송 상태가 없다: " + to));

        // 강제 표는 상태 서비스가 본다 — 전이표 밖으로 가는 길이 거기 하나라서다(마무리 46차).
        statuses.forceShipment(row.sellerOrderId(), destination, Actor.admin(userId, reason));
        auditLog.record(AuditLog.Kind.OUTCOME, "order.status_forced", userId,
                AuditLog.Target.of("seller_order", row.sellerOrderId()),
                Map.of("from", from.name(), "to", destination.name()));
    }

    /**
     * 이 사람이 이 묶음을 강제로 옮길 수 있는 곳(`16c`). 권한이 없으면 비어 있다.
     *
     * <p>{@link #allowedActions} 와 따로 둔다 — 그쪽은 전이표 안의 화살표만 권하고(관리자에게는 반품 판정만, `Q202`)
     * 강제 전이는 전이표 밖의 이동이다. 강제 전이 버튼은 이 판정 하나로 고른다.
     */
    public List<String> forcibleStatuses(long userId, long buyerUserId, long sellerId, String status) {
        Target target = Target.of(buyerUserId, sellerId).inStatus(status);
        if (!evaluator.allowedActions(userId, "order", Set.of("force_status"), target).contains("force_status")) {
            return List.of();
        }
        return OrderTransitions.forcibleFrom(Shipment.of(status)).stream().map(Enum::name).toList();
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
        run(userId, sellerOrderNumber, action, reason, null);
    }

    /**
     * 반품 사유를 실어 부른다.
     *
     * <p><b>{@link Action#REQUEST_RETURN} 에만 쓴다.</b> 사유가 무엇이냐로 기한과 제한이 갈린다 —
     * 하자 반품은 3개월이고 청약철회 제한이 안 걸린다(`D2` R3, 전자상거래법 제17조제3항).
     *
     * @param returnReason 없으면 단순 변심으로 본다
     */
    @Transactional
    public void run(long userId, String sellerOrderNumber, Action action, String reason,
            OrderStatusService.ReturnReason returnReason) {

        run(userId, sellerOrderNumber, action, reason, returnReason, null);
    }

    /**
     * 반품 판정을 실어 부른다(`43a-2`).
     *
     * <p><b>{@link Action#APPROVE_RETURN}·{@link Action#REJECT_RETURN} 에만 쓴다.</b>
     * 판정과 묶음 이동이 한 트랜잭션이어야 `V63` 의 지연 트리거를 지난다.
     *
     * @param decision 승인이면 재고 복구 여부, 거절이면 사유
     */
    @Transactional
    public void run(long userId, String sellerOrderNumber, Action action, String reason,
            OrderStatusService.ReturnReason returnReason,
            ReturnRequestService.Decision decision) {

        Row row = find(sellerOrderNumber);

        Target target = Target.of(row.buyerUserId(), row.sellerId()).inStatus(row.status());
        if (!evaluator.decide(userId, "order", action.permission(), target).allowed()) {
            throw notFound(sellerOrderNumber);
        }

        statuses.moveShipment(row.sellerOrderId(), action.to(), actorOf(userId, row, reason),
                returnReason, decision);
    }

    /**
     * 돌아온 물건이 들어왔다고 적는다(`43a-2`).
     *
     * <p><b>{@link Action} 이 아니다.</b> 입고는 반품 표 안의 진행이고 묶음을 안 옮긴다 —
     * {@code Action} 은 옮겨 놓을 상태를 들고 있어야 해서 여기 안 들어온다.
     * 그래서 묶음의 {@code allowed_actions} 에 안 실리고 반품 진행의 목록({@link #returnActions})에 실린다(`43a-5`).
     *
     * <p>대신 <b>판정과 행위자 결정은 같은 자리를 지난다</b>. 갈라 두면 이 경로만
     * 스코프를 안 보게 되는 날이 온다.
     *
     * <p><b>입고 시각이 환급 기산점이다</b> — 제18조제2항 1호(`D2` R5).
     */
    @Transactional
    public void receiveReturn(long userId, String sellerOrderNumber, String reason, String inspectionNote) {
        Row row = find(sellerOrderNumber);

        Target target = Target.of(row.buyerUserId(), row.sellerId()).inStatus(row.status());
        if (!evaluator.decide(userId, "order", "receive_return", target).allowed()) {
            throw notFound(sellerOrderNumber);
        }

        returns.receive(row.sellerOrderId(), actorOf(userId, row, reason), inspectionNote);
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
     *
     * @param returnRequest 이 묶음의 반품 진행. 없으면 null — 승인을 권할지가 입고에 걸려 있다
     */
    public List<String> allowedActions(long userId, long buyerUserId, long sellerId, String status,
            ReturnRequestQuery.Progress returnRequest) {
        return allowedActions(userId, memberOf(userId), buyerUserId, sellerId, status, returnRequest);
    }

    /**
     * 소속을 미리 읽어 온 판(`Q202`). 주문 상세처럼 묶음이 여럿이면 부르는 쪽이 {@link #memberOf} 를 한 번 읽어 넘긴다 —
     * 묶음마다 읽으면 묶음 수만큼 질의가 는다.
     *
     * <p><b>누구 몫인지로 거른다</b>({@link Party}). 판정을 통과한 동작 중에서 주문자에게는 주문자 몫을, 소속에게는
     * 셀러 몫을, 둘 다 아닌 사람(관리자)에게는 관리자 몫(반품 판정)만 권한다.
     *
     * <p><b>입고 전에는 승인을 안 권한다</b>(`43a-5`). 판정이 입고를 요구해서({@code RETURN_NOT_RECEIVED}, `V63`)
     * 권하면 누르는 순간 튕기는 버튼이 된다. 거절은 입고 없이도 된다.
     */
    public List<String> allowedActions(long userId, Set<Long> memberOf, long buyerUserId, long sellerId,
            String status, ReturnRequestQuery.Progress returnRequest) {
        Shipment from = Shipment.of(status);
        boolean buyer = userId == buyerUserId;
        boolean member = memberOf.contains(sellerId);
        boolean received = returnRequest != null && returnRequest.received();
        Target target = Target.of(buyerUserId, sellerId).inStatus(status);

        Set<String> permitted = evaluator.allowedActions(userId, "order",
                Arrays.stream(Action.values())
                        .map(Action::permission)
                        .collect(Collectors.toUnmodifiableSet()),
                target);

        return Arrays.stream(Action.values())
                .filter(action -> permitted.contains(action.permission()))
                .filter(action -> OrderTransitions.allows(from, action.to()))
                .filter(action -> action.party.offeredTo(buyer, member))
                .filter(action -> action != Action.APPROVE_RETURN || received)
                .map(Enum::name)
                .toList();
    }

    /**
     * 반품 진행 안에서 이 사람이 할 수 있는 것(`43a-5`). 지금은 입고({@code RECEIVE}) 하나다.
     *
     * <p><b>묶음의 목록과 가른다.</b> 입고는 묶음을 안 옮기고 경로가 {@code /api/returns/{번호}/receive} 라
     * 묶음 목록의 「이름이 곧 경로」 짝에 못 들어간다({@link ReturnController}).
     *
     * <p><b>셀러 몫이다</b> — 물건이 왔는지는 받아 본 쪽이 안다. 관리자는 사유를 달고 입구를 직접 부를 수 있지만
     * 화면이 권하지는 않는다({@link Party} 와 같은 판단).
     */
    public List<String> returnActions(long userId, Set<Long> memberOf, long buyerUserId, long sellerId,
            String status, ReturnRequestQuery.Progress returnRequest) {
        if (returnRequest == null || !returnRequest.receivable() || !memberOf.contains(sellerId)) {
            return List.of();
        }
        Target target = Target.of(buyerUserId, sellerId).inStatus(status);
        return evaluator.allowedActions(userId, "order", Set.of("receive_return"), target).contains("receive_return")
                ? List.of("RECEIVE")
                : List.of();
    }

    /** 이 사람이 속한 셀러들. 버튼을 고를 때 한 번 읽는다 */
    public Set<Long> memberOf(long userId) {
        return jdbc.sql("select seller_id from seller_member where user_id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .set();
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
            return Actor.customer(userId);
        }
        if (isMemberOf(userId, row.sellerId())) {
            return Actor.seller(userId);
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
