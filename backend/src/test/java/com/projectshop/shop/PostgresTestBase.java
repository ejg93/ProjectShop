package com.projectshop.shop;

import org.junit.jupiter.api.AfterEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;

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
 */
@SpringBootTest
@Transactional
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
     */
    @AfterEach
    protected void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {

        /**
         * `docker-compose.yml` 과 같은 이미지를 쓴다. 버전이 갈리면 테스트가 통과해도 운영에서 깨진다.
         */
        @Bean
        @ServiceConnection
        @SuppressWarnings("resource")
        PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>("postgres:17-alpine")
                    .withReuse(true);
        }
    }
}
