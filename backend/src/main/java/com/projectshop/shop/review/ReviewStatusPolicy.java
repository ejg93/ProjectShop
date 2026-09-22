package com.projectshop.shop.review;

import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.projectshop.shop.auth.Allowed;
import com.projectshop.shop.auth.StatusPolicy;

/**
 * 후기가 어느 상태에서 열리나. 상태 축의 후기 쪽 표다(`47`).
 *
 * <p><b>보는 상태는 후기의 것이 아니라 주문 줄의 것이다.</b> 후기에는 상태가 없다 —
 * 있는 것은 수명(`deleted_at`)뿐이고 그건 축이 다르다(`D4`). 그래서 부르는 쪽이
 * {@code Target.inStatus} 에 <b>그 주문 줄의 배송 상태</b>를 실어 보낸다.
 *
 * <p><b>DB 도 같은 것을 막는다</b>({@code check_review_target}, `V84`). 두 벌인 이유는
 * 걸리는 자리가 달라서다 — 이 표는 판정을 지나는 입구에만 걸리고, 배치나 시드가 표를
 * 직접 만지면 안 지난다. <b>안 받은 물건의 후기가 제일 비싼 거짓이라</b>(다른 소비자가
 * 그것을 근거로 산다) 아래쪽에도 내렸다(`D23` 축 2).
 *
 * <p>구현을 {@code auth} 가 아니라 여기 두는 것은 {@code OrderStatusPolicy} 와 같은 이유다 —
 * 반대로 하면 {@code auth → review} 의존이 생긴다.
 */
@Component
class ReviewStatusPolicy implements StatusPolicy {

    /**
     * <b>{@code create} 하나만 상태를 본다.</b>
     *
     * <p><b>{@code returned} 를 뺐다.</b> 반품으로 끝난 줄은 물건을 안 가진 사람의 후기라
     * 「써 본 사람의 말」이 아니다. {@code cancelled} 도 같다 — 받은 적이 없다.
     *
     * <p><b>{@code confirmed} 를 넣는다.</b> 구매확정은 우리가 정한 기한이 지난 것이지
     * 후기를 닫을 이유가 아니다 — 오히려 그때가 제일 할 말이 많은 시점이다.
     *
     * <p><b>{@code update}·{@code delete} 는 상태를 안 본다.</b> 넣었다가 뺐다 —
     * 배송 상태는 후기가 달린 뒤에도 움직여서({@code delivered} → {@code return_requested}),
     * 상태로 막으면 <b>반품을 넣는 순간 자기 글을 못 고치고 못 지운다.</b> 그럴 근거가 없다.
     * 자기 것만 건드리는 것은 {@code own} 스코프가 이미 든다.
     *
     * <p>공개한 운영정책도 「직접 삭제할 수 있습니다」로 적혀 있다(`V85`) —
     * 법이 요구해서 공개한 문서라 그것과 코드가 갈리면 그냥 오타가 아니다(`D2` `R27`).
     *
     * <p>{@code read} 는 표에 없다. 후기는 공개 글이라 상태를 안 본다 —
     * {@code Allowed.everything()} 으로 떨어진다.
     */
    private static final Map<String, Allowed<String>> BY_ACTION = Map.of(
            "create", Allowed.only(Set.of("delivered", "confirmed")));

    @Override
    public Allowed<String> allowedStatuses(String resource, String action) {
        if (!"review".equals(resource)) {
            return Allowed.everything();
        }
        return BY_ACTION.getOrDefault(action, Allowed.everything());
    }
}
