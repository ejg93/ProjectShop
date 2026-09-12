package com.projectshop.shop;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 응답의 시각이 UTC {@code Z} 로 나간다({@code Q35}, {@code time-rules.md} 「API 표기 — UTC」).
 *
 * <p><b>문서 둘이 갈려 있었다.</b> {@code time-rules.md} 는 {@code "2026-08-06T00:55:01Z"} 이고
 * {@code api-guidelines.md} 「값의 형식」은 {@code +09:00} 을 예로 들었다. 시간 문서가 이긴다 —
 * 「문자열로 정렬해도 시간 순서와 같다」는 약속은 오프셋이 하나일 때만 성립한다.
 *
 * <p><b>왜 HTTP 층인가.</b> 재는 것이 Jackson 직렬화 결과라 {@code MockMvc} 로도 되지만,
 * {@code OpenApiSpecTest} 와 같은 이유로 진짜 HTTP 를 받는다 — 보안 설정이 막는 것까지 같이 본다.
 *
 * <p><b>강제 지점은 설정이다.</b> {@code application.yml} 의 {@code spring.jackson.time-zone: UTC} 가
 * 문맥 시간대를 박고, 이 테스트는 그 설정이 실제로 나가는 문자열을 정하는지를 고정한다.
 * Jackson 3 는 {@code WRITE_DATES_WITH_CONTEXT_TIME_ZONE} 이 기본이라 설정이 없어도 UTC 로 나갔다 —
 * 그러나 기본값은 다음 판에서 바뀔 수 있고 누가 {@code time-zone} 을 서울로 바꾸면 그 순간 어긋난다.
 * 그때 여기가 빨개진다(설정을 {@code Asia/Seoul} 로 바꿔서 확인했다).
 *
 * <p>{@code /api/policies/privacy_policy} 를 본다 — 로그인이 없고 {@code V21} 이 넣은 행이라
 * 픽스처 없이 시각({@code effective_at})이 하나 나온다.
 */
class ResponseTimeFormatTest extends HttpTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** RFC 3339, 소수 초 유무 무관, 오프셋은 {@code Z} 만. */
    private static final String UTC_INSTANT = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?Z";

    @Test
    @DisplayName("응답의 시각은 UTC 의 Z 로 끝난다")
    void timestampsEndWithZ() {
        Response response = newSession().get("/api/policies/privacy_policy");
        assertThat(response.is(200)).as("정책 문서는 로그인 없이 읽힌다 — %s", response.body()).isTrue();

        JsonNode effectiveAt = JSON.readTree(response.body()).path("effective_at");

        assertThat(effectiveAt.isString())
                .as("effective_at 이 문자열이 아니면 시각이 숫자(epoch)로 나가고 있다")
                .isTrue();
        assertThat(effectiveAt.asString())
                .as("오프셋이 Z 가 아니면 문자열 정렬이 시간 순서와 갈린다 (time-rules.md 「API 표기 — UTC」)")
                .matches(UTC_INSTANT);
    }

    @Test
    @DisplayName("정규식이 서울 오프셋을 거부한다")
    void regexRejectsSeoulOffset() {
        assertThat("2026-08-05T14:30:00+09:00")
                .as("+09:00 을 통과시키면 위 테스트가 아무것도 안 잰다")
                .doesNotMatch(UTC_INSTANT);
    }
}
