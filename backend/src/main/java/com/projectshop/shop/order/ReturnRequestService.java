package com.projectshop.shop.order;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.order.OrderStatusService.Actor;
import com.projectshop.shop.order.OrderStatusService.ReturnReason;

/**
 * 반품 하나를 연다(청크 `43a`).
 *
 * <p><b>`43` 이 표를 세우고 넣는 코드를 안 만들어서 반품이 통째로 막혀 있었다.</b>
 * 묶음만 {@code return_requested} 로 가면 `V63` 의 지연 트리거가 커밋에서 거부한다 —
 * 걸려 있는데 한 번도 안 돌던 트리거가 데모 시드 기동에서 처음 돌면서 드러났다.
 *
 * <h2>전이 서비스가 부른다</h2>
 *
 * <p>입구가 아니라 {@link OrderStatusService} 안에서 부른다. 입구마다 부르면
 * <b>새 입구가 생겼을 때 빠뜨리고</b>, 그렇게 빠뜨린 경로는 커밋에서야 터진다(`D23` 축 2).
 *
 * <p><b>{@code OrderStatusService} 를 거꾸로 안 부른다.</b> 이 클래스는 반품 표에만 쓴다 —
 * 묶음을 옮기는 것은 {@link OrderStatusService} 고, 그것을 여기 두면 순환 의존이 된다.
 */
@Service
public class ReturnRequestService {

    /**
     * 판정과 같이 실려 오는 값(`43a-2`).
     *
     * <p><b>봉인 인터페이스로 갈랐다.</b> 승인에만 있는 값과 거절에만 있는 값이 다른데
     * 한 record 에 담으면 반대쪽 칸이 늘 비고, <b>빈 값에 뜻을 싣게 된다</b>(`D23`).
     *
     * <p><b>부담 주체는 여기 없다.</b> 세 경우가 전부 결정적이라 입력이 아니라 계산이다 —
     * {@link ReturnShippingFeeBearer#of(ReturnStatus, OrderStatusService.ReturnReason)} 를 본다. 칸을 두면 `V63` 의 {@code check} 가 막긴 해도
     * <b>고를 수는 있게 되고</b>, 「계약에 칸이 없다」가 강제 지점 1순위다.
     */
    public sealed interface Decision {

        /**
         * 반품으로 인정한다.
         *
         * @param restock 돌아온 물건을 다시 팔 수 있나. <b>검수 결과라 계산이 안 된다</b> —
         *                같은 승인이라도 단순 변심으로 돌아온 새 물건과 파손된 물건이 다르다(`V63`)
         */
        record Approve(boolean restock) implements Decision {
        }

        /**
         * 인정하지 않는다. 물건이 소비자에게 돌아간다.
         *
         * @param code   왜 인정하지 않았나 — 닫힌 목록(`Q212`). {@code DAMAGED} 는 검수 소견이 있어야 고른다
         * @param reason 거절 사유 글. <b>없으면 커밋에서 거부된다</b> —
         *               `V63` 의 {@code return_requires_rejection_reason} 이 지연으로 본다
         */
        record Reject(RejectionReason code, String reason) implements Decision {
        }
    }

    /**
     * 거절 사유(`Q212`, `V115`). <b>닫힌 목록이다</b> — 법이 거절을 인정하는 자리가 정해져 있고(`D23` 「법이 인정한 목록은 닫는다」),
     * 자유 글이면 시스템이 훼손 때문인지 모른다. 저장값은 소문자다(`D5`).
     */
    public enum RejectionReason {

        /**
         * 소비자 책임으로 물건이 훼손됐다(전자상거래법 제17조제2항제1호). <b>검수 소견이 있어야 고른다</b> —
         * 훼손 책임의 입증은 통신판매업자가 한다(제17조제5항, `D2` R37). {@code return_request_damaged_inspected_check} 가 막는다
         */
        DAMAGED("damaged"),

        /** 청약철회 기간이 지났다(제17조제1항·제3항). 물건을 받기 전에도 정당하다 */
        PERIOD_EXPIRED("period_expired"),

