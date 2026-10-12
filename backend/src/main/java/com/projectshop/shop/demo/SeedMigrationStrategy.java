package com.projectshop.shop.demo;

import java.util.Arrays;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 스키마 마이그레이션과 데모 시드를 <b>다른 이력 표</b>로 돌린다(`Q200`, 사용자 결정 2026-09-23).
 *
 * <p><b>왜 가르나.</b> 시드({@code V900}~)가 스키마와 같은 이력 표에 있으면 시드를 받은 DB 에는 뒤에 온 스키마
 * 마이그레이션이 늘 「더 낮은 번호」라 Flyway 가 막았다 — 운영이 #70~#74 동안 그렇게 못 올라갔고(`Q199`),
 * 그 뒤로 {@code out-of-order} 를 켜 두어야 했다. 그 설정은 진짜 순서 사고도 같이 통과시킨다.
 * 시드를 {@value #SEED_TABLE} 로 떼면 스키마 이력은 스키마만 들고 순서 검사를 다시 켤 수 있다.
 *
 * <h2>자리</h2>
 *
 * <p><b>{@code Flyway} 빈을 하나 더 두지 않는다.</b> Boot 의 Flyway 자동 설정은 {@code @ConditionalOnMissingBean} 이라
 * 빈을 하나 더 두면 스키마 Flyway 가 물러난다(4.1.1 을 javap 로 봤다). 대신 자동 설정이 받는 {@link FlywayMigrationStrategy}
 * 로 스키마 {@code migrate()} 앞뒤에 끼운다 — 공식 자리다.
 *
 * <h2>도는 순서</h2>
 *
 * <ol>
 *   <li><b>옮긴다</b> — 스키마 이력에 시드 행({@value #SEED_FLOOR} 이상)이 남아 있으면 시드 이력을 그 최고 버전으로
 *       baseline 하고 나서 스키마 이력의 그 행들을 지운다. <b>순서가 규칙이다</b>: 중간에 죽어도 다음 기동은
 *       「시드 이력 있음 + 시드 행 남음」이라 지우기만 다시 한다. 반대 순서면 빈 시드 이력에 시드를 다시 붓는다</li>
 *   <li>스키마를 올린다 — 자동 설정이 만든 Flyway 그대로</li>
 *   <li>시드를 붓는다 — 위치({@code shop.seed.locations})가 비면 건너뛴다(기본 프로필)</li>
 * </ol>
 */
@Component
class SeedMigrationStrategy implements FlywayMigrationStrategy {

    /** 시드 이력 표. 스키마 이력({@code flyway_schema_history})과 따로 산다 */
    static final String SEED_TABLE = "flyway_seed_history";

    /** 이 번호부터가 시드다(`db/seed`·`db/seed-demo` 의 {@code V900}~). 스키마는 이 밑에서만 번호를 딴다 */
    static final int SEED_FLOOR = 900;

    private static final Logger log = LoggerFactory.getLogger(SeedMigrationStrategy.class);

    /** 버전 칸이 숫자인 행만 정수로 본다. {@code version::int} 를 바로 쓰면 숫자가 아닌 행에서 캐스팅이 터질 수 있다 */
    private static final String SEED_VERSION = "case when version ~ '^[0-9]+$' then version::int end";

    private final String[] locations;

    /**
     * @param locations 쉼표로 이은 시드 위치. 비면 시드를 안 붓는다 — {@code application-local.yml}·{@code application-demo.yml}
     *                  만 준다
     */
    SeedMigrationStrategy(@Value("${shop.seed.locations:}") String locations) {
        this.locations = Arrays.stream(locations.split(","))
                .map(String::trim)
                .filter(location -> !location.isEmpty())
                .toArray(String[]::new);
    }

    @Override
    public void migrate(Flyway schema) {
        DataSource dataSource = schema.getConfiguration().getDataSource();
        moveSeedHistory(dataSource);
        schema.migrate();
        if (locations.length > 0) {
            seed(dataSource).baselineOnMigrate(true).baselineVersion("0").load().migrate();
        }
    }

    /**
     * 스키마 이력에 남은 시드 행을 시드 이력으로 옮긴다. 옮길 것이 없으면 아무것도 안 한다 — 빈 DB 와 이미 옮긴 DB 가 그렇다.
     *
     * <p>행을 복사하지 않고 <b>baseline 한 줄</b>로 남긴다. 시드 파일은 이미 그 DB 에 부어졌으므로 「거기까지 받았다」만
     * 알면 되고, 그 위 번호의 새 시드만 붓는다.
     */
    void moveSeedHistory(DataSource dataSource) {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        if (!exists(jdbc, "flyway_schema_history")) {
            return;
        }
        Integer top = jdbc.sql("select max(" + SEED_VERSION + ") from flyway_schema_history where "
                        + SEED_VERSION + " >= :floor")
                .param("floor", SEED_FLOOR)
                .query(Integer.class)
                .single();
        if (top == null) {
            return;
        }
        if (!exists(jdbc, SEED_TABLE)) {
            seed(dataSource).baselineVersion(String.valueOf(top))
                    .baselineDescription("스키마 이력에서 옮긴 시드(Q200)")
                    .load()
                    .baseline();
        }
        int moved = jdbc.sql("delete from flyway_schema_history where " + SEED_VERSION + " >= :floor")
                .param("floor", SEED_FLOOR)
                .update();
        log.info("시드 이력을 옮겼다: 스키마 이력에서 {}줄을 지우고 {} 를 V{} 로 baseline", moved, SEED_TABLE, top);
    }

    private FluentConfiguration seed(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .table(SEED_TABLE)
                .locations(locations);
    }

    private static boolean exists(JdbcClient jdbc, String table) {
        return Boolean.TRUE.equals(jdbc.sql("select to_regclass(:table) is not null")
                .param("table", table)
                .query(Boolean.class)
                .single());
    }
}
