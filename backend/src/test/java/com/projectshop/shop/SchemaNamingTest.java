package com.projectshop.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code naming-rules.md} 의 SQL 이름 규칙을 <b>도는 스키마</b>에 건다(`Q31`).
 *
 * <p><b>문서에만 있던 규칙이다.</b> 마이그레이션이 65개인데 이름을 보는 테스트가 0이었다.
 * 어겨도 빌드가 초록이라 읽은 사람만 지킨다.
 *
 * <p><b>마이그레이션 파일을 안 훑는다.</b> 글자를 훑으면 <b>적힌 것</b>을 보게 되는데 물어야
 * 하는 것은 <b>지금 도는 스키마</b>다 — {@code alter} 가 쌓이면 그 둘이 갈린다
 * ({@link LengthConstraintTest} 가 같은 이유로 DB 에서 읽는다).
 *
 * <h2>예외를 이름으로 안 적고 구조로 가른다</h2>
 *
 * <p>기본키 이름이 {@code <테이블>_id} 가 아닌 표가 열하나인데, <b>여덟은 같은 꼴</b>이다 —
 * 1:1 로 본체를 늘린 표라 <b>기본키가 곧 본체를 가리키는 외래키</b>다. 이름을 여덟 개 적으면
 * 아홉 번째가 생길 때 목록이 낡는다. 그래서 {@code information_schema} 에 「이 기본키 컬럼이
 * 외래키이기도 한가」를 물어서 가른다.
 *
 * <p><b>이름으로 적는 예외는 셋뿐이고 항목마다 {@code D22} 의 절 이름이 붙는다</b> —
 * 절이 없으면 못 넣는다({@link #NAMED_EXCEPTIONS}).
 *
 * <h2>무엇을 못 보나</h2>
 *
 * <p><b>복합 기본키는 안 잰다.</b> {@code D22} 가 「새 규칙이면」이라고 적어 기존 넷을 인정했다
 * ({@code role_permission}·{@code role_permission_field}·{@code seller_member}·{@code sku_option_value}).
 *
 * <p><b>단수·복수는 못 본다.</b> 표 이름이 단수인지는 뜻을 읽어야 정해져서 사람이 본다.
 */
@DisplayName("스키마 이름 규칙")
class SchemaNamingTest extends PostgresTestBase {

    /** 이름을 짓는 규칙이 아니라 Flyway 가 만드는 표다 */
    private static final String FLYWAY = "flyway_schema_history";

    /** 소문자·숫자·밑줄, 끝이 밑줄이 아니다 */
    private static final Pattern COLUMN_NAME = Pattern.compile("^[a-z][a-z0-9_]*[a-z0-9]$");

    private static final int MAX_NAME_LENGTH = 30;

    /** 표 이름과 기본키 접두사가 갈리는 둘. {@code user}·{@code order} 가 예약어라 표에만 접두사를 붙였다 */
    private static final Map<String, String> PREFIXED_TABLES = Map.of(
            "app_user", "user",
            "shop_order", "order");

    /** 정수(원)로 저장한다. 부동소수·십진 타입이 들어오면 반올림이 어디서 나는지 아무도 모른다 */
    /** 타입을 약속하는 접미사. 뜻만 있는 접미사(`_name`·`_reason`)는 여기 안 넣는다 — 기계가 판정할 것이 없다. */
    private static final Map<String, String> TYPED_SUFFIXES = Map.of(
            "_at", "timestamp with time zone",
            "_date", "date",
            "_bp", "integer");

    private static final Set<String> NON_INTEGER_MONEY_TYPES = Set.of(
            "numeric", "decimal", "real", "double precision", "money");

    /**
     * 이름으로 적는 예외 셋. <b>값이 {@code D22} 의 절 이름이다</b> — 절이 없으면 여기 못 넣는다.
     *
     * <p>줄이는 방향으로만 고친다. 늘리려면 먼저 {@code D22} 에 절을 쓰고, 그것이
     * 「이 이름이 맞다」는 판단이어야 한다 — 「고치기 귀찮다」는 근거가 아니다.
     */
    private static final Map<String, String> NAMED_EXCEPTIONS = Map.of(
            "user_consent.granted", "「불리언은 is_ 로 시작한다」 — 기존에 어긋난 것으로 적어 뒀다",
            "return_request.restock", "「불리언은 is_ 로 시작한다」 — 요청 필드 이름이라 고치면 API 가 바뀐다",
            "holiday.holiday_date", "「기준에서 벗어난 것」 — 기본키가 식별자가 아니라 값 자체다");

    /**
     * 우리가 발급하지 않는 번호 컬럼. <b>우리가 만들어 내보내는 번호가 여기 들어오면 안 된다.</b>
     *
     * <p>값이 「누가 발급했나」다 — 우리가 발급한 것이면 이름이 {@code _number} 여야 한다.
     */
    private static final Map<String, String> INTERNAL_SEQUENCE_COLUMNS = new java.util.TreeMap<>(Map.of(
            "consent_item.sort_no", "동의 항목을 화면에 세우는 순서. 우리가 형식을 정하지 않는다",
            "product_option.sort_no", "옵션을 화면에 세우는 순서",
            "product_option_value.sort_no", "옵션 값을 화면에 세우는 순서",
            "seller.business_reg_no", "사업자등록번호. 국가가 발급한다 — 셀러 신원 표시에 쓴다(`D2` R1)",
            "seller.mail_order_no", "통신판매업신고번호. 국가가 발급한다(`D2` R1)"));

    @Autowired
    private JdbcClient jdbc;

    private record Column(String table, String name, String type) {
        String qualified() {
            return table + "." + name;
        }
    }

    @Test
    @DisplayName("훑을 컬럼이 있다")
    void schemaIsNotEmpty() {
        // 질의가 틀리면 0개를 재고 아홉 중 여덟이 조용히 통과한다.
        // 유일하게 남는 namedExceptionsStillExist 도 예외 목록이 비는 순간 같이 빈 검사가 된다.
        assertThat(columns()).hasSizeGreaterThan(200);
    }

    @Test
    @DisplayName("컬럼 이름이 소문자와 숫자와 밑줄로만 돼 있다")
    void columnNamesAreLowercase() {
        assertThat(columns().stream()
                .filter(c -> !COLUMN_NAME.matcher(c.name()).matches())
                .map(Column::qualified)
                .toList())
                .as("대문자나 다른 글자가 섞이면 따옴표 없이 못 부르는 컬럼이 생긴다"
                        + " (naming-rules.md 「SQL › 공통」)")
                .isEmpty();
    }

    /**
     * <b>이 자리가 실제로 깨져 있었다</b>(`Q31`). 마이그레이션을 글자로 재던 측정은 「위반 0」이라고
     * 했는데 도는 스키마에 물으니 32자짜리가 있었다 — {@code order_item.withdrawal_restriction_agreed_at}.
     * `Q40` 이 {@code withdrawal_notice_agreed_at} 으로 줄였다(`V66`).
     */
    @Test
    @DisplayName("컬럼 이름이 30자를 안 넘는다")
    void columnNamesAreShort() {
        assertThat(columns().stream()
                .filter(c -> c.name().length() > MAX_NAME_LENGTH)
                .map(Column::qualified)
                .sorted()
                .toList())
                .as("긴 이름은 인덱스·제약 이름이 63자에서 잘린다 (naming-rules.md 「SQL › 공통」)")
                .isEmpty();
    }

    @Test
    @DisplayName("시각 컬럼과 _at 접미사가 서로를 가리킨다")
    void timeColumnsEndWithAt() {
        List<String> mismatched = columns().stream()
                .filter(c -> c.name().endsWith("_at") != "timestamp with time zone".equals(c.type()))
                .map(c -> c.qualified() + " (" + c.type() + ")")
                .toList();

        assertThat(mismatched)
                .as("양방향으로 잰다. _at 인데 시각이 아니면 읽는 쪽이 시각으로 다루고,"
                        + " 시각인데 _at 이 아니면 그 컬럼이 시각인 줄 모른다 (naming-rules.md 「SQL」)")
                .isEmpty();
    }

    @Test
    @DisplayName("불리언 컬럼이 is_ 로 시작한다")
    void booleanColumnsStartWithIs() {
        assertThat(violations(columns().stream()
                .filter(c -> "boolean".equals(c.type()) && !c.name().startsWith("is_"))
                .map(Column::qualified)
                .toList()))
                .as("접두사가 없으면 값이 참일 때 무엇이 참인지가 이름에 안 들어간다"
                        + " (naming-rules.md 「불리언은 is_ 로 시작한다」)")
                .isEmpty();
    }

    @Test
    @DisplayName("컬럼 이름에 _num 접미사를 안 쓴다")
    void noNumSuffix() {
        assertThat(columns().stream()
                .filter(c -> c.name().endsWith("_num"))
                .map(Column::qualified)
                .toList())
                .as("줄인 이름은 무엇의 번호인지가 안 남는다. 세는 수는 _count, 번호는 _number 나 _no"
                        + " (naming-rules.md 「SQL」)")
                .isEmpty();
    }

    /**
     * 「번호」가 둘이라 접미사도 둘이다(`Q42`, {@code naming-rules.md} 「접미사」).
     *
     * <b>가르는 기준은 「누가 발급했나」다.</b> 우리가 만들어 내보내는 번호는 {@code _number} 고
     * 형식을 우리가 정하지 않는 것(화면 순번, 국가가 발급한 등록번호)은 {@code _no} 다.
     *
     * <p><b>실측이 문서를 고쳤다.</b> {@code D22} 는 「번호는 {@code _no}」라고만 적었는데 실물은
     * {@code _number} 여덟에 {@code _no} 다섯이었고, 그 다섯이 <b>순번 셋과 법정 번호 둘</b>이라
     * 한 갈래가 아니었다. 둘이 다른 것이라 문서를 갈랐다.
     */
    @Test
    @DisplayName("_no 로 끝나는 컬럼이 적어 둔 것뿐이다")
    void internalSequenceSuffixIsPinned() {
        assertThat(columns().stream()
                .filter(c -> c.name().endsWith("_no"))
                .map(Column::qualified)
                .sorted()
                .toList())
                .as("노출 번호를 _no 로 지으면 뜻이 뒤집힌다 (naming-rules.md 「접미사」)."
                        + " 새로 생기면 그것이 바깥이 부르는 번호인지 먼저 묻는다")
                .isEqualTo(List.copyOf(INTERNAL_SEQUENCE_COLUMNS.keySet()));
    }

    @Test
    @DisplayName("단일 기본키가 <테이블>_id 이거나 본체를 가리키는 외래키다")
    void primaryKeyIsTableId() {
        Map<String, List<String>> primaryKeys = keyColumns("PRIMARY KEY");
        Set<String> foreignKeyColumns = keyColumns("FOREIGN KEY").entrySet().stream()
                .flatMap(e -> e.getValue().stream().map(column -> e.getKey() + "." + column))
                .collect(Collectors.toSet());

        List<String> wrong = primaryKeys.entrySet().stream()
                // 복합 기본키는 안 잰다 — D22 가 기존 넷을 인정했다.
                .filter(entry -> entry.getValue().size() == 1)
                .filter(entry -> {
                    String table = entry.getKey();
                    String column = entry.getValue().get(0);
                    String expected = PREFIXED_TABLES.getOrDefault(table, table) + "_id";
                    // 1:1 로 본체를 늘린 표는 기본키가 곧 본체를 가리키는 외래키다.
                    return !column.equals(expected) && !foreignKeyColumns.contains(table + "." + column);
                })
                .map(entry -> entry.getKey() + "." + entry.getValue().get(0))
                .toList();

        assertThat(violations(wrong))
                .as("기본키 이름이 제각각이면 조인을 쓸 때마다 컬럼 이름을 찾아봐야 한다"
                        + " (naming-rules.md 「SQL」). 1:1 확장 표는 구조로 가르므로 목록이 안 낡는다")
                .isEmpty();
    }

    /**
     * 표와 <b>같은 이름</b>의 컬럼을 두지 않는다.
     *
     * <p><b>접두사는 위반이 아니다.</b> 처음에 {@code c.name().startsWith(table + "_")} 로 쟀더니
     * 일곱을 짚었는데 <b>전부 의도된 것이었다</b> — {@code seller_order.seller_order_number} 는
     * 바깥 시스템이 부르는 노출 번호고, {@code settlement.settlement_cycle_id} 는
     * {@code settlement_cycle} 을 가리키는 외래키다. {@code D22} 가 적은 것은
     * 「테이블과 같은 이름의 컬럼」 하나고, 규칙보다 센 검사를 세우면 <b>맞는 이름을 고치라고 시킨다.</b>
     */
    @Test
    @DisplayName("표와 같은 이름의 컬럼이 없다")
    void noColumnNamedAfterItsTable() {
        assertThat(violations(columns().stream()
                .filter(c -> c.name().equals(c.table()))
                .map(Column::qualified)
                .toList()))
                .as("product.product 는 무엇을 담는 칸인지가 이름에 없다 (naming-rules.md 「SQL › 테이블」)")
                .isEmpty();
    }

    @Test
    @DisplayName("금액 컬럼이 정수 타입이다")
    void moneyColumnsAreIntegers() {
        assertThat(columns().stream()
                .filter(c -> NON_INTEGER_MONEY_TYPES.contains(c.type()))
                .map(c -> c.qualified() + " (" + c.type() + ")")
                .toList())
                .as("금액은 원 단위 정수로 저장한다 (money-rules.md 「저장」)."
                        + " 십진·부동소수가 섞이면 반올림이 어디서 나는지 추적할 수 없다")
                .isEmpty();
    }

    @Test
    @DisplayName("이름으로 적은 예외가 전부 실물이다")
    void namedExceptionsStillExist() {
        Set<String> existing = columns().stream().map(Column::qualified).collect(Collectors.toSet());

        assertThat(NAMED_EXCEPTIONS.keySet().stream()
                .filter(name -> !existing.contains(name))
                .toList())
                .as("없어진 컬럼이 예외 목록에 남으면 그 목록이 무엇을 봐주고 있는지 아무도 모른다."
                        + " 컬럼을 지웠으면 SchemaNamingTest.NAMED_EXCEPTIONS 에서도 지운다")
                .isEmpty();
    }

    /** 이름으로 적은 예외를 뺀다. 뺀 자리마다 {@code D22} 의 절이 근거로 붙어 있다 */
    private static List<String> violations(List<String> found) {
        return found.stream().filter(name -> !NAMED_EXCEPTIONS.containsKey(name)).toList();
    }

    private List<Column> columns() {
        return jdbc.sql("""
                        select t.table_name, c.column_name, c.data_type
                        from information_schema.columns c
                        join information_schema.tables t using (table_schema, table_name)
                        where t.table_schema = 'public'
                          and t.table_type = 'BASE TABLE'
                          and t.table_name <> :flyway
                        """)
                .param("flyway", FLYWAY)
                .query((rs, rowNum) -> new Column(
                        rs.getString("table_name"), rs.getString("column_name"), rs.getString("data_type")))
                .list();
    }

    /** 표마다 그 제약이 잡은 컬럼들 */
    private Map<String, List<String>> keyColumns(String constraintType) {
        record KeyColumn(String table, String column) {}
        return jdbc.sql("""
                        select tc.table_name, kcu.column_name
                        from information_schema.table_constraints tc
                        join information_schema.key_column_usage kcu
                          on kcu.constraint_name = tc.constraint_name
                         and kcu.constraint_schema = tc.constraint_schema
                        where tc.constraint_type = :type
                          and tc.table_schema = 'public'
                          and tc.table_name <> :flyway
                        """)
                .param("type", constraintType)
                .param("flyway", FLYWAY)
                .query((rs, rowNum) -> new KeyColumn(rs.getString("table_name"), rs.getString("column_name")))
                .list().stream()
                .collect(Collectors.groupingBy(KeyColumn::table,
                        Collectors.mapping(KeyColumn::column, Collectors.toList())));
    }

    /**
     * 접미사가 약속한 타입과 실제 타입이 같나(`Q78`).
     *
     * <p><b>접미사 표 전체는 기계로 못 잰다.</b> {@code _name}·{@code _reason} 처럼 뜻만 있는 것은
     * 사람이 읽어야 판정되고, 컬럼 이름의 마지막 토막을 전부 접미사로 보면 {@code card_issuer}·
     * {@code source_url} 같은 평범한 명사까지 규칙 위반이 된다. <b>타입을 약속하는 접미사만</b> 잰다.
     *
     * <p>{@code _at} 과 {@code _date} 가 이번에 갈린 자리다 — {@code D22} 가 「우리는 전부
     * {@code timestamptz} 다」로 적어 뒀는데 {@code baseline_date}·{@code payout_date} 가 이미
     * {@code date} 였다(점검 O). 문서를 사실로 고치면서 <b>둘이 섞이는 것</b>을 여기서 막는다.
     */
    @Test
    @DisplayName("접미사가 약속한 타입과 실제 타입이 같다")
    void typedSuffixesMatchColumnTypes() {
        List<String> mismatched = columns().stream()
                .map(SchemaNamingTest::typeMismatch)
                .filter(java.util.Objects::nonNull)
                .toList();

        assertThat(mismatched)
                .as("접미사가 타입을 약속한다 (naming-rules.md 「접미사」)."
                        + " 이름을 고치거나 타입을 고친다 — 둘이 갈리면 이름이 거짓말을 한다")
                .isEmpty();
    }

    /** 약속을 어긴 칸이면 설명을, 아니면 {@code null}. */
    private static String typeMismatch(Column column) {
        String expected = TYPED_SUFFIXES.entrySet().stream()
                .filter(entry -> column.name().endsWith(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
        if (expected == null || expected.equals(column.type())) {
            return null;
        }
        return column.qualified() + " 는 " + column.type() + " 인데 " + expected + " 여야 한다";
    }

    /**
     * {@code _no}·{@code _number} 로 끝나는 컬럼 중 <b>우리가 발급하지 않는</b> 것과 그 근거.
     *
     * <p>{@code D22} 「접미사」가 가르는 기준이 「누가 발급했나」인데, <b>이름만으로는 그것을 못 본다.</b>
     * 그래서 남이 준 번호를 여기 적고 <b>왜 그 이름으로 남았는지</b>를 값에 둔다.
     */
    private static final Map<String, String> FOREIGN_NUMBERS = Map.of(
            "payment.approval_number",
            "결제 대행사가 준 번호다. 기준대로면 _no 인데 approvalNumber 가 이미 주문 응답과 거래기록에 실렸다",
            "refund.gateway_refund_number",
            "결제 대행사가 준 번호다. 위와 같은 이유로 이름을 안 바꿨다");

    /**
     * {@code _number} 가 「우리가 발급한 번호」라는 약속을 지키나(`Q77`).
     *
     * <p><b>{@code _no} 쪽만 묻고 있었다.</b> {@code internalSequenceSuffixIsPinned} 가 그쪽을 핀하는 동안
     * {@code _number} 는 아무것도 안 물어서, 남이 준 번호가 우리 노출 번호 행세를 해도 조용했다(점검 O).
     * 예외는 <b>이미 나간 이름</b>뿐이고 새 컬럼은 못 쓴다 — 아직 아무것도 안 나갔으면 {@code _no} 가 싸다.
     */
    @Test
    @DisplayName("남이 발급한 번호가 _number 를 쓰면 근거가 적혀 있다")
    void foreignNumbersArePinned() {
        List<String> undocumented = columns().stream()
                .filter(column -> column.name().endsWith("_number"))
                .map(Column::qualified)
                .filter(name -> !OUR_NUMBERS.contains(name))
                .filter(name -> !FOREIGN_NUMBERS.containsKey(name))
                .toList();

        assertThat(undocumented)
                .as("_number 는 우리가 발급해서 바깥이 부르는 번호다 (naming-rules.md 「접미사」)."
                        + " 남이 준 번호면 _no 로 짓고, 이미 나간 이름이면 FOREIGN_NUMBERS 에 근거를 적는다")
                .isEmpty();
    }

    /** 이 저장소가 발급하는 노출 번호. {@code identifier-rules.md} 가 형식까지 정한 것들이다. */
    private static final Set<String> OUR_NUMBERS = Set.of(
            "shop_order.order_number",
            "seller_order.seller_order_number",
            "refund.refund_number",
            "settlement.settlement_number",
            "inquiry.inquiry_number");
}
