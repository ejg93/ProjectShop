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
 * 판정이 묶음을 옮기는 것은 `43a` 의 다음 조각이고, 그것을 여기 두면 순환 의존이 된다.
 */
@Service
public class ReturnRequestService {

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
}
