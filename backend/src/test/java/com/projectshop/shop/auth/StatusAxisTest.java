package com.projectshop.shop.auth;

import static com.projectshop.shop.auth.PermissionEvaluator.evaluate;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.projectshop.shop.auth.PermissionEvaluator.Decision;
import com.projectshop.shop.auth.PermissionEvaluator.Rule;
import com.projectshop.shop.auth.PermissionEvaluator.Target;

/**
 * 상태 축이 판정을 어떻게 깎나. 표의 내용이 아니라 <b>축의 동작</b>을 본다.
 *
 * <p>주문의 표가 맞는지는 {@code OrderStatusPolicyTest} 가 본다 — 표는 {@code order} 패키지에 있고
 * 여기는 그 표가 무엇이든 판정이 같은 방식으로 걸리는지만 확인한다.
 */
class StatusAxisTest {

    private static final long ME = 100L;
    private static final long ALPHA = 1L;

    private static final List<Rule> SELLER_CAN =
            List.of(new Rule("seller_owner", null, "seller", "allow", Set.of()));

    /** 표에 걸린 동작이라고 가정한 것 */
    private static final Allowed<String> ONLY_PREPARING = Allowed.only(Set.of("preparing"));

    private static Decision decide(Target target, Allowed<String> statuses) {
        return evaluate(SELLER_CAN, Set.of(ALPHA), ME, target, statuses);
    }

    @Test
    @DisplayName("상태 축이 안 걸린 동작은 상태를 안 실어도 통과한다")
    void unrestrictedActionIgnoresStatus() {
        Decision decision = decide(Target.ofSeller(ALPHA), Allowed.everything());

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    @DisplayName("허용 상태면 통과하고 이유는 이긴 규칙 그대로다")
    void allowedStatusKeepsRuleReason() {
        Decision decision = decide(Target.ofSeller(ALPHA).inStatus("preparing"), ONLY_PREPARING);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.reason()).contains("seller_owner");
    }

    @Test
    @DisplayName("허용 밖의 상태면 권한이 있어도 거부한다")
    void closedStatusDenies() {
        Decision decision = decide(Target.ofSeller(ALPHA).inStatus("delivered"), ONLY_PREPARING);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("delivered");
    }

    /**
     * 빠뜨린 것이 조용히 허용으로 안 떨어져야 한다. 상태를 안 싣는 실수는 새 경로를 만들 때 나오는데,
     * 통과시키면 그 경로만 축이 없는 채로 열린다.
     */
    @Test
    @DisplayName("상태에 걸린 동작인데 상태를 안 실으면 거부한다")
    void missingStatusDenies() {
        Decision decision = decide(Target.ofSeller(ALPHA), ONLY_PREPARING);

        assertThat(decision.allowed())
                .as("상태를 빠뜨린 경로가 축 없이 열리면 축을 만든 뜻이 없다")
                .isFalse();
        assertThat(decision.reason()).contains("안 실려 왔다");
    }

    /**
     * 순서가 결과가 아니라 <b>이유</b>를 바꾼다. 상태를 먼저 보면 권한이 아예 없는 사용자도
     * "상태 때문" 이라는 답을 받고, 그 답으로는 권한 설정을 못 고친다.
     */
    @Test
    @DisplayName("범위 밖 대상은 상태가 아니라 규칙으로 거부된다")
    void ruleDenialWinsOverStatus() {
        Decision decision = decide(Target.ofSeller(999L).inStatus("delivered"), ONLY_PREPARING);

        assertThat(decision.reason())
                .as("권한이 없어서 막힌 것을 상태 탓으로 답하면 권한 설정을 못 고친다")
                .contains("범위 밖");
        assertThat(decision.allowed()).isFalse();
    }

    /**
     * 능력 목록(8a)이 지나는 경로다. 여기서 상태가 걸리면 셀러의 「상태 변경」 권한이
     * 목록에서 통째로 사라져서, 화면이 있지도 않은 권한을 없는 것으로 그린다.
     */
    @Test
    @DisplayName("상태를 안 보는 판정은 축을 안 건다")
    void capabilityPathSkipsStatus() {
        Decision decision = evaluate(SELLER_CAN, Set.of(ALPHA), ME, Target.ofSeller(ALPHA));

        assertThat(decision.allowed())
                .as("여기서 상태가 걸리면 능력 목록에서 권한이 통째로 사라져 화면이 버튼을 안 그린다")
                .isTrue();
    }
}
