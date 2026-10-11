package com.projectshop.shop.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

import com.projectshop.shop.auth.PermissionEvaluator.Decision;
import com.projectshop.shop.auth.PermissionEvaluator.Rule;
import com.projectshop.shop.auth.PermissionEvaluator.Target;

/**
 * 판정의 모든 축을 조합해 표로 떠서 파일에 고정한다. 판정이 바뀌면 diff 가 뜬다.
 *
 * <p>다른 테스트들은 "이 경우엔 이래야 한다" 를 하나씩 못박는다. 못박은 것만 지켜진다.
 * 이 테스트는 반대로 <b>전부를 찍어 놓고 바뀌면 알려준다.</b> 의도한 변경인지는 사람이 diff 를 보고 판단한다.
 *
 * <p>스냅샷은 {@code -Dsnapshot.update=true} 로만 갱신한다(D15).
 * 자동으로 덮으면 판정이 망가진 것을 갱신으로 지나친다.
 *
 * <p>축은 다섯이다 — 부여 방식(전역·조직), 스코프(own·seller·all), 효과(allow·deny), 대상의 생김새,
 * 대상의 상태. 목록 조회(청크 8)는 행 하나의 판정이 아니라 여기 없다.
 *
 * <p><b>같은 스냅샷을 두 구현에 돌린다</b>(청크 `74`, {@link Decider}). 스냅샷은 운영 판정기만 쓴다 —
 * 두 번째 구현이 다른 칸은 그 파라미터만 빨갛다.
 */
class PermissionMatrixTest {

    private static final Path SNAPSHOT = Path.of("src/test/resources/snapshots/permission-matrix.md");

    /** 판정을 받는 사람 */
    private static final long ME = 100L;
    /** 남 */
    private static final long OTHER = 200L;
    /** ME 가 속한 셀러 */
    private static final long ALPHA = 1L;
    /** ME 가 안 속한 셀러 */
    private static final long BETA = 2L;

    private static final Set<Long> MEMBER_OF = Set.of(ALPHA);

    /** 상태 축 절에서 동작이 열리는 상태 */
    private static final Allowed<String> OPEN_WHEN_PAID = Allowed.only(Set.of("PAID"));

    static final List<Decider> DECIDERS = List.of(Decider.EVALUATOR, Decider.MANAGED);

    /** 대상의 생김새. 주인이 있나 없나, 셀러에 속하나 안 속하나의 조합이다 */
    private record Column(String label, Target target) {}

    private static final List<Column> COLUMNS = List.of(
            new Column("내 것", Target.ownedBy(ME)),
            new Column("남의 것", Target.ownedBy(OTHER)),
            new Column("알파(속함)", Target.ofSeller(ALPHA)),
            new Column("베타(안 속함)", Target.ofSeller(BETA)),
            new Column("내 것+알파", Target.of(ME, ALPHA)),
            new Column("남 것+베타", Target.of(OTHER, BETA)));

    @ParameterizedTest(name = "{0}")
    @DisplayName("판정 매트릭스가 두 구현에서 스냅샷과 같다")
    @FieldSource("DECIDERS")
    void matrixMatchesSnapshot(Decider decider) throws IOException {
        String rendered = render(decider);

        // **생성물에 CR 이 있으면 안 된다**(`Q115`). 파일은 `.gitattributes` 가 `eol=lf` 로 박는데
        // 생성이 플랫폼 개행(`%n`·`lineSeparator`)을 쓰면 **Windows 에서만** 갈려서,
        // 리눅스 CI 가 초록인 채로 새 clone 이 빨개진다. 파일이 아니라 **생성 쪽**을 재야
        // 어느 플랫폼에서 돌리든 같은 답이 나온다.
        assertThat(rendered)
                .as("스냅샷 생성이 플랫폼 개행을 썼다. `%%n` 을 `\\n` 으로 바꾼다(Q115)")
                .doesNotContain("\r");

        if (Boolean.getBoolean("snapshot.update") && decider == Decider.EVALUATOR) {
            Files.createDirectories(SNAPSHOT.getParent());
            Files.writeString(SNAPSHOT, rendered, StandardCharsets.UTF_8);
            return;
        }

        assertThat(SNAPSHOT)
                .as("스냅샷 파일이 없다. -Dsnapshot.update=true 로 만든다")
                .exists();
        assertThat(Files.readString(SNAPSHOT, StandardCharsets.UTF_8))
                .as("%s 의 판정이 스냅샷과 다르다. 운영 판정기면 diff 를 보고 의도한 변경일 때 "
                        + "-Dsnapshot.update=true 로 갱신하고, 두 번째 구현이면 그 칸을 명세(D6)와 견준다(75)", decider)
                .isEqualTo(rendered);
    }

