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
    @DisplayName("상태를 옮기는 동작은 배송 전 구간과 반품 접수에서만 열린다")
    void updateStatusOpenBeforeDelivery() {
        assertThat(updateStatus().covers("preparing")).isTrue();
        assertThat(updateStatus().covers("shipping")).isTrue();
        assertThat(updateStatus().covers("return_requested")).isTrue();
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
