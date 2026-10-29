package com.projectshop.shop.support;

import java.util.Objects;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.QueryParameter;

import org.springdoc.core.customizers.OpenApiCustomizer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 스펙이 응답과 같은 표기를 쓰게 한다(`Q41`).
 *
 * <p><b>스펙이 응답과 갈려 있었다.</b> 응답은 {@code order_number} 로 나가는데 스펙은
 * {@code orderNumber} 라고 그렸다. {@code api-guidelines.md} 가
 * 「스펙에서 타입을 생성하면 {@code snake_case} 로 나온다」를 전제로 프론트 타입 생성을 적어 뒀는데
 * <b>그 전제가 깨져 있던 것</b>이라, 스펙을 보고 부르는 쪽은 없는 키를 읽는다.
 *
 * <p><b>왜 저절로 안 맞았나.</b> 애플리케이션은 Jackson 3({@code tools.jackson})을 쓰고
 * 스펙을 그리는 swagger-core 는 Jackson 2({@code com.fasterxml.jackson})다. 타입이 아예 달라서
 * 스프링이 만든 매퍼를 그대로 건넬 수가 없고, swagger 쪽은 자기 기본값(낙타 표기)으로 그린다.
 *
 * <p><b>그래서 표기를 여기서 한 번 더 정한다.</b> 값이 두 곳에 사는 모양이 되므로
 * {@code application.yml} 의 그 줄을 읽어서 쓰고, <b>모르는 값이면 뜨지 않는다</b> —
 * 조용히 기본값으로 돌아가면 지금 고친 것이 그대로 되돌아온다.
 *
 * <p>갈리는 것 자체는 {@code OpenApiSpecTest} 가 막는다. 설정만 고치면 다음에 또 갈린다.
 */
@Configuration
class OpenApiConfig {

    private static final String SNAKE_CASE = "SNAKE_CASE";

    private final String namingStrategy;

    OpenApiConfig(@Value("${spring.jackson.property-naming-strategy}") String namingStrategy) {
        this.namingStrategy = namingStrategy;
    }

    @Bean
    ModelResolver modelResolver() {
        if (!SNAKE_CASE.equals(namingStrategy)) {
            throw new IllegalStateException(
                    "spring.jackson.property-naming-strategy 가 " + namingStrategy + " 다."
                            + " 스펙을 그리는 swagger-core 는 Jackson 2 라 그 설정을 못 읽으므로"
                            + " 여기에 같은 표기를 적어야 한다 (Q41, api-guidelines.md 「JSON 속성은 snake_case」)");
        }
        com.fasterxml.jackson.databind.ObjectMapper specMapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        specMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        return new ModelResolver(specMapper);
    }

    /**
     * 모든 {@code GET} 에 {@code fields} 를 적는다(`Q309a`).
     *
     * <p>걸리는 자리가 {@link FieldSelectionAdvice} 하나라 입구 선언에 파라미터가 없다 — 여기서 안 적으면
     * 스펙만 보는 쪽이 칸을 고를 수 있는지 모른다. 입구마다 적지 않는 것과 같은 이유로 한 곳에서 적는다.
     */
    @Bean
    OpenApiCustomizer fieldSelectionParameter() {
        return openApi -> openApi.getPaths().values().stream()
                .map(PathItem::getGet)
                .filter(Objects::nonNull)
                .forEach(get -> get.addParametersItem(new QueryParameter()
                        .name(FieldSelection.PARAMETER)
                        .description("쉼표로 구분한 최상위 칸 이름. 목록이면 items 의 원소에 건다. 모르는 이름은 400")
                        .schema(new StringSchema())));
    }
}
