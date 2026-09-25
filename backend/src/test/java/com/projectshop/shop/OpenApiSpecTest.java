package com.projectshop.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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


    /** 노출 번호를 둔 자원의 내부 ID. 어느 응답에도 실리면 안 된다(`identifier-rules.md` 「자원별 노출 방식」) */
    private static final Set<String> INTERNAL_IDS =
            Set.of("order_id", "seller_order_id", "payment_id", "settlement_id");

    /**
     * 소문자와 숫자와 밑줄만(`D5` 「JSON 속성은 snake_case」).
     *
     * <p><b>맨 앞 밑줄은 허용한다.</b> {@code _visible_field_groups} 가 그 꼴이고
     * {@code @JsonProperty} 로 박혀 있다 — 자원의 값이 아니라 <b>봉투에 실린 메타</b>라는 표식이다.
     */
    private static final Pattern SNAKE_CASE = Pattern.compile("^_?[a-z][a-z0-9_]*$");

    /**
     * 여러 경로가 <b>같은 record 를</b> 응답으로 쓰는 자리. 합쳐진 것이 아니라 같은 것이다.
     *
     * <p>줄이는 방향으로만 고친다. 늘리려면 <b>정말 같은 타입인지</b> 먼저 본다 —
     * 합쳐진 것을 여기 적으면 이 게이트가 자기가 막으려던 것을 봐주게 된다.
     */
    private static final Map<String, List<String>> SHARED_RESPONSES = Map.of(
            "Account", List.of("GET /api/me", "PATCH /api/me", "POST /api/me/email/confirm"),
            // 내 주문과 관리자 목록(`Q176`)이 같은 record 를 낸다 — 문의 목록 셋과 같은 모양이다
            "OrderPage", List.of("GET /api/admin/orders", "GET /api/orders"),
            "PageInquiryEntry", List.of("GET /api/inquiries", "GET /api/me/inquiries",
                    "GET /api/seller/inquiries"),
            "ProductCreated", List.of("POST /api/products", "PUT /api/products/{productId}"),
            "Refund", List.of("POST /api/refunds", "POST /api/refunds/{refundNumber}/approve",
                    "POST /api/refunds/{refundNumber}/reject"));

    /**
     * 진짜 응답을 받아 스펙과 맞춰 볼 경로. <b>로그인 없이 200 이 나오는 것</b>만 쓴다 —
     * 재는 대상은 응답의 모양이지 권한이 아니다.
     *
     * <p>{@code /api/health} 는 여러 단어 키({@code applied_migrations}·{@code checked_at})가
     * <b>데이터 없이도 늘 있어서</b> 표기가 갈리면 바로 드러난다. 실제로 {@code OpenApiConfig} 를
     * 빼고 돌려 보니 그 둘을 짚었다.
     *
     * <p><b>목록 안쪽은 아직 못 본다.</b> 통합 레인 DB 에 상품이 없어서 {@code items} 가 비고,
     * 그래서 걷는 키가 여덟(건강 확인 넷 + 목록 바깥 넷)이다. 목록에 행을 만들어 주는 자리가 생기면
     * 그때 안쪽까지 걸린다 — <b>지금 값은 「데이터 없이도 무는 만큼」이다.</b>
     */
    private static final List<String> REAL_RESPONSE_ROUTES = List.of("/api/health", "/api/products");

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
     * 목록 껍데기는 {@code items}·{@code page}·{@code size}·{@code total} 넷이다(`Q227`, {@code quality-gates.md} 「규칙 원장」 ⑨).
     *
     * <p>위 시험은 목록 경로가 page·size 를 <b>요청</b>에 싣는지만 봤다. 응답 껍데기 이름이 섞이면({@code content}·{@code count})
     * 화면이 엔드포인트마다 다른 코드를 쓴다({@code api-guidelines.md} 「목록 조회 — 응답」).
     *
     * <p><b>{@code page} 가 {@code items} 나 {@code total} 과 같이 있을 때만 껍데기로 본다.</b> {@code total} 은 금액으로도 쓰이고
     * (장바구니 {@code Cart(items, total)}·쿠폰 {@code Applied}), {@code items} 만 있는 도메인 목록(주문 항목)은 껍데기가 아니다.
     */
    @Test
    @DisplayName("목록 응답 껍데기가 items·page·size·total 넷을 다 싣는다")
    void pageEnvelopesCarryAllFour() {
        JsonNode schemas = spec().path("components").path("schemas");
        List<String> envelopes = new ArrayList<>();
        List<String> partial = new ArrayList<>();
        for (String name : names(schemas)) {
            Set<String> properties = new java.util.TreeSet<>(names(schemas.path(name).path("properties")));
            if (!properties.contains("page") || !(properties.contains("items") || properties.contains("total"))) {
                continue;
            }
            envelopes.add(name);
            if (!properties.containsAll(Set.of("items", "page", "size", "total"))) {
                partial.add(name + " " + properties);
            }
        }
        assertThat(envelopes).as("목록 껍데기를 0개 찾았다 — 스키마를 못 걷었거나 기준이 바뀌었다").hasSizeGreaterThan(5);
        assertThat(partial)
                .as("껍데기 넷 중 빠진 것이 있다 — 이름을 하나로 고정한다(api-guidelines.md 「목록 조회 — 응답」)")
                .isEmpty();
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
     * <p><b>스펙 전체를 본다.</b> 넷 다 어느 응답에도 실릴 것이 아니라 범위를 넓혀도 뜻이 같다.
     *
     * <p><b>「경로별로는 못 잰다」고 적었던 것은 틀렸다</b>(`Q31` → `Q45`). 그때는
     * {@code /api/orders} 를 따라가면 정산 필드가 딸려 와서 스키마가 공유되는 줄 알았는데,
     * 실제 원인은 <b>서로 다른 타입이 같은 이름으로 합쳐진 것</b>이었다. `Q45` 가 이름을 갈라서
     * 지금은 경로별로도 잴 수 있고, 그 대조를 `Q43` 이 세웠다({@link #specMatchesRealResponse}).
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
     * <p><b>한 방향만 본다.</b> 「응답에 있는데 스펙에 없는 키」만 세므로 <b>스펙이 실물보다 넓은</b>
     * 경우는 못 잡는다 — 값이 없을 때 빠지는 칸이 정상이라 반대 방향을 그대로 재면 오탐이 된다.
     * 스펙이 넓어지는 사고(타입이 이름으로 합쳐지는 것)는 {@code SchemaNameTest} 가 소스에서 막는다.
     *
     * <p><b>처음 세웠을 때는 아무것도 안 쟀다</b>(`Q41` → `Q43`, 마무리 독립 리뷰가 짚었다).
     * 맨 바깥 키만 봤고({@code items}·{@code page}·{@code size}·{@code total} — 전부 한 단어라
     * 표기가 틀려도 글자가 같다), 비교 대상도 <b>스펙 전체 속성을 한 덩이로 합친 것</b>이라
     * 엉뚱한 응답에만 있는 이름이어도 통과했다.
     *
     * <p><b>고칠 수 있게 된 것은 `Q45` 덕이다.</b> 그전에는 응답 타입이 이름으로 합쳐져 있어서
     * 「이 경로의 스키마」를 고를 수가 없었다. 지금은 경로마다 제 스키마를 가리킨다.
     *
     * <p>응답을 <b>중첩까지 전부</b> 걷고, 그 경로의 스키마에서 {@code $ref} 를 따라간 속성과만
     * 맞춘다. 목록 안쪽의 여러 단어 키가 여기서 걸린다.
     */
    @Test
    @DisplayName("실제 응답의 키가 그 경로의 스펙에 전부 있다")
    void specMatchesRealResponse() {
        JsonNode spec = spec();
        List<String> missing = new java.util.ArrayList<>();
        int checked = 0;

        for (String path : REAL_RESPONSE_ROUTES) {
            Response response = newSession().get(path);
            assertThat(response.is(200))
                    .as("%s 가 %s — 이 대조는 응답을 받아야 성립한다", path, response.status())
                    .isTrue();

            Set<String> keys = new java.util.TreeSet<>();
            collectKeys(JSON.readTree(response.body()), keys);
            Set<String> declared = schemaProperties(spec, path);
            checked += keys.size();

            keys.stream().filter(key -> !declared.contains(key))
                    .forEach(key -> missing.add(path + "  " + key));
        }

        // 응답이 비면 0개를 재고 조용히 통과한다. 목록 안쪽까지 걷는 것이 이 대조의 값이다.
        assertThat(checked).isGreaterThan(7);

        assertThat(missing)
                .as("응답에 있는데 그 경로의 스펙에 없는 키는, 문서를 보고 부르는 쪽이 모른다."
                        + " 스펙을 코드에서 뽑는 이유가 이 자리다")
                .isEmpty();
    }

    /** JSON 을 중첩까지 걸어 객체의 키를 전부 모은다 */
    private static void collectKeys(JsonNode node, Set<String> keys) {
        if (node.isArray()) {
            node.forEach(child -> collectKeys(child, keys));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        for (String name : names(node)) {
            keys.add(name);
            collectKeys(node.path(name), keys);
        }
    }

    /** 한 경로의 200 응답 스키마에서 {@code $ref} 를 따라간 속성 이름 */
    private static Set<String> schemaProperties(JsonNode spec, String path) {
        JsonNode content = spec.path("paths").path(path).path("get").path("responses")
                .path("200").path("content");
        Set<String> properties = new java.util.TreeSet<>();
        Set<String> pending = new java.util.LinkedHashSet<>();
        for (String mediaType : names(content)) {
            collectFrom(content.path(mediaType).path("schema"), properties, pending);
        }
        Set<String> seen = new java.util.HashSet<>();
        while (!pending.isEmpty()) {
            String schema = pending.iterator().next();
            pending.remove(schema);
            if (seen.add(schema)) {
                collectFrom(spec.path("components").path("schemas").path(schema), properties, pending);
            }
        }
        return properties;
    }

    /**
     * 서로 다른 응답 타입이 스펙에서 같은 이름으로 합쳐지지 않는다(`Q45`).
     *
     * <p><b>실제로 합쳐져 있었다.</b> springdoc 이 스키마 이름을 <b>자바 클래스의 짧은 이름</b>으로
     * 짓는데 {@code Page} 가 여섯, {@code Summary}·{@code Detail} 이 각각 넷이라 서로 덮어썼다 —
     * <b>{@code GET /api/orders} 가 정산 요약을 돌려준다고 스펙에 적혀 있었다.</b>
     * 이름을 준 뒤 스키마가 63개에서 81개가 됐다. 열여덟이 덮여서 사라져 있던 것이다.
     *
     * <p><b>어떻게 잡나</b>: 합쳐지면 <b>상관없는 경로 둘이 같은 스키마를 가리키게 된다.</b>
     * 그 조합을 박아 두고 늘면 빨갛게 한다. 이름이 겹칠 상대가 이미 있을 때 걸린다 — 소스 쪽 `SchemaNameTest` 가 그보다 앞서고 넓다.
     *
     * <p><b>같은 record 를 여러 경로가 쓰는 것은 정상이다</b> — {@link #SHARED_RESPONSES} 가 그 넷이고,
     * 늘리려면 <b>합쳐진 것이 아니라 같은 것인지</b>를 확인하고 이 목록을 고친다.
     */
    @Test
    @DisplayName("상관없는 경로가 같은 응답 스키마를 가리키지 않는다")
    void responseSchemasAreNotMerged() {
        JsonNode spec = spec();
        JsonNode paths = spec.path("paths");
        Map<String, List<String>> bySchema = new java.util.TreeMap<>();

        for (String path : names(paths)) {
            for (String method : names(paths.path(path))) {
                JsonNode content = paths.path(path).path(method).path("responses").path("200").path("content");
                for (String mediaType : names(content)) {
                    String schema = content.path(mediaType).path("schema").path("$ref")
                            .asString("").replace("#/components/schemas/", "");
                    if (!schema.isEmpty()) {
                        bySchema.computeIfAbsent(schema, key -> new java.util.ArrayList<>())
                                .add(method.toUpperCase(java.util.Locale.ROOT) + " " + path);
                    }
                }
            }
        }

        assertThat(bySchema).as("스키마를 못 걷으면 0개를 재고 조용히 통과한다").hasSizeGreaterThan(20);

        Map<String, List<String>> shared = new java.util.TreeMap<>();
        bySchema.forEach((schema, users) -> {
            if (users.size() > 1) {
                shared.put(schema, users.stream().sorted().toList());
            }
        });

        assertThat(shared)
                .as("상관없는 경로 둘이 같은 스키마를 가리키면 응답 타입이 이름으로 합쳐진 것이다."
                        + " 스펙을 보고 만든 타입이 통째로 다른 자원의 모양이 된다 (Q45)."
                        + " 새 응답 record 에는 @Schema(name = …) 로 이름을 준다")
                .isEqualTo(SHARED_RESPONSES);
    }

    /**
     * {@link SchemaNameTest#INTERNAL_ONLY} 가 「스펙에 안 닿는다」고 적어 둔 이름이 정말 없다(`Q45`).
     *
     * <p>그쪽은 <b>이름이 겹쳐도 봐주는 목록</b>이라, 그중 하나가 나중에 응답에 실리면
     * <b>봐주는 채로 합쳐진다.</b> 그 순간을 여기서 잡는다 — 목록이 주장이 아니라 검사가 된다.
     */
    @Test
    @DisplayName("스펙에 안 닿는다고 적어 둔 이름이 정말 스펙에 없다")
    void internalOnlyNamesStayOutOfSpec() {
        Set<String> schemas = new java.util.TreeSet<>(names(spec().path("components").path("schemas")));

        assertThat(schemas).as("스키마를 못 걷으면 0개를 재고 조용히 통과한다").hasSizeGreaterThan(50);
        assertThat(SchemaNameTest.INTERNAL_ONLY.keySet().stream().filter(schemas::contains).toList())
                .as("SchemaNameTest.INTERNAL_ONLY 는 이름이 겹쳐도 봐주는 목록이다."
                        + " 그 이름이 스펙에 뜨면 봐주는 채로 합쳐진 것이라, 목록에서 빼고"
                        + " @Schema(name = …) 로 이름을 준다")
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
