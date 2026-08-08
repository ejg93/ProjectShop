package com.projectshop.shop.auth;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 권한 테스트가 쓰는 계정·셀러·역할 부여를 만든다.
 *
 * <p>테스트 클래스마다 복제돼 있던 것을 모았다. 셋에 흩어져 있으면
 * 스키마가 바뀔 때 한 벌만 고치고 나머지를 놓친다.
 *
 * <p>Spring 빈이 아니라 {@code new AuthFixture(jdbc)} 로 만든다.
 * 테스트가 자기 {@code JdbcClient} 를 넘기므로 트랜잭션 경계가 테스트의 것과 같다.
 */
public class AuthFixture {

    private final JdbcClient jdbc;

    public AuthFixture(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insertSeller(String code, String name) {
        return jdbc.sql("insert into seller (code, name) values (:code, :name) returning seller_id")
                .param("code", code)
                .param("name", name)
                .query(Long.class)
                .single();
    }

    /**
     * 셀러를 판매 가능 상태로 올린다.
     *
     * <p>신원정보를 같이 채운다 — `3c` 의 {@code seller_verified_fields_check} 가 빈 칸을 막고,
     * `7c` 의 트리거가 {@code active} 아닌 셀러의 상품을 {@code on_sale} 로 못 가게 한다.
     * <b>상품을 파는 상태로 만드는 테스트는 전부 이걸 먼저 부른다.</b>
     */
    public void verifySeller(long sellerId) {
        jdbc.sql("""
                        update seller
                           set business_name = '주식회사 테스트', representative_name = '홍길동',
                               business_reg_no = '1234567891',
                               address = '서울시 강남구', phone = '02-0000-0000',
                               email = 'seller@test.local',
                               mail_order_exempt_reason = 'simplified_taxpayer',
                               status = 'active'
                         where seller_id = :id
                        """)
                .param("id", sellerId)
                .update();
    }

    public long insertUser(String email, String displayName) {
        return jdbc.sql("""
                        insert into app_user (email, password_hash, display_name)
                        values (:email, 'not-a-real-hash', :displayName)
                        returning user_id
                        """)
                .param("email", email)
                .param("displayName", displayName)
                .query(Long.class)
                .single();
    }

    public void joinSeller(long sellerId, long userId) {
        jdbc.sql("insert into seller_member (seller_id, user_id) values (:sellerId, :userId)")
                .param("sellerId", sellerId)
                .param("userId", userId)
                .update();
    }

    public void leaveSeller(long sellerId, long userId) {
        jdbc.sql("delete from seller_member where seller_id = :sellerId and user_id = :userId")
                .param("sellerId", sellerId)
                .param("userId", userId)
                .update();
    }

    public void grantGlobal(long userId, String roleCode) {
        jdbc.sql("""
                        insert into user_role (user_id, role_id)
                        select :userId, role_id from role where code = :roleCode
                        """)
                .param("userId", userId)
                .param("roleCode", roleCode)
                .update();
    }

    public void grantOrg(long userId, String roleCode, long sellerId) {
        jdbc.sql("""
                        insert into user_role (user_id, role_id, seller_id)
                        select :userId, role_id, :sellerId from role where code = :roleCode
                        """)
                .param("userId", userId)
                .param("roleCode", roleCode)
                .param("sellerId", sellerId)
                .update();
    }

    public void revokeAllRoles(long userId) {
        jdbc.sql("delete from user_role where user_id = :userId")
                .param("userId", userId)
                .update();
    }
}
