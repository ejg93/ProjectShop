package com.projectshop.shop.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 되돌리는 마이그레이션이 <b>새 {@code V} 마다 있는가</b>({@code 65}).
 *
 * <h2>왜 파일의 존재를 세나</h2>
 *
 * <p>Flyway 무료판에 {@code undo} 가 없다. 되돌리는 SQL 은 사람이 쓰는 것이라
 * <b>안 쓰면 아무 일도 안 일어난다</b> — 필요해지는 날은 이미 되돌려야 하는 날이고,
 * 그때 쓰기 시작하면 늦다.
 *
 * <h2>경계가 {@code V78} 이다</h2>
 *
 * <p>그 앞 일흔일곱 개를 소급해서 쓰지 않는다. <b>쓰는 데 하루가 가고 아무도 안 읽는다</b> —
 * 안 지켜지는 규칙은 없는 규칙이다({@code /warmup}). 여기서 막는 것은
 * <b>새로 더하는 것이 짝 없이 들어오는 일</b>이다.
 */
@DisplayName("되돌리는 마이그레이션")
class MigrationUndoTest {

    private static final Path MIGRATION = Path.of("src", "main", "resources", "db", "migration");
    private static final Path UNDO = Path.of("src", "main", "resources", "db", "undo");

    /** 이 번호부터 짝이 있어야 한다. 앞은 소급해서 안 쓴다 */
    private static final int FROM = 78;

    /** 시드는 뺀다. {@code V900+} 는 데모 데이터라 되돌릴 스키마가 없다 */
    private static final int SEED_FROM = 900;

    private static final Pattern VERSION = Pattern.compile("^V(\\d+)__");

    @Test
    @DisplayName("V78 부터는 되돌리는 파일이 짝으로 있다")
    void 새_마이그레이션은_되돌리는_파일이_있다() throws IOException {
        List<Integer> written = undoVersions();

        List<Integer> missing = versions(MIGRATION).stream()
                .filter(v -> v >= FROM && v < SEED_FROM)
                .filter(v -> !written.contains(v))
                .toList();

        assertThat(missing)
                .describedAs("되돌리는 파일이 없는 마이그레이션. db/undo 에 U<번호>__*.sql 을 만든다(`65`)")
                .isEmpty();
    }

    /** 죽은 줄을 남기지 않는다 — 없는 {@code V} 를 되돌리는 파일은 아무도 안 부른다 */
    @Test
    @DisplayName("되돌리는 파일은 실재하는 마이그레이션의 짝이다")
    void 되돌리는_파일이_실재하는_짝이다() throws IOException {
        List<Integer> applied = versions(MIGRATION);

        List<Integer> orphans = undoVersions().stream()
                .filter(v -> !applied.contains(v))
                .toList();

        assertThat(orphans)
                .describedAs("짝이 없는 되돌리기 파일")
                .isEmpty();
    }

    private static List<Integer> versions(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString())
                    .map(VERSION::matcher)
                    .filter(Matcher::find)
                    .map(m -> Integer.parseInt(m.group(1)))
                    .sorted()
                    .toList();
        }
    }

    private static List<Integer> undoVersions() throws IOException {
        Pattern undo = Pattern.compile("^U(\\d+)__");
        try (Stream<Path> files = Files.list(UNDO)) {
            return files.map(p -> p.getFileName().toString())
                    .map(undo::matcher)
                    .filter(Matcher::find)
                    .map(m -> Integer.parseInt(m.group(1)))
                    .toList();
        }
    }
}
