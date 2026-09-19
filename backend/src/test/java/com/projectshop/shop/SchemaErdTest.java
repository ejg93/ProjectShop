package com.projectshop.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 스키마에서 ERD 를 뽑아 {@code doc/erd/} 의 그림과 대조한다. 갈리면 실패한다.
 *
 * <p><b>손으로 그리면 다음 마이그레이션에서 바로 어긋난다</b>(청크 66). 그림은 그리는 날엔 맞고
 * 한 달 뒤엔 거짓말을 하는데, 그림이라서 아무도 안 깨진다 — 강제 지점 5위(문서)다.
 * 여기서는 그림을 <b>스냅샷</b>으로 두고 DB 에서 다시 뽑은 것과 견줘서 <b>4위(테스트)로 내린다.</b>
 *
 * <p><b>표를 묶음으로 가른다.</b> 쉰여섯을 한 장에 넣으면 화면에 안 들어가서 안 읽힌다 —
 * 읽히지 않는 그림은 안 그린 것과 같다. 가르는 결은 <b>패키지와 같다</b>
 * ({@code coding-rules.md} 「패키지 — 자원 단위로 판다」) — 두 벌의 경계를 만들지 않는다.
 *
 * <p><b>묶음 밖으로 나가는 외래키는 점선이다.</b> 그 표를 그 장에 다 그리면 묶음이 무너지고,
 * 아예 안 그리면 <b>바깥과 이어진 자리가 안 보인다</b> — 정산이 주문을 {@code restrict} 로만
 * 가리키는 것 같은 결정이 그 선에 실린다({@code domain-model.md}).
 *
 * <p>갱신은 {@code -Dsnapshot.update=true} 로만 한다(D15) — {@code PermissionMatrixTest} 와 같은 틀이다.
 */
@DisplayName("스키마와 ERD 의 대조")
class SchemaErdTest extends PostgresTestBase {

    private static final Path ERD_DIR = Path.of("..", "doc", "erd");

    /**
     * 묶음 → 그 묶음이 드는 표.
     *
     * <p><b>순서가 뜻이다.</b> 위에서부터 주문·상품·계정처럼 얼굴에 가까운 것이 오고,
     * 운영은 맨 뒤다 — {@code index.md} 의 차례가 이것을 그대로 쓴다.
     */
    private static final Map<String, List<String>> GROUPS = new LinkedHashMap<>();

    static {
        GROUPS.put("order", List.of(
                "shop_order", "seller_order", "order_item", "order_shipping",
                "order_status_history", "order_status_history_note", "order_contract_document",
                "return_request", "return_request_item", "return_note", "return_pickup",
                "compensation", "compensation_note", "payment", "payment_card",
                "idempotency_key", "cart", "cart_item"));
        GROUPS.put("product", List.of(
                "product", "product_image", "product_option", "product_option_value",
                "product_substantiation", "sku", "sku_option_value", "sku_stock",
                "sku_stock_movement", "copyright_report"));
        GROUPS.put("account", List.of(
                "app_user", "user_consent", "consent_item", "policy_document",
                "email_change_request", "password_reset_token", "seller", "seller_member"));
        GROUPS.put("settlement", List.of(
                "settlement", "settlement_cycle", "settlement_item",
                "refund", "refund_item", "refund_note"));
        GROUPS.put("permission", List.of(
                "permission", "permission_field_group", "role",
                "role_permission", "role_permission_field", "user_role"));
        GROUPS.put("ops", List.of(
                "audit_log", "batch_run", "outbox_event", "inquiry", "holiday",
                "notification", "notification_body", "notification_template"));
    }

    @Autowired
    private JdbcClient jdbc;

    /**
     * DB 의 모든 표가 어느 묶음엔가 들어 있는지 본다.
     *
     * <p><b>이것이 없으면 그림이 조용히 늙는다.</b> 새 표가 어느 장에도 안 들어가면
     * 그림 여섯은 전부 자기 몫을 다 그린 채 초록이고, 빠진 표는 아무 데도 안 보인다 —
     * {@code TriggerCoverageTest} 가 트리거에 한 것과 같은 회계다.
     */
    @Test
    @DisplayName("모든 표가 어느 묶음엔가 들어 있다")
    void everyTableBelongsToAGroup() {
        Set<String> grouped = new TreeSet<>();
        GROUPS.values().forEach(grouped::addAll);

        List<String> ungrouped = liveTables().stream().filter(t -> !grouped.contains(t)).toList();
        List<String> stale = grouped.stream().filter(t -> !liveTables().contains(t)).toList();

        assertThat(ungrouped)
                .describedAs("DB 에 있는데 SchemaErdTest 의 GROUPS 에 없는 표. 어느 장에 그릴지 정한다")
                .isEmpty();
        assertThat(stale)
                .describedAs("GROUPS 에 있는데 DB 에 없는 표. 지운 표면 그 줄도 지운다")
                .isEmpty();
    }

