package com.projectshop.shop;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

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
import org.junit.jupiter.params.ParameterizedTest;

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

    private static final Pattern HANGUL = Pattern.compile("\\p{IsHangul}");

    /** 백틱·{@code 「」}·큰따옴표 안은 인용이라 걷어낸다({@code doc-lint.sh} 와 같다). */
    private static final Pattern QUOTED = Pattern.compile("`[^`]*`|「[^」]*」|\"[^\"]*\"");

    private static final Pattern HONORIFIC = Pattern.compile("습니다|합니다|하세요|입니다");

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
