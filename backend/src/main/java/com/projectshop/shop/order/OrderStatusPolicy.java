package com.projectshop.shop.order;

import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.projectshop.shop.auth.Allowed;
import com.projectshop.shop.auth.StatusPolicy;
import com.projectshop.shop.order.OrderTransitions.Shipment;

/**
 * 주문이 어느 상태일 때 어느 동작이 열리나. 상태 축의 주문 쪽 표다(`ADR 0009` 가 코드에 두라고 정했다).
 *
 * <p><b>{@link OrderTransitions} 와 묻는 것이 다르다.</b> 저쪽은 "이 순서로 갈 수 있나" 고
 * 여기는 "지금 이 상태에서 사람이 손을 댈 수 있나" 다. 둘을 한 표로 합치면
 * 전이 하나를 막으려 할 때 <b>도메인 규칙을 고쳐야 하는지 권한을 고쳐야 하는지가 안 갈린다.</b>
 *
 * <p>여기 있는 상태는 배송 상태다. 결제 상태는 안 본다 — 미결제 건을 셀러가 손대는 것은
 * "남의 것을 본다" 가 아니라 "업무가 잘못 돈다" 라서 도메인 규칙 쪽이고, 셀러에게 안 보이게 하는 것은
 * 청크 11c 가 뷰로 막는다(`D6` 「이건 축인가 도메인 규칙인가」).
 */
@Component
class OrderStatusPolicy implements StatusPolicy {

    /**
     * 상태를 옮기는 동작이 열려 있는 상태.
     *
     * <p><b>{@code delivered} 를 지나면 닫힌다.</b> 배송완료 뒤의 전이(구매확정·반품접수)는
     * 소비자가 일으키는 사건이고, 셀러가 그걸 밀 수 있으면 청약철회 기산점을 조작할 수 있다(`D7`).
     *
     * <p>{@code return_requested} 는 열어 둔다 — 반품 완료 처리는 물건을 받아 본 셀러가 한다.
     *
     * <p>종착 상태({@code confirmed}·{@code cancelled}·{@code returned})는 전이표가 이미 막지만
     * 권한도 같이 닫는다. 그래야 시도가 감사 로그에 남는다 — 도메인 예외로만 막으면
     * 누가 종착 주문을 계속 두드리는지 세는 자리가 없다.
     */
    private static final Map<String, Set<String>> BY_ACTION = Map.of(
            "update_status", Set.of(
                    Shipment.PREPARING.code(),
                    Shipment.SHIPPING.code(),
                    Shipment.RETURN_REQUESTED.code()));

    /**
     * <b>관리자도 같이 걸린다.</b> 축은 규칙 위에 있어서 스코프로 비켜 갈 수 없다.
     * 운영이 종착 주문을 강제로 옮겨야 할 일이 생기면 이 표에 예외를 파지 말고
     * 별도 동작(`order:force_status`)을 만든다 — 그래야 그 권한을 누구에게 줬는지가 데이터로 남는다.
     */
    @Override
    public Allowed<String> allowedStatuses(String resource, String action) {
        if (!"order".equals(resource)) {
            return Allowed.everything();
        }
        Set<String> statuses = BY_ACTION.get(action);
        return statuses == null ? Allowed.everything() : Allowed.only(statuses);
    }
}
