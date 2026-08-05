package com.projectshop.shop;

import org.springframework.boot.test.context.SpringBootTest;
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
