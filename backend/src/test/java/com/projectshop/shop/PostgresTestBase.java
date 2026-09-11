package com.projectshop.shop;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Map;

import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;

import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * DB 가 필요한 테스트의 바탕. 컨테이너를 테스트가 직접 띄운다.
 *
 * <p>손으로 띄운 로컬 컨테이너에 붙으면 Docker Desktop 을 끄는 순간 테스트가 전부 실패하고,
 * 앞선 테스트가 남긴 데이터에 결과가 좌우된다. CI(청크 2c)에서는 아예 붙을 DB 가 없다.
 *
 * <p>컨테이너는 <b>테스트마다 새로 뜨지 않는다.</b> static 필드로 한 번 띄우고 JVM 이 끝날 때까지 쓴다.
 * Spring 컨텍스트 캐싱과 맞물려서, 컨테이너 기동 비용은 전체 실행에 한 번만 든다.
 *
 * <p>마이그레이션은 Flyway 가 컨테이너에 적용한다. 그래서 이 바탕을 쓰는 테스트는
 * 스키마 제약(트리거·기본키·체크)까지 실제로 검증한다.
 *
 * <h2>{@code db} 태그가 레인을 가른다</h2>
 *
 * <p>이 바탕을 상속하면 태그가 따라오고, 그 순간 <b>느린 레인({@code integrationTest})</b>으로 간다.
 * 빠른 레인({@code test})은 이 태그를 제외해서 컨테이너를 한 번도 안 띄운다 —
 * 검증 한 번이 2분에서 초 단위로 줄고, 그만큼 고치고 다시 돌리는 주기가 짧아진다.
 *
 * <p><b>붙이는 것을 빠뜨릴 자리가 없다.</b> 컨테이너가 이 바탕과 {@link HttpTestBase} 에만 있어서
 * DB 를 쓰려면 둘 중 하나를 상속해야 하고, 상속하면 태그가 같이 온다.
 * 상속하지 않고 DB 를 쓰면 빠른 레인에서 곧바로 실패한다.
 */
@SpringBootTest
/*
 * **여기 있는 이유는 캐시다**(`2i-3`). 이 표시는 컨텍스트 캐시 키의 일부라, 테스트 클래스마다
 * 붙이면 **같은 설정인데 컨텍스트가 둘로 갈린다** — 재 보니 느린 레인의 컨텍스트가 셋이었고
 * 그중 하나가 이것 때문이었다(78개 중 15개만 붙이고 있었다).
 *
 * <p>fork 마다 컨텍스트를 새로 띄우므로 **가짓수가 곧 기동 횟수**다. 바탕으로 올리면
 * MockMvc 를 안 쓰는 테스트도 그 자동설정을 지고 가지만, **컨텍스트 하나를 통째로 아끼는 값이 더 크다.**
 *
 * <p><b>MockMvc 를 권하는 것이 아니다</b>(`D15`) — 서블릿 컨테이너를 안 띄워서 실제 HTTP 와
 * 갈리는 자리가 세 번 나왔고, 관통하는 흐름은 {@link HttpTestBase} 가 든다.
 */
@AutoConfigureMockMvc
@Transactional
@Tag("db")
@Import(PostgresTestBase.Containers.class)
public abstract class PostgresTestBase {

    /**
     * 인증을 스레드에서 걷어낸다.
     *
     * <p>{@code @Transactional} 은 데이터만 되돌린다. 로그인 컨트롤러가
     * {@code SecurityContextHolder} 에 심은 값은 스레드에 남고, MockMvc 테스트들이
     * 스레드를 나눠 쓰기 때문에 <b>다음 테스트 클래스가 인증된 상태로 시작한다.</b>
     *
     * <p>테스트마다 손으로 붙이지 않고 여기 둔 이유는, 빠뜨렸을 때 깨지는 것이
     * <b>빠뜨린 그 클래스가 아니라 남의 클래스</b>라서다. 원인을 찾을 실마리가 없다.
     * 실제로 {@code AuthLoginTest} 를 추가했을 때 {@code CsrfTokenTest} 가 6개 깨졌다.
     *
     * <p>{@code protected} 여야 한다. 하위 테스트가 다른 패키지에 있어서
     * package-private 이면 상속되지 않고, <b>JUnit 이 조용히 안 부른다.</b>
     *
     * <p>둘을 다 비운다. {@code SecurityContextHolder} 는 로그인 컨트롤러가 심은 것을,
     * {@code TestSecurityContextHolder} 는 {@code with(user(...))} 가 심은 것을 들고 있다.
     * 한쪽만 비우면 다른 쪽이 남아 같은 증상이 난다.
     */
    @AfterEach
    protected void clearSecurityContext() {
        SecurityContextHolder.clearContext();
        TestSecurityContextHolder.clearContext();
    }

