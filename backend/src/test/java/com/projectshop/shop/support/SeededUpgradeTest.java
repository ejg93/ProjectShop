package com.projectshop.shop.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.stream.Stream;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.projectshop.shop.PostgresTestBase;

/**
 * 시드를 스키마와 다른 이력 표로 돌린다(`Q200`) — 옛 모양 DB 가 새 기동을 지나고 시드가 안 겹치나.
 *
 * <p><b>운영 백엔드가 이틀 동안 못 올라갔다</b>(#70~#74, `Q199`). 배포 DB 는 `V81` 까지 받은 뒤 데모 시드(`V900`~`V906`)를
 * 같은 이력 표에 받았고, 그 뒤에 온 `V82` 부터는 시드보다 번호가 낮아서 Flyway 가 기동을 막았다. `Q199` 는 {@code out-of-order}
 * 로 풀었고 `Q200` 은 시드를 {@code flyway_seed_history} 로 떼서 그 설정을 끈다. 로컬과 CI 는 매번 빈 DB 라 <b>한 번도 옛 모양을
 * 안 지났다</b> — 이 시험이 그 모양을 만든다.
 *
 * <p><b>배포 설정 파일을 그대로 읽는다.</b> {@code application-demo.yml} 의 {@code shop.seed.locations} 로 전략을 세워서,
 * 누가 그 줄을 지우면 시드가 안 들어 여기가 빨개진다.
 */
