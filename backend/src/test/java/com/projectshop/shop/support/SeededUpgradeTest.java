package com.projectshop.shop.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Properties;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.projectshop.shop.PostgresTestBase;

/**
 * 시드가 먼저 들어간 DB 에 뒤의 스키마 마이그레이션이 올라가나(`Q199`).
 *
 * <p><b>운영 백엔드가 이틀 동안 못 올라갔다</b>(#70~#74). 배포 DB 는 `V81` 까지 받은 뒤 데모 시드(`V900`~`V906`)를
 * 받았고, 그 뒤에 온 `V82` 부터는 시드보다 번호가 낮아서 Flyway 가 「순서가 어긋났다」며 기동을 막았다
 * ({@code Detected resolved migration not applied to database: 82}). 로컬과 CI 는 매번 빈 DB 에서 올려서 시드가
 * 늘 맨 뒤에 붙으므로 **한 번도 이 모양을 안 지났다** — 이 시험이 그 모양을 만든다.
 *
 * <p><b>배포 설정 파일을 그대로 읽는다.</b> {@code application-demo.yml} 의 위치와 {@code out-of-order} 로 Flyway 를
 * 돌려서, 누가 그 줄을 지우면 여기가 빨개진다.
 */
@DisplayName("시드 뒤에 올라가는 마이그레이션")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SeededUpgradeTest extends PostgresTestBase {

    /**
     * 배포 DB 가 시드를 받은 자리(2026-09-23 에 운영 로그로 확인). 이 번호 위의 스키마 마이그레이션은 전부 시드 뒤에
     * 올라가야 한다 — 뒤에 오는 마이그레이션도 이 시험이 같이 지난다.
     */
    private static final String SEEDED_AT = "81";

    @Autowired
    private PostgreSQLContainer postgres;

    @Test
    @DisplayName("시드를 받은 DB 에 나머지 스키마가 배포 설정 그대로 올라간다")
    void appliesLaterSchemaOnTheSeededDatabase() throws Exception {
        Properties demo = demoProfile();
        String[] locations = demo.getProperty("spring.flyway.locations").split(",");
        boolean outOfOrder = Boolean.parseBoolean(demo.getProperty("spring.flyway.out-of-order", "false"));

        String database = "seeded_probe_" + System.getProperty("org.gradle.test.worker", "1")
                + "_" + Integer.toHexString(Path.of(System.getProperty("user.dir")).toAbsolutePath()
                        .hashCode()).toLowerCase(Locale.ROOT);
        String url = "jdbc:postgresql://%s:%d/%s"
                .formatted(postgres.getHost(), postgres.getFirstMappedPort(), database);

        recreate(database);
        try {
            // 운영이 받은 순서 그대로 — 스키마를 멈춘 자리까지, 그 위에 시드.
            flyway(url, false, "classpath:db/migration").target(SEEDED_AT).load().migrate();
            flyway(url, false, "classpath:db/seed", "classpath:db/seed-demo")
                    .ignoreMigrationPatterns("*:missing")
                    .load()
                    .migrate();

            // 순서를 안 풀면 운영이 본 그 오류다.
            assertThatThrownBy(() -> flyway(url, false, locations).load().migrate())
                    .isInstanceOf(FlywayValidateException.class);

            flyway(url, outOfOrder, locations).load().migrate();

            assertThat(flyway(url, outOfOrder, locations).load().info().pending())
                    .describedAs("application-demo.yml 의 spring.flyway.out-of-order 가 꺼져 있으면 배포가 기동을 못 한다")
                    .isEmpty();
        } finally {
            drop(database);
        }
    }

    private org.flywaydb.core.api.configuration.FluentConfiguration flyway(String url, boolean outOfOrder,
            String... locations) {
        return Flyway.configure()
                .dataSource(url, postgres.getUsername(), postgres.getPassword())
                .locations(locations)
                .outOfOrder(outOfOrder);
    }

    private static Properties demoProfile() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-demo.yml"));
        return yaml.getObject();
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
}