    /**
     * 앞 테스트가 따로 커밋한 감사 기록을 걷어낸다.
     *
     * <p>시도 기록({@code AuditLog.Kind.ATTEMPT})은 {@code REQUIRES_NEW} 라 <b>테스트 롤백에 안 쓸린다.</b>
     * 그게 청크 {@code 4b-2} 의 목적이라 고칠 것이 아니고, 대신 다음 테스트로 넘어간다.
     *
     * <p><b>지우는 것도 별도 트랜잭션이어야 한다.</b> 테스트 트랜잭션 안에서 지우면 삭제까지 같이
     * 롤백돼서 아무것도 안 지운 것이 된다.
     *
     * <p>{@code @BeforeTransaction} 을 안 쓴 이유는 <b>{@code @Nested} 클래스에서 안 돌아서</b>다.
     * Spring 은 테스트 클래스의 상속 계층에서만 그 표시를 찾는데, 중첩 클래스는 이 바탕을 상속하지 않는다.
     * {@code @BeforeEach} 는 JUnit 이 바깥 클래스까지 훑어서 중첩에서도 돈다.
     *
     * <p>뒤가 아니라 앞에서 지운다. 뒤에서 지우려면 <b>아직 커밋 안 된 이 테스트의 행</b>을
     * 다른 트랜잭션이 지우려 드는 모양이 돼서 잠금에 걸린다.
     *
     * <p>남으면 깨지는 것이 <b>남의 테스트</b>라 원인을 찾을 실마리가 없다.
     * 그래서 각 테스트가 아니라 바탕에 둔다.
     */
    @BeforeEach
    protected void purgeCommittedAuditLogs() {
        TransactionTemplate detached = new TransactionTemplate(auditTxManager);
        detached.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        detached.executeWithoutResult(
                status -> auditCleanup.sql("delete from audit_log").update());
    }

    @Autowired
    private JdbcClient auditCleanup;

    @Autowired
    private PlatformTransactionManager auditTxManager;

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {

        /**
         * `docker-compose.yml` 과 같은 이미지를 쓴다. 버전이 갈리면 테스트가 통과해도 운영에서 깨진다.
         *
         * <p><b>{@code @ServiceConnection} 을 안 붙인다</b>(`2i-2`). 그것이 붙으면 컨테이너의 기본 DB
         * (`test`)로 연결이 고정돼서, 아래 {@link #forkDatabase} 가 fork 마다 가른 DB 를 못 쓴다.
         * 연결 정보를 그쪽에서 직접 만든다. <b>컨테이너 기동은 그대로다</b> — Spring 이 컨텍스트의
         * {@code Startable} 빈을 띄우고, 아래에서 한 번 더 {@code start()} 를 불러 순서를 못 박는다.
         */
        @Bean
        @SuppressWarnings("resource")
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:17-alpine")
                    .withReuse(true);
        }

