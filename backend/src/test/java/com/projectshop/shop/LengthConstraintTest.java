package com.projectshop.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

import jakarta.validation.constraints.Size;

/**
 * 요청 record 의 {@code @Size(max = N)} 과 DB {@code check} 제약의 N 을 대조한다(`Q22`).
 *
 * <p><b>숫자가 두 곳에 산다.</b> 앱 검증은 400 을 내주려고 있고 DB 제약은 앱을 안 지나는 입구를
 * 막으려고 있어서, 어느 한쪽을 지우면 그 역할이 통째로 빈다. 둘 다 두되 <b>갈리는 것을 여기서 막는다.</b>
 *
 * <p><b>갈리면 어떻게 되나가 방향마다 다르고 둘 다 조용하다.</b>
 * <ul>
 *   <li><b>앱이 더 헐겁다</b> — 사용자가 통과한 값이 저장에서 걸려 <b>400 이어야 할 것이 500</b> 이 된다</li>
 *   <li><b>DB 가 더 헐겁다</b> — 앱을 안 지나는 입구(배치·시드·psql)에 <b>상한이 사실상 없다</b></li>
 * </ul>
 *
 * <p><b>이 대조가 필요한 이유는 실물로 확인됐다</b>(`점검 L`). 같은 이메일을 가입이 254 로,
 * 변경이 320 으로 재고 있었다 — 규칙이 두 군데 있으면 한쪽만 고치는 날이 온다.
 * {@code ShippingRequest} 의 우편번호가 {@code V33} 과 갈리는 것을
 * {@code ShippingFormatTest} 가 막는 것과 같은 꼴이고, 이쪽은 길이를 본다.
 *
 * <p><b>제약 정의는 DB 에서 읽는다.</b> 마이그레이션 파일을 훑으면 <b>적힌 것</b>을 보게 되는데,
 * 물어야 하는 것은 <b>지금 도는 스키마</b>다 — 나중에 {@code alter} 가 쌓이면 그 둘이 갈린다.
 *
 * <p><b>보는 것은 N 하나다.</b> 빈 문자열 허용 여부({@code between 1 and N} 이냐 {@code <= N} 이냐)는
 * 대조하지 않는다 — {@code @NotBlank} 와 짝지으려면 이 테스트가 검증 조합 전체를 알아야 하고,
 * 그러면 규칙이 세 번째 사본이 된다. <b>지금 막는 것은 숫자가 갈리는 것뿐이다.</b>
 */
@DisplayName("길이 상한과 DB 제약의 대조")
class LengthConstraintTest extends PostgresTestBase {

    /** 정의 안의 마지막 정수가 상한이다 — {@code between 1 and 200} 도 {@code <= 200} 도 그렇다. */
    private static final Pattern LAST_NUMBER = Pattern.compile("(\\d+)(?!.*\\d)", Pattern.DOTALL);

    @Autowired
    private JdbcClient jdbc;