    private String render(Decider decider) {
        StringBuilder out = new StringBuilder();
        out.append("""
                # 권한 판정 매트릭스

                `PermissionMatrixTest` 가 생성한다. 손으로 고치지 않는다.
                갱신은 `gradlew test -Dsnapshot.update=true` 로만 한다.

                판정을 받는 사람은 `user=100` 이고 셀러 `1`(알파)에만 속한다.
                조직 부여는 전부 알파에서 받은 것으로 놓는다.

                """);
        appendSingleRuleMatrix(out, decider);
        appendCombinationMatrix(out, decider);
        appendReasons(out, decider);
        appendStatusAxis(out, decider);
        appendTiesAndFieldGroups(out, decider);
        return out.toString();
    }

    /** 규칙 하나만 걸렸을 때. 스코프가 대상을 덮는지가 그대로 드러난다 */
    private void appendSingleRuleMatrix(StringBuilder out, Decider decider) {
        out.append("## 규칙 하나\n\n");
        appendHeader(out, List.of("부여", "스코프", "효과"));

        for (String grant : List.of("전역", "조직")) {
            for (Effect effect : Effect.values()) {
                for (Scope scope : Scope.values()) {
                    Rule rule = rule(grant, scope, effect);
                    appendRow(out, decider, List.of(rule), List.of(grant, scope.code(), effect.code()));
                }
            }
        }
        out.append('\n');
    }

    /**
     * 규칙이 여럿 걸렸을 때. 스코프는 넓은 쪽이 이기고 효과는 좁은 쪽이 이겨서 방향이 반대다.
     *
     * <p>순서를 바꾼 짝을 같이 넣는다. 순서가 결과를 바꾸면 그 자리에서 드러나야 한다.
     */
    private void appendCombinationMatrix(StringBuilder out, Decider decider) {
        out.append("## 규칙 여럿\n\n");
        appendHeader(out, List.of("규칙 1", "규칙 2"));

        for (Combination c : combinations()) {
            appendRow(out, decider, c.rules(), List.of(c.first(), c.second()));
        }
        out.append('\n');
    }

    /** 거부가 왜 났는지. 판정 근거 문자열이 바뀌면 디버깅 경로가 바뀐 것이다 */
    private void appendReasons(StringBuilder out, Decider decider) {
        out.append("## 판정 근거\n\n");
        out.append("| 규칙 | 대상 | 근거 |\n|---|---|---|\n");

        List<List<Rule>> samples = List.of(
                List.of(),
                List.of(rule("전역", Scope.OWN, Effect.ALLOW)),
                List.of(rule("전역", Scope.SELLER, Effect.ALLOW)),
                List.of(rule("조직", Scope.SELLER, Effect.ALLOW)),
                List.of(rule("전역", Scope.ALL, Effect.ALLOW), rule("전역", Scope.OWN, Effect.DENY)),
                List.of(rule("전역", Scope.SELLER, Effect.ALLOW), rule("전역", Scope.OWN, Effect.DENY)));

        for (List<Rule> rules : samples) {
            for (Column column : COLUMNS) {
                // `%n` 이 아니라 `\n` 이다(`Q115`). `%n` 은 **플랫폼 개행**이라 Windows 에서 `\r\n` 을 낸다 —
                // 스냅샷 파일은 `.gitattributes` 가 `eol=lf` 로 못 박아서, 리눅스 CI 는 초록인데
                // **새 Windows clone 은 이 시험 하나로 빨갰다.** 아래 CR 검사가 재발을 막는다.
                out.append("| %s | %s | %s |\n".formatted(
                        rules.isEmpty() ? "(없음)" : join(rules),
                        column.label(),
                        decide(decider, rules, column.target()).reason()));
            }
        }
    }

