package com.projectshop.shop.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 배치 카탈로그(`D19`)의 행과 실제 {@code @Scheduled} 메서드를 양방향으로 대조한다(`Q70`).
 *
 * <p><b>문서가 실물과 갈려도 아무것도 안 깨졌다.</b> 점검 O 가 그 자리를 실물로 찾았다 —
 * 「정산 마감」 행이 <b>둘</b>이었고 하나가 「미정(청크 19) · 아직 없다」였는데,
 * 그때 이미 {@code SettlementCloseBatch} 가 매월 1일에 돌고 있었다. 카탈로그는 <b>운영이 무엇을
 * 기대할지</b>를 정하는 문서라, 없다고 적힌 배치가 도는 것은 「안 도는 줄 알았던 것이 돈다」와 같다.
 *
 * <p><b>양쪽을 다 본다.</b> 한 방향만 보면 반대쪽이 조용히 샌다 —
 * 문서에만 있는 행은 <b>안 도는 배치를 돈다고 적은 것</b>이고,
 * 코드에만 있는 메서드는 <b>아무도 모르는 배치</b>다.
 *
 * <p><b>빠른 레인에 둔다</b>(`D15`). 글자로 읽어 비교하는 것이라 컨테이너가 필요 없다 —
 * {@code BatchSchedulingTest} 는 스레드가 실제로 도는지를 보느라 컨텍스트를 띄우지만 여기는 다르다.
 */
@DisplayName("배치 카탈로그와 실물")
class BatchCatalogTest {

    private static final Path CATALOG = Path.of("..", "doc", "reference", "batch-catalog.md");
    private static final Path SOURCE_ROOT = Path.of("src", "main", "java");

    /** 카탈로그 표가 사는 절. 이 문서의 다른 표(`batch_run` 컬럼 설명 등)를 안 걷으려고 범위를 자른다. */
    private static final String SECTION = "## 카탈로그";

    /** 표 한 줄의 마지막 칸이 클래스다. {@code `OutboxPublisher.publish`} 꼴이다. */
    private static final Pattern CLASS_CELL = Pattern.compile("`([A-Z]\\w+\\.\\w+)`");

    /** {@code @Scheduled} 가 줄 맨 앞에 오는 것만 본다 — 주석 안의 언급을 안 세려고. */
    private static final Pattern SCHEDULED = Pattern.compile("^\\s*@Scheduled\\b");

    /** 애노테이션 다음에 나오는 메서드 이름. 접근 제어자와 반환형을 지나 이름과 여는 괄호를 잡는다. */
    private static final Pattern METHOD = Pattern.compile("\\b(\\w+)\\s*\\(");

    @Test
    @DisplayName("카탈로그가 가리키는 메서드가 실재하고 @Scheduled 가 붙어 있다")
    void catalogRowsPointToScheduledMethods() {
        List<String> scheduled = scheduledMethods();

        List<String> missing = catalogClasses().stream()
                .filter(entry -> !scheduled.contains(entry))
                .toList();

        assertThat(missing)
                .describedAs("카탈로그의 「클래스」 칸이 가리키는 곳에 @Scheduled 메서드가 없다. "
                        + "이름이 바뀌었으면 문서를 고치고, 배치가 사라졌으면 행을 지운다")
                .isEmpty();
    }

    @Test
    @DisplayName("@Scheduled 메서드가 전부 카탈로그에 있다")
    void everyScheduledMethodIsInTheCatalog() {
        List<String> catalog = catalogClasses();

        List<String> undocumented = scheduledMethods().stream()
                .filter(entry -> !catalog.contains(entry))
                .toList();

        assertThat(undocumented)
                .describedAs("카탈로그에 없는 배치가 돈다(`D19`). 무엇을 언제 하고 두 번 돌아도 "
                        + "괜찮은 이유가 무엇인지를 카탈로그에 행으로 적는다")
                .isEmpty();
    }

    /** 카탈로그 절의 표에서 「클래스」 칸만 걷는다. */
    private static List<String> catalogClasses() {
        List<String> found = new ArrayList<>();
        boolean inSection = false;
        for (String line : readLines(CATALOG)) {
            if (line.startsWith("## ")) {
                inSection = line.startsWith(SECTION);
                continue;
            }
            if (!inSection || !line.trim().startsWith("|")) {
                continue;
            }
            String[] cells = line.split("\\|");
            Matcher matcher = CLASS_CELL.matcher(cells[cells.length - 1]);
            if (matcher.find()) {
                found.add(matcher.group(1));
            }
        }
        return found;
    }

    /** {@code @Scheduled} 가 붙은 메서드를 {@code 클래스.메서드} 로 모은다. */
    private static List<String> scheduledMethods() {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String type = file.getFileName().toString().replace(".java", "");
                List<String> lines = readLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    if (!SCHEDULED.matcher(lines.get(i)).find()) {
                        continue;
                    }
                    methodNameAfter(lines, i).ifPresent(name -> found.add(type + "." + name));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("소스를 못 읽었다: " + SOURCE_ROOT.toAbsolutePath(), e);
        }
        return List.copyOf(new TreeSet<>(found));
    }

    /**
     * 애노테이션 다음의 메서드 이름을 찾는다.
     *
     * <p><b>애노테이션이 여러 줄일 수 있어 괄호 균형으로 끝을 센다.</b> 줄 단위로 「{@code (} 를 만나면
     * 메서드」로 잡으면 {@code @Scheduled(fixedDelayString = "${...}",} 의 다음 줄에서 틀린다 —
     * 그 줄에는 여는 괄호가 없고 {@code ${} 의 중괄호만 있어서, 처음 쓴 판이 여기서 메서드를 놓쳤다.
     * 실물이 그 꼴이라({@code OutboxPublisher.publish}) 첫 회차에 바로 드러났다.
     */
    private static java.util.Optional<String> methodNameAfter(List<String> lines, int start) {
        int depth = balance(lines.get(start));
        for (int i = start + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (depth > 0) {
                depth += balance(line);
                continue;
            }
            if (line.isBlank() || line.trim().startsWith("@") || line.trim().startsWith("//")) {
                continue;
            }
            Matcher matcher = METHOD.matcher(line);
            return matcher.find() ? java.util.Optional.of(matcher.group(1)) : java.util.Optional.empty();
        }
        return java.util.Optional.empty();
    }

    /** 한 줄의 괄호 증감. 애노테이션이 어디서 끝나는지를 이것으로 센다. */
    private static int balance(String line) {
        int depth = 0;
        for (char c : line.toCharArray()) {
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
        }
        return depth;
    }

    private static List<String> readLines(Path path) {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("못 읽었다: " + path.toAbsolutePath(), e);
        }
    }
}
