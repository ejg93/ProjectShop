package com.projectshop.shop.webhook;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.support.EnumValue;
import com.projectshop.shop.support.ListQuery.Paging;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 한 엔드포인트의 발송 기록(`31`·`Q175`). 최근 것부터다 — 셀러가 여는 이유가 「방금 것이 갔나」다.
 *
 * <p><b>재발송을 권하는 것은 서버다</b>({@code allowedActions}) — 실패로 닫힌 줄({@code FAILED}·{@code EXHAUSTED})에만,
 * <b>그리고 다시 보낼 권한({@code webhook:manage})이 있는 사람에게만</b> {@code RESEND} 가 실린다. 보기만 하는 관리자·감사자에게
 * 실으면 누르는 순간 403 이다(`Q211`, 마무리 53차 독립 리뷰). 화면이 상태를 보고 판단하면 규칙이 두 벌이 된다(`Q79`).
 */
@Service
public class WebhookDeliveryQuery {

    /**
     * @param eventType     사건 이름(대문자, {@link WebhookEventType})
     * @param lastError     마지막 실패의 짧은 사유. 받는 쪽의 응답 본문은 안 담는다
     */
    @Schema(name = "WebhookDelivery")
    public record Delivery(long webhookDeliveryId, String eventType, String status, int attemptCount,
            OffsetDateTime nextAttemptAt, Integer lastStatusCode, String lastError, OffsetDateTime createdAt,
            OffsetDateTime deliveredAt, List<String> allowedActions) {
    }

    @Schema(name = "WebhookDeliveryPage")
    public record Page(List<Delivery> items, int page, int size, long total) {
    }

    private final JdbcClient jdbc;
    private final WebhookEndpointService endpoints;

    WebhookDeliveryQuery(JdbcClient jdbc, WebhookEndpointService endpoints) {
        this.jdbc = jdbc;
        this.endpoints = endpoints;
    }

    /**
     * @param status {@code PENDING}·{@code SENT}·{@code FAILED}·{@code EXHAUSTED} 중 하나. null 이면 전부
     */
    public Page find(long userId, long endpointId, String status, Paging paging) {
        boolean canResend = endpoints.canResend(userId, endpoints.find(userId, endpointId).sellerId());
        String stored = storedStatus(status);

        List<Delivery> items = jdbc.sql("""
                        select d.webhook_delivery_id, e.type, d.status, d.attempt_count, d.next_attempt_at,
                               d.last_status_code, d.last_error, d.created_at, d.delivered_at
                          from webhook_delivery d
                          join outbox_event e on e.outbox_event_id = d.outbox_event_id
                         where d.webhook_endpoint_id = :endpointId
                           and (cast(:status as text) is null or d.status = cast(:status as text))
                         order by d.created_at desc, d.webhook_delivery_id desc
                         limit :size offset :offset
                        """)
                .param("endpointId", endpointId)
                .param("status", stored)
                .param("size", paging.size())
                .param("offset", paging.offset())
                .query((rs, rowNum) -> {
                    WebhookDeliveryStatus deliveryStatus = WebhookDeliveryStatus.of(rs.getString("status"));
                    return new Delivery(
                            rs.getLong("webhook_delivery_id"),
                            EnumValue.of(rs.getString("type"), WebhookEventType::of),
                            deliveryStatus.name(),
                            rs.getInt("attempt_count"),
                            rs.getObject("next_attempt_at", OffsetDateTime.class),
                            rs.getObject("last_status_code", Integer.class),
                            rs.getString("last_error"),
                            rs.getObject("created_at", OffsetDateTime.class),
                            rs.getObject("delivered_at", OffsetDateTime.class),
                            canResend && deliveryStatus.resendable() ? List.of("RESEND") : List.of());
                })
                .list();

        long total = jdbc.sql("""
                        select count(*) from webhook_delivery
                         where webhook_endpoint_id = :endpointId
                           and (cast(:status as text) is null or status = cast(:status as text))
                        """)
                .param("endpointId", endpointId)
                .param("status", stored)
                .query(Long.class)
                .single();
        return new Page(items, paging.page(), paging.size(), total);
    }

    private static String storedStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return WebhookDeliveryStatus.valueOf(status.toUpperCase(Locale.ROOT)).code();
        } catch (IllegalArgumentException e) {
            throw new ShopException(ErrorCode.VALIDATION_FAILED, "그런 발송 상태가 없다: " + status);
        }
    }
}
