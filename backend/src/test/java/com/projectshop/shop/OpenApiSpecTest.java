package com.projectshop.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 뜬 라우트가 전부 스펙에 있는지 본다(`2a`).
 *
 * <p><b>손으로 쓴 API 문서는 코드와 어긋난다.</b> 어긋나도 아무것도 안 깨져서, 문서를 믿고 부른
 * 쪽이 404 를 받고 나서야 알게 된다. 스펙을 코드에서 뽑으면 그 자리가 사라지는데 —
 * <b>뽑는 것이 실제로 도는지는 또 다른 물음</b>이라 여기서 그것만 본다.
 *
 * <p><b>한쪽만 본다.</b> 라우트 → 스펙이다. 반대 방향(스펙에만 있는 path)은 springdoc 이
 * 코드에서 뽑으므로 생길 수가 없고, 억지로 보면 springdoc 자신을 검증하는 테스트가 된다.
 *
 * <p><b>스펙 자신은 뺀다.</b> {@code /api/docs} 를 springdoc 이 스스로 그리지 않는다.
 *
 * <p>경로 변수 이름은 안 본다 — {@code /api/products/{productId}} 를 스펙이 {@code {id}} 로 적어도
 * 계약은 같다. <b>모양만 맞춘다.</b>
 *
 * <p>진짜 HTTP 로 받는다({@link HttpTestBase}). 스펙은 <b>필터를 지나서</b> 나오는 것이라,
 * 컨텍스트에서 빈을 꺼내 보면 「보안 설정이 막아 놓은 것」을 못 본다.
 */
@DisplayName("OpenAPI 스펙")
class OpenApiSpecTest extends HttpTestBase {

    /** springdoc 이 자기 자신은 안 그린다. 스펙에 없어도 맞다 */
    private static final Set<String> NOT_IN_SPEC = Set.of("/api/docs", "/api/docs.yaml");

    /** 페이지를 내주는 경로. 하나라도 빠뜨리면 그 경로만 문서가 조용히 틀린다 */
    private static final List<String> PAGED_ROUTES = List.of(
            "/api/products", "/api/seller/products", "/api/orders", "/api/seller/orders",
            "/api/audit-logs", "/api/settlements", "/api/refunds",
            "/api/me/inquiries", "/api/seller/inquiries");

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 라우트 표를 든 빈.
     *
     * <p><b>타입으로 바로 못 받는다.</b> 이 컨텍스트에 {@code HandlerMapping} 이 여럿이고
     * 그중 하나만 우리 라우트를 든다. 목록으로 받아서 골라낸다.
     */
    @Autowired
    private List<HandlerMapping> handlerMappings;

    @Test
    @DisplayName("뜬 라우트가 전부 스펙에 있다")
    void everyRouteIsInSpec() {
        Set<String> specPaths = shapesIn(spec().path("paths"));

        assertThat(routeShapes())
                .as("스펙에 없는 라우트는 부르는 쪽이 문서를 보고는 못 찾는다")
                .allSatisfy(shape -> assertThat(specPaths).contains(shape));
    }

    /**
     * 목록 경로가 {@code page}·{@code size} 를 스펙에 싣는다.
     *
     * <p><b>이 자리가 실제로 깨졌다</b>(마무리 12차). {@code Q23} 이 {@code @RequestParam int page}
     * 를 커스텀 {@code HandlerMethodArgumentResolver} 로 옮기면서 springdoc 이 그 둘을 놓쳤고,
     * 스펙에는 <b>존재하지도 않는 {@code paging} 파라미터</b>가 실렸다 —
     * 문서를 보고 부르는 쪽은 {@code ?paging=...} 을 보내게 된다.
     *
     * <p><b>경로만 보던 것이 이 검사가 없던 이유다.</b> 위 대조는 라우트가 스펙에 있나만 보므로
     * <b>파라미터가 통째로 틀려도 초록</b>이었다. 이 클래스의 전제(스펙을 코드에서 뽑으면
     * 손으로 쓴 문서가 어긋나는 자리가 사라진다)가 그만큼 비어 있었다.
     *
     * <p>고친 방법은 {@code @ParameterObject} 다. {@code Paging} 자체에는 안 붙인다 —
     * 그 record 는 {@code support} 에 살고 <b>거기서 springdoc 을 부르면
     * {@code ArchitectureTest} 의 「공용 도구는 자원을 모른다」가 막는다.</b>
     */
    @Test
    @DisplayName("목록 경로가 page·size 를 스펙에 싣는다")
    void listRoutesDocumentPaging() {
        JsonNode paths = spec().path("paths");

        for (String route : PAGED_ROUTES) {
            Set<String> names = new java.util.TreeSet<>();
            paths.path(route).path("get").path("parameters")
                    .forEach(parameter -> names.add(parameter.path("name").asString()));

            assertThat(names)
                    .as("%s 가 page·size 를 안 싣는다. 부르는 쪽이 문서만 보고는 페이지를 못 넘긴다"
                            + " (api-guidelines.md 「목록 조회」). 실린 것: %s", route, names)
                    .contains("page", "size");
        }
    }

    @Test
    @DisplayName("스펙이 OpenAPI 3.1 이다")
    void speaksOpenApi31() {
        assertThat(spec().path("openapi").asString())
                .as("버전이 갈리면 스펙을 읽는 도구가 조용히 다른 규칙으로 해석한다")
                .startsWith("3.1");
    }

    private JsonNode spec() {
        Response response = newSession().get("/api/docs");
        assertThat(response.is(200))
                .as("스펙이 200 이 아니면 %s — 보안 설정이 막았거나 springdoc 이 안 떴다", response.status())
                .isTrue();
        return JSON.readTree(response.body());
    }

    /** 우리 API 라우트를 경로 모양으로 뽑는다 */
    private List<String> routeShapes() {
        return handlerMappings.stream()
                .filter(RequestMappingHandlerMapping.class::isInstance)
                .map(RequestMappingHandlerMapping.class::cast)
                .flatMap(mapping -> mapping.getHandlerMethods().keySet().stream())
                .map(RequestMappingInfo::getPathPatternsCondition)
                .filter(Objects::nonNull)
                .flatMap(condition -> condition.getPatternValues().stream())
                .filter(path -> path.startsWith("/api/"))
                .filter(path -> !NOT_IN_SPEC.contains(path))
                .map(OpenApiSpecTest::shapeOf)
                .distinct()
                .toList();
    }

    private static Set<String> shapesIn(JsonNode paths) {
        Iterator<String> names = paths.propertyNames().iterator();
        return java.util.stream.Stream.generate(() -> names.hasNext() ? names.next() : null)
                .takeWhile(Objects::nonNull)
                .map(OpenApiSpecTest::shapeOf)
                .collect(Collectors.toSet());
    }

    /** {@code /api/products/{productId}} 와 {@code /api/products/{id}} 를 같은 것으로 본다 */
    private static String shapeOf(String path) {
        return path.replaceAll("\\{[^}]*\\}", "{}");
    }
}
