package com.projectshop.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 선언 차례 억제 목록이 늘지 않고 줄마다 근거를 드는가(`Q292`).
 *
 * <p>{@code quality-gates.md} 의 문턱 「억제 목록은 줄이기만」과 {@code suppressions.xml} 머리의
 * 「새 줄은 근거가 있어야 한다 · 임시 줄엔 걷을 청크 번호」가 문서로만 서 있었다 — 그 파일을 읽는 곳이 없어서
 * 근거 없는 줄을 더해도 빌드가 초록이었다. <b>근거 없는 제외가 쌓이면 게이트가 무엇을 막는지 아무도 모른다.</b>
 *
 * <p><b>기준선은 래칫이다.</b> 줄이 줄면 아래 수를 같이 내린다. 늘리려면 그 줄이 영구 칸의 예외 둘
 * ({@code coding-rules.md} 「선언 순서」) 중 하나를 대야 하고, 그때 이 수를 올리는 것이 리뷰에 보이는 자리다.
 */
@DisplayName("Checkstyle 억제 목록")
class CheckstyleSuppressionTest {

    /**
     * 억제 줄 수의 천장. 2026-10-23 `Q291` 이 `StorageTestBase` 를 걷어 영구 `ArchitectureTest` 하나였고,
     * 2026-10-25 `Q304` 가 순환 복잡도 상한을 켜며 넘는 셋을 임시로 올렸다(`Q306` 이 걷는다)
     */
    private static final int BASELINE = 4;

    private static final Path SUPPRESSIONS = Path.of("config", "checkstyle", "suppressions.xml");

    private static final Pattern TICKET = Pattern.compile("Q\\d+");

    /** 칸을 가르는 머리 주석. 이것은 근거가 아니다 */
    private static final List<String> SECTION_MARKERS = List.of("<!-- 영구 -->", "<!-- 임시 -->");

    /** 억제 줄 하나와 그 바로 위 주석. 주석이 없으면 빈 글자다 */
    private record Entry(int line, String suppress, String comment, boolean temporary) {}

    private static List<Entry> entries() throws IOException {
        List<String> lines = Files.readAllLines(SUPPRESSIONS, StandardCharsets.UTF_8);
        List<Entry> found = new ArrayList<>();
        boolean temporary = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.equals("<!-- 임시 -->")) {
                temporary = true;
            } else if (line.equals("<!-- 영구 -->")) {
                temporary = false;
            }
            if (line.startsWith("<suppress ")) {
                found.add(new Entry(i + 1, line, commentAbove(lines, i), temporary));
            }
        }
        return found;
    }

    /** {@code index} 줄 바로 위의 주석 덩어리. 여러 줄 주석은 {@code <!--} 줄까지 올라가 이어 붙인다 */
    private static String commentAbove(List<String> lines, int index) {
        int end = index - 1;
        if (end < 0 || !lines.get(end).strip().endsWith("-->")) {
            return "";
        }
        int start = end;
        while (start > 0 && !lines.get(start).contains("<!--")) {
            start--;
        }
        String comment = String.join(" ", lines.subList(start, end + 1)).strip();
        return SECTION_MARKERS.contains(comment) ? "" : comment;
    }

    /**
     * 수가 기준선과 <b>같아야</b> 한다. 넘지 않나만 보면 하나가 빠진 뒤 빈 자리가 리뷰 없이 다시 찬다 —
     * 줄었을 때도 빨개야 기준선이 따라 내려간다(PR 리뷰 전 독립 리뷰, 마무리 68차).
     */
    @Test
    @DisplayName("억제 줄 수가 기준선과 같다")
    void matchesBaseline() throws IOException {
        assertThat(entries())
                .describedAs("억제 목록은 줄이기만 한다(quality-gates.md). 늘었으면 새 위반을 고치거나, 영구 칸의 예외를 대고 "
                        + "이 시험의 BASELINE 을 같이 올린다. 줄었으면 BASELINE 을 내린다")
                .hasSize(BASELINE);
    }

    @Test
    @DisplayName("억제 줄마다 바로 위에 근거 주석이 있다")
    void everyEntryHasReason() throws IOException {
        assertThat(entries())
                .describedAs("근거 없는 제외가 쌓이면 게이트가 무엇을 막는지 아무도 모른다(suppressions.xml 머리). "
                        + "칸 머리(<!-- 영구 -->·<!-- 임시 -->)는 근거가 아니다")
                .allSatisfy(entry -> assertThat(entry.comment())
                        .describedAs("suppressions.xml:%d %s", entry.line(), entry.suppress())
                        .isNotBlank());
    }

    @Test
    @DisplayName("임시 칸의 줄은 걷을 청크 번호를 든다")
    void temporaryEntryNamesTicket() throws IOException {
        assertThat(entries().stream().filter(Entry::temporary).toList())
                .describedAs("임시 칸은 풀 길이 있는데 범위 밖이라 남긴 것이다 — 걷을 청크가 없으면 영구와 같아진다")
                .allSatisfy(entry -> assertThat(TICKET.matcher(entry.comment()).find())
                        .describedAs("suppressions.xml:%d 주석에 Q 번호가 없다: %s", entry.line(), entry.comment())
                        .isTrue());
    }
}
