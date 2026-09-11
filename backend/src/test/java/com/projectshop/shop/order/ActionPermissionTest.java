package com.projectshop.shop.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.order.OrderActionService.Action;

/**
 * 주문 동작이 넘기는 권한 이름이 {@code permission} 표에 실제로 있는지 대조한다(`43a-21`).
 *
 * <p><b>닫힌 목록이 {@code check} 가 아니라 표의 행이다.</b> 그래서 {@code EnumConstraintTest} 가
 * 이 열거형을 {@code EXEMPT} 로 넘긴다 — 뽑는 자리가 제약 정의가 아니라 데이터라서 같은 표에 못 담는다.
 * {@code FieldGroupTest} 가 {@code permission_field_group} 에 대해 세워 둔 것과 같은 모양이다.
 *
 * <p><b>어긋나면 조용하다.</b> 권한 행을 지우거나 이름을 고치면
 * {@link com.projectshop.shop.auth.PermissionEvaluator} 가 그 이름을 가진 규칙을 못 찾고,
 * 그 동작은 <b>언제나 거부</b>가 된다. 컴파일도 테스트도 초록이고 오류도 안 난다 —
 * 사용자에게는 「권한이 없다」로 보여서 <b>버그가 아니라 정책처럼 읽힌다.</b>
 *
 * <p><b>한쪽만 본다.</b> 표에만 있는 권한은 정상이다 — {@code create}·{@code read}·{@code refund}
 * 처럼 이 열거형이 아닌 자리에서 쓰는 것이 있다. 코드가 부르는 이름이 표에 없는 것만 잡는다.
 */
@DisplayName("주문 동작 권한")
class ActionPermissionTest extends PostgresTestBase {

    @Autowired
    private JdbcClient jdbc;

    @Test
    @DisplayName("동작이 넘기는 권한이 전부 표에 있다")
    void everyActionPermissionIsATableRow() {
        List<String> inTable = jdbc.sql("select action from permission where resource = 'order'")
                .query(String.class)
                .list();

        assertThat(Arrays.stream(Action.values()).map(Action::permission).distinct().toList())
                .as("표에 없는 이름을 넘기면 판정이 규칙을 못 찾아 그 동작이 언제나 거부된다")
                .isSubsetOf(inTable);
    }
}
