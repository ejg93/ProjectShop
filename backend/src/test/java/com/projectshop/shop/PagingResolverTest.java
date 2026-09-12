package com.projectshop.shop;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code page}·{@code size} 가 실제 HTTP 요청에서 {@link com.projectshop.shop.support.ListQuery.Paging}
 * 으로 바뀌는지 본다(`Q23`).
 *
 * <p><b>이 층이라야 확인된다.</b> 인자를 만들어 주는 것을 <b>서블릿 컨테이너가 부르므로</b>,
 * {@code Query} 를 직접 부르는 테스트는 이미 만들어진 {@code Paging} 을 넘겨서 이 경로를 안 지난다.
 *
 * <p><b>등록을 빼고 재 봤더니 넷 중 하나만 빨갰다</b>(`Q23`). Spring 이
 * {@code Paging} 을 <b>생성자 바인딩</b>으로 만들어 주고, 그러면 보정·400 은 그대로 돈다 —
 * <b>강제가 {@code PagingArgumentResolver} 에 매달려 있지 않다</b>는 증거다.
 * 그 클래스가 혼자 드는 것은 <b>기본값</b>뿐이다: 없으면 Spring 이 0 을 넣고 생성자가 1 로 보정해서
 * {@code size} 가 20 이 아니라 1 이 된다. 그래서 등록이 빠지면 「안 주면 기본값」만 무너진다.
 *
 * <p><b>공개 목록을 쓴다.</b> 로그인도 데이터도 필요 없어서, 재는 대상(파라미터를 어떻게 읽나)만 남는다.
 */
@DisplayName("페이지 인자 주입")
class PagingResolverTest extends HttpTestBase {

    private static final String PUBLIC_LIST = "/api/products";

    @Test
    @DisplayName("안 주면 기본값이 실린다")
    void defaultsWhenAbsent() {
        Response response = newSession().get(PUBLIC_LIST);

        assertThat(response.is(200)).isTrue();
        assertThat(response.body())
                .describedAs("컨트롤러마다 적던 defaultValue 를 ListQuery.DEFAULT_SIZE 로 모았다")
                .contains("\"page\":0", "\"size\":20");
    }

    @Test
    @DisplayName("상한을 넘겨도 100 에서 잘린다")
    void capsOversizedRequest() {
        Response response = newSession().get(PUBLIC_LIST + "?page=0&size=5000");

        assertThat(response.is(200)).isTrue();
        assertThat(response.body())
                .describedAs("안 막으면 목록 하나로 전체를 긁어 간다(`D5`)")
                .contains("\"size\":100");
    }

    @Test
    @DisplayName("음수 페이지는 0 으로 본다")
    void negativePageIsFirstPage() {
        Response response = newSession().get(PUBLIC_LIST + "?page=-3&size=20");

        assertThat(response.is(200)).isTrue();
        assertThat(response.body()).contains("\"page\":0");
    }

    /**
     * <b>계약을 안 바꿨다.</b> {@code @RequestParam int} 였을 때 Spring 이 내던 400 을
     * 그대로 낸다 — 조용히 기본값으로 떨어뜨리면 오타를 낸 쪽이 영영 모른다.
     */
    @Test
    @DisplayName("숫자가 아니면 400 이다")
    void rejectsNonNumeric() {
        Response response = newSession().get(PUBLIC_LIST + "?size=abc");

        assertThat(response.is(400))
                .describedAs("실제 응답: %s %s", response.status(), response.body())
                .isTrue();
    }
}
