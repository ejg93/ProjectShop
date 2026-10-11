package com.projectshop.shop;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;

/**
 * 관리 포트를 따로 띄웠을 때 지표가 그 포트에서만 세션 없이 열리는지 본다(`63`, `SecurityConfig.managementPortOnly`).
 *
 * <p>컴포즈 `observe` 프로필이 `MANAGEMENT_SERVER_PORT=9091` 을 준다 — Prometheus 는 세션이 없다. 그 포트는 컴포즈 망
 * 안에만 있다(호스트에 안 묶는다). 배포는 이 값을 안 줘서 같은 포트고, 그때 지표는 `Q53` 대로 관리자만 본다
 * ({@link MetricsExposureTest}).
 *
 * <p><b>클래스를 뗀 이유</b>: 관리 포트를 따로 띄우면 actuator 가 서버 포트에서 <b>통째로 빠진다</b>(404).
 * 바탕({@link HttpTestBase})에 두면 `Q53` 의 8080 시험이 잴 것이 없어진다 — 그래서 컨텍스트 하나를 더 쓴다(`D15`).
 */
@DisplayName("관리 포트")
@TestPropertySource(properties = "management.server.port=0")
class ManagementPortTest extends HttpTestBase {

    private static final String PASSWORD = "hunter2-and-then-some";
    private static final String ADMIN_EMAIL = EMAIL_PREFIX + "management-admin@test.local";

    @Autowired
    JdbcClient jdbc;

    @LocalManagementPort
    int managementPort;

    @LocalServerPort
    int serverPort;

    @Test
    @DisplayName("관리 포트는 세션 없이 지표를 낸다")
    void managementPortServesWithoutSession() {
        assertThat(managementPort)
                .as("관리 포트가 서버 포트와 같으면 이 시험이 서버 포트를 잰다")
                .isNotEqualTo(serverPort);

        String body = RestClient.create("http://localhost:" + managementPort)
                .get().uri("/actuator/prometheus").retrieve().body(String.class);

        assertThat(body).contains("jvm_memory_used_bytes");
    }

    /**
     * 관리 포트를 따로 띄우면 actuator 가 서버 포트에서 빠진다(Boot 동작, `stack.md`). 그래서 이 컨텍스트에서는
     * 「서버 포트가 막혔나」를 못 잰다 — 그것은 같은 포트 배포를 재는 {@link MetricsExposureTest} 몫이다.
     *
     * <p><b>관리자로 묻는다.</b> 익명은 경로가 없어도 「인증 필요」가 먼저 걸려 401 이라 옮겨 갔는지를 못 가른다.
     * 관리자는 actuator 가 남아 있으면 200, 옮겨 갔으면 404 다.
     */
    @Test
    @DisplayName("관리 포트를 따로 두면 서버 포트에는 actuator 가 없다")
    void actuatorMovesOffServerPort() {
        Session admin = newSession();
        admin.get("/api/health");
        admin.post("/api/auth/signup", """
                {
                  "email": "%s",
                  "password": "%s",
                  "display_name": "http",
                  "birth_date": "1990-01-01",
                  "consents": {"terms_of_service": true, "privacy_collect": true}
                }
                """.formatted(ADMIN_EMAIL, PASSWORD));
        jdbc.sql("""
                insert into user_role (user_id, role_id)
                select u.user_id, r.role_id from app_user u, role r
                 where u.email = :email and r.code = 'admin'
                """).param("email", ADMIN_EMAIL).update();
        assertThat(admin.post("/api/auth/login", """
                {"email": "%s", "password": "%s"}
                """.formatted(ADMIN_EMAIL, PASSWORD)).is(200)).isTrue();

        assertThat(admin.get("/actuator/prometheus").status().value())
                .as("관리자에게 200 이면 관리 포트를 따로 두고도 지표가 공개 포트에 남았다")
                .isEqualTo(404);
    }
}