@DisplayName("시드 이력 가르기")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SeededUpgradeTest extends PostgresTestBase {

    /**
     * 배포 DB 가 시드를 받은 자리(2026-09-23 에 운영 로그로 확인). 이 번호 위의 스키마 마이그레이션은 전부 시드 뒤에
     * 올라가야 한다 — 뒤에 오는 마이그레이션도 이 시험이 같이 지난다.
     */
    private static final String SEEDED_AT = "81";

    /** 숫자 버전이 시드 바닥 이상인 행. 전략 안의 판정과 같은 글자다 */
    private static final String SEED_ROWS = "case when version ~ '^[0-9]+$' then version::int end >= 900";

    @Autowired
    private PostgreSQLContainer postgres;

    @Test
    @DisplayName("시드를 같은 이력에 받은 옛 DB 가 순서 검사를 켠 채 올라가고 시드가 안 겹친다")
    void movesSeedsOffAnOldShapedDatabase() throws Exception {
        String url = recreate("old");
        try {
            seedTheOldWay(url);
            long accounts = count(url, "select count(*) from app_user");

            // 옮기지 않으면 운영이 본 그 오류다 — 순서 검사가 켜져 있고 시드 행이 스키마 이력에 있다.
            assertThatThrownBy(() -> schema(url).migrate()).isInstanceOf(FlywayValidateException.class);

            strategy().migrate(schema(url));

            assertMoved(url, accounts);
        } finally {
            drop(url);
        }
    }

    /**
     * <b>옮기다 죽어도 다시 기동하면 된다.</b> 전략은 시드 이력을 먼저 baseline 하고 그다음 스키마 이력의 시드 행을 지운다 —
     * 그 사이에 죽은 꼴(시드 이력 있음 + 시드 행 남음)에서 지우기만 다시 해야 한다. 순서가 거꾸로면 빈 시드 이력에 시드를 다시 붓는다.
     */
    @Test
    @DisplayName("옮기다 끊긴 DB 도 다음 기동에 시드를 두 번 안 붓고 마저 옮긴다")
    void resumesAnInterruptedMove() throws Exception {
        String url = recreate("resume");
        try {
            seedTheOldWay(url);
            long accounts = count(url, "select count(*) from app_user");
            Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                    .table(SeedMigrationStrategy.SEED_TABLE)
                    .baselineVersion("906")
                    .load()
                    .baseline();

            strategy().migrate(schema(url));

            assertMoved(url, accounts);
            assertThat(count(url, "select count(*) from " + SeedMigrationStrategy.SEED_TABLE + " where type = 'BASELINE'"))
                    .as("baseline 은 한 줄이다 — 다시 기동해도 또 안 찍는다")
                    .isEqualTo(1);
        } finally {
            drop(url);
        }
    }

    @Test
    @DisplayName("빈 DB 에는 스키마가 먼저, 시드가 따로 들고 다시 기동해도 그대로다")
    void seedsAfterSchemaOnAnEmptyDatabase() throws Exception {
        String url = recreate("empty");
        try {
            strategy().migrate(schema(url));
            // 다시 기동한다 — 스키마 이력은 있고 시드 행은 없는 꼴이다. 시험 DB·옮긴 뒤의 운영이 매 기동 이 길을 지난다.
            strategy().migrate(schema(url));

            assertThat(count(url, "select count(*) from flyway_schema_history where success"))
                    .as("스키마 이력에는 스키마만 든다 — `/api/health` 의 적용 수와 `deploy-check.sh` 가 이 수를 본다")
                    .isEqualTo(versionedFiles("db/migration"));
            assertThat(count(url, "select count(*) from " + SeedMigrationStrategy.SEED_TABLE
                    + " where success and type = 'SQL'"))
                    .as("시드 이력에는 배포 프로필이 붓는 시드 파일이 다 든다")
                    .isEqualTo(versionedFiles("db/seed") + versionedFiles("db/seed-demo"));
            assertThat(count(url, "select count(*) from app_user")).as("시드가 실제로 부어졌다").isPositive();
        } finally {
            drop(url);
        }
    }


    /**
     * <b>시드를 싣는 프로필이 스키마 Flyway 를 안 건드린다</b>(마무리 62차 독립 리뷰). 위 시험들은 스키마 Flyway 를 손으로 세워서
     * 프로필이 {@code out-of-order} 를 되살리거나 시드를 스키마 위치에 다시 넣어도 초록이다 — 그 두 줄이 `Q200` 이 지운 것이다.
     */
    @Test
    @DisplayName("시드를 싣는 프로필은 순서 검사를 안 끄고 시드를 스키마 위치에 안 넣는다")
    void seedProfilesLeaveSchemaFlywayAlone() {
        for (String file : List.of("application-demo.yml", "application-local.yml")) {
            Properties profile = profile(file);
            assertThat(profile.getProperty("spring.flyway.out-of-order"))
                    .as("%s 가 순서 검사를 끈다 — 진짜 순서 사고도 같이 통과한다", file).isNull();
            assertThat(profile.getProperty("spring.flyway.locations", ""))
                    .as("%s 가 시드를 스키마 이력에 다시 섞는다", file).doesNotContain("seed");
            assertThat(profile.getProperty("shop.seed.locations", ""))
                    .as("%s 가 시드 위치를 안 준다", file).contains("db/seed");
        }
    }

    /** 옮긴 뒤의 모양 — 스키마 이력에 시드 행이 없고, 시드 이력이 baseline 을 들고, 시드가 두 번 안 부어졌다 */
    private void assertMoved(String url, long accountsBefore) throws Exception {
        assertThat(count(url, "select count(*) from flyway_schema_history where " + SEED_ROWS))
                .as("스키마 이력에 시드 행이 남았다").isZero();
        assertThat(count(url, "select count(*) from " + SeedMigrationStrategy.SEED_TABLE
                + " where type = 'BASELINE' and version = '906'"))
                .as("시드 이력이 받은 자리(V906)를 baseline 으로 든다").isEqualTo(1);
        assertThat(schema(url).info().pending())
                .as("순서 검사를 켠 채 스키마가 끝까지 올라갔다").isEmpty();
        assertThat(count(url, "select count(*) from app_user"))
                .as("시드를 두 번 부으면 계정이 는다").isEqualTo(accountsBefore);
    }

    /** 운영이 받은 순서 그대로 — 스키마를 멈춘 자리까지, 그 위에 시드를 **같은 이력 표**에 */
    private void seedTheOldWay(String url) {
        Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").target(SEEDED_AT).load().migrate();
        Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/seed", "classpath:db/seed-demo")
                .ignoreMigrationPatterns("*:missing")
                .load()
                .migrate();
    }

    /** 자동 설정이 만드는 스키마 Flyway 와 같은 꼴 — 기본 위치 하나, 순서 검사는 기본값(켜짐) */
    private Flyway schema(String url) {
        return Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load();
    }

    private static SeedMigrationStrategy strategy() {
        return new SeedMigrationStrategy(profile("application-demo.yml").getProperty("shop.seed.locations", ""));
    }

    private static Properties profile(String file) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource(file));
        return yaml.getObject();
    }

    private long count(String url, String sql) {
        return JdbcClient.create(new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()))
                .sql(sql).query(Long.class).single();
    }

    /** 그 폴더의 버전 마이그레이션 파일 수. 시험의 작업 디렉터리가 {@code backend/} 다 */
    private static long versionedFiles(String folder) {
        try (Stream<Path> files = Files.list(Path.of("src", "main", "resources", folder))) {
            return files.filter(path -> path.getFileName().toString().matches("V\\d+__.+\\.sql")).count();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String recreate(String purpose) throws SQLException {
        String database = "seeded_" + purpose + "_" + System.getProperty("org.gradle.test.worker", "1")
                + "_" + Integer.toHexString(Path.of(System.getProperty("user.dir")).toAbsolutePath()
                        .hashCode()).toLowerCase(Locale.ROOT);
        execute("drop database if exists \"" + database + "\" with (force)");
        execute("create database \"" + database + "\"");
        return "jdbc:postgresql://%s:%d/%s".formatted(postgres.getHost(), postgres.getFirstMappedPort(), database);
    }

    private void drop(String url) throws SQLException {
        execute("drop database if exists \"" + url.substring(url.lastIndexOf('/') + 1) + "\" with (force)");
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** 이 시험이 읽는 폴더 셋 — 하나라도 못 읽으면 위 셈이 0 이 되어 거짓 초록이 난다 */
    @Test
    @DisplayName("세는 폴더가 다 있다")
    void foldersExist() {
        assertThat(List.of("db/migration", "db/seed", "db/seed-demo"))
                .allSatisfy(folder -> assertThat(versionedFiles(folder)).as(folder).isPositive());
    }

    /**
     * <b>스키마는 바닥 밑, 시드는 바닥 위다</b>(PR #100 리뷰). 전략은 스키마 이력에서 {@value SeedMigrationStrategy#SEED_FLOOR} 이상을
     * 시드로 보고 옮겨 지운다 — 스키마 번호가 거기 닿으면 진짜 스키마 행을 지운다. 그 전제를 여기서 못박는다.
     */
    @Test
    @DisplayName("스키마 번호는 시드 바닥 밑이고 시드 번호는 그 위다")
    void schemaStaysBelowTheSeedFloor() throws IOException {
        assertThat(versions("db/migration")).as("스키마 번호가 시드 바닥에 닿았다 — 이관이 그 행을 지운다")
                .allMatch(version -> version < SeedMigrationStrategy.SEED_FLOOR);
        assertThat(versions("db/seed")).as("시드 번호가 바닥 밑이다 — 시드 이력으로 안 옮겨진다")
                .allMatch(version -> version >= SeedMigrationStrategy.SEED_FLOOR);
        assertThat(versions("db/seed-demo")).allMatch(version -> version >= SeedMigrationStrategy.SEED_FLOOR);
    }

    private static List<Integer> versions(String folder) throws IOException {
        try (Stream<Path> files = Files.list(Path.of("src", "main", "resources", folder))) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.matches("V\\d+__.+\\.sql"))
                    .map(name -> Integer.parseInt(name.substring(1, name.indexOf("__"))))
                    .toList();
        }
    }
}