    /**
     * 상태 축. 동작이 {@code PAID} 에서만 열릴 때 대상의 상태마다 무엇이 나나.
     *
     * <p><b>상태를 규칙 뒤에 보는지</b>가 드러나게 거부 규칙과 범위 밖 규칙을 같이 넣는다 — 앞에 보면
     * 권한이 없는 사람도 「상태 때문에 막혔다」를 받는다(`D6`).
     */
    private void appendStatusAxis(StringBuilder out, Decider decider) {
        out.append("## 상태 축\n\n");
        out.append("동작은 `PAID` 에서만 열린다. 대상은 `내 것`이다.\n\n");
        out.append("| 규칙 | 상태 | 근거 |\n|---|---|---|\n");

        List<List<Rule>> samples = List.of(
                List.of(rule("전역", Scope.ALL, Effect.ALLOW)),
                List.of(rule("전역", Scope.OWN, Effect.ALLOW)),
                List.of(rule("전역", Scope.ALL, Effect.ALLOW), rule("전역", Scope.OWN, Effect.DENY)),
                List.of(rule("전역", Scope.SELLER, Effect.ALLOW)));
        List<String> statuses = Arrays.asList(null, "PAID", "SHIPPED");

        for (List<Rule> rules : samples) {
            for (String status : statuses) {
                Target target = Target.ownedBy(ME).inStatus(status);
                out.append("| %s | %s | %s |\n".formatted(
                        join(rules),
                        status == null ? "(없음)" : status,
                        decider.decide(rules, MEMBER_OF, ME, target, OPEN_WHEN_PAID).reason()));
            }
        }
    }

    /**
     * 같은 넓이의 허용·거부 둘·필드 그룹. <b>허용·거부는 같아도 근거와 보이는 필드가 갈릴 수 있는 자리</b>다 —
     * 앞의 절은 규칙에 필드 그룹을 안 달아서 그 갈림을 못 봤다(마무리 60차 독립 리뷰).
     *
     * <p>짝마다 순서를 바꾼 것을 같이 넣는다. 근거가 「먼저 읽힌 줄」이라는 명세(`D6` 「판정 순서」)가 그 자리에서 드러난다.
     */
    private void appendTiesAndFieldGroups(StringBuilder out, Decider decider) {
        out.append("## 같은 넓이·거부 둘·필드 그룹\n\n");
        out.append("| 규칙 | 대상 | 근거 | 필드 그룹 |\n|---|---|---|---|\n");

        List<List<Rule>> samples = List.of(
                List.of(rule("조직", Scope.SELLER, Effect.ALLOW, Set.of("contact")),
                        rule("전역", Scope.SELLER, Effect.ALLOW, Set.of("settlement"))),
                List.of(rule("전역", Scope.SELLER, Effect.ALLOW, Set.of("settlement")),
                        rule("조직", Scope.SELLER, Effect.ALLOW, Set.of("contact"))),
                List.of(rule("전역", Scope.ALL, Effect.DENY), rule("전역", Scope.OWN, Effect.DENY)),
                List.of(rule("전역", Scope.OWN, Effect.DENY), rule("전역", Scope.ALL, Effect.DENY)),
                List.of(rule("전역", Scope.OWN, Effect.ALLOW, Set.of("contact")),
                        rule("전역", Scope.ALL, Effect.ALLOW)));

        for (List<Rule> rules : samples) {
            for (Column column : COLUMNS) {
                Decision decision = decide(decider, rules, column.target());
                out.append("| %s | %s | %s | %s |\n".formatted(
                        rules.stream().map(rule -> rule + fieldGroupsOf(rule)).reduce((a, b) -> a + " + " + b)
                                .orElse(""),
                        column.label(),
                        decision.reason(),
                        render(decision.visibleFieldGroups())));
            }
        }
    }

    private static String fieldGroupsOf(Rule rule) {
        return rule.fieldGroups().isEmpty() ? "" : rule.fieldGroups().stream().sorted().toList().toString();
    }

