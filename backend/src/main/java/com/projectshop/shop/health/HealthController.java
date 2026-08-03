package com.projectshop.shop.health;

import java.time.OffsetDateTime;

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

    HealthController(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @GetMapping("/api/health")
    HealthResponse health() {
        String database = jdbcClient.sql("select current_database()")
                .query(String.class)
                .single();
        Long applied = jdbcClient.sql("select count(*) from flyway_schema_history where success")
                .query(Long.class)
                .single();
        return new HealthResponse("shop-backend", database, applied, OffsetDateTime.now());
    }

    record HealthResponse(String app, String database, Long appliedMigrations, OffsetDateTime checkedAt) {
    }
}
