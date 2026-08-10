package com.projectshop.shop.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.projectshop.shop.PostgresTestBase;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * 로그 한 줄에서 요청 하나를 되찾을 수 있는가(`D16`).
 *
 * <p>추적 ID 의 값은 <b>로그와 응답이 같아야 뜻이 있다.</b> 사용자가 오류 화면의 ID 를 불러 줬는데
 * 그 값으로 로그를 못 찾으면 ID 를 내려 준 의미가 없다. 두 자리가 각자 ID 를 만들면
 * 그 일이 조용히 일어난다 — 응답에도 값이 있고 로그에도 값이 있어서 눈으로는 정상으로 보인다.
 *
 * <p>형식 자체(패턴 문자열)는 안 본다. 그건 `logback-spring.xml` 의 몫이고,
 * 여기서 문자열을 고정하면 형식을 바꿀 때마다 테스트가 깨진다. 여기서 보는 것은
 * <b>MDC 에 값이 실려 있는가</b>와 <b>그 값이 응답과 이어지는가</b>다.
 */
@AutoConfigureMockMvc
@DisplayName("요청 추적")
class RequestTraceTest extends PostgresTestBase {

    /** 밖에서 온 것처럼 보내는 W3C 헤더. 가운데 32자리가 trace-id 다 */
    private static final String INCOMING_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String TRACEPARENT =
            "00-" + INCOMING_TRACE_ID + "-00f067aa0ba902b7-01";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    private ListAppender<ILoggingEvent> captured;

    @BeforeEach
    void captureLogs() {
        captured = new ListAppender<>();
        captured.start();
        rootLogger().addAppender(captured);
    }

    @AfterEach
    void stopCapturing() {
        rootLogger().detachAppender(captured);
        captured.stop();
    }

    @Nested
    @DisplayName("추적 ID 는")
    class TraceId {

        @Test
        @DisplayName("모든 로그 줄에 실린다")
        void ridesOnEveryLine() throws Exception {
            mvc.perform(get("/api/health")).andExpect(status().isOk());

            assertThat(linesFromRequest())
                    .as("한 줄이라도 비면 그 줄만 어느 요청의 것인지 모르게 된다")
                    .isNotEmpty()
                    .allSatisfy(event -> assertThat(event.getMDCPropertyMap().get("traceId"))
                            .isNotBlank());
        }

        @Test
        @DisplayName("밖에서 온 `traceparent` 를 이어받는다")
        void continuesIncomingHeader() throws Exception {
            mvc.perform(get("/api/health").header("traceparent", TRACEPARENT))
                    .andExpect(status().isOk());

            assertThat(traceIdsInLog())
                    .as("자기 ID 를 새로 뽑으면 프록시·프론트 쪽 로그와 안 이어진다(W3C Trace Context)")
                    .contains(INCOMING_TRACE_ID);
        }

        @Test
        @DisplayName("같은 시각에 온 두 요청이 로그에서 갈린다")
        void staysDistinctWithinTheSameSecond() throws Exception {
            mvc.perform(get("/api/health")).andExpect(status().isOk());
            mvc.perform(get("/api/health")).andExpect(status().isOk());

            List<String> shown = traceIdsInLog().stream().map(RequestTraceTest::asShownInLog).toList();

            assertThat(shown)
                    .as("Brave 의 앞 8자리는 생성 시각이다. 앞자리를 찍으면 두 요청이 같아 보인다")
                    .doesNotHaveDuplicates()
                    .hasSize(2);
        }

        @Test
        @DisplayName("오류 응답의 값과 로그의 값이 같다")
        void matchesTheErrorResponse() throws Exception {
            // 인증 없이 막히는 경로다. 보안 필터가 끊는 자리라 응답 본문을 ProblemFactory 가 만든다.
            MvcResult result = mvc.perform(get("/api/me"))
                    .andExpect(status().isUnauthorized())
                    .andReturn();

            JsonNode body = json.readTree(result.getResponse().getContentAsString());

            assertThat(body.get("trace_id").asString())
                    .as("사용자가 불러 준 ID 로 로그를 못 찾으면 ID 를 내려 준 뜻이 없다")
                    .isIn(traceIdsInLog());
        }
    }

    @Nested
    @DisplayName("요청 한 건은")
    class RequestLine {

        @Test
        @DisplayName("메서드·경로·상태·걸린 시간이 한 줄로 남는다")
        void leavesOneLine() throws Exception {
            mvc.perform(get("/api/health")).andExpect(status().isOk());

            assertThat(messagesFrom(RequestLogFilter.class))
                    .anySatisfy(message -> assertThat(message)
                            .startsWith("GET /api/health 200 ")
                            .endsWith("ms"));
        }

        @Test
        @DisplayName("헬스체크는 안 남는다")
        void skipsActuator() throws Exception {
            mvc.perform(get("/actuator/health"));

            assertThat(messagesFrom(RequestLogFilter.class))
                    .as("30초마다 두드리는 것을 남기면 진짜 요청이 그 사이에 묻힌다")
                    .isEmpty();
        }
    }

    /** 요청 안에서 찍힌 줄만 고른다. 기동 로그에는 추적 문맥이 없다 */
    private List<ILoggingEvent> linesFromRequest() {
        return captured.list.stream()
                .filter(event -> event.getMDCPropertyMap().containsKey("traceId"))
                .toList();
    }

    private List<String> traceIdsInLog() {
        return linesFromRequest().stream()
                .map(event -> event.getMDCPropertyMap().get("traceId"))
                .distinct()
                .toList();
    }

    private List<String> messagesFrom(Class<?> source) {
        return captured.list.stream()
                .filter(event -> event.getLoggerName().equals(source.getName()))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /** 로그 패턴이 잘라 찍는 것과 같은 조각. `logback-spring.xml` 의 `%.6X{traceId}` 다 */
    private static String asShownInLog(String traceId) {
        return traceId.substring(traceId.length() - 6);
    }

    private static Logger rootLogger() {
        return (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    }
}