    /**
     * 제약 하나와 그 수를 들고 있는 요청 record 의 칸들.
     *
     * <p><b>한 컬럼을 여러 입구가 채운다.</b> {@code display_name} 이 가입과 이름 변경 둘이고,
     * {@code email} 도 가입과 이메일 변경 둘이다 — <b>그 둘이 갈렸던 것이 `점검 L` 이 찾은 것</b>이라
     * 입구를 하나만 대조하면 이 테스트가 그 사고를 다시 놓친다.
     */
    static Stream<Arguments> pairs() {
        return Stream.of(
                Arguments.of("app_user_email_length_check", List.of(
                        component(com.projectshop.shop.auth.AuthController.SignupRequest.class, "email"),
                        component(com.projectshop.shop.account.MeController.EmailRequest.class, "email"))),
                Arguments.of("app_user_display_name_length_check", List.of(
                        component(com.projectshop.shop.auth.AuthController.SignupRequest.class, "displayName"),
                        component(com.projectshop.shop.account.MeController.UpdateRequest.class, "displayName"))),
                Arguments.of("order_shipping_receiver_name_length_check", List.of(
                        component(com.projectshop.shop.order.OrderController.ShippingRequest.class, "receiverName"))),
                Arguments.of("order_shipping_address1_length_check", List.of(
                        component(com.projectshop.shop.order.OrderController.ShippingRequest.class, "address1"))),
                Arguments.of("order_shipping_address2_length_check", List.of(
                        component(com.projectshop.shop.order.OrderController.ShippingRequest.class, "address2"))),
                Arguments.of("order_shipping_delivery_memo_length_check", List.of(
                        component(com.projectshop.shop.order.OrderController.ShippingRequest.class, "deliveryMemo"))),
                Arguments.of("product_name_length_check", List.of(
                        component(com.projectshop.shop.product.ProductController.ProductRequest.class, "name"))),
                Arguments.of("product_review_note_length_check", List.of(
                        component(com.projectshop.shop.product.ProductController.RejectRequest.class, "note"))),
                Arguments.of("product_block_reason_length_check", List.of(
                        component(com.projectshop.shop.product.ProductController.BlockRequest.class, "reason"))),
                Arguments.of("product_option_name_length_check", List.of(
                        component(com.projectshop.shop.product.ProductController.OptionRequest.class, "name"))),
                Arguments.of("product_substantiation_source_url_length_check", List.of(
                        component(com.projectshop.shop.product.ProductController.SubstantiationRequest.class,
                                "sourceUrl"))),
                // Q28 이 넓힌 넷. 요청 record 와 컬럼이 1:1 로 붙는 자리부터 이었다.
                Arguments.of("product_substantiation_claim_length_check", List.of(
                        component(com.projectshop.shop.product.ProductController.SubstantiationRequest.class,
                                "claim"))),
                Arguments.of("product_substantiation_evidence_length_check", List.of(
                        component(com.projectshop.shop.product.ProductController.SubstantiationRequest.class,
                                "evidence"))),
                Arguments.of("inquiry_question_length_check", List.of(
                        component(com.projectshop.shop.inquiry.InquiryController.NewInquiryRequest.class,
                                "question"))),
                Arguments.of("inquiry_answer_length_check", List.of(
                        component(com.projectshop.shop.inquiry.InquiryController.AnswerRequest.class,
                                "answer"))),
                // Q46 이 이은 넷. 서비스가 요청의 사유를 옮겨 담는 자리라 호출 사슬을 따라가 찾았다.
                //
                // `order_status_history_note.reason` 은 **입구가 넷**이다 — 상태를 옮기는 모든
                // 동작이 같은 칸에 사유를 쌓는다. 하나만 이으면 나머지 셋이 갈려도 안 걸린다.
                Arguments.of("order_status_history_note_reason_length_check", List.of(
                        component(com.projectshop.shop.order.ShipmentController.ActionRequest.class, "reason"),
                        component(com.projectshop.shop.order.ShipmentController.ApproveReturnRequest.class,
                                "reason"),
                        component(com.projectshop.shop.order.ShipmentController.RejectReturnRequest.class,
                                "reason"),
                        component(com.projectshop.shop.order.ReturnController.ReceiveRequest.class, "reason"))),
                Arguments.of("return_note_decision_reason_length_check", List.of(
                        component(com.projectshop.shop.order.ShipmentController.RejectReturnRequest.class,
                                "decisionReason"))),
                Arguments.of("refund_note_request_reason_length_check", List.of(
                        component(com.projectshop.shop.payment.RefundController.RefundRequest.class, "reason"))),
                Arguments.of("refund_note_decision_reason_length_check", List.of(
                        component(com.projectshop.shop.payment.RefundController.DecisionRequest.class, "reason"))));
    }