    /** 묶음마다 그림 하나를 견준다. */
    @Test
    @DisplayName("묶음별 ERD 가 스키마와 같다")
    void erdMatchesSchema() throws IOException {
        List<Edge> edges = foreignKeys();
        boolean update = Boolean.getBoolean("snapshot.update");
        if (update) {
            Files.createDirectories(ERD_DIR);
        }

        List<String> drifted = new ArrayList<>();
        Path index = ERD_DIR.resolve("index.md");
        String renderedIndex = renderIndex(edges);
        if (update) {
            Files.writeString(index, renderedIndex, StandardCharsets.UTF_8);
        } else if (!Files.exists(index)) {
            drifted.add("index.md 가 없다");
        } else if (!Files.readString(index, StandardCharsets.UTF_8).equals(renderedIndex)) {
            drifted.add("index.md 가 스키마와 다르다");
        }

        for (String group : GROUPS.keySet()) {
            Path file = ERD_DIR.resolve(group + ".md");
            String rendered = render(group, edges);
            if (update) {
                Files.writeString(file, rendered, StandardCharsets.UTF_8);
                continue;
            }
            if (!Files.exists(file)) {
                drifted.add(group + ".md 가 없다");
            } else if (!Files.readString(file, StandardCharsets.UTF_8).equals(rendered)) {
                drifted.add(group + ".md 가 스키마와 다르다");
            }
        }

        assertThat(drifted)
                .describedAs("ERD 가 스키마와 갈렸다. diff 를 보고 의도한 변경이면 "
                        + "`gradlew integrationTest -Dsnapshot.update=true` 로 갱신한다")
                .isEmpty();
    }

    /**
     * 묶음 사이의 결만 그린다. <b>표는 안 그린다</b> — 쉰여섯을 한 장에 넣으면 못 읽어서
     * 장을 가른 것인데, 입구에 다시 다 그리면 가른 뜻이 없다.
     *
     * <p>선 위의 수는 <b>그 방향으로 가는 외래키의 수</b>다. 굵기가 곧 결합의 세기라
     * 어느 묶음을 따로 떼기 어려운지가 이 한 장에서 보인다.
     */
    private String renderIndex(List<Edge> edges) {
        Map<String, Integer> across = new LinkedHashMap<>();
        for (Edge e : edges) {
            String from = groupOf(e.child());
            String to = groupOf(e.parent());
            if (from == null || to == null || from.equals(to)) {
                continue;
            }
            across.merge(from + '\u0000' + to, 1, Integer::sum);
        }

        StringBuilder out = new StringBuilder();
        out.append("# ERD — 묶음 사이\n\n");
        out.append("**생성물이다. 손으로 고치지 않는다** — `SchemaErdTest` 가 스키마에서 뽑는다.\n\n");
        out.append("```mermaid\nflowchart LR\n");
        across.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String[] pair = entry.getKey().split("\u0000");
            out.append("    ").append(pair[0]).append(" -->|").append(entry.getValue())
                    .append("| ").append(pair[1]).append('\n');
        });
        out.append("```\n\n");
        out.append("화살표는 **가리키는 쪽 → 가리켜지는 쪽**이고, 수는 그 방향의 외래키 수다.\n\n");
        GROUPS.forEach((group, tables) -> out.append("- [[").append(group).append("]] — 표 ")
                .append(tables.size()).append("개\n"));
        return out.toString();
    }

    /** 표가 속한 묶음. 어느 묶음에도 없으면 {@code null} — 그것은 위 회계가 잡는다. */
    private static String groupOf(String table) {
        return GROUPS.entrySet().stream()
                .filter(entry -> entry.getValue().contains(table))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    /** 한 묶음의 그림을 글자로 만든다. */
    private String render(String group, List<Edge> edges) {
        List<String> tables = GROUPS.get(group);
        StringBuilder out = new StringBuilder();
        out.append("# ERD — ").append(group).append("\n\n");
        out.append("**생성물이다. 손으로 고치지 않는다** — `SchemaErdTest` 가 스키마에서 뽑고,\n");
        out.append("갈리면 빨개진다. 갱신은 `gradlew integrationTest -Dsnapshot.update=true` 다.\n\n");
        out.append("```mermaid\nerDiagram\n");

        for (Edge e : edges) {
            boolean childHere = tables.contains(e.child());
            boolean parentHere = tables.contains(e.parent());
            if (!childHere && !parentHere) {
                continue;
            }
            String link = childHere && parentHere ? "}o--||" : "}o..||";
            out.append("    ").append(e.child()).append(' ').append(link).append(' ')
                    .append(e.parent()).append(" : \"").append(e.column()).append("\"\n");
        }

        for (String table : tables) {
            boolean linked = edges.stream()
                    .anyMatch(e -> e.child().equals(table) || e.parent().equals(table));
            if (!linked) {
                out.append("    ").append(table).append(" {\n    }\n");
            }
        }

        out.append("```\n\n");
        out.append("점선은 이 묶음 밖으로 나가는 외래키다. 상자만 있고 선이 없는 표는\n");
        out.append("외래키로 아무것도 안 가리키고 아무도 안 가리키는 표다.\n");
        return out.toString();
    }

    /** {@code public} 의 표 이름. */
    private List<String> liveTables() {
        return jdbc.sql("""
                select table_name from information_schema.tables
                where table_schema = 'public' and table_type = 'BASE TABLE'
                  and table_name <> 'flyway_schema_history'
                order by table_name
                """)
                .query(String.class)
                .list();
    }

    /** 외래키를 {@code 자식 → 부모} 로 걷는다. 한 제약이 여러 열이면 열 이름을 이어 붙인다. */
    private List<Edge> foreignKeys() {
        return jdbc.sql("""
                select c.conrelid::regclass::text as child,
                       c.confrelid::regclass::text as parent,
                       (select string_agg(a.attname, '+' order by a.attnum)
                          from pg_attribute a
                         where a.attrelid = c.conrelid and a.attnum = any (c.conkey)) as column_name
                from pg_constraint c
                join pg_namespace n on n.oid = c.connamespace
                where c.contype = 'f' and n.nspname = 'public'
                order by 1, 3
                """)
                .query((rs, n) -> new Edge(rs.getString("child"), rs.getString("parent"),
                        rs.getString("column_name")))
                .list();
    }

    /** 외래키 하나. {@code child.column} 이 {@code parent} 를 가리킨다. */
    private record Edge(String child, String parent, String column) {
    }
}
