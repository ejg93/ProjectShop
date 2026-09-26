package com.projectshop.shop.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;

/**
 * 오류 응답의 모양(`D5`·`D16`).
 *
 * <p><b>{@code type} 은 계약이다.</b> `D5` 가 "프론트는 상태 코드가 아니라 {@code type} 으로 분기한다"
 * 고 정했으므로 이 값이 바뀌면 화면이 깨진다. 여기서 못박아 두면 슬러그를 고칠 때 테스트가 알려 준다.
 */
@DisplayName("오류 응답")
class ProblemResponseTest extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

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
                    .andExpect(jsonPath("$.trace_id").isNotEmpty())
                    // 사용자 문구(`Q233`). 필터가 끊는 자리도 같은 공장을 지나서 실린다.
                    .andExpect(jsonPath("$.message").value(ErrorCode.UNAUTHENTICATED.userText()))
                    // RFC 9110 제15.5.2절이 401 에 챌린지를 **MUST** 로 둔다(`Q12`).
                    // 등록된 스킴을 넣으면 브라우저 기본 인증 대화상자가 로그인 화면을 가린다.
                    .andExpect(header().string("WWW-Authenticate", "Session"));
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
                    .andExpect(jsonPath("$.trace_id").isNotEmpty())
                    // 문구가 없는 코드는 공통 문구다 — 칸 자체는 늘 있다.
                    .andExpect(jsonPath("$.message").value(ErrorCode.FALLBACK_USER_TEXT));
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

        /**
         * 실제로 있는 계정이어야 한다. 세션 생존 검사가 앞이라 없는 사용자 ID 로 보내면
         * 검증에 닿기 전에 401 이다.
         */
        private ShopUser buyer;

        @BeforeEach
        void makeBuyer() {
            long userId = new AuthFixture(jdbc).insertUser("q127@test.local", "검증");
            buyer = new ShopUser(userId, "q127@test.local", null, true);
        }

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

        /**
         * 파라미터에 걸린 제약이 있는 입구(`Q127`).
         *
         * <p>주문 입구는 헤더에 {@code @Size} 가 붙어 있어서 Spring 이 본문 검증까지
         * {@code HandlerMethodValidationException} 으로 묶어 던진다. <b>같은 검증 실패인데
         * 예외가 갈리는 자리</b>라, 여기서 이름이 하나로 나오는지를 못박는다.
         */
        @Test
        @DisplayName("헤더 제약이 있는 입구에서도 검증 실패는 같은 이름으로 나간다")
        void methodValidationKeepsTheSameType() throws Exception {
            mvc.perform(post("/api/orders")
                            .with(user(buyer))
                            .with(csrf())
                            .header("Idempotency-Key", "11111111-2222-3333-4444-555555555555")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"cart_item_ids":[1],
                                     "shipping":{"receiver_name":"홍길동",
                                                 "receiver_phone":"010-0000-0000",
                                                 "postal_code":"우편번호아님",
                                                 "address1":"서울특별시 강남구"}}
                                    """))
                    .andExpect(status().isBadRequest())
                    // 그전에는 malformed-request 였다. 화면에 validation-failed 갈래가
                    // 이미 있는데 그 이름이 안 와서 "결제하지 못했습니다" 로 떨어졌다.
                    .andExpect(jsonPath("$.type").value("tag:projectshop.example,2026:error:validation-failed"))
                    // 중첩 본문이라 점 표기다. 화면이 이 이름으로 칸을 찾는다.
                    .andExpect(jsonPath("$.errors[?(@.field == 'shipping.postal_code')]").exists());
        }

        /**
         * 본문 칸이 아닌 것이 틀린 경우.
         *
         * <p><b>Java 이름이 아니라 보낸 이름으로 부른다</b> — 헤더는 요청에
         * {@code Idempotency-Key} 로 실려 있었고, {@code idempotency_key} 를 주면
         * 화면이 없는 칸을 짚는다(`D20` 「모르는 칸을 지목하지 않는다」).
         */
        @Test
        @DisplayName("헤더가 틀리면 헤더 이름을 그대로 짚는다")
        void namesTheHeaderAsSent() throws Exception {
            mvc.perform(post("/api/orders")
                            .with(user(buyer))
                            .with(csrf())
                            .header("Idempotency-Key", "")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"cart_item_ids":[1],
                                     "shipping":{"receiver_name":"홍길동",
                                                 "receiver_phone":"010-0000-0000",
                                                 "postal_code":"06134",
                                                 "address1":"서울특별시 강남구"}}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("tag:projectshop.example,2026:error:validation-failed"))
                    .andExpect(jsonPath("$.errors[?(@.field == 'Idempotency-Key')]").exists());
        }
    }

    /**
     * 컨트롤러 밖에서 끊는 자리({@code Q84}).
     *
     * <p><b>{@code @ExceptionHandler} 는 여기까지 못 온다.</b> 필터가 먼저 끊으면 MVC 를 안 지나서
     * 그전에는 셋 다 상태 코드만 나갔다 — 받는 쪽에 {@code trace_id} 도 {@code type} 도 없었고
     * {@code ProblemFactory} 를 안 지나 <b>오류율 지표에도 안 잡혔다</b>(청크 62).
     */
    @Nested
    @DisplayName("필터가 끊는 자리")
    class FilterCut {

        /**
         * 로그인은 했는데 권한이 없는 요청이다. 지표는 관리자만 본다({@code Q53}).
         *
         * <p>그전에는 스프링 기본 {@code AccessDeniedHandler} 의 {@code sendError} 로 나가서
         * 본문이 비었다.
         */
        @Test
        @DisplayName("인가 거부가 403 이고 본문이 Problem Details 다")
        void accessDeniedHasBody() throws Exception {
            mvc.perform(get("/actuator/prometheus").with(user("nobody")))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(
                            MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("tag:projectshop.example,2026:error:access-denied"))
                    .andExpect(jsonPath("$.trace_id").isNotEmpty());
        }
    }
}
