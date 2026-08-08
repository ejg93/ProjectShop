package com.projectshop.shop.auth;

import static com.projectshop.shop.auth.PermissionEvaluator.evaluate;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
 * <p>축은 넷이다 — 부여 방식(전역·조직), 스코프(own·seller·all), 효과(allow·deny), 대상의 생김새.
 * 상태 축(청크 11a)과 목록 조회(청크 8)는 코드가 아직 없어서 여기 없다.
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

    /** 대상의 생김새. 주인이 있나 없나, 셀러에 속하나 안 속하나의 조합이다 */
    private record Column(String label, Target target) {}

    private static final List<Column> COLUMNS = List.of(
            new Column("내 것", Target.ownedBy(ME)),
            new Column("남의 것", Target.ownedBy(OTHER)),
            new Column("알파(속함)", Target.ofSeller(ALPHA)),
            new Column("베타(안 속함)", Target.ofSeller(BETA)),
            new Column("내 것+알파", Target.of(ME, ALPHA)),
            new Column("남 것+베타", Target.of(OTHER, BETA)));

    @Test
    @DisplayName("판정 매트릭스가 스냅샷과 같다")
    void matrixMatchesSnapshot() throws IOException {
        String rendered = render();

        if (Boolean.getBoolean("snapshot.update")) {
            Files.createDirectories(SNAPSHOT.getParent());
            Files.writeString(SNAPSHOT, rendered, StandardCharsets.UTF_8);
            return;
        }

        assertThat(SNAPSHOT)
                .as("스냅샷 파일이 없다. -Dsnapshot.update=true 로 만든다")
                .exists();
        assertThat(Files.readString(SNAPSHOT, StandardCharsets.UTF_8))
                .as("판정이 바뀌었다. diff 를 보고 의도한 변경이면 -Dsnapshot.update=true 로 갱신한다")
                .isEqualTo(rendered);
    }

    private String render() {
        StringBuilder out = new StringBuilder();
        out.append("""
                # 권한 판정 매트릭스

                `PermissionMatrixTest` 가 생성한다. 손으로 고치지 않는다.
                갱신은 `gradlew test -Dsnapshot.update=true` 로만 한다.

                판정을 받는 사람은 `user=100` 이고 셀러 `1`(알파)에만 속한다.
                조직 부여는 전부 알파에서 받은 것으로 놓는다.

                """);
        appendSingleRuleMatrix(out);
        appendCombinationMatrix(out);
        appendReasons(out);
        return out.toString();
    }

    /** 규칙 하나만 걸렸을 때. 스코프가 대상을 덮는지가 그대로 드러난다 */
    private void appendSingleRuleMatrix(StringBuilder out) {
        out.append("## 규칙 하나\n\n");
        appendHeader(out, List.of("부여", "스코프", "효과"));

        for (String grant : List.of("전역", "조직")) {
            for (String effect : List.of("allow", "deny")) {
                for (String scope : List.of("own", "seller", "all")) {
                    Rule rule = rule(grant, scope, effect);
                    appendRow(out, List.of(rule), List.of(grant, scope, effect));
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
    private void appendCombinationMatrix(StringBuilder out) {
        out.append("## 규칙 여럿\n\n");
        appendHeader(out, List.of("규칙 1", "규칙 2"));

        for (Combination c : combinations()) {
            appendRow(out, c.rules(), List.of(c.first(), c.second()));
        }
        out.append('\n');
    }

    /** 거부가 왜 났는지. 판정 근거 문자열이 바뀌면 디버깅 경로가 바뀐 것이다 */
    private void appendReasons(StringBuilder out) {
        out.append("## 판정 근거\n\n");
        out.append("| 규칙 | 대상 | 근거 |\n|---|---|---|\n");

        List<List<Rule>> samples = List.of(
                List.of(),
                List.of(rule("전역", "own", "allow")),
                List.of(rule("전역", "seller", "allow")),
                List.of(rule("조직", "seller", "allow")),
                List.of(rule("전역", "all", "allow"), rule("전역", "own", "deny")),
                List.of(rule("전역", "seller", "allow"), rule("전역", "own", "deny")));

        for (List<Rule> rules : samples) {
            for (Column column : COLUMNS) {
                out.append("| %s | %s | %s |%n".formatted(
                        rules.isEmpty() ? "(없음)" : join(rules),
                        column.label(),
                        decide(rules, column.target()).reason()));
            }
        }
    }

    private record Combination(String first, String second, List<Rule> rules) {}

    private List<Combination> combinations() {
        return List.of(
                combo("전역 allow/all", "전역 deny/own",
                        rule("전역", "all", "allow"), rule("전역", "own", "deny")),
                combo("전역 deny/own", "전역 allow/all",
                        rule("전역", "own", "deny"), rule("전역", "all", "allow")),
                combo("전역 allow/seller", "전역 deny/own",
                        rule("전역", "seller", "allow"), rule("전역", "own", "deny")),
                combo("조직 allow/seller", "전역 deny/own",
                        rule("조직", "seller", "allow"), rule("전역", "own", "deny")),
                combo("전역 allow/own", "조직 allow/seller",
                        rule("전역", "own", "allow"), rule("조직", "seller", "allow")),
                combo("조직 allow/seller", "전역 allow/own",
                        rule("조직", "seller", "allow"), rule("전역", "own", "allow")),
                combo("전역 allow/all", "조직 deny/seller",
                        rule("전역", "all", "allow"), rule("조직", "seller", "deny")),
                combo("전역 allow/own", "전역 allow/all",
                        rule("전역", "own", "allow"), rule("전역", "all", "allow")));
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

    private void appendRow(StringBuilder out, List<Rule> rules, List<String> labels) {
        out.append('|');
        labels.forEach(label -> out.append(" %s |".formatted(label)));
        for (Column column : COLUMNS) {
            out.append(" %s |".formatted(decide(rules, column.target()).allowed() ? "허용" : "거부"));
        }
        out.append('\n');
    }

    /**
     * 규칙을 만든다. 조직 부여는 알파에서 받은 것으로 고정한다.
     *
     * <p>{@code grantSellerId} 는 {@code seller} 스코프에서만 뜻이 있는데 다른 스코프에도 채운다.
     * 안 채우면 "own 인데 조직 부여" 같은 조합이 표에서 빠져서, 뜻이 없다는 것 자체가 안 드러난다.
     */
    private Rule rule(String grant, String scope, String effect) {
        Long grantSellerId = "조직".equals(grant) ? ALPHA : null;
        return new Rule("role", grantSellerId, scope, effect, Set.of());
    }

    private Decision decide(List<Rule> rules, Target target) {
        if (rules.isEmpty()) {
            return new Decision(false, "거부 — 걸린 규칙이 하나도 없다", Allowed.only(Set.of()));
        }
        return evaluate(rules, MEMBER_OF, ME, target);
    }

    private String join(List<Rule> rules) {
        return rules.stream().map(Rule::toString).reduce((a, b) -> a + " + " + b).orElse("");
    }
}
