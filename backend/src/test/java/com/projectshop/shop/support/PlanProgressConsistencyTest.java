package com.projectshop.shop.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 이력이 완료로 적은 청크를 분할표에서 찾아 그 행이 닫혔는지 대조한다. 두 문서가 갈리면 실패한다.
 *
 * <p><b>기록은 재발을 못 막는다</b>(CLAUDE.md 「끝 — 이 다섯을 채워야 닫힌다」).
 * 청크를 닫을 때 {@code PROGRESS.md} 이력과 {@code PLAN.md} 분할표를 손으로 둘 다 고치는데,
 * 한쪽만 고쳐도 아무 명령이 안 깨져서 <b>다음 사람이 그 청크를 미착수로 읽는다.</b>
 * {@code 12a-2} 가 그렇게 이틀 남아 있었고, 그동안 후속 {@code 12a-3} 은 완료로 닫혀 있었다.
 *
 * <p>대조는 <b>한 방향만</b> 한다 — 이력에 완료인데 분할표가 안 닫힌 것을 찾는다.
 * 반대 방향(이력에 없는 분할표 완료)은 안 본다. 이력에는 청크가 아닌 줄이 섞여 있어서다 —
 * 점검·마무리·문서 보강은 분할표에 행이 없고, 그것이 정상이다.
 * 분할표에 <b>행이 없는</b> 이력 id 도 같은 이유로 넘긴다.
 */
class PlanProgressConsistencyTest {

    private static final Path PLAN = Path.of("..", "PLAN.md");
    private static final Path PROGRESS = Path.of("..", "PROGRESS.md");

    /** 이력 줄은 첫 칸이 날짜다. 다른 표와 갈리는 유일한 표시다. */
    private static final Pattern HISTORY_DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    /** 칸 구분자. 정규식 특수문자라 그대로 못 쓴다. */
    private static final Pattern CELL_SEPARATOR = Pattern.compile(Pattern.quote("|"));

    @Test
    @DisplayName("이력이 완료로 적은 청크는 분할표에서도 닫혀 있다")
    void completedChunksAreClosedInPlan() throws IOException {
        Map<String, Boolean> closedById = planRows();

        List<String> stillOpen = new ArrayList<>();
        for (String id : completedInHistory()) {
            Boolean closed = closedById.get(id);
            if (closed != null && !closed) {
                stillOpen.add(id);
            }
        }

        assertThat(stillOpen)
                .describedAs("PROGRESS 이력은 완료인데 PLAN 분할표가 안 닫힌 청크. "
                        + "그 행의 청크 칸에 취소선을 치고 선행 칸을 `완료` 로 바꾼다")
                .isEmpty();
    }

    /**
     * 분할표의 각 행이 닫혔는지를 청크 번호별로 모은다. <b>닫힘 표시가 두 형식이라 둘 다 본다</b> —
     * 청크 칸의 취소선과 선행 칸의 {@code 완료} 는 같은 뜻이다(PLAN.md 분할표 머리).
     *
     * <p>{@code 원안} 행은 뺀다 — 쪼개기 전 설명을 남겨 둔 줄이라 그 청크의 현재 상태가 아니다.
     */
    private static Map<String, Boolean> planRows() throws IOException {
        Map<String, Boolean> closedById = new HashMap<>();
        for (String line : Files.readAllLines(PLAN, StandardCharsets.UTF_8)) {
            String[] cells = cellsOf(line);
            if (cells.length < 4) {
                continue;
            }
            String id = cells[0];
            boolean idStruckThrough = id.startsWith("~~");
            id = strip(id.replace("~~", ""));
            if (id.isEmpty() || id.endsWith("원안")) {
                continue;
            }
            boolean closed = idStruckThrough || cells[1].startsWith("~~") || "완료".equals(cells[cells.length - 1]);
            closedById.merge(id, closed, (before, now) -> before || now);
        }
        return closedById;
    }

    /** 이력에서 완료로 적힌 줄의 청크 번호를 뽑는다. 청크 칸은 {@code 12a-2. 환불 입구} 모양이다. */
    private static List<String> completedInHistory() throws IOException {
        List<String> ids = new ArrayList<>();
        for (String line : Files.readAllLines(PROGRESS, StandardCharsets.UTF_8)) {
            String[] cells = cellsOf(line);
            if (cells.length < 4 || !HISTORY_DATE.matcher(cells[0]).matches()) {
                continue;
            }
            if (!cells[2].startsWith("완료")) {
                continue;
            }
            String name = cells[1];
            int dot = name.indexOf('.');
            ids.add(strip(dot < 0 ? name : name.substring(0, dot)));
        }
        return ids;
    }

    /** 표 한 줄을 칸으로 가른다. 표가 아닌 줄에는 빈 배열을 준다. */
    private static String[] cellsOf(String line) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("|")) {
            return new String[0];
        }
        String body = trimmed.substring(1);
        if (body.endsWith("|")) {
            body = body.substring(0, body.length() - 1);
        }
        String[] cells = CELL_SEPARATOR.split(body, -1);
        for (int i = 0; i < cells.length; i++) {
            cells[i] = strip(cells[i]);
        }
        return cells;
    }

    /** 칸에서 값만 남긴다 — 굵게 표시와 홑따옴표는 번호가 아니라 꾸밈이다. */
    private static String strip(String cell) {
        return cell.replace("**", "").replace("`", "").trim();
    }
}
