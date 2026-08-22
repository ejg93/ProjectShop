package com.projectshop.shop.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;

/**
 * 사유 글을 5년 표에서 뺀 뒤에도 막던 것이 막히는가(청크 5i-2).
 *
 * <p><b>옮기면서 잃을 뻔한 것이 하나 있다.</b> 「반려에는 사유가 필요하다」가
 * {@code refund_rejection_reason_check} 였는데, 컬럼이 다른 표로 가면서
 * <b>한 행 안에서 안 끝나는 조건</b>이 됐다. 지연 제약 트리거로 내렸고 여기서 그것을 밟는다.
 */
@DisplayName("환불 사유 글")
class RefundNoteSchemaTest extends PostgresTestBase {

    @Autowired
    private JdbcClient jdbc;

    @Test
    @DisplayName("5년 표에 사유 컬럼이 없다")
    void refundHasNoReasonColumns() {
        assertThat(columnsOf("refund"))
                .describedAs("사람이 쓴 글이 5년을 살면 거기 섞인 연락처도 같이 산다")
                .doesNotContain("request_reason", "decision_reason");
    }

    @Test
    @DisplayName("사유 글은 발송 표가 아니라 별도 표에 있다")
    void noteTableHoldsBothReasons() {
        assertThat(columnsOf("refund_note")).contains("request_reason", "decision_reason");
    }

    @Test
    @DisplayName("빈 사유 글은 행이 안 된다")
    void rejectsEmptyNote() {
        assertThatThrownBy(() -> jdbc.sql("""
                        insert into refund_note (refund_id, request_reason, decision_reason)
                        values (1, null, null)
                        """).update())
                .describedAs("빈 행이 쌓이면 파기가 셀 대상만 늘고 「사유가 있었나」가 흐려진다")
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private java.util.List<String> columnsOf(String table) {
        return jdbc.sql("""
                        select column_name from information_schema.columns
                         where table_schema = 'public' and table_name = :table
                        """)
                .param("table", table)
                .query(String.class)
                .list();
    }
}
