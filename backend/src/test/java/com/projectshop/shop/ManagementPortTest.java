package com.projectshop.shop;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
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

    @Test
    @DisplayName("서버 포트로는 세션 없이 지표가 안 열린다")
    void serverPortStaysClosed() {
        assertThat(newSession().get("/actuator/prometheus").is(200))
                .as("포트 매처가 서버 포트까지 물면 지표가 손님에게 열린다(Q53)")
                .isFalse();
    }
}
