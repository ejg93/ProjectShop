package com.projectshop.shop.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;

/**
 * DB 의 표 목록과 {@code data-lifecycle.md} 의 수명 정책을 이름으로 대조한다. 한쪽에만 있으면 실패한다.
 *
 * <p><b>새 표를 만들면서 수명을 안 정해도 아무것도 안 깨진다.</b> 2026-08-21 에 표를 넷 만들었는데
 * {@code sku_stock} 이 정책에서 빠졌고, 잡은 것은 <b>사람이 센 것뿐</b>이다. 같은 날
 * {@code idempotency_key} 는 반대로 갔다 — 코드가 24시간마다 지우고 있는데 문서에 행이 없었다.
 *
 * <p>제약으로는 못 내린다({@code D23} 축 2). 「문서에 행이 있나」는 DB 가 모르는 사실이라
 * <b>테스트가 걸 수 있는 가장 낮은 자리</b>다.
 *
 * <p>대조는 <b>양방향</b>이다. 정책 없는 표는 수명이 안 정해진 것이고, 표 없는 정책 행은
 * 지워진 표를 가리키는 것이다. 둘 다 문서와 실물이 갈린 것이라 같이 본다.
 */
@DisplayName("표와 수명 정책의 대조")
class DataLifecycleCoverageTest extends PostgresTestBase {

    private static final Path LIFECYCLE = Path.of("..", "doc", "reference", "data-lifecycle.md");

    /** 표 이름을 담은 절. 다른 절의 표에도 백틱이 있어서 절을 좁혀야 컬럼 이름이 안 섞인다. */
    private static final Set<String> SECTIONS = Set.of("## 자원별 정책", "## 수명을 안 정하는 표");

    /** 표 이름을 든 칸의 머리글. 칸 순서가 바뀌어도 이 이름으로 찾는다. */
    private static final String TABLE_COLUMN = "표";

    /** 칸 안의 표 이름. 백틱으로 감싼 소문자와 밑줄만 본다. */
    private static final Pattern TABLE_NAME = Pattern.compile("`([a-z_]+)`");

    private static final Pattern CELL_SEPARATOR = Pattern.compile(Pattern.quote("|"));

    /** Flyway 가 스스로 만드는 표라 우리 정책의 대상이 아니다. */
    private static final Set<String> NOT_OURS = Set.of("flyway_schema_history");

    @Autowired
    private JdbcClient jdbc;

    @Test
    @DisplayName("모든 표가 수명 정책이나 예외 목록에 있다")
    void everyTableHasALifecycleRow() throws IOException {
        Set<String> missing = new TreeSet<>(tablesInDatabase());
        missing.removeAll(tablesInDocument());

        assertThat(missing)
                .describedAs("수명이 안 정해진 표. `data-lifecycle.md` 의 「자원별 정책」에 행을 더하거나, "
                        + "정할 것이 없으면 「수명을 안 정하는 표」에 근거와 함께 적는다(`D13`)")
                .isEmpty();
    }

    @Test
    @DisplayName("정책이 부르는 표가 전부 실물로 있다")
    void everyPolicyRowNamesARealTable() throws IOException {
        Set<String> gone = new TreeSet<>(tablesInDocument());
        gone.removeAll(tablesInDatabase());

        assertThat(gone)
                .describedAs("`data-lifecycle.md` 가 없는 표를 가리킨다. 표 이름이 바뀌었으면 문서를 고치고, "
                        + "표가 사라졌으면 그 행을 지운다")
                .isEmpty();
    }

    private Set<String> tablesInDatabase() {
        Set<String> tables = new TreeSet<>(jdbc.sql("""
                        select table_name from information_schema.tables
                         where table_schema = 'public' and table_type = 'BASE TABLE'
                        """)
                .query(String.class)
                .list());
        tables.removeAll(NOT_OURS);
        return tables;
    }

    /**
     * 두 절의 표에서 「표」 칸에 적힌 이름을 모은다.
     *
     * <p>칸 하나에 이름이 여럿일 수 있다 — 한 자원이 표 여럿으로 갈린 자리가 있어서다.
     * 대응하는 표가 없는 행은 그 칸이 {@code —} 라 아무것도 안 낸다.
     */
    private Set<String> tablesInDocument() throws IOException {
        Set<String> names = new TreeSet<>();
        boolean inSection = false;
        int tableColumn = -1;

        for (String line : Files.readAllLines(LIFECYCLE, StandardCharsets.UTF_8)) {
            if (line.startsWith("## ")) {
                inSection = SECTIONS.contains(line.trim());
                tableColumn = -1;
                continue;
            }
            if (!inSection) {
                continue;
            }
            if (!line.startsWith("|")) {
                tableColumn = -1;
                continue;
            }

            List<String> cells = cellsOf(line);
            if (tableColumn < 0) {
                tableColumn = cells.indexOf(TABLE_COLUMN);
                continue;
            }
            if (tableColumn < cells.size()) {
                collectNames(cells.get(tableColumn), names);
            }
        }
        return names;
    }

    private List<String> cellsOf(String row) {
        return CELL_SEPARATOR.splitAsStream(row).map(String::trim).toList();
    }

    private void collectNames(String cell, Set<String> into) {
        Matcher matcher = TABLE_NAME.matcher(cell);
        while (matcher.find()) {
            into.add(matcher.group(1));
        }
    }
}
