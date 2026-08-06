package com.projectshop.shop.auth;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 권한 테스트가 쓰는 계정·셀러·역할 부여를 만든다.
 *
 * <p>같은 코드가 {@code PermissionEvaluatorTest} 와 {@code FieldVisibilityTest} 에 이미 두 벌 있다.
 * 세 번째를 만들면서 여기로 뺐다. 앞의 두 벌을 옮기는 것은 청크 4a-1 이다.
 */
class AuthFixture {

    private final JdbcClient jdbc;

    AuthFixture(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    long insertSeller(String code, String name) {
        return jdbc.sql("insert into seller (code, name) values (:code, :name) returning id")
                .param("code", code)
                .param("name", name)
                .query(Long.class)
                .single();
    }

    long insertUser(String email, String displayName) {
        return jdbc.sql("""
                        insert into app_user (email, password_hash, display_name)
                        values (:email, 'not-a-real-hash', :displayName)
                        returning id
                        """)
                .param("email", email)
                .param("displayName", displayName)
                .query(Long.class)
                .single();
    }

    void joinSeller(long sellerId, long userId) {
        jdbc.sql("insert into seller_member (seller_id, user_id) values (:sellerId, :userId)")
                .param("sellerId", sellerId)
                .param("userId", userId)
                .update();
    }

    void leaveSeller(long sellerId, long userId) {
        jdbc.sql("delete from seller_member where seller_id = :sellerId and user_id = :userId")
                .param("sellerId", sellerId)
                .param("userId", userId)
                .update();
    }

    void grantGlobal(long userId, String roleCode) {
        jdbc.sql("""
                        insert into user_role (user_id, role_id)
                        select :userId, id from role where code = :roleCode
                        """)
                .param("userId", userId)
                .param("roleCode", roleCode)
                .update();
    }

    void grantOrg(long userId, String roleCode, long sellerId) {
        jdbc.sql("""
                        insert into user_role (user_id, role_id, seller_id)
                        select :userId, id, :sellerId from role where code = :roleCode
                        """)
                .param("userId", userId)
                .param("roleCode", roleCode)
                .param("sellerId", sellerId)
                .update();
    }

    void revokeAllRoles(long userId) {
        jdbc.sql("delete from user_role where user_id = :userId")
                .param("userId", userId)
                .update();
    }
}
