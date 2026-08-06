package com.projectshop.shop.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.PermissionEvaluator.Decision;
import com.projectshop.shop.auth.PermissionEvaluator.Target;

/**
 * 행에 접근할 수 있다는 것과 그 행을 통째로 볼 수 있다는 것이 다르다는 걸 검증한다.
 * 셀러에게 주문을 보여주는 건 제3자 제공이라 배송에 필요한 만큼까지다.
 */
class FieldVisibilityTest extends PostgresTestBase {

    @Autowired
    PermissionEvaluator evaluator;

    @Autowired
    JdbcClient jdbc;

    long alpha;
    long buyer;
    long seller;

    @BeforeEach
    void setUp() {
        alpha = insertSeller("alpha", "알파상회");
        buyer = insertUser("buyer@test.local", "구매자");
        seller = insertUser("seller@test.local", "알파 대표");

        grantGlobal(buyer, "customer");
        joinSeller(alpha, seller);
        grantOrg(seller, "seller_owner", alpha);
    }

    @Test
    @DisplayName("고객은 자기 주문의 결제 수단까지 본다")
    void customerSeesEverything() {
        Decision decision = evaluator.decide(buyer, "order", "read", Target.of(buyer, alpha));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.visibleFieldGroups()).containsExactlyInAnyOrder("basic", "shipping", "payment");
    }

    @Test
    @DisplayName("셀러는 같은 주문에서 결제 수단을 못 본다")
    void sellerCannotSeePayment() {
        Decision decision = evaluator.decide(seller, "order", "read", Target.of(buyer, alpha));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.canSee("shipping")).isTrue();
        assertThat(decision.canSee("payment")).isFalse();
    }

    @Test
    @DisplayName("필드 그룹을 안 건 자원은 제한이 없다")
    void resourceWithoutGroupsIsUnrestricted() {
        Decision decision = evaluator.decide(buyer, "product", "read", Target.ofSeller(alpha));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.fieldRestricted()).isFalse();
        assertThat(decision.canSee("무엇이든")).isTrue();
    }

    @Test
    @DisplayName("거부된 판정은 어떤 필드도 못 본다")
    void deniedSeesNothing() {
        Decision decision = evaluator.decide(buyer, "order", "read", Target.of(seller, alpha));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.canSee("basic")).isFalse();
    }

    @Test
    @DisplayName("역할이 둘이면 양쪽이 허용하는 필드를 합친다")
    void twoRolesUnionFields() {
        joinSeller(alpha, buyer);
        grantOrg(buyer, "seller_owner", alpha);

        Decision decision = evaluator.decide(buyer, "order", "read", Target.of(buyer, alpha));

        assertThat(decision.visibleFieldGroups())
                .as("고객 역할이 payment 를, 셀러 대표 역할이 basic·shipping 을 준다")
                .containsExactlyInAnyOrder("basic", "shipping", "payment");
    }

    @Test
    @DisplayName("제한 없는 규칙이 하나라도 걸리면 제한이 풀린다")
    void unrestrictedRuleWins() {
        long admin = insertUser("admin@test.local", "관리자");
        grantGlobal(admin, "admin");
        grantGlobal(admin, "customer");

        Decision decision = evaluator.decide(admin, "order", "read", Target.of(buyer, alpha));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.fieldRestricted())
                .as("관리자 규칙에 필드 그룹 연결이 없다")
                .isFalse();
    }

    @Test
    @DisplayName("넓은 스코프의 허용이 판정 근거로 남는다")
    void widestScopeIsReported() {
        joinSeller(alpha, buyer);
        grantOrg(buyer, "seller_owner", alpha);

        Decision decision = evaluator.decide(buyer, "order", "read", Target.of(buyer, alpha));

        assertThat(decision.reason())
                .as("own 과 seller 가 둘 다 걸리면 넓은 seller 가 근거가 된다")
                .contains("allow/seller");
    }

    private long insertSeller(String code, String name) {
        return jdbc.sql("insert into seller (code, name) values (:code, :name) returning id")
                .param("code", code)
                .param("name", name)
                .query(Long.class)
                .single();
    }

    private long insertUser(String email, String displayName) {
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

    private void joinSeller(long sellerId, long userId) {
        jdbc.sql("insert into seller_member (seller_id, user_id) values (:sellerId, :userId)")
                .param("sellerId", sellerId)
                .param("userId", userId)
                .update();
    }

    private void grantGlobal(long userId, String roleCode) {
        jdbc.sql("insert into user_role (user_id, role_id) select :userId, id from role where code = :roleCode")
                .param("userId", userId)
                .param("roleCode", roleCode)
                .update();
    }

    private void grantOrg(long userId, String roleCode, long sellerId) {
        jdbc.sql("""
                        insert into user_role (user_id, role_id, seller_id)
                        select :userId, id, :sellerId from role where code = :roleCode
                        """)
                .param("userId", userId)
                .param("roleCode", roleCode)
                .param("sellerId", sellerId)
                .update();
    }
}
