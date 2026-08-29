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
     * 동작마다 열려 있는 상태.
     *
     * <p><b>동작이 넷인 이유가 이 표에 있다</b>(`V20`). 이 메서드의 인자가 {@code resource} 와
     * {@code action} 뿐이라 <b>역할을 못 본다</b> — 고객과 셀러가 같은 동작을 쓰면 허용 상태가
     * 한 집합이 되고, 한쪽에 필요한 상태를 열면 다른 쪽에도 열린다.
     *
     * <p>{@code update_status} 는 셀러 몫이다. <b>{@code delivered} 를 지나면 닫힌다</b> —
     * 배송완료 뒤의 전이는 소비자가 일으키는 사건이고, 셀러가 그걸 밀 수 있으면
     * 청약철회 기산점을 조작할 수 있다(`D7`).
     *
     * <p><b>{@code return_requested} 를 뺐다</b>(`43a-2`). 그전에는 「반품 완료는 물건을 받아 본
     * 셀러가 한다」는 이유로 열어 뒀는데, 그 상태가 열려 있으면 셀러가 {@code DELIVER} 로
     * {@code return_requested → delivered} 를 민다 — <b>그것이 반품 거절이고 `D7` 은 관리자만이라고
     * 적어 뒀다.</b> 이 표가 역할을 못 보므로 한 동작으로는 「승인은 셀러, 거절은 관리자」를 못 가른다.
     *
     * <p>그래서 반품 셋을 동작으로 갈랐다({@code receive_return}·{@code approve_return}·
     * {@code reject_return}, `V64`). 판정 둘이 관리자인 근거는 제17조제5항이 훼손 책임의
     * <b>입증을 우리에게</b> 지운 것이다(`D2` R37) — 셀러의 소견이 곧 결론이 되면
     * 입증책임이 우리에게 있다는 사실이 데이터에서 사라진다.
     *
     * <p>{@code confirm} 은 {@code delivered} 에서만 열린다.
     * {@code cancel} 은 {@code preparing} 에서만 — 물건이 떠난 뒤의 되돌림은 취소가 아니라
     * 반품이다(`glossary.md`).
     *
     * <p><b>{@code request_return} 은 {@code confirmed} 에서도 열린다</b>(`43a-3`).
     * {@link OrderTransitions} 가 그 화살표를 냈는데 이 표가 {@code delivered} 만 들고 있어서
     * <b>길은 났고 문이 잠겨 있었다.</b> 제17조제3항의 하자 반품은 공급받은 날부터 3개월이라
     * 구매확정으로 안 끝난다 — 확정은 우리가 정한 기한이고 그 조항은 법이 준 기한이다(`D2` R3).
     *
     * <p><b>여기서 사유를 안 가른다.</b> 이 메서드가 {@code resource}·{@code action} 만 받아서
     * 무엇으로 접수하는지를 못 본다. 확정 뒤 단순 변심을 막는 것은
     * {@code OrderStatusService.requireWithdrawable} 이고, 그쪽이 배송완료 때 박제한
     * {@code withdrawal_expire_at} 을 읽는다. <b>동작을 갈라 상태 축으로 막지 않은 이유가 있다</b> —
     * 사는 사람이 7일 안에 손으로 확정할 수 있고, 그때 제17조제1항의 권리는 아직 살아 있다.
     * 축으로 닫으면 우리가 만든 장치로 법이 준 기간을 자른다.
     *
     * <p>{@code cancelled}·{@code returned} 는 어느 동작에도 없다. 전이표가 이미 막지만 권한도
     * 같이 닫는다 — 그래야 시도가 감사 로그에 남는다. 도메인 예외로만 막으면 누가 끝난 주문을
     * 계속 두드리는지 세는 자리가 없다.
     */
    private static final Map<String, Allowed<String>> BY_ACTION = Map.of(
            "update_status", Allowed.only(Set.of(
                    Shipment.PREPARING.code(),
                    Shipment.SHIPPING.code())),

            "cancel", Allowed.only(Set.of(Shipment.PREPARING.code())),
            "confirm", Allowed.only(Set.of(Shipment.DELIVERED.code())),
            "request_return", Allowed.only(Set.of(
                    Shipment.DELIVERED.code(),
                    Shipment.CONFIRMED.code())),

            "receive_return", Allowed.only(Set.of(Shipment.RETURN_REQUESTED.code())),
            "approve_return", Allowed.only(Set.of(Shipment.RETURN_REQUESTED.code())),
            "reject_return", Allowed.only(Set.of(Shipment.RETURN_REQUESTED.code())));

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
        return BY_ACTION.getOrDefault(action, Allowed.everything());
    }
}
