package com.projectshop.shop.me;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.projectshop.shop.audit.AuditLog;
import com.projectshop.shop.auth.PermissionEvaluator;
import com.projectshop.shop.auth.PermissionEvaluator.Target;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 자기 동의를 보고 바꾼다.
 *
 * <p>`5-0` 이 동의를 append-only 로 만든 이유가 철회인데 <b>부를 경로가 없었다.</b>
 * 스키마가 표현할 수 있는 것을 앱이 못 하면 그 설계는 쓰인 적이 없는 것이다(`D2` R7).
 *
 * <p>여기서도 사건을 적는다. 철회는 기존 행을 고치는 것이 아니라 {@code granted=false} 한 줄을 더한다.
 */
@Service
public class ConsentService {

    private static final String SOURCE = "mypage";

    private final JdbcClient jdbc;
    private final PermissionEvaluator evaluator;
    private final AuditLog auditLog;

    ConsentService(JdbcClient jdbc, PermissionEvaluator evaluator, AuditLog auditLog) {
        this.jdbc = jdbc;
        this.evaluator = evaluator;
        this.auditLog = auditLog;
    }

    /**
     * <p>{@code required} 는 JSON 으로 나갈 때 {@code is_required} 가 된다(`D22`).
     * 불리언의 {@code is_} 접두사는 Java 에서만 뗀다 — 게터가 {@code isRequired()} 라 두 번 붙는다.
     * DB 컬럼과 JSON 필드명은 같아야 로그·쿼리·응답을 같은 단어로 검색할 수 있다(`D5`).
     *
     * @param granted 동의 상태. 한 번도 건드리지 않았으면 false 고 {@code actedAt} 이 null 이다
     * @param actedAt 마지막으로 동의하거나 철회한 시각. null 이면 건드린 적이 없다
     * @param dependsOn 이 항목을 켜려면 먼저 켜야 하는 항목. 없으면 null
     */
    public record ConsentView(String code, String title,
            @JsonProperty("is_required") boolean required, boolean granted,
            OffsetDateTime actedAt, String dependsOn) {
    }

    /**
     * 지금 효력이 있는 항목을 전부 보여준다. <b>건드린 적 없는 항목도 나온다.</b>
     *
     * <p>동의한 것만 보여주면 무엇을 더 켤 수 있는지 알 방법이 없다.
     */
    public List<ConsentView> list(long userId) {
        requirePermission(userId, "read", "동의 내역을 볼 권한이 없다");

        return jdbc.sql("""
                        select ci.code, ci.title, ci.is_required, p.code as depends_on,
                               coalesce(cc.granted, false) as granted, cc.acted_at
                          from (
                              select distinct on (code) consent_item_id, code, title, is_required, depends_on_id
                                from consent_item
                               where effective_at <= now()
                               order by code, effective_at desc, version desc
                          ) ci
                          left join consent_item p on p.consent_item_id = ci.depends_on_id
                          left join current_consent cc
                                 on cc.user_id = :userId and cc.item_code = ci.code
                         order by ci.is_required desc, ci.code
                        """)
                .param("userId", userId)
                .query((rs, rowNum) -> new ConsentView(
                        rs.getString("code"),
                        rs.getString("title"),
                        rs.getBoolean("is_required"),
                        rs.getBoolean("granted"),
                        rs.getObject("acted_at", OffsetDateTime.class),
                        rs.getString("depends_on")))
                .list();
    }

    /**
     * 필수 항목은 철회할 수 없다.
     *
     * <p>필수 동의를 거둔 계정은 서비스를 받을 근거가 없어진다. 그건 탈퇴(5g)지 철회가 아니다.
     * 여기서 허용하면 <b>동의 없이 살아 있는 계정</b>이 생기고, 가입이 막던 상태가 뒷문으로 들어온다.
     */
    @Transactional
    public void revoke(long userId, String code, String actorIp) {
        requirePermission(userId, "update", "동의를 바꿀 권한이 없다");

        Item item = findItem(code);
        if (item.required()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "필수 동의 항목이라 철회할 수 없다. 탈퇴로 처리한다");
        }

