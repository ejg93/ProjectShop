package com.projectshop.shop.consent;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectshop.shop.audit.AuditLog;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
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

    /** 마이페이지에서 손으로 바꾼 것 */
    private static final String SOURCE_MYPAGE = "mypage";

    /** 탈퇴가 일으킨 것. 나중에 이력을 볼 때 사람이 끈 것과 갈린다 */
    private static final String SOURCE_WITHDRAW = "withdraw";

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
     * 동의받을 때 알린 내용. <b>본문은 여기에만 실린다</b> — 목록에 넣으면 약관 전문이
     * 항목 수만큼 딸려 나와서 동의 화면이 무거워진다.
     *
     * @param version 이 고지가 몇 판인가. 내용이 바뀌면 새 판이다(개인정보법 제15조제2항 후단)
     * @param body    약관 본문. 마크다운이고, 개인정보 항목에는 없다
     */
    public record Notice(String code, String title, int version,
            @JsonProperty("is_required") boolean required,
            String purpose, String collectedItems, String retentionPeriod,
            String refusalDisadvantage, String body, String dependsOn,
            OffsetDateTime effectiveAt) {
    }

    /**
     * 내가 동의한 그 고지와 그때의 상태.
     *
     * @param notice <b>지금 효력 있는 판이 아니라 내가 동의한 판</b>이다. 개정됐으면 둘이 다르다
     */
    public record MyNotice(Notice notice, boolean granted, OffsetDateTime actedAt) {
    }

    /**
     * 지금 효력이 있는 판의 고지 내용. <b>로그인 없이 부른다.</b>
     *
     * <p>가입 화면이 이걸 쓴다. 동의하려면 먼저 읽어야 하는데 그 시점은 로그인 전이다 —
     * 약관규제법 제3조제2항·제3항이 "계약을 체결할 때" 밝히고 설명하라고 한 자리가 여기다.
     */
    public Notice readCurrent(String code) {
        return jdbc.sql("""
                        select ci.code, ci.title, ci.version, ci.is_required,
                               ci.purpose, ci.collected_items, ci.retention_period,
                               ci.refusal_disadvantage, ci.body, ci.effective_at,
                               p.code as depends_on
                          from consent_item ci
                          left join consent_item p on p.consent_item_id = ci.depends_on_id
                         where ci.code = :code and ci.effective_at <= now()
                         order by ci.effective_at desc, ci.version desc
                         limit 1
                        """)
                .param("code", code)
                .query(ConsentService::mapNotice)
                .optional()
                .orElseThrow(() -> new ShopException(ErrorCode.CONSENT_ITEM_NOT_FOUND, "그런 동의 항목이 없다: " + code));
    }

    /**
     * 내가 동의한 판의 사본.
     *
     * <p>약관규제법 제3조제2항이 고객 요구 시 사본을 내주라고 한다. 그 사본은
     * <b>내가 계약한 그 약관</b>이지 지금 걸려 있는 최신판이 아니다 —
     * 최신판을 내주면 그 사이 우리가 고친 것을 들이미는 꼴이 된다.
     *
     * <p>건드린 적 없는 항목은 사본이 없어서 404 다. 무엇을 더 켤 수 있는지는 목록이 답한다.
     */
    public MyNotice readMine(long userId, String code) {
        requirePermission(userId, "read", "동의 내역을 볼 권한이 없다");

        return jdbc.sql("""
                        select ci.code, ci.title, ci.version, ci.is_required,
                               ci.purpose, ci.collected_items, ci.retention_period,
                               ci.refusal_disadvantage, ci.body, ci.effective_at,
                               p.code as depends_on,
                               uc.granted, uc.acted_at
                          from user_consent uc
                          join consent_item ci on ci.consent_item_id = uc.consent_item_id
                          left join consent_item p on p.consent_item_id = ci.depends_on_id
                         where uc.user_id = :userId and ci.code = :code
                         -- 한 트랜잭션에서 여러 행이 같은 acted_at 을 갖는다. id 가 순서를 정한다(5-0)
                         order by uc.acted_at desc, uc.user_consent_id desc
                         limit 1
                        """)
                .param("userId", userId)
                .param("code", code)
                .query((rs, rowNum) -> new MyNotice(
                        mapNotice(rs, rowNum),
                        rs.getBoolean("granted"),
                        rs.getObject("acted_at", OffsetDateTime.class)))
                .optional()
                .orElseThrow(() -> new ShopException(ErrorCode.CONSENT_NOT_FOUND, "동의한 적이 없는 항목이다: " + code));
    }

    /**
     * 지금 효력 있는 항목 전부. <b>로그인 전에 부른다.</b>
     *
     * <p>가입 화면이 <b>무엇에 동의받아야 하는지</b>를 여기서 안다. 화면에 코드를 박으면
     * `V11` 이 항목을 데이터로 둔 뜻이 사라진다 — 항목을 하나 늘릴 때 화면을 같이 고치게 된다.
     *
     * <p><b>본문을 안 내린다.</b> 약관 전문이 항목 수만큼 딸려 나온다.
     * 펼칠 때 {@link #readCurrent} 로 그 항목만 따로 받는다.
     *
     * <p><b>사람 데이터가 없다.</b> 나가는 것은 법이 공개하라고 한 고지 문안뿐이고,
     * "누가 무엇에 동의했나" 는 {@link #list(long)} 가 답한다 — 그쪽은 로그인이 필요하다.
     */
    public List<Notice> listCurrent() {
        return jdbc.sql("""
                        select ci.code, ci.title, ci.version, ci.is_required,
                               ci.purpose, ci.collected_items, ci.retention_period,
                               ci.refusal_disadvantage, null as body, ci.effective_at,
                               p.code as depends_on
                          from (
                              select distinct on (code) *
                                from consent_item
                               where effective_at <= now()
                               order by code, effective_at desc, version desc
                          ) ci
                          left join consent_item p on p.consent_item_id = ci.depends_on_id
                         order by ci.is_required desc, ci.sort_no, ci.code
                        """)
                .query(ConsentService::mapNotice)
                .list();
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
                              select distinct on (code)
                                     consent_item_id, code, title, is_required, sort_no, depends_on_id
                                from consent_item
                               where effective_at <= now()
                               order by code, effective_at desc, version desc
                          ) ci
                          left join consent_item p on p.consent_item_id = ci.depends_on_id
                          left join current_consent cc
                                 on cc.user_id = :userId and cc.item_code = ci.code
                         -- 가입 화면과 같은 순서다. 갈리면 같은 항목이 화면마다 다른 자리에 뜬다.
                         order by ci.is_required desc, ci.sort_no, ci.code
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
            throw new ShopException(ErrorCode.REQUIRED_CONSENT_REVOKE,
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
            throw new ShopException(ErrorCode.CONSENT_DEPENDENCY,
                    item.code() + " 는 " + item.dependsOnCode() + " 에 동의해야 받을 수 있다");
        }

        record(userId, item, true, actorIp);
    }

    /**
     * 켜져 있는 것을 전부 거둔다. <b>탈퇴(5g)가 부른다.</b>
     *
     * <p><b>{@link #revoke} 와 두 가지가 다르다.</b> 필수 항목도 거두고, 종속을 안 따진다 —
     * 전부가 대상이라 따질 것이 없다. 그래서 {@code revoke} 를 반복 호출하지 않는다.
     * 필수 항목에서 그대로 터진다.
     *
     * <p>권한을 안 본다. 되돌릴 수 없는 조작이라 <b>부르는 쪽이 비밀번호를 다시 받아</b>
     * 이미 확인했다. 여기서 또 물으면 같은 판단이 두 벌이 된다.
     *
     * <p><b>이 메서드가 {@code account} 에 있었다.</b> 거기 두면 「철회란 무엇인가」가 두 곳에
     * 적히고, 한쪽 규칙을 고치는 사람이 다른 쪽을 못 본다(`D23` 「패키지」, `5m`).
     *
     * @param userId   거둘 사람
     * @param actorIp  요청이 온 곳. 동의 이력의 입증 자료다(`D2` R11)
     */
    @Transactional
    public void revokeAll(long userId, String actorIp) {
        // 켜져 있는 것만 고른다. 상태가 안 바뀌는 행은 안 쓴다는 규칙이 여기서도 같다 —
        // 한 줄 SQL 이라 record() 와 모양이 다를 뿐 뜻은 하나다.
        jdbc.sql("""
                        insert into user_consent (user_id, consent_item_id, granted, source, acted_ip)
                        select :userId, cc.consent_item_id, false, :source, cast(:actorIp as inet)
                          from current_consent cc
                         where cc.user_id = :userId and cc.granted
                        """)
                .param("userId", userId)
                .param("source", SOURCE_WITHDRAW)
                .param("actorIp", actorIp)
                .update();
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
                .param("source", SOURCE_MYPAGE)
                .param("actorIp", actorIp)
                .update();

        auditLog.record(AuditLog.Kind.OUTCOME, granted ? "consent.granted" : "consent.revoked", userId,
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
                .orElseThrow(() -> new ShopException(ErrorCode.CONSENT_ITEM_NOT_FOUND, "그런 동의 항목이 없다: " + code));
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
            throw new ShopException(ErrorCode.CONSENT_FORBIDDEN, message);
        }
    }

    private record Item(long consentItemId, String code, boolean required, String dependsOnCode) {
    }

    /** 두 조회가 같은 열을 읽는다. 매핑이 두 벌이면 열을 하나 더할 때 한쪽만 고친다. */
    private static Notice mapNotice(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Notice(
                rs.getString("code"),
                rs.getString("title"),
                rs.getInt("version"),
                rs.getBoolean("is_required"),
                rs.getString("purpose"),
                rs.getString("collected_items"),
                rs.getString("retention_period"),
                rs.getString("refusal_disadvantage"),
                rs.getString("body"),
                rs.getString("depends_on"),
                rs.getObject("effective_at", OffsetDateTime.class));
    }
}
