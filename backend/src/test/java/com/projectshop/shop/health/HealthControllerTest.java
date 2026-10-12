package com.projectshop.shop.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.projectshop.shop.PostgresTestBase;

/**
 * 건강 확인이 배포 검사에 필요한 셋을 내나(`Q205`). {@code scripts/deploy-check.sh} 가 {@code commit} 으로 「그 커밋이
 * 떴나」를, {@code applied_migrations} 로 「마이그레이션이 다 올라갔나」를 잰다.
 *
 * <h2>적용 수를 파일 수와 견준다(`Q223`)</h2>
 *
 * <p>전에는 「0 보다 크다」만 봐서 <b>마이그레이션이 일부만 올라가도 초록</b>이었다. 운영에서는 {@code deploy-check.sh} 가
 * 파일을 세어 견주는데 시험에는 그 대조가 없었다. ProjectTicket {@code B0-4} 가 같은 것을 고쳤다.
 *
 * <p><b>세는 폴더는 이 시험이 실제로 붓는 위치다</b> — {@code spring.flyway.locations} 를 {@link Environment} 에서 읽는다.
 * 폴더를 글자로 박으면 프로필이 시드 폴더를 더하는 날(`Q143` — {@code demo}·{@code local}) 두 값이 갈린다.
 * 그래서 {@link #MIGRATION_ROOT} 는 {@code classpath:} 를 푸는 뿌리 하나뿐이다.
 */
@DisplayName("건강 확인")
class HealthControllerTest extends PostgresTestBase {

    /** {@code classpath:} 위치를 푸는 뿌리. 테스트의 작업 디렉터리가 {@code backend/} 라 상대 경로다 */
    private static final Path MIGRATION_ROOT = Path.of("src", "main", "resources");

    /** Flyway 의 버전 마이그레이션 이름. {@code U*}(되돌리기)·{@code R*}(반복)은 적용 수에 안 든다 */
    private static final Pattern VERSIONED = Pattern.compile("V\\d+__.+\\.sql");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    @DisplayName("커밋을 모르는 판은 unknown 이라 말한다 — 배포 검사가 그것을 빨강으로 읽는다")
    void reportsUnknownCommit() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commit").value("unknown"));
    }

    @Test
    @DisplayName("적용한 마이그레이션 수가 이 시험이 붓는 폴더의 V 파일 수와 같다")
    void appliedMigrationsMatchFiles() throws Exception {
        long files = versionedFiles();

        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied_migrations").value(Math.toIntExact(files)));
    }

    /**
     * Redis 에 못 닿으면 건강하다고 안 답한다(`Q262`). 세션·로그인이 Redis 에 있어서 DB 만 보면
     * 로그인한 손님의 요청이 다 실패하는데 200 이었다(`68` 실험 B).
     *
     * <p>헬스는 던지기만 한다 — 그것을 503 으로 옮기는 반쪽은 {@code ProblemResponseTest} 「바깥 의존이 죽으면」이
     * 같은 예외로 잰다. <b>목을 손으로 넣는다</b> — 빈으로 바꾸면 공유 컨텍스트가 하나 더 뜬다.
     */
    @Test
    @DisplayName("Redis 에 못 닿으면 건강하다고 안 답한다")
    void redisDownIsNotHealthy() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        willThrow(new QueryTimeoutException("Redis command timed out"))
                .given(redis).execute(ArgumentMatchers.<RedisCallback<String>>any());

        HealthController controller = new HealthController(jdbcClient, redis, "unknown");

        assertThatThrownBy(controller::health).isInstanceOf(QueryTimeoutException.class);
    }

    /** {@code spring.flyway.locations} 의 {@code classpath:} 폴더마다 {@code V*} 파일을 센다. 못 읽으면 실패한다 */
    private long versionedFiles() throws IOException {
        String locations = environment.getProperty("spring.flyway.locations", "classpath:db/migration");
        long count = 0;
        for (String raw : locations.split(",")) {
            String location = raw.trim();
            assertThat(location)
                    .as("classpath: 위치만 센다 — 다른 꼴(filesystem: 등)이 들어오면 이 시험을 넓힌다")
                    .startsWith("classpath:");
            Path dir = MIGRATION_ROOT.resolve(location.substring("classpath:".length()));
            assertThat(dir).as("마이그레이션 폴더를 못 읽었다: %s", dir.toAbsolutePath()).isDirectory();
            try (Stream<Path> files = Files.list(dir)) {
                count += files.filter(file -> VERSIONED.matcher(file.getFileName().toString()).matches()).count();
            }
        }
        assertThat(count).as("V 파일을 0개 셌다 — 경로나 이름 규칙이 바뀌었다: %s", locations).isPositive();
        return count;
    }
}