        record(userId, item, false, actorIp);

        // 이 항목에 걸린 것들도 같이 거둔다. 채널을 껐는데 야간 수신만 남으면
        // 보낼 수 없는 동의가 남고, 나중에 채널만 켜지는 순간 야간까지 열린다(R14).
        for (Item dependent : findDependents(item.consentItemId())) {
            record(userId, dependent, false, actorIp);
        }
    }

    /**
     * 다시 켠다. 선택 항목을 껐다 켜는 것은 흔한 요구다.
     *
     * <p>종속을 앱이 막는다 — 스키마의 {@code depends_on_id} 는 관계를 적어 둘 뿐 강제하지 않는다.
     */
    @Transactional
    public void grant(long userId, String code, String actorIp) {
        requirePermission(userId, "update", "동의를 바꿀 권한이 없다");

        Item item = findItem(code);
        if (item.dependsOnCode() != null && !isGranted(userId, item.dependsOnCode())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    item.code() + " 는 " + item.dependsOnCode() + " 에 동의해야 받을 수 있다");
        }

        record(userId, item, true, actorIp);
    }

    /**
     * 상태가 안 바뀌면 행을 안 쓴다.
     *
     * <p>append-only 라고 같은 값을 계속 쌓으면 이력이 의미를 잃는다.
     * 남길 것은 <b>바뀐 순간</b>이지 요청이 온 횟수가 아니다.
     */
    private void record(long userId, Item item, boolean granted, String actorIp) {
        if (isGranted(userId, item.code()) == granted) {
            return;
        }

        jdbc.sql("""
                        insert into user_consent (user_id, consent_item_id, granted, source, acted_ip)
                        values (:userId, :itemId, :granted, :source, cast(:actorIp as inet))
                        """)
                .param("userId", userId)
                .param("itemId", item.consentItemId())
                .param("granted", granted)
                .param("source", SOURCE)
                .param("actorIp", actorIp)
                .update();

        auditLog.record(granted ? "consent.granted" : "consent.revoked", userId,
                AuditLog.Target.of("user", userId), Map.of("item_code", item.code()));
    }

    private boolean isGranted(long userId, String code) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        select coalesce(
                            (select granted from current_consent
                              where user_id = :userId and item_code = :code), false)
                        """)
                .param("userId", userId)
                .param("code", code)
                .query(Boolean.class)
                .single());
    }

    private Item findItem(String code) {
        return jdbc.sql("""
                        select ci.consent_item_id, ci.code, ci.is_required, p.code as depends_on
                          from consent_item ci
                          left join consent_item p on p.consent_item_id = ci.depends_on_id
                         where ci.code = :code and ci.effective_at <= now()
                         order by ci.effective_at desc, ci.version desc
                         limit 1
                        """)
                .param("code", code)
                .query((rs, rowNum) -> new Item(
                        rs.getLong("consent_item_id"),
                        rs.getString("code"),
                        rs.getBoolean("is_required"),
                        rs.getString("depends_on")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "그런 동의 항목이 없다: " + code));
    }

    private List<Item> findDependents(long itemId) {
        return jdbc.sql("""
                        select consent_item_id, code, is_required, null as depends_on
                          from consent_item
                         where depends_on_id = :itemId and effective_at <= now()
                        """)
                .param("itemId", itemId)
                .query((rs, rowNum) -> new Item(
                        rs.getLong("consent_item_id"),
                        rs.getString("code"),
                        rs.getBoolean("is_required"),
                        null))
                .list();
    }

    private void requirePermission(long userId, String action, String message) {
        if (!evaluator.decide(userId, "user", action, Target.ownedBy(userId)).allowed()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, message);
        }
    }

    private record Item(long consentItemId, String code, boolean required, String dependsOnCode) {
    }
}
