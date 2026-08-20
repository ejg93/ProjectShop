package com.projectshop.shop.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.projectshop.shop.PostgresTestBase;

/**
 * 오류 응답의 모양(`D5`·`D16`).
 *
 * <p><b>{@code type} 은 계약이다.</b> `D5` 가 "프론트는 상태 코드가 아니라 {@code type} 으로 분기한다"
 * 고 정했으므로 이 값이 바뀌면 화면이 깨진다. 여기서 못박아 두면 슬러그를 고칠 때 테스트가 알려 준다.
 */
@AutoConfigureMockMvc
@DisplayName("오류 응답")
class ProblemResponseTest extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;

    @Nested
    @DisplayName("형식")
    class Shape {

        @Test
        @DisplayName("인증이 없으면 401 이고 본문이 Problem Details 다")
        void unauthenticatedHasBody() throws Exception {
            mvc.perform(get("/api/me"))
                    .andExpect(status().isUnauthorized())
                    // charset 이 붙어서 정확히 같지는 않다. 타입이 맞는지만 본다.
                    .andExpect(content().contentTypeCompatibleWith(
                            MediaType.APPLICATION_PROBLEM_JSON))
                    // 보안 필터가 MVC 앞에서 끊는 자리라 @RestControllerAdvice 가 못 잡는다.
                    // 본문 없이 상태만 나가면 클라이언트가 이 하나만 다르게 처리해야 한다.
                    .andExpect(jsonPath("$.type").value("tag:projectshop.example,2026:error:unauthenticated"))
                    .andExpect(jsonPath("$.trace_id").isNotEmpty());
        }

        @Test
        @DisplayName("업무 오류에 type·title·trace_id 가 실린다")
        void businessErrorHasAllFields() throws Exception {
            mvc.perform(post("/api/auth/login").with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"nobody@test.local\",\"password\":\"whatever-long\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.type").value("tag:projectshop.example,2026:error:login-failed"))
                    .andExpect(jsonPath("$.title").isNotEmpty())
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.instance").value("/api/auth/login"))
                    .andExpect(jsonPath("$.trace_id").isNotEmpty());
        }

        @Test
        @DisplayName("보낸 traceparent 의 trace-id 를 그대로 쓴다")
        void reusesIncomingTraceId() throws Exception {
            String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";

            mvc.perform(get("/api/me")
                            .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01"))
                    // 우리가 새로 만들면 클라이언트가 들고 있는 ID 와 안 이어진다.
                    .andExpect(jsonPath("$.trace_id").value(traceId));
        }
    }

    @Nested
    @DisplayName("입력 검증")
    class Validation {

        @Test
        @DisplayName("어느 필드가 왜 틀렸는지 알려준다")
        void namesTheBadFields() throws Exception {
            mvc.perform(post("/api/auth/signup").with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"not-an-email","password":"short",
                                     "display_name":"","consents":{}}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("tag:projectshop.example,2026:error:validation-failed"))
                    // "형식이 맞지 않는다" 만 주면 어디를 고칠지 몰라서 사람이 눈으로 찾는다.
                    .andExpect(jsonPath("$.errors").isNotEmpty())
                    // 요청에 쓴 이름과 오류에 나온 이름이 다르면 화면이 그 필드를 못 찾는다.
                    .andExpect(jsonPath("$.errors[?(@.field == 'display_name')]").exists());
        }
    }
}
