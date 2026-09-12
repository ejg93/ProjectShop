package com.projectshop.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
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
                                "sourceUrl"))));
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
     */
    private static int maxOf(RecordComponent component) {
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
