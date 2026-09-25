package com.projectshop.shop.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.projectshop.shop.PostgresTestBase;

/**
 * 되돌리는 파일을 <b>실제로 돌려 본다</b>(`Q173`).
 *
 * <p>{@code MigrationUndoTest} 는 {@code U} 가 {@code V} 와 짝으로 있는지만 센다. 그래서 <b>돌리면 실패하는 파일</b>이
 * 초록으로 지나갔다 — 처음 돌리자 넷이 빨갰다. `U85`·`U90`·`U94` 는 시행된 고지를 지우려 해서
 * {@code policy_document_immutable} 에 막혔고, `U85` 가 통째로 롤백되니 `U83` 도 남은 답글 표에 걸렸다.
 * 되돌리기가 필요한 날은 이미 무엇이 틀어진 날이라, 그날 처음 알면 늦다.
 *
 * <h2>어떻게 돌리나</h2>
 *
 * <p>빈 DB 하나를 따로 만들어 마지막 판까지 올리고, {@code U} 를 <b>번호 역순으로</b> 하나씩 돌린다 —
 * 실제로 되돌릴 때의 순서다. 파일 하나를 한 트랜잭션으로 돌리고, 실패하면 그 파일만 롤백하고 다음으로 간다.
 * 파일을 문장으로 쪼개지 않는다 — 함수 본문의 {@code $$} 안 세미콜론을 쪼개는 쪽이 틀린다.
 * PostgreSQL 드라이버가 여러 문장을 한 번에 받는다.
 *
 * <p><b>fork DB 를 안 쓴다.</b> 되돌리면 표가 사라지고, 같은 JVM 의 다른 시험이 그 DB 를 쓴다.
 */
@DisplayName("되돌리는 파일을 돌린다")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MigrationUndoRunTest extends PostgresTestBase {

    private static final Path UNDO = Path.of("src", "main", "resources", "db", "undo");

    private static final Pattern VERSION = Pattern.compile("^U(\\d+)__");

    /**
     * <b>돌지 않는 것이 맞는 파일</b>과 그 이유. 여기 있는 번호가 실제로 돌면 빨갛다 —
     * 목록이 낡으면 「못 도는 줄 알았던 것」이 조용히 남는다.
     */
    private static final Map<Integer, String> CANNOT_UNDO = Map.of();

    @Autowired
    private PostgreSQLContainer postgres;

    @Test
    @DisplayName("되돌리는 파일은 역순으로 다 돌고, 못 도는 것은 이유가 적혀 있다")
    void everyUndoRunsInReverse() throws Exception {
        String database = "undo_probe_" + System.getProperty("org.gradle.test.worker", "1")
                + "_" + Integer.toHexString(Path.of(System.getProperty("user.dir")).toAbsolutePath()
                        .hashCode()).toLowerCase(Locale.ROOT);
        String url = "jdbc:postgresql://%s:%d/%s"
                .formatted(postgres.getHost(), postgres.getFirstMappedPort(), database);

        recreate(database);
        try {
            Flyway.configure()
                    .dataSource(url, postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            Map<Integer, String> failed = new TreeMap<>();
            try (Connection connection = DriverManager.getConnection(
                    url, postgres.getUsername(), postgres.getPassword())) {
                connection.setAutoCommit(false);
                for (Path file : undoFilesNewestFirst()) {
                    int version = versionOf(file);
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(Files.readString(file));
                        connection.commit();
                    } catch (SQLException e) {
                        connection.rollback();
                        failed.put(version, firstLine(e.getMessage()));
                    }
                }
            }

            assertThat(failed.keySet())
                    .describedAs("돌지 않은 되돌리는 파일: %s. 고치거나, 돌지 않는 것이 맞으면 이유와 함께"
                            + " CANNOT_UNDO 에 적는다. 목록에만 있고 실제로 돌면 그 줄을 지운다", failed)
                    .containsExactlyInAnyOrderElementsOf(CANNOT_UNDO.keySet());
        } finally {
            drop(database);
        }
    }

    private void recreate(String database) throws SQLException {
        drop(database);
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("create database \"" + database + "\"");
        }
    }

    private void drop(String database) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("drop database if exists \"" + database + "\" with (force)");
        }
    }

    /**
     * 되돌리는 파일을 번호 역순으로. <b>0개면 실패한다</b>(`Q224`) — 목록이 비면 루프가 안 돌고 `failed` 가 비어 초록이 된다.
     * 정규식을 깨 보니 실제로 그랬다.
     */
    private static List<Path> undoFilesNewestFirst() throws IOException {
        try (Stream<Path> files = Files.list(UNDO)) {
            List<Path> found = files.filter(file -> VERSION.matcher(file.getFileName().toString()).find())
                    .sorted(Comparator.comparingInt(MigrationUndoRunTest::versionOf).reversed())
                    .toList();
            assertThat(found).as("되돌리는 파일을 0개 읽었다 — 경로나 이름 규칙이 바뀌었다: %s", UNDO).isNotEmpty();
            return found;
        }
    }

    private static int versionOf(Path file) {
        Matcher matcher = VERSION.matcher(file.getFileName().toString());
        if (!matcher.find()) {
            throw new IllegalStateException("번호가 없는 되돌리는 파일이다: " + file);
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static String firstLine(String message) {
        return message == null ? "(메시지 없음)" : message.lines().findFirst().orElse(message);
    }
}
