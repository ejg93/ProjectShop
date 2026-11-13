package com.projectshop.shop.brokerage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.support.BusinessCalendar;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 중개 명세 배치가 회차를 남기고 연체 지표를 채우는지 잰다(`Q455`).
 */
@DisplayName("중개 명세 배치")
class BrokerageStatementBatchTest extends PostgresTestBase {

    @Autowired
    private BrokerageStatementBatch batch;

    @Autowired
    private BrokerageStatementService statements;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private JdbcClient jdbc;

    @Test
    @DisplayName("회차가 남고 지표가 연체 수와 같다")
    void recordsRunAndGauge() {
        LocalDate baseline = LocalDate.now(BusinessCalendar.ZONE).minusDays(1);

        batch.close(baseline);

        assertThat(jdbc.sql("""
                        select count(*) from batch_run where batch_name = 'brokerage_statement' and baseline_date = :day
                        """)
                .param("day", baseline)
                .query(Integer.class)
                .single())
                .isEqualTo(1);
        assertThat(meters.get("shop.brokerage_statement.overdue").gauge().value())
                .isEqualTo(statements.countOverdue(LocalDate.now(BusinessCalendar.ZONE)));
    }
}