        /** 훼손 밖의 청약철회 제한 사유(제17조제2항 각 호). 물건을 받기 전에도 정당하다 */
        RESTRICTED("restricted"),

        /** 그 밖. 사유 글이 무엇인지 말한다 */
        OTHER("other");

        private final String code;

        RejectionReason(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private final JdbcClient jdbc;

    ReturnRequestService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 접수 행과 반품 항목을 만든다.
     *
     * <p><b>묶음 통째로 담는다.</b> 취소·반품의 최소 단위가 셀러 묶음이라(`D7`) 접수가
     * 항목을 안 받는다 — 부분 반품은 표가 항목 단위로 세고 있으니(`return_request_item`)
     * 입구가 항목을 받기 시작할 때 여기만 고치면 된다.
     *
     * <p><b>사유는 이 행이 든다.</b> 묶음의 {@code return_reason} 은 거절되면 비워지는데
     * 「무슨 사유로 접수됐다가 거절됐나」는 남아야 한다(`V63`).
     *
     * @param actor 접수한 사람. 반품은 소비자가 여는 것이라 사람 없이는 안 열린다
     */
    void open(long sellerOrderId, Actor actor, ReturnReason reason) {
        if (actor.userId() == null) {
            throw new ShopException(ErrorCode.ORDER_TRANSITION_NOT_ALLOWED,
                    "반품 접수는 사람이 부른다. 행위자가 %s 다".formatted(actor.type()));
        }

        long returnRequestId = jdbc.sql("""
                        insert into return_request (seller_order_id, reason_code,
                                                    requested_by_user_id)
                        values (:sellerOrderId, :reasonCode, :userId)
                        returning return_request_id
                        """)
                .param("sellerOrderId", sellerOrderId)
                .param("reasonCode", reason.code())
                .param("userId", actor.userId())
                .query(Long.class)
                .single();

        // 주문 항목에서 그대로 옮긴다. 수량을 요청에서 받으면 산 것보다 많이 반품하는 값이
        // 들어올 수 있고, 그것을 막는 제약이 표에 없다.
        jdbc.sql("""
                        insert into return_request_item (return_request_id, order_item_id, quantity)
                        select :returnRequestId, oi.order_item_id, oi.quantity
                          from order_item oi
                         where oi.seller_order_id = :sellerOrderId
                        """)
                .param("returnRequestId", returnRequestId)
                .param("sellerOrderId", sellerOrderId)
                .update();
    }

    /**
     * 물건이 들어왔다고 적는다(`43a-2`).
     *
     * <p><b>묶음은 안 옮긴다.</b> 입고는 반품 표 안의 진행이고 묶음은 「반품 중인가」에만
     * 답한다(`D7`). 그래서 이것만 전이가 아니라 {@link ReturnController} 가 직접 부른다.
     *
     * <p><b>이 시각이 환급 기산점이다</b> — 제18조제2항 1호가 「재화등을 <b>반환받은 날</b>」이라
     * 정했다(`D2` R5). 승인이 입고를 요구하는 것도 같은 이유고, `V63` 의
     * {@code return_request_timeline_check} 가 그것을 막고 있다.
     *
     * <p><b>소견을 같이 적으면 한 번에 검수까지 간다</b>(`43a-5`). 받아 본 사람이 곧 검수한 사람이라
     * {@code inspected_at}·{@code inspected_by_user_id} 가 입고와 같은 시각·같은 사람이다. 소견은 제17조제5항의
     * 훼손 책임을 입증하는 근거라(`D2` R37) 글로 남긴다. 소견 없는 입고는 {@code received} 에 멈추고,
     * 판정은 어느 쪽이든 {@code received_at} 만 본다.
     *
     * @param actor          입고를 적은 사람. 받아 본 셀러다
     * @param inspectionNote 검수 소견. 비었으면 입고만 적는다
     */
    void receive(long sellerOrderId, Actor actor, String inspectionNote) {
        actorMustBePerson(actor, "입고");

        long returnRequestId = openReturnIdOf(sellerOrderId);
        boolean inspected = inspectionNote != null && !inspectionNote.isBlank();

        int moved = jdbc.sql("""
                        update return_request
                           set status               = :to,
                               received_at          = now(),
                               inspected_at         = case when :inspected then now() end,
                               inspected_by_user_id = case when :inspected then cast(:userId as bigint) end
                         where return_request_id = :id
                           and status in (:requested, :pickedUp)
                        """)
                // **열거형이 값을 댄다**(`43a-18`). 리터럴로 두면 타입을 고쳐도 이 쓸이
                // 옛 글자를 물고 **조용히 아무도 안 걸린다**.
                .param("to", (inspected ? ReturnStatus.INSPECTED : ReturnStatus.RECEIVED).code())
                .param("inspected", inspected)
                .param("userId", actor.userId())
                .param("requested", ReturnStatus.REQUESTED.code())
                .param("pickedUp", ReturnStatus.PICKED_UP.code())
                .param("id", returnRequestId)
                .update();

        if (moved == 0) {
            // 이미 입고됐거나 검수까지 갔다. 대상 행의 현재 상태와의 충돌이라 409 다(`D5`).
            throw new ShopException(ErrorCode.ORDER_TRANSITION_NOT_ALLOWED,
                    "이미 입고된 반품이다 (return_request_id=%d)".formatted(returnRequestId));
        }

        if (inspected) {
            jdbc.sql("""
                            insert into return_note (return_request_id, inspection_note)
                            values (:id, :note)
                            on conflict (return_request_id)
                                do update set inspection_note = excluded.inspection_note
                            """)
                    .param("id", returnRequestId)
                    .param("note", inspectionNote)
                    .update();
        }
    }

    /**
     * 반품을 판정한다(`43a-2`).
     *
     * <p><b>{@link OrderStatusService} 가 부른다.</b> 묶음 이동과 같은 트랜잭션이어야 해서다 —
     * 갈라 두면 `V63` 의 지연 트리거가 커밋에서 둘의 어긋남을 잡고, 그때는 이 요청이
     * 무엇을 하려던 것인지가 오류에 안 남는다.
     *
     * <p><b>승인은 입고를 요구한다.</b> 여기서 미리 보는 이유는 제약이 지연이라
     * 커밋에서야 터지고 그 예외는 500 으로 나가서다 — 막는 것은 제약이고 이건 말을 붙이는 자리다.
     *
     * @return 승인이고 다시 팔 수 있으면 참. 재고를 되돌릴지는 부른 쪽이 정한다
     */
    boolean decide(long sellerOrderId, Actor actor, Decision decision) {
        actorMustBePerson(actor, "반품 판정");

        long returnRequestId = openReturnIdOf(sellerOrderId);
        String reasonCode = reasonCodeOf(returnRequestId);

        if (decision instanceof Decision.Reject reject) {
            if (reject.code() == RejectionReason.DAMAGED) {
                requireInspected(returnRequestId);
            }
            writeDecisionReason(returnRequestId, reject.reason());
            close(returnRequestId, actor, ReturnStatus.REJECTED, reasonCode, null, reject.code());
            return false;
        }

        Decision.Approve approve = (Decision.Approve) decision;
        requireReceived(returnRequestId);
        close(returnRequestId, actor, ReturnStatus.APPROVED, reasonCode, approve.restock(), null);
        return approve.restock();
    }

    /**
     * 판정을 적는다. <b>부담 주체를 인자로 안 받는다</b>(`43a-18`) —
     * {@link ReturnShippingFeeBearer#of} 가 판정과 사유에서 내므로 <b>고를 자리가 없다.</b>
     * 법이 정하는 값이라 부르는 쪽이 넘기게 두면 그 자리가 선택지가 된다(`D2` R36).
     *
     * <p><b>거절 사유 코드를 상태와 한 문장에 쓴다</b> — `V115` 의 {@code return_request_rejection_code_presence_check} 가
     * 「거절이면 코드가 있고 아니면 없다」를 즉시 본다. 나눠 쓰면 첫 문장에서 걸린다.
     */
    private void close(long returnRequestId, Actor actor, ReturnStatus status, String reasonCode,
            Boolean restock, RejectionReason rejection) {

        ReturnShippingFeeBearer bearer =
                ReturnShippingFeeBearer.of(status, OrderStatusService.ReturnReason.of(reasonCode));

        jdbc.sql("""
                        update return_request
                           set status                     = :status,
                               decided_at                 = now(),
                               decided_by_user_id         = :userId,
                               return_shipping_fee_bearer = :bearer,
                               restock                    = :restock,
                               rejection_reason_code      = :rejection
                         where return_request_id = :id
                        """)
                .param("status", status.code())
                .param("userId", actor.userId())
                .param("bearer", bearer.code())
                .param("restock", restock)
                .param("rejection", rejection == null ? null : rejection.code())
                .param("id", returnRequestId)
                .update();
    }

    /**
     * 거절 사유를 남긴다. <b>판정보다 먼저 쓴다</b> —
     * `V63` 의 트리거는 지연이라 순서를 안 따지지만, 사람이 읽을 때 순서가 뜻을 만든다.
     */
    private void writeDecisionReason(long returnRequestId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ShopException(ErrorCode.RETURN_DECISION_REASON_REQUIRED);
        }

        jdbc.sql("""
                        insert into return_note (return_request_id, decision_reason)
                        values (:id, :reason)
                        on conflict (return_request_id)
                            do update set decision_reason = excluded.decision_reason
                        """)
                .param("id", returnRequestId)
                .param("reason", reason)
                .update();
    }