    /**
     * <b>대조하지 않는 제약과 그 이유</b>(`Q28`).
     *
     * <p>{@code EnumConstraintTest.everyEnumIsAccountedFor} 와 같은 틀이다 —
     * <b>대조하든 안 하든, 적히지 않으면 빌드를 세운다.</b> 새 {@code length} 제약이 들어오면
     * 여기나 {@link #pairs()} 중 한 곳에 이름이 있어야 한다.
     *
     * <p>값이 「왜 앱 검증과 대조할 것이 없나」다. 답이 안 되면 {@code pairs()} 로 가야 한다.
     */
    private static final Map<String, String> NOT_COMPARED = new java.util.TreeMap<>(Map.ofEntries(
            Map.entry("password_reset_token_hash_length_check",
                    "토큰 해시는 우리가 만든다 — 요청으로 들어오는 값이 아니다(`5c-1`)"),
            Map.entry("batch_run_failure_reason_length_check", "배치가 실패 사유를 직접 쓴다. 요청 입구가 없다"),
            Map.entry("idempotency_key_length_check", "헤더로 받은 키를 그대로 저장한다. 요청 record 의 칸이 아니다"),
            Map.entry("payment_approval_number_length_check", "결제 대행사가 준 값이다. 우리가 상한을 정하지 않는다"),
            Map.entry("payment_card_issuer_length_check", "결제 대행사가 준 값이다"),
            Map.entry("payment_decline_reason_length_check", "결제 대행사가 준 값이다"),
            Map.entry("refund_gateway_refund_number_length_check", "결제 대행사가 준 값이다"),
            Map.entry("return_note_inspection_note_length_check", "쓰는 코드가 아직 없다 — 검수 소견을 남기는 입구가 `43a` 다. 그 입구가 서면 pairs() 로 옮긴다"),
            Map.entry("return_note_request_reason_length_check", "쓰는 코드가 아직 없다 — 반품 접수 입구가 `43a` 다. 그 입구가 서면 pairs() 로 옮긴다")));