        /**
         * fork 마다 DB 를 가른다(`2i-2`). 컨테이너는 하나고 그 안의 데이터베이스만 나눈다.
         *
         * <p><b>느린 레인이 병렬로 못 돌던 이유가 이것이었다</b>(`D15` 「fork 를 못 늘리는 이유」).
         * {@link #purgeCommittedAuditLogs} 는 롤백에 안 쓸리는 시도 기록을 매 테스트 앞에서 지우는데,
         * fork 둘이 DB 하나를 나눠 쓰면 <b>한쪽의 비우기가 다른 쪽이 방금 쓴 행을 지운다.</b>
         * 컨테이너를 fork 마다 띄우면 격리는 되지만 재사용이 죽어서 기동 시간을 도로 낸다 —
         * 그래서 컨테이너를 놔두고 DB 만 가른다.
         *
         * <p>이름이 {@code shop_test_<작업트리>_<worker>} 인 이유가 둘이다.
         *
         * <ul>
         *   <li>{@code worker} — Gradle 이 fork 마다 {@code org.gradle.test.worker} 를 다르게 준다.
         *       이것이 fork 사이를 가른다.
         *   <li>{@code 작업트리} — <b>병렬 줄 둘이 재사용 컨테이너 하나를 나눠 쓴다</b>(`2p`).
         *       worker 번호는 줄마다 1부터라 fork 만 가르면 줄 사이에서 같은 사고가 난다.
         * </ul>
         *
         * <p>작업트리는 {@code user.dir}(Gradle 테스트 JVM 에서는 {@code backend/})의 부모 이름으로 읽는다.
         * {@code git rev-parse} 를 부르지 않는 이유는 <b>테스트 JVM 마다 프로세스를 하나 더 띄우게 돼서</b>다 —
         * worktree 는 디렉터리가 따로라 경로만으로 갈린다.
         *
         * <p><b>DB 는 있으면 안 만든다.</b> 재사용 컨테이너라 지난 실행의 DB 가 그대로 있고,
         * 그때는 Flyway 가 이미 적용된 것을 확인만 하고 지나간다. Postgres 에
         * {@code create database if not exists} 가 없어서 {@code pg_database} 를 먼저 본다.
         */
        @Bean
        JdbcConnectionDetails forkDatabase(PostgreSQLContainer postgres) {
            postgres.start();
            String database = forkDatabaseName();
            createDatabaseIfAbsent(postgres, database);

            String url = "jdbc:postgresql://%s:%d/%s"
                    .formatted(postgres.getHost(), postgres.getFirstMappedPort(), database);
            return new JdbcConnectionDetails() {
                @Override
                public String getJdbcUrl() {
                    return url;
                }

                @Override
                public String getUsername() {
                    return postgres.getUsername();
                }

                @Override
                public String getPassword() {
                    return postgres.getPassword();
                }
            };
        }

        private static String forkDatabaseName() {
            Path projectDir = Path.of(System.getProperty("user.dir"));
            Path worktree = projectDir.getParent() == null ? projectDir : projectDir.getParent();
            return "shop_test_%s_%d".formatted(sanitize(worktree.getFileName().toString()), workerId());
        }

        /**
         * Gradle 이 이 fork 에 준 번호. 동시에 도는 fork 끼리 반드시 다르다.
         *
         * <p><b>번호를 접지 않는다.</b> 이 값은 빌드 전체에서 이어지는 번호라 태스크를 여럿 돌면
         * 5, 6, … 으로 커지고 <b>중간이 비기도 한다</b>(빠른 레인이 먼저 쓴 번호). 그것을 fork 수로
         * 접으면 1, 2, 3, 5 → 1, 2, 3, 1 처럼 <b>겹친다</b> — 이 청크가 없애려는 바로 그 사고다.
         * 그래서 Postgres DB 이름은 이 값을 그대로 쓰고, Redis 는 논리 DB 수를 늘려서 받는다.
         */
        private static long workerId() {
            try {
                return Long.parseLong(System.getProperty("org.gradle.test.worker", "1"));
            } catch (NumberFormatException e) {
                return 1;
            }
        }

