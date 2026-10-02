package com.projectshop.shop.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 성능 기준선의 원본({@code load/baseline.json})과 사본({@code performance-goals.md} 「기준선」 표)이 같은지 본다(`70`, `D21`).
 *
 * <p><b>원본은 JSON 이다.</b> {@code scripts/load-test.sh} 가 그것과 견주고, 사람은 문서 표를 읽는다.
 * 기준선을 다시 쓰고(`--update-baseline`) 표를 안 고치면 문서가 낡은 수를 들고 「여기까지는 괜찮다」고 말한다 —
 * 그 반대면 표가 회귀를 덮는다.
 *
 * <p><b>키로 잇는다.</b> 표의 둘째 칸(백틱 안)이 JSON 의 이름이다. p95 칸에 수가 없는 행(「미측정」)이 남아도 빨갛다 —
 * 기준선을 세운 뒤에는 표의 모든 입구가 재져 있어야 한다.
 */
@DisplayName("성능 기준선의 원본과 사본")
class PerformanceBaselineConsistencyTest {

    private static final Path BASELINE = Path.of("..", "load", "baseline.json");
    private static final Path GOALS = Path.of("..", "doc", "reference", "performance-goals.md");

    /** 납작한 JSON 의 한 쌍. {@code load/compare.mjs} 가 이 꼴로만 쓴다 */
    private static final Pattern JSON_PAIR = Pattern.compile("\"([a-z_]+)\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)");

    /** 「기준선」 표의 행 — 입구 | `키` | 경로 | 주 질의 평균 | p95 */
    private static final Pattern TABLE_ROW =
            Pattern.compile("^\\|[^|]+\\|\\s*`([a-z_]+)`\\s*\\|[^|]+\\|[^|]+\\|\\s*([^|]+?)\\s*\\|\\s*$");

    @Test
    @DisplayName("기준선 JSON 과 문서 표의 p95 가 같다")
    void documentCopiesBaseline() throws IOException {
        Map<String, BigDecimal> json = baseline();
        Map<String, String> table = tableP95();

        assertThat(json).as("기준선이 비었다 — %s 를 못 읽었다", BASELINE.toAbsolutePath()).isNotEmpty();
        assertThat(table.keySet())
                .as("표의 입구와 JSON 의 입구가 같아야 한다 (performance-goals.md 「기준선」)")
                .isEqualTo(json.keySet());

        Map<String, String> mismatched = new TreeMap<>();
        json.forEach((key, value) -> {
            String copied = table.get(key);
            if (!copied.matches("[0-9]+(\\.[0-9]+)?") || new BigDecimal(copied).compareTo(value) != 0) {
                mismatched.put(key, "표 " + copied + " ↔ JSON " + value);
            }
        });
        assertThat(mismatched)
                .as("기준선을 다시 썼으면 같은 커밋에서 표를 고친다 (performance-goals.md 「기준선을 옮길 때」)")
                .isEmpty();
    }

    private static Map<String, BigDecimal> baseline() throws IOException {
        Map<String, BigDecimal> values = new TreeMap<>();
        Matcher pair = JSON_PAIR.matcher(Files.readString(BASELINE, StandardCharsets.UTF_8));
        while (pair.find()) {
            values.put(pair.group(1), new BigDecimal(pair.group(2)));
        }
        return values;
    }

    /** 「## 기준선」 절 안의 표만 읽는다. 문서의 다른 표가 같은 꼴이어도 안 섞인다 */
    private static Map<String, String> tableP95() throws IOException {
        Map<String, String> rows = new TreeMap<>();
        boolean inSection = false;
        for (String line : Files.readAllLines(GOALS, StandardCharsets.UTF_8)) {
            if (line.startsWith("## ")) {
                inSection = line.trim().equals("## 기준선");
                continue;
            }
            Matcher row = inSection ? TABLE_ROW.matcher(line) : null;
            if (row != null && row.matches()) {
                rows.put(row.group(1), row.group(2));
            }
        }
        return rows;
    }
}
