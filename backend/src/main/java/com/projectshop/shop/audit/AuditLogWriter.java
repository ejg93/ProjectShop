package com.projectshop.shop.audit;

import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 감사 기록을 실제로 넣는다. 전파만 다른 입구 둘을 가진다.
 *
 * <p><b>{@link AuditLog} 와 나눈 이유는 자기 호출로는 전파가 안 걸려서다.</b>
 * 같은 클래스 안에서 부르면 프록시를 안 지나므로 {@code @Transactional} 이 무시되고,
 * {@code REQUIRES_NEW} 가 조용히 {@code REQUIRED} 처럼 동작한다 — 고쳤다고 믿는 채로
 * 원래 결함이 그대로 남는 모양이라 눈으로는 못 잡는다.
 */
@Component
class AuditLogWriter {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    AuditLogWriter(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    /** 업무 트랜잭션에 얹혀 간다. 업무가 롤백되면 이 기록도 사라진다 */
    @Transactional(propagation = Propagation.REQUIRED)
    void joined(String eventType, Long actorUserId, AuditLog.Target target,
            Map<String, Object> detail) {

        insert(eventType, actorUserId, target, detail);
    }

    /**
     * 업무 트랜잭션을 잠시 밀어 두고 따로 커밋한다.
     *
     * <p>커넥션을 하나 더 잡는다. 업무 트랜잭션이 자기 커넥션을 쥔 채로 두 번째를 빌리는 것이라
     * 풀이 마르면 여기서 막힌다. 거부 판정에서만 쓰이고 {@code audit_log} 는
     * 외래키가 없는 삽입 한 줄이라 잡는 시간이 짧다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void detached(String eventType, Long actorUserId, AuditLog.Target target,
            Map<String, Object> detail) {

        insert(eventType, actorUserId, target, detail);
    }

    private void insert(String eventType, Long actorUserId, AuditLog.Target target,
            Map<String, Object> detail) {

        jdbcClient.sql("""
                        insert into audit_log (event_type, actor_user_id, target_type, target_id, detail)
                        values (:eventType, :actorUserId, :targetType, :targetId, :detail::jsonb)
                        """)
                .param("eventType", eventType)
                .param("actorUserId", actorUserId)
                .param("targetType", target.type())
                .param("targetId", target.id())
                .param("detail", toJson(detail))
                .update();
    }

    /**
     * 감사 기록이 실패해서 업무가 멈추면 안 된다는 주장이 있지만 여기서는 반대로 간다.
     * 남기지 못한 채로 진행하면 그 사건은 영영 없던 일이 된다. 차라리 터뜨려서 알게 한다.
     */
    private String toJson(Map<String, Object> detail) {
        try {
            return objectMapper.writeValueAsString(detail == null ? Map.of() : detail);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("감사 기록의 detail 을 JSON 으로 못 바꾼다: " + detail, e);
        }
    }
}
