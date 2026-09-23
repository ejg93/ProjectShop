package com.projectshop.shop.order;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectshop.shop.order.OrderActionService.Action;

/**
 * 송장과 함께 발송한다(`57`).
 *
 * <p><b>전이와 송장이 한 트랜잭션이다.</b> 전이는 {@link OrderActionService} 가 판정·전이표·이력까지 지고, 이 클래스는 그
 * 위에 송장만 얹는다 — 전이 서비스에 송장을 넣으면 강제 전이(`16c`)처럼 송장이 없는 전이가 그 인자를 억지로 받는다.
 * 송장을 따로 적으면 「발송했는데 송장이 없다」가 두 호출 사이에서 생기므로 같은 트랜잭션에 묶는다.
 *
 * <p><b>하이픈을 여기서 뗀다.</b> 셀러가 택배사 화면의 번호를 그대로 붙여 넣으면 하이픈이 섞여 오고, 저장값은 숫자만이다
 * ({@code seller_order_tracking_no_check}).
 */
@Service
public class ShipmentTrackingService {

    private final OrderActionService actions;
    private final JdbcClient jdbc;

    ShipmentTrackingService(OrderActionService actions, JdbcClient jdbc) {
        this.actions = actions;
        this.jdbc = jdbc;
    }

    @Transactional
    public void ship(long userId, String sellerOrderNumber, Carrier carrier, String trackingNo) {
        actions.run(userId, sellerOrderNumber, Action.SHIP, null);

        jdbc.sql("""
                        update seller_order
                           set carrier_code = :carrier, tracking_no = :trackingNo
                         where seller_order_number = :number
                        """)
                .param("carrier", carrier.code())
                .param("trackingNo", trackingNo.replace("-", "").strip())
                .param("number", sellerOrderNumber)
                .update();
    }
}
