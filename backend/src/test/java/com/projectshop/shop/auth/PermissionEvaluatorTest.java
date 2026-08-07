package com.projectshop.shop.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.PermissionEvaluator.Decision;
import com.projectshop.shop.auth.PermissionEvaluator.Target;

/**
 * 판정 엔진을 화면 없이 검증한다.
 *
 * <p>테스트마다 계정과 셀러를 새로 만들고 트랜잭션째 되돌린다.
 * 마이그레이션이 넣은 역할·권한은 그대로 쓴다. 판정이 실제 초기 데이터 위에서 도는지 봐야 해서다.
 */
class PermissionEvaluatorTest extends PostgresTestBase {

    @Autowired
    PermissionEvaluator evaluator;

    @Autowired
    JdbcClient jdbc;

    AuthFixture fixture;
    long alpha;
    long beta;
    long customer;
    long alphaSeller;
    long betaSeller;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);
        alpha = fixture.insertSeller("alpha", "알파상회");
        beta = fixture.insertSeller("beta", "베타상회");
        customer = fixture.insertUser("customer@test.local", "고객");
        alphaSeller = fixture.insertUser("alpha-seller@test.local", "알파 대표");
        betaSeller = fixture.insertUser("beta-seller@test.local", "베타 대표");

        fixture.grantGlobal(customer, "customer");
        fixture.joinSeller(alpha, alphaSeller);
        fixture.grantOrg(alphaSeller, "seller_owner", alpha);
        fixture.joinSeller(beta, betaSeller);
        fixture.grantOrg(betaSeller, "seller_owner", beta);
    }

    @Nested
    @DisplayName("스코프")
    class Scope {

        @Test
        @DisplayName("고객은 자기 주문을 본다")
        void ownOrderIsVisible() {
            Decision decision = evaluator.decide(customer, "order", "read", Target.of(customer, alpha));

            assertThat(decision.allowed()).isTrue();
            assertThat(decision.reason()).contains("customer", "allow/own");
        }

        @Test
        @DisplayName("고객은 남의 주문을 못 본다")
        void othersOrderIsHidden() {
            Decision decision = evaluator.decide(customer, "order", "read", Target.of(alphaSeller, alpha));

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.reason()).contains("범위 밖");
        }

        @Test
        @DisplayName("권한 자체가 없으면 규칙이 하나도 안 걸린다")
        void missingPermissionHasNoRule() {
            Decision decision = evaluator.decide(customer, "role", "manage", Target.ownedBy(customer));

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.reason()).contains("규칙이 하나도 없다");
        }
    }

    @Nested
    @DisplayName("조직")
    class Organization {

        @Test
        @DisplayName("셀러 대표는 자기 셀러의 주문을 본다")
        void ownSellerOrderIsVisible() {
            Decision decision = evaluator.decide(alphaSeller, "order", "read", Target.of(customer, alpha));

            assertThat(decision.allowed()).isTrue();
            assertThat(decision.reason()).contains("allow/seller");
        }

        @Test
        @DisplayName("셀러 대표는 남의 셀러의 주문을 못 본다")
        void otherSellerOrderIsHidden() {
            Decision decision = evaluator.decide(alphaSeller, "order", "read", Target.of(customer, beta));

            assertThat(decision.allowed()).isFalse();
        }

        @Test
        @DisplayName("같은 역할이라도 부여받은 셀러가 다르면 판정이 갈린다")
        void sameRoleDifferentSeller() {
            Target alphaOrder = Target.of(customer, alpha);

            assertThat(evaluator.decide(alphaSeller, "order", "read", alphaOrder).allowed()).isTrue();
            assertThat(evaluator.decide(betaSeller, "order", "read", alphaOrder).allowed()).isFalse();
        }

        @Test
        @DisplayName("셀러 두 곳에 속하면 양쪽 주문을 본다")
        void memberOfTwoSellers() {
            fixture.joinSeller(beta, alphaSeller);
            fixture.grantOrg(alphaSeller, "seller_owner", beta);

            assertThat(evaluator.decide(alphaSeller, "order", "read", Target.of(customer, alpha)).allowed()).isTrue();
            assertThat(evaluator.decide(alphaSeller, "order", "read", Target.of(customer, beta)).allowed()).isTrue();
        }
    }

    @Nested
    @DisplayName("거부")
    class Deny {

        @Test
        @DisplayName("셀러 대표는 자기 셀러 주문의 상태를 바꾼다")
        void sellerAdvancesOrder() {
            Decision decision = evaluator.decide(alphaSeller, "order", "update_status", Target.of(customer, alpha));

            assertThat(decision.allowed()).isTrue();
            assertThat(decision.reason()).contains("allow/seller");
        }

        @Test
        @DisplayName("자기가 주문한 자기 셀러 주문은 자기가 못 바꾼다")
        void sellerCannotAdvanceOwnPurchase() {
            Target selfPurchase = Target.of(alphaSeller, alpha);

            Decision decision = evaluator.decide(alphaSeller, "order", "update_status", selfPurchase);

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.reason()).contains("deny/own");
        }

        @Test
        @DisplayName("좁은 deny 가 넓은 allow 를 이긴다")
        void denyBeatsWiderAllow() {
            long auditor = fixture.insertUser("auditor@test.local", "감사자");
            fixture.grantGlobal(auditor, "auditor");
            fixture.grantGlobal(auditor, "admin");

            Target anyProduct = Target.ofSeller(alpha);

            assertThat(evaluator.decide(auditor, "product", "read", anyProduct).allowed()).isTrue();

            Decision write = evaluator.decide(auditor, "product", "update", anyProduct);
            assertThat(write.allowed()).isFalse();
            assertThat(write.reason()).contains("auditor", "deny/all");
        }

        @Test
        @DisplayName("감사자 역할이 없으면 관리자는 그대로 쓴다")
        void adminAloneCanWrite() {
            long admin = fixture.insertUser("admin@test.local", "관리자");
            fixture.grantGlobal(admin, "admin");

            assertThat(evaluator.decide(admin, "product", "update", Target.ofSeller(alpha)).allowed()).isTrue();
        }
    }

    /**
     * 청크 3b 에서 감사자의 거부를 데이터로 깔았는데, 그 데이터는 마이그레이션을 쓰던 시점의 권한만 훑는다.
     * 뒤 청크가 권한을 추가하면서 감사자 deny 를 같이 안 넣으면 감사자가 그 권한을 쓰게 된다.
     *
     * <p>이 테스트는 그 구멍이 실재함을 고정한다. 지금은 통과하는 것이 정상이고,
     * 읽기·쓰기 분류를 데이터로 갖게 되면 이 테스트가 깨진다. 깨지면 기대값을 뒤집는다.
     */
    @Nested
    @DisplayName("알려진 구멍")
    class KnownHole {

        @Test
        @DisplayName("나중에 추가된 권한은 감사자를 막지 못한다")
        void auditorLeaksThroughNewPermission() {
            jdbc.sql("insert into permission (resource, action, description) values ('product', 'approve', '테스트용')")
                    .update();
            jdbc.sql("""
                            insert into role_permission (role_id, permission_id, scope, effect)
                            select r.role_id, p.permission_id, 'all', 'allow'
                            from permission p join role r on r.code = 'admin'
                            where p.resource = 'product' and p.action = 'approve'
                            """)
                    .update();

            long auditor = fixture.insertUser("auditor2@test.local", "감사자");
            fixture.grantGlobal(auditor, "auditor");
            fixture.grantGlobal(auditor, "admin");

            Decision decision = evaluator.decide(auditor, "product", "approve", Target.ofSeller(alpha));

            assertThat(decision.allowed())
                    .as("감사자가 새 쓰기 권한을 통과한다. 권한을 추가한 마이그레이션이 deny 를 안 넣어서다")
                    .isTrue();
        }
    }

}
