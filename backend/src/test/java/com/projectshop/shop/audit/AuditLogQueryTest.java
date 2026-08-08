package com.projectshop.shop.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.HttpStatus;

import com.projectshop.shop.error.ShopException;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.audit.AuditLogQuery.Criteria;
import com.projectshop.shop.audit.AuditLogQuery.Page;
import com.projectshop.shop.auth.AuthFixture;

/**
 * 쌓인 감사 기록을 꺼내는 쪽을 본다. 쌓기만 하고 못 꺼내면 감사가 성립하지 않는다.
 *
 * <p>{@code PermissionEvaluator.decide} 를 요청 흐름에서 부르는 첫 자리다.
 * {@code 8a} 는 대상 없는 계산({@code evaluate})만 썼다.
 */
class AuditLogQueryTest extends PostgresTestBase {

    @Autowired
    AuditLogQuery query;

    @Autowired
    AuditLog auditLog;

    @Autowired
    JdbcClient jdbc;

    AuthFixture fixture;
    long auditor;
    long customer;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);

        auditor = fixture.insertUser("q-auditor@test.local", "감사자");
        fixture.grantGlobal(auditor, "auditor");

        customer = fixture.insertUser("q-customer@test.local", "고객");
        fixture.grantGlobal(customer, "customer");

        auditLog.record("order.created", customer, AuditLog.Target.of("order", 11L), Map.of());
        auditLog.record("order.cancelled", customer, AuditLog.Target.of("order", 11L), Map.of());
        auditLog.record("role.granted", auditor, AuditLog.Target.of("user", customer),
                Map.of("role_code", "customer"));
    }

    @Nested
    @DisplayName("권한")
    class Authorization {

        @Test
        @DisplayName("감사자는 전체를 본다")
        void auditorSeesEverything() {
            assertThat(find(auditor, all()).total()).isGreaterThanOrEqualTo(3);
        }

        @Test
        @DisplayName("고객은 못 본다 — 403 이고 404 가 아니다")
        void customerIsForbidden() {
            // 상태 코드를 문자열로 훑지 않고 오류 코드에서 직접 읽는다.
            // 감사 로그가 있다는 사실 자체는 비밀이 아니라서 존재를 감추지 않는다(`D5`).
            assertThatThrownBy(() -> find(customer, all()))
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code().status()).isEqualTo(HttpStatus.FORBIDDEN));
        }

        @Test
        @DisplayName("고객은 자기 기록으로 좁혀도 못 본다")
        void narrowingDoesNotGrantAccess() {
            assertThatThrownBy(() -> find(customer, criteria(customer, null)))
                    .as("own 스코프를 안 줬으므로 좁히는 것만으로 열리면 안 된다")
                    .isInstanceOf(ShopException.class);
        }

        @Test
        @DisplayName("거부가 조회 자체를 막는다 — 0건이 아니다")
        void deniesBeforeReading() {
            // 0건과 못 봄이 갈려야 한다. 행을 읽고 거르면 개수로 정보가 샌다.
            assertThatThrownBy(() -> find(customer, criteria(-999L, null)))
                    .isInstanceOf(ShopException.class);
        }
    }

    @Nested
    @DisplayName("필터")
    class Filtering {

        @Test
        @DisplayName("행위자로 좁힌다")
        void byActor() {
            Page page = find(auditor, criteria(customer, null));

            assertThat(page.items()).isNotEmpty();
            assertThat(page.items()).allSatisfy(row ->
                    assertThat(row.actorUserId()).isEqualTo(customer));
        }

        @Test
        @DisplayName("대상 종류로 좁힌다")
        void byTargetType() {
            Page page = find(auditor, criteria(null, "order"));

            assertThat(page.items()).isNotEmpty();
            assertThat(page.items()).allSatisfy(row ->
                    assertThat(row.targetType()).isEqualTo("order"));
        }

        @Test
        @DisplayName("기간 밖은 안 나온다")
        void byPeriod() {
            OffsetDateTime tomorrow = OffsetDateTime.now().plusDays(1);
            Page page = find(auditor, new Criteria(null, null, null, tomorrow, null, 0, 20));

            assertThat(page.total()).isZero();
            assertThat(page.items()).as("빈 목록은 null 이 아니라 [] 다").isEmpty();
        }

        @Test
        @DisplayName("끝 시각은 포함하지 않는다")
        void endIsExclusive() {
            // 경계는 실제로 존재하는 행의 시각이어야 한다. now() 를 쓰면 세 건이 모두 그 이전이라
            // 포함이든 배타든 결과가 같아서 아무것도 안 잡는다.
            OffsetDateTime oldest = jdbc.sql("select min(created_at) from audit_log")
                    .query(OffsetDateTime.class)
                    .single();

            long total = find(auditor, all()).total();
            long upToOldest = find(auditor,
                    new Criteria(null, null, null, null, oldest, 0, 20)).total();

            assertThat(upToOldest)
                    .as("끝을 열어 둬야 하루씩 이어 붙일 때 경계 행이 두 번 안 나온다")
                    .isLessThan(total);
        }

        @Test
        @DisplayName("detail 이 객체로 돌아온다")
        void detailComesBackAsObject() {
            Page page = find(auditor, criteria(auditor, "user"));

            assertThat(page.items()).isNotEmpty();
            assertThat(page.items().get(0).detail()).containsEntry("role_code", "customer");
        }
    }

    @Nested
    @DisplayName("페이징")
    class Paging {

        @Test
        @DisplayName("최신순으로 나온다")
        void newestFirst() {
            Page page = find(auditor, criteria(customer, null));

            assertThat(page.items().get(0).eventType()).isEqualTo("order.cancelled");
        }

        @Test
        @DisplayName("한 페이지 크기를 100 으로 막는다")
        void capsPageSize() {
            Page page = find(auditor, new Criteria(null, null, null, null, null, 0, 5000));

            assertThat(page.size())
                    .as("안 막으면 목록 하나로 전체를 긁어 간다")
                    .isEqualTo(100);
        }

        @Test
        @DisplayName("전체 개수는 페이지 크기와 따로 센다")
        void totalIgnoresPaging() {
            Page page = find(auditor, new Criteria(null, null, null, null, null, 0, 1));

            assertThat(page.items()).hasSize(1);
            assertThat(page.total()).isGreaterThan(1);
        }

        @Test
        @DisplayName("음수 페이지는 0 으로 본다")
        void negativePageIsFirstPage() {
            assertThat(find(auditor, new Criteria(null, null, null, null, null, -3, 20)).page())
                    .isZero();
        }
    }

    private Page find(long viewer, Criteria criteria) {
        return query.find(viewer, criteria);
    }

    private static Criteria all() {
        return new Criteria(null, null, null, null, null, 0, 20);
    }

    private static Criteria criteria(Long actorUserId, String targetType) {
        return new Criteria(actorUserId, targetType, null, null, null, 0, 20);
    }
}