    /**
     * 모든 {@code length} 제약이 <b>대조되거나 이유가 적혀 있다</b>(`Q28`).
     *
     * <p><b>손으로 적는 쪽은 빠뜨려도 안 걸린다.</b> 독립 리뷰가 짚었다 —
     * {@code coding-rules.md} 는 「갈리는 것은 이 테스트가 막는다」를 <b>저장소 전체 규칙</b>으로
     * 적었는데 실제로 대조하던 것은 {@code V65} 가 넣은 열한 쌍뿐이었다.
     * <b>문서가 약속한 범위와 테스트가 덮는 범위가 달랐다.</b>
     *
     * <p>그래서 <b>DB 에 물어서</b> 목록을 만든다. 새 제약이 생기면 둘 중 한 곳에 적기 전까지 빨갛다.
     */
    @Test
    @DisplayName("모든 길이 제약이 대조되거나 이유가 적혀 있다")
    void everyLengthConstraintIsAccountedFor() {
        List<String> declared = jdbc.sql("""
                        select conname
                        from pg_constraint
                        where contype = 'c' and conname like '%length!_check' escape '!'
                        """)
                .query(String.class)
                .list();

        assertThat(declared).as("제약을 못 읽으면 0개를 재고 조용히 통과한다").hasSizeGreaterThan(20);

        Set<String> accounted = new java.util.TreeSet<>(NOT_COMPARED.keySet());
        pairs().map(arguments -> (String) arguments.get()[0]).forEach(accounted::add);

        assertThat(declared.stream().filter(name -> !accounted.contains(name)).sorted().toList())
                .as("적히지 않은 제약은 대조 밖이라, 문서가 약속한 범위와 실제로 덮는 범위가 갈린다"
                        + " (coding-rules.md 「길이」, Q28). pairs() 로 잇거나 NOT_COMPARED 에 이유를 적는다")
                .isEmpty();

        assertThat(accounted.stream().filter(name -> !declared.contains(name)).sorted().toList())
                .as("없어진 제약이 목록에 남으면 그 목록이 무엇을 덮는지 아무도 모른다")
                .isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pairs")
    @DisplayName("제약의 상한과 요청 record 의 상한이 같다")
    void constraintMatchesRequestRecords(String constraintName, List<RecordComponent> components) {
        int inDatabase = capIn(ConstraintValues.definitionOf(jdbc, constraintName));

        for (RecordComponent component : components) {
            assertThat(maxOf(component))
                    .describedAs("%s.%s 의 상한이 %s 와 갈렸다. 한쪽만 고치면 "
                            + "앱이 헐거우면 400 이 500 이 되고 DB 가 헐거우면 배치·psql 에 상한이 없다",
                            component.getDeclaringRecord().getSimpleName(), component.getName(),
                            constraintName)
                    .isEqualTo(inDatabase);
        }
    }

    private static int capIn(String definition) {
        Matcher number = LAST_NUMBER.matcher(definition);
        assertThat(number.find()).describedAs("제약 정의에 수가 없다: %s", definition).isTrue();
        return Integer.parseInt(number.group(1));
    }

    /**
     * 그 칸이 실제로 받는 상한.
     *
     * <p><b>두 자리를 다 본다.</b>
     * <ul>
     *   <li><b>{@code @Size} 는 record component 에 안 남는다</b> — 그 애너테이션의
     *       {@code @Target} 에 {@code RECORD_COMPONENT} 가 없어서 컴파일러가 필드로 보낸다.
     *       {@link RecordComponent#getAnnotation} 만 보면 <b>열 칸이 「없다」로 나온다</b></li>
     *   <li><b>직접 안 붙었을 수도 있다</b> — {@code email} 은 {@code @EmailAddress} 하나만
     *       붙는데 그 안에 {@code @Size} 가 들어 있다. 규칙을 한 곳으로 모은 결과라
     *       직접 붙은 것만 보면 <b>이메일이 이 대조에서 통째로 빠진다</b></li>
     * </ul>
     *
     * <p><b>{@link ScreenLengthTest} 도 이것을 쓴다</b>(`Q27`). 같은 함정을 두 번 풀면
     * 한쪽만 고치는 날이 온다. 상한이 없으면 {@code AssertionError} 를 던지므로,
     * 부르는 쪽이 그것을 「없는 칸」으로 넘길 때는 <b>메시지를 보고 가려야 한다</b> —
     * record 가 깨진 경우도 같은 예외로 온다.
     */
    static int maxOf(RecordComponent component) {
        for (Annotation annotation : annotationsOn(component)) {
            if (annotation instanceof Size size) {
                return size.max();
            }
            Size meta = annotation.annotationType().getAnnotation(Size.class);
            if (meta != null) {
                return meta.max();
            }
        }
        throw new AssertionError(component.getDeclaringRecord().getSimpleName() + "."
                + component.getName() + " 에 @Size 가 없다. 제약만 있고 앱은 안 막는다");
    }

    /** 칸에 붙은 것과 그 칸이 만든 필드에 붙은 것을 합쳐서 본다. */
    private static List<Annotation> annotationsOn(RecordComponent component) {
        List<Annotation> found = new ArrayList<>(List.of(component.getAnnotations()));
        try {
            found.addAll(List.of(component.getDeclaringRecord()
                    .getDeclaredField(component.getName()).getAnnotations()));
        } catch (NoSuchFieldException e) {
            throw new AssertionError("record 인데 칸에 맞는 필드가 없다: " + component, e);
        }
        return found;
    }

    private static RecordComponent component(Class<?> record, String name) {
        for (RecordComponent candidate : record.getRecordComponents()) {
            if (candidate.getName().equals(name)) {
                return candidate;
            }
        }
        throw new AssertionError(record.getSimpleName() + " 에 " + name + " 칸이 없다");
    }
}
