package com.projectshop.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.support.ConstraintValues;

/**
 * 열거형의 값 목록과 DB 가 닫아 둔 목록을 전부 대조한다
 * (`D23` 「목록이 둘로 갈리는 것을 테스트가 막는다」).
 *
 * <p>어긋나는 방향마다 증상이 다르고 <b>둘 다 늦게 드러난다.</b>
 * <ul>
 *   <li><b>DB 에만 있다</b> — 그 값을 읽는 순간 {@code of()} 가 터진다. 조회 하나가 통째로 500</li>
 *   <li><b>코드에만 있다</b> — {@code update} 가 {@code check} 에 걸린다. 표에는 있는데
 *       <b>절대 성공하지 않는 전이</b>가 된다</li>
 * </ul>
 *
 * <p><b>한 파일에 모은 이유는 {@link #everyEnumIsAccountedFor()} 때문이다</b>(`43a-19`, 사용자 선택).
 * 열거형마다 옆에 두면 <b>새 열거형이 대조를 안 받는 것을 막는 것이 아무것도 없다</b> —
 * `점검 H` 가 찾은 구멍이 정확히 그 모양이었다. 규칙은 `D23` 에 있었고 적용은 열여덟 중 일곱이었다.
 *
 * <p><b>목록을 여기 손으로 적지 않는다.</b> DB 쪽은 제약 정의에서 뽑고({@link ConstraintValues})
 * 코드 쪽은 {@code values()} 에서 뽑는다. 적으면 세 번째 사본이 생겨서, 이 테스트가 막으려는 것이 된다.
 *
 * <p>열거형이 {@code package-private} 이라 <b>{@code code()} 를 리플렉션으로 부른다.</b>
 * 대안은 대조 하나 때문에 열여덟 개의 접근 범위를 넓히는 것인데, 그러면
 * 「이 열거형을 어디까지 쓰나」를 정한 결정들(`43a-15` 의 {@code ActorType} 등)이 테스트 때문에 풀린다.
 */
@DisplayName("열거형과 DB 목록의 대조")
class EnumConstraintTest extends PostgresTestBase {

    private static final String PACKAGE = "com.projectshop.shop.";

    /**
     * 열거형과 그것을 닫아 두는 {@code check} 제약. <b>제약이 여럿이면 전부 같아야 한다.</b>
     *
     * <p>{@code ActorType} 이 그 경우다 — {@code V25} 가 「{@code order_status_history} 가 같은
     * 문제를 이미 풀었으니 같은 모양을 쓴다」고 적어 뒀는데, 한쪽만 보면 <b>둘이 갈리는 것을 못 잡는다.</b>
     */
    private static final Map<String, List<String>> PAIRS = pairs();

    /**
     * 생성 열이라 {@code check} 가 없는 것. 목록이 <b>생성 식 안</b>에 있다.
     *
     * <p>값이 어디서 오는지가 달라서 뽑는 자리도 다르다 — 제약 정의가 아니라
     * {@code information_schema.columns.generation_expression} 을 읽는다.
     */
    private static final Map<String, String> GENERATED = Map.of(
            "settlement.SettlementSupplier", "settlement_item.supplier");

    /** 대조할 DB 목록이 없는 것. <b>이유를 같이 적는다</b> — 안 적으면 다음 사람이 빠뜨린 것과 못 가른다 */
    private static final Map<String, String> EXEMPT = Map.of(
            "error.ErrorCode", "응답 본문의 오류 코드다. DB 에 안 산다",
            "account.UserFields", "`permission_field_group` 의 행이지 `check` 가 아니다. `FieldGroupTest` 가 대조한다",
            "inquiry.InquiryFields", "〃",
            "order.OrderFields", "〃",
            "audit.AuditLog$Kind", "커밋 방식을 가르는 구분이다. 저장 안 한다 — `audit_log` 에 그 열이 없다",
            "notification.AdvertisingGate$Verdict", "발송 판정의 결과다. 안 보낸 이유는 저장 안 하고 로그로 간다",
            "order.OrderActionService$Action", "닫힌 목록이 `permission` 표의 행이지 `check` 가 아니다. 대조가 없는 것은 맞고 모양이 달라서 `43a-21` 로 세웠다");

