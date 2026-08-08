package com.projectshop.shop.audit;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.projectshop.shop.auth.PermissionEvaluator;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.support.ListQuery.Paging;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.auth.PermissionEvaluator.Decision;
import com.projectshop.shop.auth.PermissionEvaluator.Target;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 쌓인 감사 기록을 꺼낸다.
 *
 * <p>쌓아만 두고 볼 방법이 없으면 감사가 성립하지 않는다. 이 클래스가 그 반대편이다.
 *
 * <p><b>판정을 먼저 하고 조회한다.</b> 행을 읽어 놓고 거르는 방식은 권한이 없는 사람이
 * 개수만으로 정보를 얻는 길을 연다 — 조회 결과가 0건인 것과 못 보는 것이 갈려야 한다.
 */
@Service
public class AuditLogQuery {

    private static final TypeReference<Map<String, Object>> DETAIL_TYPE = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final PermissionEvaluator evaluator;
    private final ObjectMapper objectMapper;

    AuditLogQuery(JdbcClient jdbc, PermissionEvaluator evaluator, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.evaluator = evaluator;
        this.objectMapper = objectMapper;
    }

    /**
     * @param actorUserId 이 사람이 한 일만. null 이면 전체
     * @param targetType  이 종류의 자원에 일어난 일만. null 이면 전체
     * @param from        이 시각 이후(포함). null 이면 처음부터
     * @param to          이 시각 이전(제외). null 이면 끝까지
     */
    public record Criteria(Long actorUserId, String targetType, Long targetId,
            OffsetDateTime from, OffsetDateTime to, int page, int size) {
    }

    /**
     * DB 행 그대로. <b>이름이 컬럼명을 따른다</b>(`D22`) — {@code id} 가 아니라 {@code auditLogId} 다.
     *
     * <p>`3e` 가 DB 에서 맨 {@code id} 를 없앴는데 이 응답에만 남아 있었다.
     * 컬럼명과 JSON 필드명이 갈리면 로그·쿼리·응답을 같은 단어로 못 찾는다(`D5`).
     */
    public record Row(long auditLogId, String eventType, Long actorUserId, String targetType,
            Long targetId, Map<String, Object> detail, OffsetDateTime createdAt) {
    }

    public record Page(List<Row> items, int page, int size, long total) {
    }

    /**
     * 조건이 값에 따라 켜지고 꺼진다. <b>조건마다 SQL 을 잇지 않고 한 문장에 둔다</b> —
     * 어떤 조합이 와도 실행되는 쿼리가 하나로 유지된다.
     *
     * <p>파라미터마다 {@code cast} 가 붙은 이유가 있다. null 을 {@code is null} 비교에만 쓰면
     * Postgres 가 그 자리의 타입을 못 정하고 {@code could not determine data type} 으로 죽는다.
     * 값이 들어오는 경우에는 멀쩡히 돌아서 <b>필터를 안 건 요청에서만</b> 터진다.
     *
     * <p>상수라 인젝션은 아니지만 <b>문자열로 조립하지 않는다</b>(`D23`). 조건 조합이 늘어나는
     * 모양이라, 지금 상수인 것이 나중에 값을 이어 붙이는 자리가 된다.
     */
    private static final String WHERE = """
             where (cast(:actorUserId as bigint) is null or actor_user_id = cast(:actorUserId as bigint))
               and (cast(:targetType  as text)   is null or target_type   = cast(:targetType as text))
               and (cast(:targetId    as bigint) is null or target_id     = cast(:targetId as bigint))
               and (cast(:from as timestamptz) is null or created_at >= cast(:from as timestamptz))
               and (cast(:to   as timestamptz) is null or created_at <  cast(:to   as timestamptz))
            """;

    private static final String SELECT_ITEMS = """
            select audit_log_id, event_type, actor_user_id, target_type, target_id,
                   detail::text as detail, created_at
              from audit_log
            """ + WHERE + """
             order by created_at desc, audit_log_id desc
             limit :size offset :offset
            """;

    private static final String SELECT_TOTAL = "select count(*) from audit_log" + WHERE;

    public Page find(long viewerId, Criteria criteria) {
        authorize(viewerId, criteria);

        Paging paging = Paging.of(criteria.page(), criteria.size());

        List<Row> items = bind(jdbc.sql(SELECT_ITEMS), criteria)
                .param("size", paging.size())
                .param("offset", paging.offset())
                .query((rs, rowNum) -> new Row(
                        rs.getLong("audit_log_id"),
                        rs.getString("event_type"),
                        rs.getObject("actor_user_id", Long.class),
                        rs.getString("target_type"),
                        rs.getObject("target_id", Long.class),
                        objectMapper.readValue(rs.getString("detail"), DETAIL_TYPE),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();

        Long total = bind(jdbc.sql(SELECT_TOTAL), criteria)
                .query(Long.class)
                .single();

        return new Page(items, paging.page(), paging.size(), total);
    }

    /**
     * 볼 수 있는지 <b>먼저</b> 정한다.
     *
     * <p>대상은 조회하려는 사람이다. 아직 {@code all} 만 있어서 결과가 같지만,
     * 나중에 "자기 기록만 보기"(own)가 생기면 이 자리가 그대로 답한다.
     */
    private void authorize(long viewerId, Criteria criteria) {
        Target target = criteria.actorUserId() == null
                // 행위자를 안 좁혔으면 남의 기록이 섞여 온다. own 으로는 못 덮는 요청이다.
                ? Target.of(-1L, -1L)
                : Target.ownedBy(criteria.actorUserId());

        Decision decision = evaluator.decide(viewerId, "audit", "read", target);
        if (!decision.allowed()) {
            // 자원의 존재를 감추지 않는다. 감사 로그가 있다는 사실 자체는 비밀이 아니고,
            // 404 로 감추면 권한을 받은 뒤에도 같은 응답이라 원인을 못 가린다(D5).
            throw new ShopException(ErrorCode.AUDIT_FORBIDDEN);
        }
    }

    private JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, Criteria criteria) {
        return spec
                .param("actorUserId", criteria.actorUserId())
                .param("targetType", criteria.targetType())
                .param("targetId", criteria.targetId())
                .param("from", criteria.from())
                .param("to", criteria.to());
    }
}
