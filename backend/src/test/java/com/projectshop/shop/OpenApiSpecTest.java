package com.projectshop.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

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

    /** 소문자·숫자·하이픈, 그리고 {@code {자리표시자}} 만(`D5`) */
    private static final Pattern LOWERCASE_PATH =
            Pattern.compile("^/api(/[a-z0-9-]+|/\\{[A-Za-z]+\\})+$");


    /** 그 자원들의 응답에 실리면 안 되는 내부 ID */
    private static final Set<String> INTERNAL_IDS =
            Set.of("order_id", "seller_order_id", "payment_id", "settlement_id");

    /**
     * 소문자와 숫자와 밑줄만(`D5` 「JSON 속성은 snake_case」).
     *
     * <p><b>맨 앞 밑줄은 허용한다.</b> {@code _visible_field_groups} 가 그 꼴이고
     * {@code @JsonProperty} 로 박혀 있다 — 자원의 값이 아니라 <b>봉투에 실린 메타</b>라는 표식이다.
     */
    private static final Pattern SNAKE_CASE = Pattern.compile("^_?[a-z][a-z0-9_]*$");

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

    /**
     * 경로가 소문자와 하이픈으로만 돼 있다(`Q31`, `D5`).
     *
     * <p><b>문서에만 있던 규칙이다.</b> 밑줄이나 낙타 표기가 섞인 경로가 하나 나가면
     * <b>되돌릴 수가 없다</b> — 부르는 쪽이 그 주소를 박아 두기 때문이다. 규칙 중에
     * 늦게 고칠수록 비싸지는 쪽이라 코드보다 먼저 막는다.
     *
     * <p><b>단수·복수는 못 잰다.</b> {@code /api/products} 가 맞고 {@code /api/product} 가 틀린 것은
     * 뜻을 읽어야 정해져서 사람이 본다.
     */
    @Test
    @DisplayName("API 경로가 소문자와 하이픈으로만 돼 있다")
    void pathsAreLowercaseHyphen() {
        // 라우트를 못 뽑으면 0개를 재고 조용히 통과한다. 그쪽이 표기가 틀린 것보다 나쁘다.
        assertThat(apiRoutes()).hasSizeGreaterThan(20);

        assertThat(apiRoutes().stream().filter(route -> !LOWERCASE_PATH.matcher(route).matches()).toList())
                .as("한 번 나간 주소는 부르는 쪽이 박아 둬서 못 고친다"
                        + " (api-guidelines.md 「경로」). 소문자·하이픈과 {자리표시자} 만 쓴다")
                .isEmpty();
    }

    /**
     * 노출 번호가 있는 자원의 응답에 내부 ID 가 안 실린다(`Q31`, `identifier-rules.md` 「자원별 노출 방식」).
     *
     * <p><b>순번이 새면 총량이 샌다.</b> 주문 ID 가 4837 이면 그날까지 주문이 4837 건이라는 뜻이고,
     * 경쟁사가 두 번 호출해서 빼는 것이 증가 속도다. 그래서 그 자원들은 번호를 따로 둔다.
     *
     * <p><b>ArchUnit 이 아니라 여기서 잰다.</b> 구조로 재면 서비스 안에서만 도는 record 까지 물어서
     * 「나가지 않는 것」을 고치라고 시킨다. <b>스펙이 곧 나가는 것</b>이라 여기가 정확하다.
     *
     * <p><b>경로별로는 못 잰다.</b> 페이지 응답이 {@code Page} 스키마 하나를 자원 전체가 나눠 써서,
     * {@code /api/orders} 의 응답을 따라가면 {@code settlementNumber} 까지 딸려 온다(`Q31` 에서 실측).
     * 그래서 <b>스펙 전체에 그 이름이 없는지</b>로 잰다 — 넷 다 어느 응답에도 실릴 것이 아니라
     * 범위를 넓혀도 뜻이 같다.
     *
     * <p>이름을 뱀 표기로 적는다 — 스펙과 응답이 같은 표기를 쓰는 것은 `Q41` 이 세웠고
     * 바로 아래 {@link #specPropertiesAreSnakeCase} 가 그것을 지킨다.
     */
    @Test
    @DisplayName("스펙 어느 응답에도 내부 ID 가 없다")
    void responsesExposeNumbersNotIds() {
        Set<String> properties = specProperties(spec());

        // 스키마를 못 걷으면 0개를 재고 조용히 통과한다.
        assertThat(properties).hasSizeGreaterThan(50);

        assertThat(properties.stream().filter(INTERNAL_IDS::contains).toList())
                .as("내부 ID 가 나가면 노출 번호를 둔 의미가 사라진다"
                        + " (identifier-rules.md 「순번을 노출하면 새는 것」)")
                .isEmpty();
    }

    /**
     * 스펙의 속성 이름이 응답과 같은 표기다(`Q41`).
     *
     * <p><b>갈려 있었다.</b> 응답은 {@code order_number} 인데 스펙은 {@code orderNumber} 였다 —
     * 스펙을 그리는 swagger-core 가 Jackson 2 라 애플리케이션의 Jackson 3 설정을 못 읽는다.
     * {@link com.projectshop.shop.support.OpenApiConfig} 가 표기를 물리고 여기서 갈리는 것을 막는다.
     *
     * <p><b>설정만 고치고 끝내지 않는 이유</b>: 라이브러리를 올리거나 그 빈이 다른 것에 덮이면
     * 조용히 낙타 표기로 돌아간다. 그때 깨지는 것은 <b>스펙을 읽고 만든 프론트 타입</b>이라
     * 백엔드 쪽에서는 아무것도 안 빨개진다.
     */
    @Test
    @DisplayName("스펙의 속성 이름이 뱀 표기다")
    void specPropertiesAreSnakeCase() {
        Set<String> properties = specProperties(spec());

        assertThat(properties).hasSizeGreaterThan(50);
        assertThat(properties.stream().filter(name -> !SNAKE_CASE.matcher(name).matches()).toList())
                .as("스펙에서 타입을 생성하면 snake_case 로 나온다는 것이 프론트 타입 생성의 전제다"
                        + " (api-guidelines.md 「JSON 속성은 snake_case」). 갈리면 없는 키를 읽는다")
                .isEmpty();
    }

    /**
     * 실제 응답의 키가 전부 스펙에 있다(`Q41`).
     *
     * <p>표기만 재면 <b>둘 다 뱀 표기인데 서로 다른 이름</b>인 경우를 못 본다.
     * 진짜 응답을 하나 받아서 그 키가 스펙에 있는지 본다.
     *
     * <p><b>지금은 신호가 얇다.</b> 상품 목록의 최상위 키가 전부 한 단어({@code items}·{@code page}·
     * {@code size}·{@code total})라 두 표기에서 글자가 같다 — 설정을 빼고 돌려 보니
     * <b>이 대조는 안 빨개졌다</b>(위 표기 검사만 물었다). 여러 단어 키가 최상위에 생기면 그때부터 문다.
     */
    @Test
    @DisplayName("실제 응답의 키가 전부 스펙에 있다")
    void specMatchesRealResponse() {
        Response response = newSession().get("/api/products");
        assertThat(response.is(200))
                .as("상품 목록이 %s — 이 대조는 응답을 받아야 성립한다", response.status())
                .isTrue();

        Set<String> specProperties = specProperties(spec());
        List<String> missing = names(JSON.readTree(response.body())).stream()
                .filter(key -> !specProperties.contains(key))
                .toList();

        assertThat(missing)
                .as("응답에 있는데 스펙에 없는 키는 부르는 쪽이 문서만 보고는 모른다")
                .isEmpty();
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
        return apiRoutes().stream().map(OpenApiSpecTest::shapeOf).distinct().toList();
    }

    /** 뜬 라우트를 중괄호까지 그대로 뽑는다. 경로 표기를 재려면 자리표시자 이름이 남아 있어야 한다 */
    private List<String> apiRoutes() {
        return handlerMappings.stream()
                .filter(RequestMappingHandlerMapping.class::isInstance)
                .map(RequestMappingHandlerMapping.class::cast)
                .flatMap(mapping -> mapping.getHandlerMethods().keySet().stream())
                .map(RequestMappingInfo::getPathPatternsCondition)
                .filter(Objects::nonNull)
                .flatMap(condition -> condition.getPatternValues().stream())
                .filter(path -> path.startsWith("/api/"))
                .filter(path -> !NOT_IN_SPEC.contains(path))
                .distinct()
                .toList();
    }

    /** 스펙의 모든 스키마에 실린 속성 이름 */
    private static Set<String> specProperties(JsonNode spec) {
        Set<String> properties = new java.util.TreeSet<>();
        for (String schemaName : names(spec.path("components").path("schemas"))) {
            collectFrom(spec.path("components").path("schemas").path(schemaName),
                    properties, new java.util.HashSet<>());
        }
        return properties;
    }

    private static Set<String> shapesIn(JsonNode paths) {
        Iterator<String> names = paths.propertyNames().iterator();
        return java.util.stream.Stream.generate(() -> names.hasNext() ? names.next() : null)
                .takeWhile(Objects::nonNull)
                .map(OpenApiSpecTest::shapeOf)
                .collect(Collectors.toSet());
    }

    private static List<String> names(JsonNode object) {
        Iterator<String> iterator = object.propertyNames().iterator();
        return java.util.stream.Stream.generate(() -> iterator.hasNext() ? iterator.next() : null)
                .takeWhile(Objects::nonNull)
                .toList();
    }


    /** 한 덩이에서 속성 이름과 {@code $ref} 를 같이 긁는다 */
    private static void collectFrom(JsonNode node, Set<String> properties, Set<String> refs) {
        if (node.isArray()) {
            node.forEach(child -> collectFrom(child, properties, refs));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        for (String name : names(node)) {
            JsonNode child = node.path(name);
            if ("$ref".equals(name)) {
                refs.add(child.asString("").replace("#/components/schemas/", ""));
            } else if ("properties".equals(name)) {
                properties.addAll(names(child));
                collectFrom(child, properties, refs);
            } else {
                collectFrom(child, properties, refs);
            }
        }
    }

    /** {@code /api/products/{productId}} 와 {@code /api/products/{id}} 를 같은 것으로 본다 */
    private static String shapeOf(String path) {
        return path.replaceAll("\\{[^}]*\\}", "{}");
    }
}
