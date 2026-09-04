package com.projectshop.shop.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code stack.md} 의 버전 표가 실제 파일과 같은지 대조한다. 갈리면 실패한다.
 *
 * <p><b>그 문서가 스스로 「표가 낡을 수 있다」고 적어 뒀다.</b> 사람이 손으로 맞추는 표라
 * 그렇게 되는데, 청크 {@code 2f} 가 의존성 갱신을 주 1회로 열면서 <b>낡는 속도가 빨라진다</b> —
 * 갱신 PR 은 {@code build.gradle.kts} 만 고치고 문서는 안 건드린다.
 *
 * <p>「갱신되면 적용범위 문서를 다시 본다」(CLAUDE.md)는 <b>사람이 읽어야 걸리는 규칙</b>이라
 * 강제 지점 5위다. 버전만이라도 4위(테스트)로 내린다. 판단이 필요한 나머지는
 * PR 템플릿이 든다 — 그쪽은 여전히 5위고, 그것이 지금 내릴 수 있는 한계다.
 *
 * <p>표의 세 번째 칸이 <b>어느 파일에 박혀 있나</b>를 적는다. 그 칸에서 파일 이름을 뽑아
 * 실제로 읽고, 두 번째 칸의 버전 문자열이 그 안에 있는지만 본다.
 */
class StackVersionConsistencyTest {

    private static final Path STACK = Path.of("..", "doc", "reference", "stack.md");
    /** 이 제목 아래의 표만 본다. 절이 사라지면 표가 0줄이 되고 아래 검사가 그것을 잡는다. */
    private static final String VERSION_HEADING = "## 버전";

    /** 버전 표의 줄. 칸이 셋이다 — 대상·버전·어디에 박혀 있나. */
    private static final Pattern ROW = Pattern.compile("^[|]([^|]+)[|]([^|]+)[|]([^|]+)[|][ ]*$");
    /** 세 번째 칸의 홑따옴표 안이 파일 이름이다. */
    private static final Pattern BACKTICKED = Pattern.compile("`([^`]+)`");

    @Test
    @DisplayName("stack.md 의 버전 표가 실제 파일과 같다")
    void versionTableMatchesRealFiles() throws IOException {
        List<String> mismatches = new ArrayList<>();
        Path lastFile = null;
        boolean inVersionSection = false;
        List<String> checked = new ArrayList<>();

        for (String line : Files.readAllLines(STACK, StandardCharsets.UTF_8)) {
            // **절을 안 가르면 문서 안의 다른 3칸 표를 전부 먹는다.** stack.md 에는
            // 관례 표·정정 표가 여럿이고 그것들도 칸이 셋이라 모양으로는 안 갈린다.
            if (line.startsWith("## ")) {
                inVersionSection = line.trim().equals(VERSION_HEADING);
                continue;
            }
            if (!inVersionSection) {
                continue;
            }
            Matcher row = ROW.matcher(line);
            if (!row.matches()) {
                continue;
            }
            String subject = row.group(1).trim();
            String version = row.group(2).trim();
            String where = row.group(3).trim();

            // 표 머리와 구분선.
            if (subject.equals("대상") || subject.startsWith("---")) {
                continue;
            }
            // 「안 적는다」(BOM 이 관리한다)와 「아직 없다」(앞으로 들어온다)는 볼 파일이 없다.
            if (where.startsWith("안 적는다") || where.contains("아직")) {
                continue;
            }

            Path file = fileNamedIn(where);
            // 「같은 파일의 toolchain」처럼 앞 줄을 가리키는 칸이 있다.
            if (file == null) {
                file = lastFile;
            }
            if (file == null) {
                mismatches.add(subject + ": 볼 파일을 못 찾았다 — " + where);
                continue;
            }
            lastFile = file;
            checked.add(subject);

            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (!content.contains(version)) {
                mismatches.add(subject + " " + version + " 이 " + file + " 에 없다");
            }
        }

        assertThat(checked)
                .describedAs("stack.md 에서 버전 표를 한 줄도 못 읽었다. "
                        + VERSION_HEADING + " 절이 사라졌거나 이름이 바뀌었다")
                .isNotEmpty();

        assertThat(mismatches)
                .describedAs("stack.md 버전 표와 실제 파일이 갈렸다. "
                        + "의존성을 올렸으면 그 표도 같이 고친다")
                .isEmpty();
    }

    /**
     * 칸에서 홑따옴표로 감싼 이름을 찾아 실재하는 경로로 바꾼다.
     *
     * <p>표가 적는 이름이 저장소 뿌리 기준일 때도 있고({@code backend/build.gradle.kts})
     * 그냥 파일 이름일 때도 있어서({@code build.gradle.kts}) 후보를 순서대로 본다.
     * 테스트는 {@code backend/} 에서 돈다.
     */
    private static Path fileNamedIn(String cell) {
        Matcher names = BACKTICKED.matcher(cell);
        while (names.find()) {
            String name = names.group(1);
            for (Path candidate : List.of(Path.of(name), Path.of("..", name))) {
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }
}
