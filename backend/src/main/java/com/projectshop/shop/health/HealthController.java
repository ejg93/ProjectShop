package com.projectshop.shop.health;

import java.time.OffsetDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 앱이 떴는지와 DB에 실제로 질의가 나가는지를 한 번에 확인하는 엔드포인트.
 * 커넥션 풀이 커넥션을 쥐고만 있고 질의는 안 되는 상태를 걸러내려고 쿼리를 직접 던진다.
 */
@RestController
class HealthController {

    private final JdbcClient jdbcClient;
    private final String commit;

    HealthController(JdbcClient jdbcClient, @Value("${shop.deploy.commit}") String commit) {
        this.jdbcClient = jdbcClient;
        this.commit = commit;
    }

    @GetMapping("/api/health")
    HealthResponse health() {
        String database = jdbcClient.sql("select current_database()")
                .query(String.class)
                .single();
        Long applied = jdbcClient.sql("select count(*) from flyway_schema_history where success")
                .query(Long.class)
                .single();
        return new HealthResponse("shop-backend", database, applied, commit, OffsetDateTime.now());
    }

    /**
     * @param commit 이 판을 만든 커밋(`Q205`). 로컬·시험은 {@code unknown} 이다. {@code scripts/deploy-check.sh} 가
     *               머지한 커밋과 견주고, 적용한 마이그레이션 수를 파일 수와 견준다 — 헬스체크에 실패한 배포는 앞 판이
     *               그대로 돌아서 조용하다(#70~#74)
     */
    record HealthResponse(String app, String database, Long appliedMigrations, String commit,
            OffsetDateTime checkedAt) {
    }
}
