package com.projectshop.shop.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;

/**
 * 5년을 사는 표에 사람 정보 컬럼이 들어오는지 본다(`D2` R9, 개인정보보호법 제21조제3항).
 *
 * <p>보존분 분리는 <b>한 번 갈라 놓고 끝나는 일이 아니다</b> — 다음 청크가 거래기록 표에
 * 이름이나 연락처 컬럼을 하나 더하면 그 순간 분리가 깨지는데, 그때 깨지는 것은 테스트가 아니라
 * 법 요건이라 <b>아무것도 안 빨개진다</b>. 목록을 못박아서 새 컬럼이 생기면 고르게 만든다:
 * 거래 사실이면 목록에 더하고, 사람 정보면 수명이 다른 표로 뺀다.
 */
@DisplayName("5년 보존 표의 컬럼")
class RetainedColumnsTest extends PostgresTestBase {

    /**
     * 전자상거래법 제6조로 5년을 사는 표와 그 컬럼. <b>전부 거래 사실이어야 한다.</b>
     * 사람 정보는 수명이 다른 표로 갈라져 있다 — `order_shipping`·`payment_card` 가 여섯 달이다(`D13`).
     *
     * <p><b>자유 텍스트 넷은 이 목록에 남겨 뒀다</b> — `order_status_history.reason`,
     * `refund.request_reason`·`decision_reason`, `order_item.withdrawal_restriction_reason`.
     * 컬럼 자체는 사람 정보가 아니지만 <b>사람이 쓴 글이라 섞여 들어올 수 있다.</b>
     * 그것을 어떻게 다룰지는 청크 `5i-2` 가 정한다.
     */
    private static final Map<String, List<String>> RETAINED = Map.of(
            "shop_order", List.of("commission_total", "created_at", "order_id", "order_number",
                    "payable_amount", "shipping_fee_total", "status", "total_amount",
                    "updated_at", "user_id"),
            "seller_order", List.of("agreed_lead_days", "auto_confirm_at", "closed_at",
                    "created_at", "delivered_at", "order_id", "return_reason", "seller_id",
                    "seller_order_id", "seller_order_number", "ship_due_at", "shipped_at",
                    "shipping_fee", "status", "supply_lead_days", "updated_at",
                    "withdrawal_expire_at"),
            "order_item", List.of("commission_amount", "commission_bp", "created_at",
                    "line_amount", "option_label", "order_item_id", "product_name", "quantity",
                    "seller_order_id", "sku_id", "unit_price_incl_vat",
                    "withdrawal_restriction_agreed_at", "withdrawal_restriction_reason"),
            "payment", List.of("amount", "approval_number", "created_at", "decline_reason",
                    "method", "order_id", "payment_id", "status"),
            "order_status_history", List.of("actor_type", "actor_user_id", "from_status",
                    "occurred_at", "order_id", "order_status_history_id", "reason",
                    "seller_order_id", "to_status"),
            "refund", List.of("amount", "approved_by_user_id", "created_at", "decided_at",
                    "decision_reason", "due_at", "gateway_refund_number", "reason_code",
                    "refund_id", "refund_number", "request_reason", "requested_by_type",
                    "requested_by_user_id", "seller_order_id", "shipping_fee_refund", "status",
                    "updated_at"));

    @Autowired
    private JdbcClient jdbc;

    @Test
    @DisplayName("거래 사실만 있고 사람 정보는 없다")
    void holdsOnlyTransactionFacts() {
        Map<String, List<String>> actual = new TreeMap<>();
        RETAINED.keySet().forEach(table -> actual.put(table, columnsOf(table)));

        assertThat(actual)
                .describedAs("5년 보존 표의 컬럼이 바뀌었다. 거래 사실이면 이 목록에 더하고, "
                        + "사람 정보면 수명이 다른 표로 뺀다(`D2` R9)")
                .isEqualTo(new TreeMap<>(RETAINED));
    }

    private List<String> columnsOf(String table) {
        return jdbc.sql("""
                        select column_name from information_schema.columns
                         where table_schema = 'public' and table_name = :table
                         order by column_name
                        """)
                .param("table", table)
                .query(String.class)
                .list();
    }
}
