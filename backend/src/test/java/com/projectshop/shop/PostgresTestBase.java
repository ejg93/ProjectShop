package com.projectshop.shop;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
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
         */
        @Bean
        @ServiceConnection
        @SuppressWarnings("resource")
        PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>("postgres:17-alpine")
                    .withReuse(true);
        }

        /**
         * `docker-compose.yml` 과 같은 이미지를 쓴다.
         *
         * <p><b>Redis 를 안 쓰는 테스트에도 뜬다.</b> 컨테이너가 JVM 당 한 번이고 Spring 컨텍스트가
         * 캐싱되므로 비용은 전체 실행에 한 번이다. 쓰는 테스트만 따로 바탕을 만들면
         * <b>바탕이 둘이 되고, 새 테스트가 어느 쪽을 상속해야 하는지 매번 판단하게 된다.</b>
         *
         * <p>{@code @ServiceConnection} 에 이름을 준 것은 {@code GenericContainer} 라
         * 무엇에 붙일 연결인지 Spring 이 이미지만 보고 못 정하기 때문이다.
         */
        @Bean
        @ServiceConnection(name = "redis")
        @SuppressWarnings("resource")
        GenericContainer<?> redis() {
            return new GenericContainer<>("redis:7-alpine")
                    .withExposedPorts(6379)
                    .withReuse(true);
        }
    }
}
