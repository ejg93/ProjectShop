package com.projectshop.shop.audit;

import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 누가 무슨 권한으로 무엇을 했는지 남긴다.
 *
 * <p>관측 로그(D16)와 다르다. 그쪽은 "이 요청이 어디서 느려졌나" 를 파일에 남기고 지워도 되지만,
 * 이쪽은 DB 에 남기고 보존 기간이 걸려 있다.
 *
 * <p><b>업무 트랜잭션에 얹혀 간다.</b> 역할 부여가 롤백되면 그 기록도 같이 사라진다.
 * 안 일어난 일이 기록에 남지 않게 하려는 것이다. 대신 "시도했지만 실패했다" 는 안 남는다.
 *
 * <p><b>「권한 거부는 어차피 쓰기를 안 하므로 안 걸린다」는 전제가 깨졌다.</b>
 * {@code @Transactional} 서비스 안에서 판정하고 거부로 예외를 던지면 <b>그 거부 기록까지 롤백된다.</b>
 * {@code OrderActionService.run}·{@code ProductReviewService.authorize} 가 그 모양이고,
 * 청크 {@code 35c} 가 실서버에서 확인했다 — 구매자가 남의 발송 경로를 두드렸는데
 * {@code audit_log} 에 한 줄도 안 남았다.
 *
 * <p>판정 밖에서 기록하는 경로({@code /api/audit-logs} 의 403 처럼 트랜잭션이 없는 조회)는
 * 그대로 남는다. <b>즉 경로마다 남는 것과 안 남는 것이 갈려 있다.</b> 청크 {@code 4b-2} 가 정한다.
 */
@Component
public class AuditLog {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    AuditLog(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 사건 하나를 남긴다.
     *
     * @param eventType 점 표기. {@code permission.denied}, {@code role.granted} 같은 것
     * @param actorUserId 한 사람. 시스템이 한 일이면 null
     * @param target 대상 자원. 자원이 없는 사건이면 {@link Target#none()}
     * @param detail 사건마다 다른 값. 조회 조건이 될 값은 여기 두지 않는다
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void record(String eventType, Long actorUserId, Target target, Map<String, Object> detail) {
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

    /** 무엇에 대한 사건인가. 자원이 없는 사건도 있어서 둘 다 null 인 경우를 허용한다 */
    public record Target(String type, Long id) {

        public static Target none() {
            return new Target(null, null);
        }

        public static Target of(String type, long id) {
            return new Target(type, id);
        }

        /** 대상이 특정 행이 아니라 자원 종류 전체인 경우. 목록 조회 거부 같은 것 */
        public static Target ofType(String type) {
            return new Target(type, null);
        }
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
