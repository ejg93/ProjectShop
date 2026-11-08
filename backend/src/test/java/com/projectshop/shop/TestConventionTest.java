package com.projectshop.shop;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 「{@code @DisplayName} 은 한글 평서형」을 테스트 소스 전체에 건다(`Q30`).
 *
 * <p><b>문서에만 있던 규칙이다.</b> {@code coding-rules.md} 「테스트」가 정해 뒀지만
 * 안 지켜도 빌드가 초록이라, 읽은 사람만 지키는 상태였다. 2026-09-13 에 세니
 * {@code @Test} 가 있는 104 파일이 전부 지키고 있었다 — <b>위반이 0일 때 세우면
 * 새 위반이 들어오는 순간 빨개진다.</b> 쌓인 뒤에 세우면 치우는 값이 먼저 든다.
 *
 * <h2>{@link ArchitectureTest} 와 파일을 가른 이유</h2>
 *
 * <p><b>저쪽은 테스트를 아예 안 읽는다.</b> {@code DoNotIncludeTests} 가 걸려 있어서
 * 이 규칙을 넣을 자리가 없다. {@code @AnalyzeClasses} 가 클래스 단위라
 * 같은 파일에서 범위만 바꿀 수도 없다.
 *
 * <h2>무엇을 못 보나</h2>
 *
 * <p>{@code @ArchTest} 는 메서드가 아니라 <b>필드</b>라 이 규칙이 안 센다. 그쪽은
 * 한글 필드명이 실패 목록에 그대로 뜨므로 {@code @DisplayName} 이 할 일을 이름이 한다.
 *
 * <p>평서형 판정은 금지어 넷을 문자열로 찾는 것이라 <b>존댓말이 아닌 비문은 못 본다</b>.
 * {@code doc-lint.sh} 의 존댓말 검사와 같은 한계고 같은 이유로 넷만 본다 —
 * 뜻을 읽어야 하는 나머지는 기계가 못 내린다(`CLAUDE.md` 「글 작성 규칙」 4번).
 */
@AnalyzeClasses(
        packages = "com.projectshop.shop",
        importOptions = ImportOption.OnlyIncludeTests.class)
class TestConventionTest {

    /**
     * 「테스트」 — 테스트 메서드는 한글 평서형 {@code @DisplayName} 을 단다.
     *
     * <p>실패 목록이 그대로 명세가 되는 것이 이 규칙의 목적이다. 메서드 이름만 있으면
     * 실패 화면이 「무엇이 깨졌나」가 아니라 「어느 함수가 죽었나」가 된다.
     */
    @ArchTest
    static final ArchRule 테스트는_한글_평서형_DisplayName_을_단다 =
            methods()
                    .that().areAnnotatedWith(Test.class)
                    .or().areAnnotatedWith(ParameterizedTest.class)
                    .should(한글_평서형_DisplayName_을_단다())
                    .because("실패 목록이 그대로 명세가 된다 (coding-rules.md 「테스트」)");

    /**
     * 「시험 순서에 기대지 않는다 — 한 자리만 예외다」(`Q226`, {@code quality-gates.md} 「규칙 원장」 ③).
     *
     * <p>예외는 「정리가 실제로 도나」를 재는 자리 하나다({@code coding-rules.md} — 정리 검증은 앞 시험이 남긴 상태를 봐야 한다).
     * 그 자리가 {@code RateLimitFilterTest} 고, <b>둘째가 생기면 빨갛다</b> — 순서에 기대는 시험이 조용히 늘지 않게.
     */
    @ArchTest
    static final ArchRule 시험_순서를_박는_것은_정리_검증_한_자리뿐이다 =
            classes()
                    .that().areAnnotatedWith(TestMethodOrder.class)
                    .should().haveSimpleName("RateLimitFilterTest")
                    .allowEmptyShould(true)
                    .because("시험은 서로 독립이다 — 예외는 정리 검증 한 자리다 (coding-rules.md 「시험 순서에 기대지 않는다」)");

    private static final Pattern HANGUL = Pattern.compile("\\p{IsHangul}");

    /** 백틱·{@code 「」}·큰따옴표 안은 인용이라 걷어낸다({@code doc-lint.sh} 와 같다). */
    private static final Pattern QUOTED = Pattern.compile("`[^`]*`|「[^」]*」|\"[^\"]*\"");

    private static final Pattern HONORIFIC = Pattern.compile("습니다|합니다|하세요|입니다");

    private static final Pattern ARCH_TEST_LINE = Pattern.compile("^\\s*@ArchTest\\s*$");

    /**
     * 「규칙이 주석에 삼켜지지 않는다」(`Q397`) — 소스의 {@code @ArchTest} 줄 수와 컴파일된 규칙 수가 같다.
     *
     * <p>2026-09-12 에 {@code ArchitectureTest} 의 Javadoc 하나가 안 닫혀 규칙 하나가 주석이 됐고
     * 두 달 동안 CI 가 초록이었다. ArchUnit 은 <b>없는 규칙을 실패로 안 센다</b> — 컴파일된 수와
     * 원문의 수를 맞대야 드러난다. 원문은 주석을 걷지 않고 <b>줄 단위</b>로 센다 —
     * Javadoc 의 {@code {@code @ArchTest}} 같은 언급은 줄 머리에 안 오고, 삼켜진 애너테이션은 그대로 줄 머리에 남는다.
     * <b>전제가 둘이다</b> — 규칙은 필드로 두고, {@code @ArchTest} 줄에는 애너테이션만 둔다. 메서드 꼴이나 같은 줄 주석이 생기면
     * 여기가 빨개진다(놓치는 쪽이 아니라 스스로 드러나는 쪽이다).
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(classes = {ArchitectureTest.class, PublicSurfaceTest.class, TestConventionTest.class})
    @DisplayName("ArchUnit 시험 클래스의 원문 @ArchTest 줄 수와 컴파일된 규칙 수가 같다")
    void 원문의_규칙_수와_컴파일된_규칙_수가_같다(Class<?> type) throws IOException {
        Path source = Path.of("src/test/java", type.getName().replace('.', '/') + ".java");
        List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
        long written = lines.stream().filter(line -> ARCH_TEST_LINE.matcher(line).matches()).count();
        long compiled = Arrays.stream(type.getDeclaredFields())
                .filter(field -> field.isAnnotationPresent(ArchTest.class))
                .count();

        assertThat(compiled)
                .as("%s — 원문 @ArchTest %d 줄, 컴파일된 규칙 %d. 주석이 규칙을 삼켰나 본다", type.getSimpleName(), written, compiled)
                .isEqualTo(written);
    }

    private static ArchCondition<JavaMethod> 한글_평서형_DisplayName_을_단다() {
        return new ArchCondition<>("한글 평서형 @DisplayName 을 단다") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                if (!method.isAnnotatedWith(DisplayName.class)) {
                    events.add(SimpleConditionEvent.violated(method,
                            method.getFullName() + " 에 @DisplayName 이 없다"));
                    return;
                }
                String value = method.getAnnotationOfType(DisplayName.class).value();
                if (!HANGUL.matcher(value).find()) {
                    events.add(SimpleConditionEvent.violated(method,
                            method.getFullName() + " 의 @DisplayName 에 한글이 없다: " + value));
                    return;
                }
                String unquoted = QUOTED.matcher(value).replaceAll("");
                if (HONORIFIC.matcher(unquoted).find()) {
                    events.add(SimpleConditionEvent.violated(method,
                            method.getFullName() + " 의 @DisplayName 이 존댓말이다: " + value));
                }
            }
        };
    }
}
