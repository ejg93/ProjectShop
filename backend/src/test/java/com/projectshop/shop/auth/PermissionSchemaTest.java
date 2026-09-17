package com.projectshop.shop.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;

/**
 * 권한 표가 스스로 지키는 것(`Q59`, `D6`).
 *
 * <p><b>거부를 만드는 것이 사람에서 트리거로 내려간 자리다.</b> {@code PermissionEvaluatorTest} 가
 * 「막히나」를 재고 여기는 <b>그 규칙을 못 비켜 가나</b>를 잰다 — 종류를 안 주는 길, 나중에 고치는 길.
 */
@DisplayName("권한 스키마")
class PermissionSchemaTest extends PostgresTestBase {

    @Autowired
    private JdbcClient jdbc;

    /**
     * <b>기본값을 안 둔 것이 이 테스트의 요지다.</b> 기본값이 있으면 넣는 사람이 안 고르고,
     * 안 고른 값이 쓰기인데 읽기로 들어가면 <b>거부가 안 만들어진다.</b>
     */
    @Test
    @DisplayName("종류를 안 주면 거부된다")
    void requiresTheKind() {
        assertThatThrownBy(() -> jdbc.sql("""
                        insert into permission (resource, action, description)
                        values ('product', 'kindless', '테스트용')
                        """)
                .update())
                .as("kind 는 not null 이고 기본값이 없다. 넣는 사람이 매번 정한다")
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("모르는 종류는 거부된다")
    void refusesAnUnknownKind() {
        assertThatThrownBy(() -> insert("weird", "sideways"))
                .as("permission_kind_check 가 목록을 닫는다")
                .isInstanceOf(Exception.class);
    }

    /**
     * <b>고치면 이미 만든 거부가 남거나 빠진다.</b> {@code write} 를 {@code read} 로 바꿔도 거부는
     * 그대로 남고, 반대로 바꿔도 {@code after insert} 트리거는 안 돈다.
     */
    @Test
    @DisplayName("종류는 못 고친다")
    void keepsTheKindImmutable() {
        insert("immutable", "read");

        assertThatThrownBy(() -> jdbc.sql("""
                        update permission set kind = 'write'
                         where resource = 'product' and action = 'immutable'
                        """)
                .update())
                .as("종류를 바꿔야 하면 권한을 새로 만든다")
                .isInstanceOf(Exception.class);
    }

    /** 같은 값으로 다시 쓰는 것은 고치는 것이 아니다 — 트리거가 {@code is distinct from} 으로 가른다. */
    @Test
    @DisplayName("같은 종류로 다시 써도 거부되지 않는다")
    void allowsRewritingTheSameKind() {
        insert("rewritable", "read");

        int updated = jdbc.sql("""
                        update permission set kind = 'read'
                         where resource = 'product' and action = 'rewritable'
                        """)
                .update();

        assertThat(updated).isEqualTo(1);
    }

    private void insert(String action, String kind) {
        jdbc.sql("""
                        insert into permission (resource, action, description, kind)
                        values ('product', :action, '테스트용', :kind)
                        """)
                .param("action", action)
                .param("kind", kind)
                .update();
    }
}
