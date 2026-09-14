package com.projectshop.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
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

/**
 * API 응답 타입이 스펙에서 <b>같은 이름으로 합쳐지지 않는지</b> 본다(`Q45`).
 *
 * <h2>무엇이 있었나</h2>
 *
 * <p>springdoc 은 스키마 이름을 <b>자바 클래스의 짧은 이름</b>으로 짓는다. 우리 코드에
 * {@code Page} 가 여섯, {@code Summary}·{@code Detail} 이 각각 넷이라 <b>서로 덮어썼다</b> —
 * 스펙에 {@code GET /api/orders} 가 <b>정산 요약을 돌려준다</b>고 적혀 있었다.
 * 이름을 준 뒤 스키마가 63개에서 81개가 됐다. 열여덟이 덮여서 사라져 있던 것이다.
 *
 * <p><b>빌드도 테스트도 초록이었다.</b> 깨지는 것은 <b>스펙을 읽고 만든 프론트 타입</b>이라
 * 백엔드에서는 아무 신호가 없다.
 *
 * <h2>왜 스펙이 아니라 소스를 읽나</h2>
 *
 * <p>스펙 쪽에서는 <b>합쳐진 것이 안 보인다.</b> 두 타입이 하나로 합쳐져도 스펙에는 이름이
 * 하나만 남아서 <b>정상과 구분되지 않는다</b> — {@code OpenApiSpecTest} 의 경로 공유 검사는
 * 「상관없는 경로 둘이 같은 스키마를 가리키는」 꼴만 잡고, 겹친 것이 중첩 타입이거나
 * 한 경로 안에서만 쓰이면 못 본다(이 청크에서 실측했다).
 *
 * <p>그래서 <b>이름이 지어지는 자리</b>인 소스를 읽는다. 겹칠 수 있다는 것 자체를 막는 쪽이
 * 겹친 결과를 찾는 것보다 위다.
 *
 * <h2>무엇을 세나</h2>
 *
 * <p>{@code *Query}·{@code *Controller}·{@code *Service} 안의 <b>public record</b> 가 대상이다 —
 * API 로 나가는 읽기 모델과 요청·응답이 전부 그 꼴이다. 이름은 {@code @Schema(name = …)} 가
 * 있으면 그 값이고 없으면 record 이름이다.
 *
 * <h2>무엇을 못 보나</h2>
 *
 * <p><b>소스만 보고는 스펙에 닿는지 못 가른다.</b> 서비스 안에서만 도는 것도 같은 꼴이라
 * 이 검사에 같이 걸린다. 그런 이름은 {@link #INTERNAL_ONLY} 에 이유와 같이 적고 빼되,
 * <b>「안 닿는다」를 주장으로 두지 않는다</b> — {@code OpenApiSpecTest} 가 그 이름이 실제로
 * 스펙에 없는지를 재서, 나중에 응답에 실리면 그쪽이 빨개진다.
 */
class SchemaNameTest {

    private static final Path MAIN = Path.of("src", "main", "java");

    /** API 타입이 사는 자리. 여기 밖의 record 는 스펙에 안 닿는다 */
    private static final Pattern API_CLASS = Pattern.compile(".*(Query|Controller|Service)\\.java$");

    /** {@code @Schema(name = "…")} 바로 다음의 {@code public record X} */
    private static final Pattern NAMED_RECORD = Pattern.compile(
            "@Schema\\(name\\s*=\\s*\"([^\"]+)\"\\)\\s*+public record (\\w+)");

    private static final Pattern RECORD = Pattern.compile("public record (\\w+)");

    /**
     * <b>스펙에 안 닿아서 겹쳐도 되는 이름</b>. 서비스 안에서만 도는 명령·결과다.
     *
     * <p>{@code @Schema(name = …)} 를 붙이지 않는다 — 스키마가 아닌 것에 스키마 이름을 주면
     * 다음 사람이 그것을 응답으로 읽는다.
     *
     * <p><b>「안 닿는다」를 여기서 주장만 하지 않는다.</b> {@code OpenApiSpecTest} 가
     * <b>이 이름들이 실제로 스펙에 없는지</b>를 잰다 — 나중에 응답에 실리는 순간 그쪽이 빨개진다.
     */
    static final Map<String, String> INTERNAL_ONLY = Map.of(
            "Purged", "파기 배치의 결과. 배치가 로그로만 쓴다",
            "Command", "서비스에 넘기는 명령. 컨트롤러가 요청 record 를 이것으로 바꿔서 넘긴다");

    @Test
    @DisplayName("API 응답 타입의 스펙 이름이 겹치지 않는다")
    void schemaNamesAreUnique() {
        Map<String, List<String>> byName = new LinkedHashMap<>();

        for (Path file : apiSources()) {
            String text = JavaSourceText.withoutComments(read(file));
            Map<String, String> named = new LinkedHashMap<>();
            Matcher namedMatcher = NAMED_RECORD.matcher(text);
            while (namedMatcher.find()) {
                named.put(namedMatcher.group(2), namedMatcher.group(1));
            }
            Matcher recordMatcher = RECORD.matcher(text);
            while (recordMatcher.find()) {
                String simple = recordMatcher.group(1);
                String schemaName = named.getOrDefault(simple, simple);
                byName.computeIfAbsent(schemaName, key -> new ArrayList<>())
                        .add(file.getFileName() + "." + simple);
            }
        }

        assertThat(byName).as("소스를 못 읽으면 0개를 재고 조용히 통과한다").hasSizeGreaterThan(40);

        List<String> collided = byName.entrySet().stream()
                .filter(entry -> !INTERNAL_ONLY.containsKey(entry.getKey()))
                .filter(entry -> entry.getValue().size() > 1)
                .map(entry -> entry.getKey() + " ← " + String.join(", ", entry.getValue()))
                .toList();

        assertThat(collided)
                .as("springdoc 이 짧은 이름으로 스키마를 지어서, 이름이 겹치면 한쪽이 다른 쪽을"
                        + " 덮어쓴다. 스펙을 보고 만든 타입이 통째로 다른 자원의 모양이 된다 (Q45)."
                        + " @Schema(name = …) 로 이름을 주거나 record 이름을 고친다")
                .isEmpty();
    }

    private static List<Path> apiSources() {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(path -> API_CLASS.matcher(path.toString()).matches()).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("main 소스를 못 읽었다: " + MAIN.toAbsolutePath(), e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(file + " 를 못 읽었다", e);
        }
    }
}
