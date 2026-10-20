package com.projectshop.shop.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.projectshop.shop.auth.Allowed;
import com.projectshop.shop.auth.PermissionEvaluator.Decision;
import com.projectshop.shop.order.OrderPersonalFields;

/**
 * 열람 증서가 기록한 그룹만 여는지 본다(`Q282a`, 마무리 65차 독립 리뷰).
 *
 * <p>증서는 판정을 쥐고 있어서, 열 때 넘기지 않은 그룹을 물어도 판정이 허용하면 열렸다 — 그 그룹은 기록이 안 됐다.
 */
class PersonalDataAccessTest {

    private static final Decision EVERYTHING = new Decision(true, "시험 — 제한 없음", Allowed.everything());

    @Test
    @DisplayName("열 때 넘기지 않은 그룹을 물으면 예외다")
    void unrecordedGroupThrows() {
        PersonalDataAccess access = new PersonalDataAccess(EVERYTHING, Set.of(OrderPersonalFields.SHIPPING));

        assertThatThrownBy(() -> access.opened(OrderPersonalFields.PAYMENT))
                .as("판정이 허용해도 기록이 안 된 그룹이다")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("payment");
    }

    @Test
    @DisplayName("열 때 넘긴 그룹은 판정대로 답한다")
    void recordedGroupFollowsDecision() {
        PersonalDataAccess allowed = new PersonalDataAccess(EVERYTHING, Set.of(OrderPersonalFields.SHIPPING));
        PersonalDataAccess limited = new PersonalDataAccess(
                new Decision(true, "시험 — 결제만", Allowed.only(Set.of("payment"))), Set.of(OrderPersonalFields.SHIPPING));

        assertThat(allowed.opened(OrderPersonalFields.SHIPPING)).isTrue();
        assertThat(limited.opened(OrderPersonalFields.SHIPPING)).isFalse();
    }
}
