package com.projectshop.shop.support;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 컨트롤러가 받을 수 있는 인자를 넓힌다.
 *
 * <p>지금은 {@link PagingArgumentResolver} 하나뿐이다(`Q23`). <b>{@code @EnableWebMvc} 를 안 쓴다</b> —
 * 그것을 붙이면 Boot 의 자동설정이 통째로 물러나서 메시지 변환기·오류 처리까지 직접 짜야 한다.
 * {@link WebMvcConfigurer} 는 자동설정을 그대로 두고 <b>더하기만</b> 한다.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new PagingArgumentResolver());
    }
}