    /** 「전부」와 「빈 목록」을 글자로 가른다 — 둘을 같은 칸으로 찍으면 그 갈림이 스냅샷에서 사라진다 */
    private static String render(Allowed<String> groups) {
        return switch (groups) {
            case Allowed.Everything<String> ignored -> "전부";
            case Allowed.Only<String>(Set<String> values) ->
                    values.isEmpty() ? "(없음)" : String.join(", ", values.stream().sorted().toList());
        };
    }

    private record Combination(String first, String second, List<Rule> rules) {}

    private List<Combination> combinations() {
        return List.of(
                combo("전역 allow/all", "전역 deny/own",
                        rule("전역", Scope.ALL, Effect.ALLOW), rule("전역", Scope.OWN, Effect.DENY)),
                combo("전역 deny/own", "전역 allow/all",
                        rule("전역", Scope.OWN, Effect.DENY), rule("전역", Scope.ALL, Effect.ALLOW)),
                combo("전역 allow/seller", "전역 deny/own",
                        rule("전역", Scope.SELLER, Effect.ALLOW), rule("전역", Scope.OWN, Effect.DENY)),
                combo("조직 allow/seller", "전역 deny/own",
                        rule("조직", Scope.SELLER, Effect.ALLOW), rule("전역", Scope.OWN, Effect.DENY)),
                combo("전역 allow/own", "조직 allow/seller",
                        rule("전역", Scope.OWN, Effect.ALLOW), rule("조직", Scope.SELLER, Effect.ALLOW)),
                combo("조직 allow/seller", "전역 allow/own",
                        rule("조직", Scope.SELLER, Effect.ALLOW), rule("전역", Scope.OWN, Effect.ALLOW)),
                combo("전역 allow/all", "조직 deny/seller",
                        rule("전역", Scope.ALL, Effect.ALLOW), rule("조직", Scope.SELLER, Effect.DENY)),
                combo("전역 allow/own", "전역 allow/all",
                        rule("전역", Scope.OWN, Effect.ALLOW), rule("전역", Scope.ALL, Effect.ALLOW)));
    }

    private Combination combo(String first, String second, Rule a, Rule b) {
        return new Combination(first, second, List.of(a, b));
    }

    private void appendHeader(StringBuilder out, List<String> labels) {
        out.append('|');
        labels.forEach(label -> out.append(" %s |".formatted(label)));
        COLUMNS.forEach(column -> out.append(" %s |".formatted(column.label())));
        out.append("\n|");
        labels.forEach(label -> out.append("---|"));
        COLUMNS.forEach(column -> out.append("---|"));
        out.append('\n');
    }

    private void appendRow(StringBuilder out, Decider decider, List<Rule> rules, List<String> labels) {
        out.append('|');
        labels.forEach(label -> out.append(" %s |".formatted(label)));
        for (Column column : COLUMNS) {
            out.append(" %s |".formatted(decide(decider, rules, column.target()).allowed() ? "허용" : "거부"));
        }
        out.append('\n');
    }

    /**
     * 규칙을 만든다. 조직 부여는 알파에서 받은 것으로 고정한다.
     *
     * <p>{@code grantSellerId} 는 {@code seller} 스코프에서만 뜻이 있는데 다른 스코프에도 채운다.
     * 안 채우면 "own 인데 조직 부여" 같은 조합이 표에서 빠져서, 뜻이 없다는 것 자체가 안 드러난다.
     */
    private Rule rule(String grant, Scope scope, Effect effect) {
        return rule(grant, scope, effect, Set.of());
    }

    /** 필드 그룹을 단 규칙. 빈 집합은 제한이 없다는 뜻이다({@code role_permission_field} 에 행이 없다) */
    private Rule rule(String grant, Scope scope, Effect effect, Set<String> fieldGroups) {
        Long grantSellerId = "조직".equals(grant) ? ALPHA : null;
        return new Rule("role", grantSellerId, scope, effect, fieldGroups);
    }

    /** 상태 축을 안 거는 판정. 앞의 세 절은 상태와 무관한 축을 본다 */
    private Decision decide(Decider decider, List<Rule> rules, Target target) {
        return decider.decide(rules, MEMBER_OF, ME, target, Allowed.everything());
    }

    private String join(List<Rule> rules) {
        return rules.stream().map(Rule::toString).reduce((a, b) -> a + " + " + b).orElse("");
    }
}
