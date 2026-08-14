package com.projectshop.shop.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.me.UserFields;
import com.projectshop.shop.order.OrderFields;

/**
 * 필드 그룹 목록이 표와 코드 두 군데에 있다. <b>어긋나는 것을 여기서 잡는다</b>(`D23`).
 *
 * <p>다른 열거값 대조(`ProductStatusTest`)와 <b>출처가 다르다</b> — 저쪽은 `check` 제약이고
 * 이쪽은 `permission_field_group` 표다. 판정 엔진이 규칙을 그대로 읽어야 해서 표에 있다(`ADR 0003`).
 *
 * <p>어긋나면 조용하다. <b>코드에만 있으면 {@code canSee} 가 언제나 거짓</b>이라 그 필드가
 * 응답에서 빠지고, 오류도 로그도 안 남는다. <b>표에만 있으면 아무도 안 묻는 그룹</b>이라
 * 권한을 줘도 아무 일이 안 일어난다.
 */
@DisplayName("필드 그룹 목록")
class FieldGroupTest extends PostgresTestBase {

    @Autowired
    private JdbcClient jdbc;

    @Test
    @DisplayName("주문 필드 그룹이 표와 같다")
    void orderFieldsMatchTable() {
        assertThat(codesIn("order"))
                .as("코드에만 있으면 그 필드가 조용히 빠지고, 표에만 있으면 아무도 안 묻는다")
                .containsExactlyInAnyOrderElementsOf(codesOf(OrderFields.values()));
    }

    @Test
    @DisplayName("사용자 필드 그룹이 표와 같다")
    void userFieldsMatchTable() {
        assertThat(codesIn("user"))
                .containsExactlyInAnyOrderElementsOf(codesOf(UserFields.values()));
    }

    /**
     * 두 목록이 {@code basic} 을 공유하지만 같지 않다. <b>그래서 타입을 갈랐다</b> —
     * 하나로 두면 주문 판정에 {@code CONTACT} 를 물어도 컴파일이 통과하고 조용히 거짓이 온다.
     */
    @Test
    @DisplayName("주문과 사용자는 다른 목록이다")
    void twoListsDiffer() {
        assertThat(codesOf(OrderFields.values()))
                .isNotEqualTo(codesOf(UserFields.values()))
                .contains("basic");

        assertThat(codesOf(UserFields.values())).contains("basic");
    }

    private List<String> codesIn(String resource) {
        return jdbc.sql("select code from permission_field_group where resource = :resource")
                .param("resource", resource)
                .query(String.class)
                .list();
    }

    private static List<String> codesOf(FieldGroup[] values) {
        return Arrays.stream(values).map(FieldGroup::code).toList();
    }
}