    @Autowired
    private JdbcClient jdbc;

    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("enumAndConstraint")
    @DisplayName("열거형이 DB 제약과 같다")
    void matchesConstraint(String enumName, String constraintName) {
        assertThat(ConstraintValues.of(jdbc, constraintName))
                .as("한쪽에만 있는 값이 생기면 조회가 500 이 되거나 못 쓰는 값이 표에 남는다")
                .containsExactlyInAnyOrderElementsOf(codesOf(enumName));
    }

    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("enumAndGeneratedColumn")
    @DisplayName("열거형이 생성 열이 낼 수 있는 값과 같다")
    void matchesGeneratedColumn(String enumName, String qualifiedColumn) {
        assertThat(producedValuesOf(qualifiedColumn))
                .as("생성 식이 내는 값과 갈리면 그 행을 읽는 순간 `of()` 가 터진다")
                .containsExactlyInAnyOrderElementsOf(codesOf(enumName));
    }

    /**
     * <b>이 테스트가 이 파일의 이유다.</b> 위 둘은 적어 둔 것만 보는데, 이것은
     * <b>안 적은 것</b>을 본다 — 새 열거형이 대조 밖으로 조용히 생기는 자리를 막는다.
     *
     * <p>`점검 H` 가 찾은 것이 그 자리였다. `D23` 이 대조를 규칙으로 적어 뒀는데
     * <b>지켜졌는지 훑는 것은 아무도 안 했고</b>, 그 사이에 선 열하나가 전부 밖에 있었다.
     */
    @Test
    @DisplayName("main 의 열거형이 전부 이 파일에 적혀 있다")
    void everyEnumIsAccountedFor() {
        assertThat(enumsInMain())
                .as("""
                        대조하든(PAIRS·GENERATED) 안 하든(EXEMPT) 여기 적혀야 한다.
                        안 적힌 것은 '대조를 안 하기로 정한 것' 이 아니라 '아무도 안 본 것' 이다.
                        """)
                .allSatisfy(name -> assertThat(PAIRS.containsKey(name)
                                               || GENERATED.containsKey(name)
                                               || EXEMPT.containsKey(name))
                        .as("%s 가 PAIRS·GENERATED·EXEMPT 어디에도 없다", name)
                        .isTrue());
    }

    private static Map<String, List<String>> pairs() {
        Map<String, List<String>> pairs = new LinkedHashMap<>();
        pairs.put("auth.UserStatus", List.of("app_user_status_check"));
        pairs.put("auth.Effect", List.of("role_permission_effect_check"));
        pairs.put("auth.Scope", List.of("role_permission_scope_check"));
        pairs.put("seller.MailOrderExemption", List.of("seller_exempt_reason_check"));
        pairs.put("product.ProductStatus", List.of("product_status_check"));
        pairs.put("product.SkuStatus", List.of("sku_status_check"));
        pairs.put("product.WithdrawalRestrictionReason", List.of("product_withdrawal_reason_check"));
        pairs.put("order.OrderTransitions$Payment", List.of("shop_order_status_check"));
        pairs.put("order.OrderTransitions$Shipment", List.of("seller_order_status_check"));
        pairs.put("order.ActorType", List.of("order_status_history_actor_type_check",
                                             "refund_requested_by_type_check"));
        pairs.put("order.ContractClause", List.of("order_contract_document_clause_check"));
        pairs.put("order.OrderStatusService$ReturnReason", List.of("seller_order_return_reason_check",
                                                                  "return_request_reason_code_check"));
        pairs.put("payment.PaymentMethod", List.of("payment_method_check"));
        pairs.put("payment.PaymentStatus", List.of("payment_status_check"));
        pairs.put("payment.RefundReason", List.of("refund_reason_code_check"));
        pairs.put("payment.RefundStatus", List.of("refund_status_check"));
        pairs.put("inquiry.InquiryKind", List.of("inquiry_kind_check"));
        pairs.put("inquiry.InquiryStatus", List.of("inquiry_status_check"));
        pairs.put("settlement.PayoutStatus", List.of("settlement_payout_status_check"));
        pairs.put("settlement.SettlementItemKind", List.of("settlement_item_kind_check"));
        return Map.copyOf(pairs);
    }

