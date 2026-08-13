package com.projectshop.shop.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.auth.PermissionEvaluator;
import com.projectshop.shop.auth.PermissionEvaluator.Target;

/**
 * 감사 기록이 실제로 남는지, 그리고 <b>빠뜨릴 수 없는지</b>를 본다.
 *
 * <p>감사 로그의 실패는 잘못 남는 쪽이 아니라 안 남는 쪽이다.
 * 안 남으면 그 경로가 감사에서 통째로 사라지는데 나중에 알 방법이 없다.
 */
class AuditLogTest extends PostgresTestBase {

    @Autowired
    AuditLog auditLog;

    @Autowired
    PermissionEvaluator evaluator;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager txManager;

    AuthFixture fixture;
    long alpha;
    long customer;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);
        alpha = fixture.insertSeller("alpha", "알파상회");
        customer = fixture.insertUser("customer@test.local", "고객");
        fixture.grantGlobal(customer, "customer");
    }

    @Nested
    @DisplayName("기록이 남나")
    class Recording {

        @Test
        @DisplayName("사건 하나가 그대로 들어간다")
        void recordsOneEvent() {
            auditLog.record(AuditLog.Kind.OUTCOME, "role.granted", customer,
                    AuditLog.Target.of("user", customer),
                    Map.of("role_code", "seller_owner", "seller_id", alpha));

            Map<String, Object> row = latest();

            assertThat(row).containsEntry("event_type", "role.granted");
            assertThat(row).containsEntry("actor_user_id", customer);
            assertThat(row).containsEntry("target_type", "user");
            assertThat(row).containsEntry("target_id", customer);
            assertThat(row.get("detail").toString()).contains("seller_owner");
        }

        @Test
        @DisplayName("대상이 없는 사건도 남는다")
        void recordsEventWithoutTarget() {
            auditLog.record(AuditLog.Kind.OUTCOME, "session.expired", customer,
                    AuditLog.Target.none(), Map.of());

            assertThat(latest()).containsEntry("target_type", null);
        }

        @Test
        @DisplayName("시스템이 한 일은 행위자가 비어 있다")
        void systemActorIsNull() {
            auditLog.record(AuditLog.Kind.OUTCOME, "batch.purged", null,
                    AuditLog.Target.ofType("order"), Map.of("count", 12));

            assertThat(latest()).containsEntry("actor_user_id", null);
        }
    }

    @Nested
    @DisplayName("판정 거부는 저절로 남나")
    class Denial {

        @Test
        @DisplayName("거부하면 호출자가 아무것도 안 해도 기록이 생긴다")
        void deniedDecisionIsRecorded() {
            evaluator.decide(customer, "order", "read", Target.of(9999L, alpha));

            Map<String, Object> row = latest();

            assertThat(row).containsEntry("event_type", "permission.denied");
            assertThat(row).containsEntry("actor_user_id", customer);
            assertThat(row.get("detail").toString())
                    .as("왜 막혔는지가 안 남으면 권한 문제는 재현이 안 된다")
                    .contains("범위 밖");
        }

        @Test
        @DisplayName("권한 자체가 없어서 막힌 것도 남는다")
        void missingPermissionIsRecorded() {
            evaluator.decide(customer, "settlement", "read", Target.ofSeller(alpha));

            assertThat(latest().get("detail").toString()).contains("규칙이 하나도 없다");
        }

        @Test
        @DisplayName("허용은 남기지 않는다")
        void allowedDecisionIsNotRecorded() {
            evaluator.decide(customer, "order", "read", Target.of(customer, alpha));

            assertThat(count())
                    .as("허용까지 남기면 조회 한 번마다 한 줄이 쌓여서 거부가 묻힌다")
                    .isZero();
        }
    }

    @Nested
    @DisplayName("업무와 같이 롤백되나")
    class Transaction {

        @Test
        @DisplayName("결과는 같은 트랜잭션에 얹혀서 업무 데이터와 함께 보인다")
        void sharesTransactionWithBusinessWork() {
            fixture.grantOrg(customer, "seller_owner", insertMembership());
            auditLog.record(AuditLog.Kind.OUTCOME, "role.granted", customer,
                    AuditLog.Target.of("user", customer), Map.of());

            assertThat(count())
                    .as("""
                            테스트 트랜잭션 안에서 둘 다 보인다. 별도 트랜잭션이었다면
                            감사 기록만 먼저 커밋돼서 롤백 뒤에도 남는다.
                            """)
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("결과는 업무가 롤백되면 같이 사라진다")
        void outcomeVanishesWithBusinessRollback() {
            business().execute(status -> {
                auditLog.record(AuditLog.Kind.OUTCOME, "role.granted", customer,
                        AuditLog.Target.of("user", customer), Map.of());
                status.setRollbackOnly();
                return null;
            });

            assertThat(count())
                    .as("안 일어난 일이 기록에 남으면 감사가 거짓말을 한다")
                    .isZero();
        }

        @Test
        @DisplayName("거부는 업무가 롤백돼도 남는다")
        void denialSurvivesBusinessRollback() {
            business().execute(status -> {
                evaluator.decide(customer, "order", "read", Target.of(9999L, alpha));
                status.setRollbackOnly();
                return null;
            });

            assertThat(count())
                    .as("""
                            @Transactional 서비스 안의 거부는 예외와 함께 롤백된다.
                            거부가 사라지면 감사 로그로 시도를 세는 것이 성립 안 한다.
                            """)
                    .isEqualTo(1);
        }

        /**
         * 업무 트랜잭션을 흉내 낸다. 테스트 자체의 트랜잭션과 갈라야 롤백을 관찰할 수 있다 —
         * 테스트 트랜잭션은 어차피 마지막에 롤백돼서 무엇이 살아남는지 안 보인다.
         */
        private TransactionTemplate business() {
            TransactionTemplate template = new TransactionTemplate(txManager);
            template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            return template;
        }

        private long insertMembership() {
            fixture.joinSeller(alpha, customer);
            return alpha;
        }
    }

    private Map<String, Object> latest() {
        List<Map<String, Object>> rows = jdbc
                .sql("select * from audit_log order by audit_log_id desc limit 1")
                .query()
                .listOfRows();
        assertThat(rows).as("감사 기록이 하나도 없다").isNotEmpty();
        return rows.getFirst();
    }

    private long count() {
        return jdbc.sql("select count(*) from audit_log").query(Long.class).single();
    }
}