    /**
     * 훼손 거절은 검수를 거쳐야 한다(`Q212`). <b>막는 것은 `V115` 의 제약이다</b> — 이것은 말을 붙이는 자리다.
     * 422 인 이유: 소견 없이 입고된 반품은 뒤에 검수할 길이 없어(입고는 {@code requested}·{@code picked_up} 에서만)
     * 기다려도 풀리지 않는 조합이다. {@code RETURN_NOT_RECEIVED}(409)는 입고되면 풀리는 충돌이라 다르다.
     */
    private void requireInspected(long returnRequestId) {
        boolean inspected = jdbc.sql("""
                        select inspected_at is not null from return_request
                         where return_request_id = :id
                        """)
                .param("id", returnRequestId)
                .query(Boolean.class)
                .single();

        if (!inspected) {
            throw new ShopException(ErrorCode.RETURN_DAMAGED_NEEDS_INSPECTION);
        }
    }

    private void requireReceived(long returnRequestId) {
        boolean received = jdbc.sql("""
                        select received_at is not null from return_request
                         where return_request_id = :id
                        """)
                .param("id", returnRequestId)
                .query(Boolean.class)
                .single();

        if (!received) {
            throw new ShopException(ErrorCode.RETURN_NOT_RECEIVED);
        }
    }