    private static Stream<Arguments> enumAndConstraint() {
        return PAIRS.entrySet().stream()
                .flatMap(pair -> pair.getValue().stream()
                        .map(constraint -> Arguments.of(pair.getKey(), constraint)));
    }

    private static Stream<Arguments> enumAndGeneratedColumn() {
        return GENERATED.entrySet().stream().map(e -> Arguments.of(e.getKey(), e.getValue()));
    }

    /**
     * 생성 식이 <b>낼 수 있는 값</b>을 뽑는다.
     *
     * <p>{@code case when kind in (...) then 'seller' ... end} 에서 {@code then} 뒤만 고른다 —
     * 통째로 뽑으면 <b>조건 쪽의 {@code kind} 값이 섞인다.</b> 그쪽은 다른 열거형의 목록이다.
     *
     * @param qualifiedColumn {@code 표.열}
     */
    private List<String> producedValuesOf(String qualifiedColumn) {
        String[] parts = qualifiedColumn.split("\\.", 2);
        String expression = jdbc.sql("""
                        select generation_expression from information_schema.columns
                         where table_name = :table and column_name = :column
                        """)
                .param("table", parts[0])
                .param("column", parts[1])
                .query(String.class)
                .single();

        Matcher produced = Pattern.compile("\\bTHEN\\s+'([a-z0-9_]+)'", Pattern.CASE_INSENSITIVE)
                .matcher(expression);
        List<String> values = new ArrayList<>();
        while (produced.find()) {
            values.add(produced.group(1));
        }
        assertThat(values)
                .as("생성 식에 `then` 이 없으면 모양이 바뀐 것이라 이 테스트가 헛돈다: %s", expression)
                .isNotEmpty();
        return values;
    }

    /**
     * 열거형의 저장값을 뽑는다. <b>{@code code()} 는 열여덟 개가 전부 이름이 같다</b>(`D23` 「Java 표현」).
     *
     * @param enumName {@link #PACKAGE} 를 뺀 이름. 중첩 열거형은 {@code $} 로 잇는다
     */
    private static List<String> codesOf(String enumName) {
        try {
            Class<?> type = Class.forName(PACKAGE + enumName);
            Method code = type.getDeclaredMethod("code");
            code.setAccessible(true);

            List<String> codes = new ArrayList<>();
            for (Object constant : type.getEnumConstants()) {
                codes.add((String) code.invoke(constant));
            }
            return codes;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(enumName + " 의 code() 를 못 읽었다", e);
        }
    }

    /**
     * {@code main} 에 있는 열거형 전부를 준다.
     *
     * <p>클래스패스가 아니라 <b>{@code main} 이 컴파일된 자리</b>에서 걷는다 —
     * 클래스패스에는 테스트 클래스와 라이브러리가 같이 있어서, 이름으로 거르면
     * <b>거르는 규칙이 다음에 틀린다.</b>
     */
    private static List<String> enumsInMain() {
        try {
            Path root = Path.of(BackendApplication.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());

            try (Stream<Path> files = Files.walk(root)) {
                List<String> names = files
                        .filter(path -> path.toString().endsWith(".class"))
                        .map(path -> root.relativize(path).toString()
                                .replace('\\', '/')
                                .replace('/', '.')
                                .replaceAll("\\.class$", ""))
                        .filter(name -> name.startsWith(PACKAGE))
                        .filter(EnumConstraintTest::isEnum)
                        .map(name -> name.substring(PACKAGE.length()))
                        .sorted()
                        .toList();

                assertThat(names)
                        .as("한 개도 못 찾았으면 걷는 자리가 틀린 것이라 이 테스트가 헛돈다: %s", root)
                        .isNotEmpty();
                return names;
            }
        } catch (Exception e) {
            throw new IllegalStateException("main 의 열거형을 못 걸었다", e);
        }
    }

    private static boolean isEnum(String className) {
        try {
            return Class.forName(className, false, EnumConstraintTest.class.getClassLoader())
                    .isEnum();
        } catch (Throwable ignored) {
            return false;
        }
    }
}
