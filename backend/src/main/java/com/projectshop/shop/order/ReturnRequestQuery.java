package com.projectshop.shop.order;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.projectshop.shop.support.EnumValue;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 묶음마다의 반품 진행(`43a-5`). 사는 사람·셀러·관리자의 상세가 같은 모양을 받는다.
 *
 * <p><b>가장 최근 반품 하나만 싣는다.</b> 거절되면 다시 낼 수 있어서 묶음 하나에 반품이 여럿일 수 있는데,
 * 열린 것은 하나뿐이고({@code return_request_open_idx}, `V63`) 지난 판정은 주문 이력이 든다.
 *
 * <p><b>글은 안 싣는다</b> — 사유·소견·거절 사유는 사람이 쓴 글이라 따로 다룬다({@code return_note}, `D13`).
 */
@Service
public class ReturnRequestQuery {

    /**
     * @param status         반품 표의 상태(대문자, {@link ReturnStatus})
     * @param reasonCode     무엇으로 들어왔나. 하자면 기한과 비용 부담이 갈린다(`D2` R3)
     * @param receivedAt     입고 시각. 환급 기산점이다(`D2` R5)
     * @param inspectedAt    검수 시각. 소견과 함께 적었을 때만 찬다
     * @param allowedActions 이 사람이 이 반품에 할 수 있는 것({@code RECEIVE}). 경로는 {@code /api/returns/{번호}/receive}
     */
    @Schema(name = "ReturnProgress")
    public record Progress(String status, OrderStatusService.ReturnReason reasonCode, OffsetDateTime requestedAt,
            OffsetDateTime receivedAt, OffsetDateTime inspectedAt, OffsetDateTime decidedAt,
            List<String> allowedActions) {

        /** 물건이 들어왔나. 승인이 이것을 요구한다(`V63` {@code return_request_timeline_check}) */
        boolean received() {
            return receivedAt != null;
        }

        /** 입고를 적을 수 있나 — 접수됐거나 택배가 걷어 간 것 */
        boolean receivable() {
            return ReturnStatus.REQUESTED.name().equals(status) || ReturnStatus.PICKED_UP.name().equals(status);
        }

        Progress withAllowedActions(List<String> actions) {
            return new Progress(status, reasonCode, requestedAt, receivedAt, inspectedAt, decidedAt,
                    List.copyOf(actions));
        }
    }

    private static final String SELECT = """
            select distinct on (seller_order_id)
                   seller_order_id, status, reason_code, requested_at, received_at, inspected_at, decided_at
              from return_request
            """;

    private static final String ORDER_BY = " order by seller_order_id, requested_at desc, return_request_id desc";

    private final JdbcClient jdbc;

    ReturnRequestQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 주문 하나의 묶음마다. 반품이 없는 묶음은 키가 없다 */
    Map<Long, Progress> latestByOrder(long orderId) {
        return jdbc.sql(SELECT + """
                         where seller_order_id in (select seller_order_id from seller_order where order_id = :orderId)
                        """ + ORDER_BY)
                .param("orderId", orderId)
                .query((rs, rowNum) -> Map.entry(rs.getLong("seller_order_id"), progressOf(rs)))
                .list()
                .stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** 묶음 하나 */
    Optional<Progress> latestOf(long sellerOrderId) {
        return jdbc.sql(SELECT + " where seller_order_id = :sellerOrderId" + ORDER_BY)
                .param("sellerOrderId", sellerOrderId)
                .query((rs, rowNum) -> progressOf(rs))
                .optional();
    }

    private static Progress progressOf(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Progress(
                EnumValue.of(rs.getString("status"), ReturnStatus::of),
                OrderStatusService.ReturnReason.of(rs.getString("reason_code")),
                rs.getObject("requested_at", OffsetDateTime.class),
                rs.getObject("received_at", OffsetDateTime.class),
                rs.getObject("inspected_at", OffsetDateTime.class),
                rs.getObject("decided_at", OffsetDateTime.class),
                List.of());
    }
}