    /**
     * 이 묶음에 열려 있는 반품. <b>하나뿐인 것을 인덱스가 보장한다</b>
     * ({@code return_request_open_idx}, `V63`).
     */
    private long openReturnIdOf(long sellerOrderId) {
        return jdbc.sql("""
                        select return_request_id from return_request
                         where seller_order_id = :sellerOrderId
                           and status not in (:approved, :rejected)
                        """)
                .param("sellerOrderId", sellerOrderId)
                .param("approved", ReturnStatus.APPROVED.code())
                .param("rejected", ReturnStatus.REJECTED.code())
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new ShopException(ErrorCode.ORDER_TRANSITION_NOT_ALLOWED,
                        "열린 반품이 없다 (seller_order_id=%d)".formatted(sellerOrderId)));
    }

    private String reasonCodeOf(long returnRequestId) {
        return jdbc.sql("select reason_code from return_request where return_request_id = :id")
                .param("id", returnRequestId)
                .query(String.class)
                .single();
    }

    /** 반품은 사람이 움직인다. 배치가 판정하면 「누가 인정했나」가 없어진다 */
    private static void actorMustBePerson(Actor actor, String what) {
        if (actor.userId() == null) {
            throw new ShopException(ErrorCode.ORDER_TRANSITION_NOT_ALLOWED,
                    "%s 는 사람이 부른다. 행위자가 %s 다".formatted(what, actor.type()));
        }
    }
}
