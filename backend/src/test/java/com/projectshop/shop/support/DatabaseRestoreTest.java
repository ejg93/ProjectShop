package com.projectshop.shop.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.Container;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.projectshop.shop.PostgresTestBase;

/**
 * 뜨고 날리고 되살린다({@code Q101}).
 *
 * <h2>왜 자동으로 돌려야 하나</h2>
 *
 * <p>{@code 64} 가 이 리허설을 <b>손으로 한 번</b> 했고 거기서 끝났다 — CI·스크립트 어디에도
 * 다시 돌리는 자리가 없어서 <b>다음 마이그레이션이 복구를 깨도 아무것도 안 빨개진다</b>
 * (PR #53 리뷰 2회차). 짝인 {@code 65} 는 같은 회차에 테스트까지 갔는데 이쪽만 남았다.
 *
 * <h2>스크립트를 안 부른다</h2>
 *
 * <p>{@code scripts/db-dump.sh} 는 {@code docker compose exec} 로 <b>컴포즈의 서비스</b>를
 * 부르는데, 여기 도는 것은 Testcontainers 가 띄운 컨테이너라 컴포즈에 없다.
 * <b>같은 명령을 같은 방식으로 돌린다</b> — {@code pg_dump -Fc} 로 뜨고
 * {@code pg_restore --clean --if-exists} 로 되붓는 것이 스크립트와 같은 짝이다.
 *
 * <p><b>이 fork 의 DB 만 건드린다.</b> 느린 레인은 fork 가 여럿이고 컨테이너는 하나라
 * 남의 DB 를 날리면 그 fork 가 통째로 깨진다({@code 2i-2}).
 */
// **트랜잭션을 안 연다.** 바탕이 `@Transactional` 이라 그대로 두면 시험의 트랜잭션이
// 표를 잠근 채 `drop schema` 를 부르고, 둘이 서로를 기다려 **회차가 통째로 멈춘다**
// (실측 — 10분을 넘겨도 안 끝났다). 되돌릴 것도 없다. 스키마를 날렸다 되부으니까.
@org.springframework.transaction.annotation.Transactional(
        propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
@DisplayName("백업·복구")
class DatabaseRestoreTest extends PostgresTestBase {

    @Autowired
    private PostgreSQLContainer postgres;

    @Autowired
    private JdbcClient jdbc;

    /**
     * <b>「덤프가 있다」와 「되살아난다」는 다른 말이다.</b> 뜨기만 하고 안 부어 보면
     * 부을 때 처음 알게 되고, 그때는 원본이 없다.
     */
    @Test
    @DisplayName("뜨고 날리고 되부으면 행 수가 같다")
    void 뜨고_날리고_되부으면_같다() throws IOException, InterruptedException {
        String database = databaseName();
        long before = countMigrations();
        assertThat(before).isPositive();

        exec("sh", "-c", "pg_dump -Fc --no-owner --no-privileges -U %s -d %s > /tmp/%s.dump"
                .formatted(postgres.getUsername(), database, database));

        // 스키마를 통째로 날린다. 「빈 DB 에도 붓는다」가 복구 스크립트의 전제다.
        exec("psql", "-U", postgres.getUsername(), "-d", database,
                "-c", "drop schema public cascade; create schema public;");
        assertThat(tableCount()).isZero();

        exec("sh", "-c", "pg_restore --clean --if-exists --no-owner --no-privileges -U %s -d %s /tmp/%s.dump"
                .formatted(postgres.getUsername(), database, database));

        assertThat(countMigrations())
                .as("되살린 DB 의 마이그레이션 이력이 뜨기 전과 같아야 한다")
                .isEqualTo(before);
        assertThat(tableCount()).isPositive();
    }

    private void exec(String... command) throws IOException, InterruptedException {
        Container.ExecResult result = postgres.execInContainer(command);
        // pg_restore 는 되돌릴 것이 없는 `drop` 에 경고를 내고 1 로 끝날 수 있다.
        // 우리가 보는 것은 아래 행 수라, 여기서는 실행이 됐는지만 본다.
        assertThat(result.getExitCode())
                .as("명령이 실패했다: %s%n%s", String.join(" ", command), result.getStderr())
                .isLessThan(2);
    }

    private String databaseName() {
        return jdbc.sql("select current_database()").query(String.class).single();
    }

    private long countMigrations() {
        return jdbc.sql("""
                        select count(*) from information_schema.tables
                         where table_schema = 'public' and table_name = 'flyway_schema_history'
                        """)
                .query(Long.class)
                .single() == 0
                ? 0
                : jdbc.sql("select count(*) from flyway_schema_history").query(Long.class).single();
    }

    private long tableCount() {
        return jdbc.sql("select count(*) from information_schema.tables where table_schema = 'public'")
                .query(Long.class)
                .single();
    }
}
