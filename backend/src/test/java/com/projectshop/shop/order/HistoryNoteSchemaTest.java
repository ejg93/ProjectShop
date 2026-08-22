package com.projectshop.shop.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;

/**
 * 전이 사유 글을 5년 표에서 뺀 뒤에도 막던 것이 막히는가(청크 5i-3).
 *
 * <p><b>옮기면서 잃을 뻔한 것이 있다.</b> 「관리자의 강제 전이에는 사유가 필요하다」가
 * {@code order_status_history_admin_reason_check} 였는데, 컬럼이 다른 표로 가면서
 * <b>한 행 안에서 안 끝나는 조건</b>이 됐다. 지연 제약 트리거로 내렸고 여기서 그것을 밟는다.
 *
 * <p><b>이 칸은 읽는 자리가 없었다.</b> 화면도 조회도 안 쓰고 쓰기만 했다 —
 * 평소에 아무도 안 보는 글이 5년을 살던 것이라 옮기기에 제일 좋은 모양이었다.
 */
@DisplayName("전이 사유 글")
class HistoryNoteSchemaTest extends PostgresTestBase {

    @Autowired
    private JdbcClient jdbc;

    @Test
    @DisplayName("5년 표에 사유 컬럼이 없다")
    void historyHasNoReasonColumn() {
        assertThat(columnsOf("order_status_history"))
                .describedAs("사람이 쓴 글이 5년을 살면 거기 섞인 연락처도 같이 산다")
                .doesNotContain("reason");
    }

    @Test
    @DisplayName("사유 글은 별도 표에 있다")
    void noteTableHoldsReason() {
        assertThat(columnsOf("order_status_history_note")).contains("reason");
    }

    @Test
    @DisplayName("5년 표에 자유 텍스트가 하나도 안 남았다")
    void noFreeTextLeftInRetainedTables() {
        // `5i-2` 와 `5i-3` 이 끝나면 남는 것은 `check` 로 닫힌 열거값뿐이다.
        assertThat(columnsOf("refund")).doesNotContain("request_reason", "decision_reason");
        assertThat(columnsOf("order_status_history")).doesNotContain("reason");
    }

    private List<String> columnsOf(String table) {
        return jdbc.sql("""
                        select column_name from information_schema.columns
                         where table_schema = 'public' and table_name = :table
                        """)
                .param("table", table)
                .query(String.class)
                .list();
    }
}
