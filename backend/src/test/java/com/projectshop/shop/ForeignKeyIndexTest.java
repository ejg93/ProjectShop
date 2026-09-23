package com.projectshop.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * <b>외래키 칸마다 첫 칸이 그 칸인 인덱스가 있나</b>(`Q206`).
 *
 * <p>PostgreSQL 은 외래키에 인덱스를 안 만든다. 부모 행을 지우면 자식 표에서 그 키를 찾아야 하는데(막거나 같이 지우려고)
 * 인덱스가 없으면 자식 표를 통째로 훑는다. <b>DB 에 물어서</b> 외래키를 다 걷는다 — 새 외래키가 인덱스 없이 들어오면
 * 여기서 빨갛다. 인덱스를 안 달려면 {@link #EXEMPT} 에 근거를 적는다.
 *
 * <p><b>양방향으로 잰다.</b> 인덱스가 생겼거나 외래키가 사라졌는데 면제 목록에 남은 줄도 실패다 — 그 줄은 아무것도
 * 안 막는 죽은 줄이다.
 */
@DisplayName("외래키 인덱스")
class ForeignKeyIndexTest extends PostgresTestBase {

    /** 사람을 가리키는 칸의 공통 근거 */
    private static final String NEVER_DELETED_USER =
            "app_user 는 안 지운다(탈퇴는 deleted_at, 파기는 칸 비우기 — D13). 부모를 안 지우니 외래키 검사가 자식을 안 훑고, "
                    + "이 칸으로 찾는 조회도 없다";

    /**
     * 인덱스 없이 두는 외래키와 그 근거. 키는 {@code 표.칸} 이다(겹 외래키면 칸을 쉼표로 잇는다).
     *
     * <p><b>근거 없이 이름만 넣지 않는다.</b> 근거 칸이 없으면 이 목록이 인덱스를 빠뜨렸을 때 도망칠 자리가 된다.
     */
    private static final Map<String, String> EXEMPT = new TreeMap<>(Map.ofEntries(
            Map.entry("compensation.decided_by_user_id", NEVER_DELETED_USER),
            Map.entry("copyright_report.decided_by_user_id", NEVER_DELETED_USER),
            Map.entry("notification.user_id", NEVER_DELETED_USER),
            Map.entry("order_status_history.actor_user_id", NEVER_DELETED_USER),
            Map.entry("return_request.decided_by_user_id", NEVER_DELETED_USER),
            Map.entry("return_request.inspected_by_user_id", NEVER_DELETED_USER),
            Map.entry("return_request.requested_by_user_id", NEVER_DELETED_USER),
            Map.entry("review_reply.user_id", NEVER_DELETED_USER),
            Map.entry("review_report.reporter_user_id",
                    NEVER_DELETED_USER + ". 한 사람 한 신고는 review_id 로 시작하는 유일 인덱스가 든다"),
            Map.entry("review_report.resolved_by_user_id", NEVER_DELETED_USER),
            Map.entry("seller_invitation.accepted_user_id", NEVER_DELETED_USER),
            Map.entry("seller_invitation.invited_by_user_id", NEVER_DELETED_USER),
            Map.entry("settlement.payout_decided_by_user_id", NEVER_DELETED_USER),
            Map.entry("settlement.payout_requested_by_user_id", NEVER_DELETED_USER),
            Map.entry("consent_item.depends_on_id",
                    "시행된 동의 항목은 고치지도 지우지도 못한다(consent_item_immutable, V27). 표가 작다"),
            Map.entry("notification.notification_template_id", "템플릿은 판을 쌓고 안 지운다. 표가 작다"),
            Map.entry("role_permission.permission_id", "권한은 마이그레이션만 넣고 안 지운다. 표가 작다"),
            Map.entry("seller_invitation.role_id", "역할은 안 지운다. 표가 작다")));

    @Autowired
    private JdbcClient jdbc;

    @Test
    @DisplayName("인덱스 없는 외래키는 전부 면제 목록에 근거와 함께 있다")
    void everyForeignKeyIsIndexedOrExempt() {
        List<String> unindexed = unindexedForeignKeys();

        assertThat(unindexed)
                .as("외래키를 걸었으면 첫 칸이 그 칸인 인덱스를 같이 만든다. 안 만들 까닭이 있으면 EXEMPT 에 근거와 함께 적는다")
                .containsExactlyInAnyOrderElementsOf(EXEMPT.keySet());
    }

    @Test
    @DisplayName("면제 목록의 근거가 비어 있지 않다")
    void exemptionsHaveReasons() {
        assertThat(EXEMPT.values()).allSatisfy(reason -> assertThat(reason).isNotBlank());
    }

    /** 첫 칸들이 그 외래키의 칸과 같은 인덱스가 없는 외래키 */
    private List<String> unindexedForeignKeys() {
        return jdbc.sql("""
                        with fk as (
                            select c.conrelid, c.conkey,
                                   c.conrelid::regclass::text || '.' ||
                                   (select string_agg(a.attname, ',' order by k.ord)
                                      from unnest(c.conkey) with ordinality k(attnum, ord)
                                      join pg_attribute a on a.attrelid = c.conrelid and a.attnum = k.attnum) as name
                              from pg_constraint c
                              join pg_namespace n on n.oid = c.connamespace
                             where c.contype = 'f' and n.nspname = 'public'
                        )
                        select fk.name from fk
                         where not exists (
                             select 1 from pg_index i
                              where i.indrelid = fk.conrelid
                                and (i.indkey::int2[])[0:array_length(fk.conkey, 1) - 1] = fk.conkey
                         )
                         order by fk.name
                        """)
                .query(String.class)
                .list();
    }
}
