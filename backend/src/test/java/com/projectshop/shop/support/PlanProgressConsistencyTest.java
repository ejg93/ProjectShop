package com.projectshop.shop.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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

    /** 백틱으로 감싼 인용. <b>그 안의 {@code |} 는 칸 구분자가 아니다</b> — 규칙을 인용하면 표가 갈린 것처럼 보인다. */
    private static final Pattern CODE_SPAN = Pattern.compile("`[^`]*`");
    /** 머리글 바로 아래의 {@code |---|} 줄. 칸 수를 세는 대상이 아니다. */
    private static final Pattern SEPARATOR_ROW = Pattern.compile("\\|[-: |]+\\|");

    /** 마이그레이션 파일이 사는 곳. 테스트의 작업 디렉터리가 {@code backend/} 라 상대 경로다. */
    private static final Path MIGRATIONS = Path.of("src", "main", "resources", "db", "migration");
    /** 데모 시드. 번호 체계가 900번대로 따로 논다 — 여기 있는 번호는 예약이 아니다. */
    private static final Path SEEDS = Path.of("src", "main", "resources", "db", "seed");

    /** `Q142` 를 세울 때 커밋 칸이 비어 있던 완료 행의 수. **채워서 줄면 같이 내린다** */
    private static final int EMPTY_COMMIT_BASELINE = 225;
    /** {@code V74__permission_kind.sql} 의 번호. <b>예약은 이 꼴로만 적는다</b> — 아래 두 테스트가 그것만 본다. */
    private static final Pattern VERSIONED = Pattern.compile("V([0-9]+)__");

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

    /**
     * 표의 모든 줄이 머리글과 같은 칸 수인지 본다.
     *
     * <p><b>위 대조들이 칸 모자란 줄을 조용히 건너뛴다</b> — {@code cells.length < 4} 로 거르는데,
     * 그 거르기는 <b>표가 아닌 줄</b>을 빼려고 있는 것이라 형식이 틀린 <b>표 줄까지 같이 빠진다.</b>
     * 실제로 {@code Q88} 의 이력 줄을 커밋 칸 없이 3칸으로 적었더니 대조가 초록이었고,
     * 칸을 채우자 바로 빨개졌다 — <b>막으려던 거짓 초록이 막는 도구 안에 있었다.</b>
     *
     * <p>거르기 자체는 안 없앤다. 표가 아닌 줄을 빼는 데 필요하다. 대신 <b>센다.</b>
     * 세울 때 {@code PLAN} 셋과 {@code PROGRESS} 열둘이 걸렸다 — 앞은 산문에 쓴 날 {@code |} 고
     * 뒤는 커밋 칸을 빠뜨린 이력 줄이다.
     */
    @Test
    @DisplayName("표의 모든 줄이 머리글과 같은 칸 수다")
    void tableRowsHaveHeaderCellCount() throws IOException {
        List<String> ragged = new ArrayList<>(raggedRows(PLAN));
        ragged.addAll(raggedRows(PROGRESS));

        assertThat(ragged)
                .describedAs("칸 수가 머리글과 다른 표 줄. 대조가 이 줄을 통째로 건너뛴다 — "
                        + "빠진 칸은 채우고, 본문에 쓴 `|` 는 백틱으로 감싼다")
                .isEmpty();
    }


    /**
     * 완료 이력 행의 커밋 칸이 비었나(`Q142`).
     *
     * <p><b>기록만으로는 안 지켜졌다.</b> `PROGRESS.md` 「기록 규칙」이 「해시까지」를 요구하는데
     * <b>2026-09-20 하루에 세 번 샜다</b> — PR #61 의 GitHub 리뷰가 한 번, 마무리 38차 손 대조가
     * 한 번, 39차가 또 한 번 잡았다. {@link #tableRowsHaveHeaderCellCount} 는 <b>칸 수만 세서</b>
     * 빈 칸을 통과시킨다 — `Q109` 가 닫으려던 「조용히 건너뛰기」가 한 칸 안쪽에 남아 있었다.
     *
     * <h2>즉시 터지는 게이트를 못 만든다</h2>
     *
     * <p><b>청크의 마지막 커밋은 자기 해시를 모른다.</b> 그래서 해시는 늘 뒤따르는 커밋이 채우고,
     * 「비면 빨갛다」로 만들면 그 커밋 자체가 막힌다. <b>맨 마지막 완료 행 하나를 면제</b>하는 이유다.
     *
     * <h2>옛 행은 안 고친다</h2>
     *
     * <p>세울 때 {@code 225} 개가 비어 있었다. <b>이력은 그때의 사실이라 소급해서 안 채운다</b> —
     * `2c-2` 가 같은 이유로 이력을 안 고친다. 그래서 래칫이다: <b>이 수를 넘으면 빨갛다.</b>
     * 채워서 줄면 {@link #EMPTY_COMMIT_BASELINE} 를 같이 내린다.
     *
     * <h2>무엇을 막고 무엇을 못 막나</h2>
     *
     * <p><b>막는 것</b>: 빈 행이 <b>둘 이상 동시에</b> 흐르는 것. 오늘 샌 셋이 정확히 그 모양이었다.
     *
     * <p><b>못 막는 것</b>: 묶음의 <b>마지막 한 행</b>. 면제 대상이라 안 채우고 지나갈 수 있다 —
     * 그것은 마무리가 본다(`/wrapup` 「한 일을 적는다」). <b>완전히 못 내린 이유를 여기 적어 둔다.</b>
     */
    @Test
    @DisplayName("완료 이력 행의 커밋 칸이 새로 비지 않는다")
    void completedHistoryRowsCarryTheirCommit() throws IOException {
        List<String> empty = emptyCommitRows();

        assertThat(empty)
                .describedAs("커밋 칸이 빈 완료 이력 행. 맨 마지막 하나는 면제다(자기 해시를 모른다) — "
                        + "그 앞 것부터 `git log --oneline` 으로 채운다. "
                        + "옛 행을 채워서 줄었으면 EMPTY_COMMIT_BASELINE 도 같이 내린다")
                .hasSizeLessThanOrEqualTo(EMPTY_COMMIT_BASELINE);
    }

    /**
     * 커밋 칸이 빈 완료 행을 {@code 날짜 청크} 꼴로 모은다. <b>맨 마지막 하나는 뺀다.</b>
     *
     * <p>이력은 날짜순으로 덧붙이므로 파일에서 마지막에 나온 완료 행이 방금 친 청크다.
     */
    private static List<String> emptyCommitRows() throws IOException {
        List<String> empty = new ArrayList<>();
        String newest = null;

        for (String line : Files.readAllLines(PROGRESS, StandardCharsets.UTF_8)) {
            String[] cells = cellsOf(line);
            if (cells.length < 4 || !HISTORY_DATE.matcher(cells[0]).matches()) {
                continue;
            }
            if (!cells[2].startsWith("완료")) {
                continue;
            }
            String label = cells[0] + " " + cells[1];
            newest = label;
            if (cells[cells.length - 1].isEmpty()) {
                empty.add(label);
            }
        }

        empty.remove(newest);
        return empty;
    }

    /**
     * 한 파일에서 칸 수가 머리글과 다른 줄을 {@code 파일:줄 (칸 n, 머리글 m)} 꼴로 모은다.
     *
     * <p>코드 울타리 안은 표가 아니고, 백틱 인용 안의 {@code |} 는 칸 구분자가 아니다.
     */
    private static List<String> raggedRows(Path file) throws IOException {
        List<String> ragged = new ArrayList<>();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        boolean fenced = false;
        boolean inTable = false;
        int header = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("```")) {
                fenced = !fenced;
                inTable = false;
                continue;
            }
            if (fenced || !line.startsWith("|")) {
                inTable = false;
                continue;
            }
            String masked = CODE_SPAN.matcher(line).replaceAll("X").trim();
            if (SEPARATOR_ROW.matcher(masked).matches()) {
                continue;
            }
            int cells = cellsOf(masked).length;
            if (!inTable) {
                header = cells;
                inTable = true;
                continue;
            }
            if (cells != header) {
                ragged.add(file.getFileName() + ":" + (i + 1) + " (칸 " + cells + ", 머리글 " + header + ")");
            }
        }
        return ragged;
    }

    /**
     * 미착수 청크가 예약한 마이그레이션 번호가 서로 안 겹치는지 본다.
     *
     * <p><b>실제로 겹쳤다</b>(점검 O, 2026-09-17). {@code Q59} 와 {@code Q62} 가 둘 다 {@code V72} 를
     * 적어 뒀고, 그것을 보는 자리가 사람 눈뿐이었다 — {@code CLAUDE.md} 「병렬 줄」이
     * 「마이그레이션 번호를 예약한다」고 정해 놓고 겹침을 재는 것은 안 두었다.
     */
    @Test
    @DisplayName("미착수 청크가 예약한 마이그레이션 번호는 서로 안 겹친다")
    void reservedMigrationNumbersAreUnique() throws IOException {
        List<String> clashes = new ArrayList<>();
        reservedNumbers().forEach((number, chunks) -> {
            if (chunks.size() > 1) {
                clashes.add("V" + number + " ← " + String.join(", ", chunks));
            }
        });

        assertThat(clashes)
                .describedAs("미착수 청크 둘 이상이 같은 마이그레이션 번호를 예약했다. "
                        + "먼저 적은 쪽이 그 번호를 갖고 나머지는 새 번호를 받는다")
                .isEmpty();
    }

    /**
     * 예약한 번호가 이미 있는 마지막 번호보다 큰지 본다.
     *
     * <p><b>빈 번호를 뒤늦게 채우면 기동이 죽는다.</b> Flyway 는 {@code out-of-order} 가 기본으로 꺼져 있고
     * 이 저장소는 그 설정을 안 켰다. {@code V73} 을 이미 받은 DB 에 {@code V72} 가 나타나면 검증에서 막힌다 —
     * 로컬 compose 와 {@code PostgresTestBase} 의 재사용 컨테이너({@code withReuse(true)})가 그 상태로 산다.
     */
    @Test
    @DisplayName("예약한 마이그레이션 번호는 이미 있는 마지막 번호보다 크다")
    void reservedMigrationNumbersExceedApplied() throws IOException {
        int last = lastMigrationNumber();

        List<String> behind = new ArrayList<>();
        reservedNumbers().forEach((number, chunks) -> {
            if (number <= last) {
                behind.add("V" + number + " (" + String.join(", ", chunks) + ")");
            }
        });

        assertThat(behind)
                .describedAs("이미 지나간 번호를 예약했다. 마지막 번호는 V" + last
                        + " 다 — 빈 번호를 뒤에 채우면 그 번호를 안 받은 DB 가 기동에서 막힌다")
                .isEmpty();
    }

    /**
     * 미착수 행이 적어 둔 번호를 번호별로 모은다.
     *
     * <p><b>이미 파일이 있는 번호는 예약이 아니라 인용이다.</b> 미착수 행도 지난 마이그레이션을 근거로
     * 부른다({@code V902__demo_products.sql} 처럼 시드를 가리키는 줄이 실제로 있다). 그것까지 세면
     * 인용이 겹치는 것만으로 빨개진다.
     */
    private static Map<Integer, List<String>> reservedNumbers() throws IOException {
        Set<Integer> existing = existingNumbers();
        Map<Integer, List<String>> byNumber = new LinkedHashMap<>();

        for (String line : Files.readAllLines(PLAN, StandardCharsets.UTF_8)) {
            String[] cells = cellsOf(line);
            if (cells.length < 4) {
                continue;
            }
            String id = cells[0];
            boolean struckThrough = id.startsWith("~~");
            id = strip(id.replace("~~", ""));
            if (id.isEmpty() || id.endsWith("원안")) {
                continue;
            }
            if (struckThrough || cells[1].startsWith("~~") || "완료".equals(cells[cells.length - 1])) {
                continue;
            }
            Matcher matcher = VERSIONED.matcher(line);
            while (matcher.find()) {
                int number = Integer.parseInt(matcher.group(1));
                if (existing.contains(number)) {
                    continue;
                }
                List<String> chunks = byNumber.computeIfAbsent(number, key -> new ArrayList<>());
                if (!chunks.contains(id)) {
                    chunks.add(id);
                }
            }
        }
        return byNumber;
    }

    /** 마이그레이션의 마지막 번호. 예약은 이 값보다 커야 한다. */
    private static int lastMigrationNumber() throws IOException {
        return numbersIn(MIGRATIONS).stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    /** 이미 파일이 있는 번호 전부. 시드도 센다 — 거기 있는 번호는 예약할 수 없다. */
    private static Set<Integer> existingNumbers() throws IOException {
        Set<Integer> numbers = new TreeSet<>(numbersIn(MIGRATIONS));
        numbers.addAll(numbersIn(SEEDS));
        return numbers;
    }

    /** 한 폴더의 {@code V<번호>__} 파일에서 번호만 뽑는다. 폴더가 없으면 빈 목록이다. */
    private static Set<Integer> numbersIn(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return Set.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            Set<Integer> numbers = new TreeSet<>();
            files.map(file -> file.getFileName().toString())
                    .forEach(name -> {
                        Matcher matcher = VERSIONED.matcher(name);
                        if (matcher.lookingAt()) {
                            numbers.add(Integer.parseInt(matcher.group(1)));
                        }
                    });
            return numbers;
        }
    }
}
