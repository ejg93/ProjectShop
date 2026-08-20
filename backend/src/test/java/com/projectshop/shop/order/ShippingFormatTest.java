package com.projectshop.shop.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;

/**
 * 배송지 형식이 <b>두 층에서 같은 것을 말하나</b>(`Q7`, 축 6 재점검).
 *
 * <p>우편번호는 우정사업본부 고시로 <b>국가기초구역번호 다섯 자리</b>다(2015-08-01 시행).
 * 그 형식이 지금 두 곳에 있다 — {@code OrderController.ShippingRequest} 의 {@code @Pattern} 과
 * {@code V33} 의 {@code order_shipping_postal_code_check} 다.
 *
 * <p><b>층이 둘인 것은 정상이다.</b> `D23` 축 2 가 「내릴 수 있는 데까지 내리고 위에도 건다」고
 * 정했고, 앱 검증은 사람에게 문구를 주고 DB 제약은 새 입구가 생겨도 막는다.
 *
 * <p><b>문제는 둘이 갈리는 것이다.</b> 한쪽만 넓히면 앱을 지난 값이 DB 에서 터지거나
 * (500 이 되고 사용자는 이유를 모른다), 반대로 DB 만 넓히면 앱이 막아서 그 제약이 죽은 규칙이 된다.
 * {@code ProductStatusTest.withdrawalReasonMatchesConstraint} 와
 * {@code OrderContractTest} 의 조항 대조가 같은 이유로 서 있다.
 */
@DisplayName("배송지 형식")
class ShippingFormatTest extends PostgresTestBase {

    /** 두 층이 같이 받아야 하는 값과 같이 막아야 하는 값 */
    private static final String[] VALID_POSTAL_CODES = {"06134", "00000", "99999"};
    private static final String[] INVALID_POSTAL_CODES = {"0613", "061345", "0613a", "06 34", ""};

    @Autowired
    private JdbcClient jdbc;

    @Nested
    @DisplayName("우편번호는")
    class PostalCode {

        @Test
        @DisplayName("앱 검증과 DB 제약이 같은 값을 받고 같은 값을 막는다")
        void bothLayersAgree() {
            Pattern app = appPattern();

            for (String code : VALID_POSTAL_CODES) {
                assertThat(app.matcher(code).matches())
                        .as("앱이 %s 를 받아야 한다", code)
                        .isTrue();
                assertThat(passesDatabaseCheck(code))
                        .as("DB 도 %s 를 받아야 한다 — 여기서 갈리면 앱을 지난 값이 500 이 된다", code)
                        .isTrue();
            }

            for (String code : INVALID_POSTAL_CODES) {
                assertThat(app.matcher(code).matches())
                        .as("앱이 %s 를 막아야 한다", code)
                        .isFalse();
                assertThat(passesDatabaseCheck(code))
                        .as("DB 도 %s 를 막아야 한다 — 여기서 갈리면 제약이 죽은 규칙이 된다", code)
                        .isFalse();
            }
        }

        /**
         * 컨트롤러가 실제로 쓰는 정규식을 읽는다.
         *
         * <p>여기 문자열을 다시 적으면 <b>대조하려던 사본이 셋</b>이 된다.
         */
        private Pattern appPattern() throws AssertionError {
            try {
                var component = OrderController.ShippingRequest.class
                        .getDeclaredMethod("postalCode");
                var annotation = component.getAnnotation(
                        jakarta.validation.constraints.Pattern.class);

                assertThat(annotation)
                        .as("우편번호 칸에서 @Pattern 이 사라졌다. 앱 검증이 통째로 빠진 것이다")
                        .isNotNull();

                return Pattern.compile(annotation.regexp());
            } catch (NoSuchMethodException e) {
                throw new AssertionError("ShippingRequest 에 postalCode 가 없다", e);
            }
        }

        /**
         * DB 제약이 그 값을 받아들이나.
         *
         * <p><b>정규식을 여기 다시 안 적는다.</b> 적으면 대조하려던 사본이 셋이 된다 —
         * 제약의 정의에서 읽어 와 그 식을 그대로 돌린다.
         */
        private boolean passesDatabaseCheck(String code) {
            return Boolean.TRUE.equals(jdbc.sql("select :code ~ :pattern")
                    .param("code", code)
                    .param("pattern", constraintPattern())
                    .query(Boolean.class)
                    .single());
        }

        /** `order_shipping_postal_code_check` 의 정의에서 정규식만 뽑는다 */
        private String constraintPattern() {
            String definition = jdbc.sql("""
                            select pg_get_constraintdef(oid) from pg_constraint
                             where conname = 'order_shipping_postal_code_check'
                            """)
                    .query(String.class)
                    .single();

            // 정의에서 작은따옴표로 묶인 것 중 정규식 하나를 고른다.
            // 캐럿으로 시작하는 것이 그것이고, 나머지는 컬럼 이름이나 타입 표기다.
            String pattern = Pattern.compile("'([^']+)'").matcher(definition).results()
                    .map(match -> match.group(1))
                    .filter(quoted -> quoted.startsWith("^"))
                    .findFirst()
                    .orElse(null);

            assertThat(pattern)
                    .as("제약 정의에서 정규식을 못 찾았다: %s", definition)
                    .isNotNull();

            return pattern;
        }
    }
}
