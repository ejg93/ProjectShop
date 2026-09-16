package com.projectshop.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.support.BatchRuns;

/**
 * {@code /actuator/prometheus} 를 누가 볼 수 있고 무엇이 실려 나오나(`Q53`).
 *
 * <p><b>진짜 HTTP 여야 한다.</b> 이 경로를 막는 것은 컨트롤러가 아니라 필터 체인이고
 * ({@code SecurityConfig} · {@code MetricsAccessManager}), MockMvc 는 그 체인을 다르게 탄다 —
 * {@code HttpFlowTest} 가 PUBLIC_PATHS 를 여기서 보는 것과 같은 이유다.
 *
 * <p><b>401 과 403 을 갈라서 잰다.</b> 「로그인해야 본다」만 재면 <b>로그인한 손님도 보는 상태</b>가
 * 통과한다 — 이 청크가 막으려던 것이 정확히 그것이다(사용자 결정 2026-09-16).
 */
class MetricsExposureTest extends HttpTestBase {

    private static final String PASSWORD = "hunter2-and-then-some";

    /** 이 테스트가 세우는 회차. 이 층은 롤백이 없어서 남긴 행을 직접 지운다 */
    private static final String BATCH_NAME = "metrics_exposure_test";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    BatchRuns batchRuns;

    /** 이 층은 롤백이 없다. 회차가 남긴 이력을 지운다 — {@code outbox_event} 행은 그 표의 규칙대로 남는다 */
    @AfterEach
    void removeBatchRun() {
        jdbc.sql("delete from batch_run where batch_name = :name").param("name", BATCH_NAME).update();
    }

    @Test
    @DisplayName("익명은 401 이다")
    void anonymousIsUnauthorized() {
        assertThat(newSession().get("/actuator/prometheus").is(401))
                .as("지표는 공개 경로가 아니다")
                .isTrue();
    }

    @Test
    @DisplayName("로그인한 손님은 403 이다")
    void customerIsForbidden() {
        Session session = newSession();
        signUp(session, "metrics-customer");
        logIn(session, "metrics-customer");

        assertThat(session.get("/actuator/prometheus").status().value())
                .as("지표에 업무 규모가 드러난다. 가입만 하면 보이는 값이 아니다")
                .isEqualTo(403);
    }

    @Test
    @DisplayName("관리자는 200 이고 우리가 세운 지표가 실려 있다")
    void adminReadsOurMeters() {
        Session session = newSession();
        signUp(session, "metrics-admin");
        grantAdmin("metrics-admin");
        logIn(session, "metrics-admin");

        // 지표는 한 번이라도 세어진 뒤에야 노출된다. 배치를 한 회차 돌려서 Counter 를 만든다.
        batchRuns.record(BATCH_NAME, LocalDate.now(),
                () -> BatchRuns.Counts.of(0));

        Response response = session.get("/actuator/prometheus");

        assertThat(response.is(200)).as(response.body()).isTrue();
        assertThat(response.body())
                .as("이름 규약이 shop.<자원>.<무엇> 이고 Prometheus 표기에서 점이 밑줄이 된다")
                .contains("shop_batch_run_total")
                .contains("shop_permission_decide_seconds")
                .contains("shop_refund_overdue");
        assertThat(response.body())
                .as("개인정보는 태그로 안 나간다(`D16`)")
                .doesNotContain("user_id=");
    }

    private Response signUp(Session session, String name) {
        // CSRF 토큰 쿠키는 아무 GET 에나 실려 온다. 안 받고 POST 하면 401 이라 가입 자체가 안 된다.
        session.get("/api/health");
        return session.post("/api/auth/signup", """
                {
                  "email": "%s",
                  "password": "%s",
                  "display_name": "http",
                  "consents": {"terms_of_service": true, "privacy_collect": true}
                }
                """.formatted(email(name), PASSWORD));
    }

    private Response logIn(Session session, String name) {
        return session.post("/api/auth/login", """
                {"email": "%s", "password": "%s"}
                """.formatted(email(name), PASSWORD));
    }

    private void grantAdmin(String name) {
        jdbc.sql("""
                insert into user_role (user_id, role_id)
                select u.user_id, r.role_id from app_user u, role r
                 where u.email = :email and r.code = 'admin'
                """).param("email", email(name)).update();
    }

    private static String email(String name) {
        return EMAIL_PREFIX + name + "@test.local";
    }
}
