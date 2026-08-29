package com.projectshop.shop.order;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.projectshop.shop.auth.Allowed;

/**
 * 주문의 상태×동작표. <b>무엇이 닫혀 있는지</b>를 고정한다.
 *
 * <p>축이 어떻게 판정을 깎는지는 {@code StatusAxisTest} 가 본다.
 */
class OrderStatusPolicyTest {

    private final OrderStatusPolicy policy = new OrderStatusPolicy();

    private Allowed<String> updateStatus() {
        return policy.allowedStatuses("order", "update_status");
    }

    @Test
    @DisplayName("상태를 옮기는 동작은 배송 전 구간에서만 열린다")
    void updateStatusOpenBeforeDelivery() {
        assertThat(updateStatus().covers("preparing")).isTrue();
        assertThat(updateStatus().covers("shipping")).isTrue();
    }

    /**
     * <b>`43a-2` 가 뺐다.</b> 열려 있으면 셀러가 {@code DELIVER} 로
     * {@code return_requested → delivered} 를 미는데 그것이 반품 거절이고,
     * `D7` 은 그 전이를 관리자만이라고 정했다 — 셀러가 부르면 배송완료를 되돌리는 셈이다.
     *
     * <p>이 표가 역할을 못 보므로 「승인은 셀러, 거절은 관리자」를 한 동작으로는 못 가른다.
     */
    @Test
    @DisplayName("반품 접수 상태는 update_status 에 없다")
    void updateStatusClosedOnReturn() {
        assertThat(updateStatus().covers("return_requested"))
                .as("열려 있으면 DELIVER 가 곧 반품 거절이 된다")
                .isFalse();
    }

    /** 반품 셋만 그 상태에서 열린다. 판정 둘은 권한이 관리자에게만 있다(`V64`) */
    @Test
    @DisplayName("반품 동작 셋은 접수 상태에서만 열린다")
    void returnActionsOpenOnlyWhenRequested() {
        for (String action : new String[] {"receive_return", "approve_return", "reject_return"}) {
            Allowed<String> allowed = policy.allowedStatuses("order", action);

            assertThat(allowed.covers("return_requested")).as(action).isTrue();
            assertThat(allowed.covers("delivered")).as(action).isFalse();
            assertThat(allowed.covers("returned")).as(action).isFalse();
        }
    }

    /**
     * 배송완료를 지나면 셀러가 못 민다. 밀 수 있으면 청약철회 기산점을 옮길 수 있고,
     * 그건 소비자의 기간을 줄이는 일이다(`D7`).
     */
    @Test
    @DisplayName("배송완료부터는 닫힌다")
    void updateStatusClosedAfterDelivery() {
        assertThat(updateStatus().covers("delivered"))
                .as("셀러가 배송완료를 밀 수 있으면 청약철회 기산점이 옮겨진다")
                .isFalse();
        assertThat(updateStatus().covers("confirmed")).isFalse();
        assertThat(updateStatus().covers("cancelled")).isFalse();
        assertThat(updateStatus().covers("returned")).isFalse();
    }

    /**
     * 전자상거래법 제17조제3항(`D2` R3). <b>공급받은 날부터 3개월</b>이라 구매확정으로 안 끝난다 —
     * 확정은 우리가 정한 기한이고 그 조항은 법이 준 기한이다.
     *
     * <p>`43` 이 전이표에 화살표를 냈는데 이 표가 {@code delivered} 만 들고 있어서
     * <b>길은 났고 문이 잠겨 있었다</b>(`43a-3`).
     */
    @Test
    @DisplayName("반품 접수는 구매확정 뒤에도 열린다")
    void requestReturnOpenAfterConfirm() {
        Allowed<String> allowed = policy.allowedStatuses("order", "request_return");

        assertThat(allowed.covers("delivered")).isTrue();
        assertThat(allowed.covers("confirmed"))
                .as("제17조제3항의 3개월은 구매확정으로 안 끝난다")
                .isTrue();

        assertThat(allowed.covers("returned")).isFalse();
        assertThat(allowed.covers("cancelled")).isFalse();
        assertThat(allowed.covers("preparing")).isFalse();
    }

    /**
     * 확정 뒤에 열리는 것은 반품뿐이다. {@code confirm} 까지 같이 열면 확정된 것을 또 확정한다.
     */
    @Test
    @DisplayName("구매확정은 확정된 것에 다시 안 열린다")
    void confirmClosedAfterConfirm() {
        Allowed<String> allowed = policy.allowedStatuses("order", "confirm");

        assertThat(allowed.covers("delivered")).isTrue();
        assertThat(allowed.covers("confirmed")).isFalse();
    }

    @Test
    @DisplayName("조회는 상태 축이 안 걸린다")
    void readIsUnrestricted() {
        assertThat(policy.allowedStatuses("order", "read").restricted()).isFalse();
    }

    /**
     * 다른 자원에 답하면 그 자원의 표가 두 벌이 된다. 이 정책은 주문만 안다.
     */
    @Test
    @DisplayName("주문이 아닌 자원에는 아무 제한도 안 건다")
    void otherResourcesUnrestricted() {
        assertThat(policy.allowedStatuses("product", "update").restricted()).isFalse();
        assertThat(policy.allowedStatuses("user", "read").restricted()).isFalse();
    }
}
