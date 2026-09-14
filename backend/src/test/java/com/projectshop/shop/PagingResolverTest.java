package com.projectshop.shop;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import org.hamcrest.Matchers;

/**
 * {@code page}·{@code size} 가 요청에서 {@link com.projectshop.shop.support.ListQuery.Paging}
 * 으로 바뀌는지 본다(`Q23`).
 *
 * <p><b>인자를 만들어 주는 것은 우리가 부르지 않는다.</b> 스프링이 부르므로,
 * {@code Query} 를 직접 부르는 테스트는 이미 만들어진 {@code Paging} 을 넘겨서 이 경로를 안 지난다.
 * 그래서 요청으로 재야 한다.
 *
 * <h2>HTTP 층에서 통합 층으로 내렸다(`Q26`)</h2>
 *
 * <p>{@code Q23} 이 이 테스트를 HTTP 층에 뒀고 근거는 「서블릿 컨테이너가 부른다」였다.
 * 그런데 {@code D15} 가 <b>「여기 있어야 할 이유가 쿠키·세션·실제 상태 코드가 아니면 통합 층으로
 * 내린다」</b>고 정해 뒀고 그 근거는 그 목록에 없다. HTTP 층은 <b>롤백이 없어서 정리 비용이 붙는</b>
 * 층이라 케이스를 늘리지 않기로 한 자리다.
 *
 * <p><b>내려도 재는 것이 안 줄었다.</b> {@code MockMvc} 는 {@code WebMvcConfigurer} 를 그대로 태워서
 * {@code PagingArgumentResolver} 가 같은 자리에서 돈다 — 실측으로 확인했다.
 *
 * <p><b>등록을 빼고 재 봤더니 넷 중 하나만 빨갰다</b>(`Q23`). 스프링이
 * {@code Paging} 을 <b>생성자 바인딩</b>으로 만들어 주고, 그러면 보정·400 은 그대로 돈다 —
 * <b>강제가 {@code PagingArgumentResolver} 에 매달려 있지 않다</b>는 증거다.
 * 그 클래스가 혼자 드는 것은 <b>기본값</b>뿐이다: 없으면 스프링이 0 을 넣고 생성자가 1 로 보정해서
 * {@code size} 가 20 이 아니라 1 이 된다. 그래서 등록이 빠지면 「안 주면 기본값」만 무너진다.
 *
 * <p><b>공개 목록을 쓴다.</b> 로그인도 데이터도 필요 없어서, 재는 대상(파라미터를 어떻게 읽나)만 남는다.
 */
@DisplayName("페이지 인자 주입")
class PagingResolverTest extends PostgresTestBase {

    private static final String PUBLIC_LIST = "/api/products";

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("안 주면 기본값이 실린다")
    void defaultsWhenAbsent() throws Exception {
        mvc.perform(get(PUBLIC_LIST))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.allOf(
                        Matchers.containsString("\"page\":0"),
                        Matchers.containsString("\"size\":20"))));
    }

    @Test
    @DisplayName("상한을 넘겨도 100 에서 잘린다")
    void capsOversizedRequest() throws Exception {
        mvc.perform(get(PUBLIC_LIST).param("page", "0").param("size", "5000"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("\"size\":100")));
    }

    @Test
    @DisplayName("음수 페이지는 0 으로 본다")
    void negativePageIsFirstPage() throws Exception {
        mvc.perform(get(PUBLIC_LIST).param("page", "-3").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("\"page\":0")));
    }

    /**
     * <b>계약을 안 바꿨다.</b> {@code @RequestParam int} 였을 때 스프링이 내던 400 을
     * 그대로 낸다 — 조용히 기본값으로 떨어뜨리면 오타를 낸 쪽이 영영 모른다.
     */
    @Test
    @DisplayName("숫자가 아니면 400 이다")
    void rejectsNonNumeric() throws Exception {
        mvc.perform(get(PUBLIC_LIST).param("size", "abc"))
                .andExpect(status().isBadRequest());
    }
}