        /** 식별자로 쓸 수 있는 글자만 남긴다. 작업트리 이름에 `-` 가 들어간다(`ProjectShop-b`). */
        private static String sanitize(String raw) {
            String cleaned = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "_");
            return cleaned.length() > 24 ? cleaned.substring(cleaned.length() - 24) : cleaned;
        }

        private static void createDatabaseIfAbsent(PostgreSQLContainer postgres, String database) {
            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                    Statement statement = connection.createStatement()) {
                try (ResultSet existing = statement.executeQuery(
                        "select 1 from pg_database where datname = '" + database + "'")) {
                    if (existing.next()) {
                        return;
                    }
                }
                statement.execute("create database \"" + database + "\"");
            } catch (SQLException e) {
                throw new IllegalStateException("fork 용 DB 를 못 만들었다: " + database, e);
            }
        }

        /**
         * `docker-compose.yml` 과 같은 이미지를 쓴다.
         *
         * <p><b>Redis 를 안 쓰는 테스트에도 뜬다.</b> 컨테이너가 JVM 당 한 번이고 Spring 컨텍스트가
         * 캐싱되므로 비용은 전체 실행에 한 번이다. 쓰는 테스트만 따로 바탕을 만들면
         * <b>바탕이 둘이 되고, 새 테스트가 어느 쪽을 상속해야 하는지 매번 판단하게 된다.</b>
         *
         * <p><b>{@code @ServiceConnection} 을 안 붙인다</b>(`2i-2`). Postgres 와 같은 이유고,
         * 여기서 가르는 것은 <b>Redis 의 논리 DB 번호</b>다 — 아래 {@link #forkRedis} 를 본다.
         */
        @Bean
        @SuppressWarnings("resource")
        GenericContainer<?> redis() {
            return new GenericContainer<>("redis:7-alpine")
                    .withExposedPorts(6379)
                    // 논리 DB 를 기본 16 에서 늘린다(`2i-2`). fork 마다 하나씩 잡는데
                    // Gradle worker 번호가 빌드 내내 커져서 16 으로는 곧 넘친다.
                    .withCommand("redis-server", "--databases", String.valueOf(REDIS_DATABASES))
                    .withReuse(true);
        }

        /** Redis 논리 DB 수. 0 번은 손으로 들여다볼 때 쓰라고 비워 둔다. */
        private static final int REDIS_DATABASES = 256;

        /**
         * fork 마다 Redis 논리 DB 를 가른다(`2i-2`).
         *
         * <p><b>Postgres 와 똑같은 사고가 여기서도 난다.</b> {@code LoginAttemptTest} 는
         * 앞 테스트가 남긴 키를 {@code redis.delete(redis.keys("login:fail:*"))} 로 지우는데 —
         * 트랜잭션 롤백이 Redis 를 안 되돌리니 맞는 처리다 — <b>fork 넷이 Redis 하나를 쓰면
         * 그 비우기가 남의 fork 가 방금 쓴 키를 지운다.</b> 게다가 {@code HttpTestBase} 의
         * 계정 일련번호가 JVM 마다 1부터라 fork 끼리 <b>같은 이메일</b>을 만든다 — 키까지 겹친다.
         *
         * <p><b>여기만 번호를 접는다.</b> Postgres 는 DB 이름에 {@link #workerId()} 를 그대로 넣지만
         * Redis 는 논리 DB 가 번호라 상한이 있다. 기본값 16 으로는 빌드가 길어지면 넘쳐서
         * {@link #REDIS_DATABASES} 로 늘리고 그 수로 접는다 — <b>겹치려면 worker 번호가 255 차이나야 하고
         * 한 빌드에서 그럴 수가 없다.</b> {@code workerId()} 가 「접지 마라」고 적은 것은
         * <b>fork 수(2~4)로 접는 것</b>을 말한다. 0 번은 손으로 들여다볼 때 쓰라고 비워 둔다.
         *
         * <p><b>작업트리 사이는 이것으로 안 갈린다.</b> 병렬 줄 둘이 재사용 컨테이너를 나눠 쓰면
         * worker 번호가 줄마다 1부터라 같은 논리 DB 를 잡는다. 그래서 `CLAUDE.md` 「병렬 줄」의
         * 「느린 레인도 한 줄만」이 그대로 남는다 — 이 청크가 푼 것은 <b>fork 사이</b>다.
         */
        @Bean
        DataRedisConnectionDetails forkRedis(GenericContainer<?> redis) {
            redis.start();
            String host = redis.getHost();
            int port = redis.getFirstMappedPort();
            int database = (int) (workerId() % (REDIS_DATABASES - 1)) + 1;
            return new DataRedisConnectionDetails() {
                @Override
                public Standalone getStandalone() {
                    return Standalone.of(host, port, database);
                }
            };
        }

        /**
         * 테스트에서만 bcrypt 비용을 4 로 낮춘다(`2i-1`).
         *
         * <p><b>운영은 그대로 10 이다</b> — `SecurityConfig` 의
         * {@code PasswordEncoderFactories.createDelegatingPasswordEncoder()} 를 안 건드린다.
         * `D14` 가 정한 값이고 여기서 바꾸는 것은 픽스처를 만드는 비용뿐이다.
         *
         * <p><b>이미 저장된 해시는 여전히 10 으로 검증된다.</b> bcrypt 는 비용을 해시 문자열
         * 안에 담아서, 시드가 {@code gen_salt('bf', 10)} 로 만든 값은 그 10 으로 대조된다.
         * 빨라지는 것은 테스트가 새로 만드는 계정의 {@code encode} 뿐이다.
         *
         * <p>{@code DelegatingPasswordEncoder} 를 그대로 쓴다 — 저장값에 {@code {bcrypt}}
         * 접두가 붙어 있어서 접두를 안 읽는 인코더로 바꾸면 시드 계정 로그인이 통째로 깨진다.
         */
        @Bean
        @Primary
        PasswordEncoder testPasswordEncoder() {
            return new DelegatingPasswordEncoder("bcrypt",
                    Map.of("bcrypt", new BCryptPasswordEncoder(4)));
        }
    }
}
