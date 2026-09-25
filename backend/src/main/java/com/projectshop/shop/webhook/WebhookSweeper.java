package com.projectshop.shop.webhook;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.support.EventEnvelope;

import tools.jackson.databind.ObjectMapper;

/**
 * 아웃박스 사건을 셀러 웹훅으로 보낸다(`30`).
 *
 * <p><b>스위퍼 하나다.</b> 결정은 「Kafka 소비자 + 스위퍼」였는데 배포에 브로커가 없어서(`shop.events.sink=none`) 배포에서
 * 도는 것은 늘 스위퍼뿐이다 — 소비자는 로컬에서 몇 초를 앞당길 뿐이라 행의 실패 사다리 1단을 먼저 골랐다.
 *
 * <p><b>두 걸음이다.</b> ① 펼치기 — 구독한 엔드포인트마다 발송 줄을 만든다(유일 제약이 중복을 막는다). ② 보내기 — 기다리는 줄을
 * 집어(짧은 트랜잭션) 보내고(트랜잭션 밖) 결과를 적는다(짧은 트랜잭션). {@code OutboxPublisher} 와 같은 순서다 —
 * 보내는 동안 행 잠금을 쥐고 있으면 그 잠금이 손님 요청을 막는다.
 *
 * <p><b>줄은 하나, 보내기는 최소 한 번이다.</b> 유일 제약은 같은 사건의 줄이 둘 생기는 것을 막을 뿐이다 — 받고 답이 오기 전에
 * 끊기면 다시 보낸다. 받는 쪽이 {@code webhook-id} 로 거른다(Standard Webhooks). 우리 쪽은 적을 때 집은 시도 수를 확인해서,
 * 늦게 끝난 스위퍼가 먼저 적힌 결과를 덮지 못하게 한다.
 *
 * <p><b>셀러 경계</b>(`D14`). 사건의 셀러를 {@code subject}(노출 번호)로 찾아 엔드포인트의 셀러와 같을 때만 줄을 만든다.
 * 엔드포인트가 걸리기 전의 사건은 안 보낸다.
 */
@Component
public class WebhookSweeper {

    private static final Logger log = LoggerFactory.getLogger(WebhookSweeper.class);

    /** 한 회차에 보내는 줄 수. 받는 쪽이 늦으면 한 줄마다 타임아웃만큼 걸린다 */
    static final int BATCH_SIZE = 50;

    /**
     * 집고 못 적은 줄을 다시 집기까지. <b>한 회차 전체</b>보다 길어야 한다 — 줄 하나의 타임아웃과만 견주면 쉰 줄을 보내는 동안
     * 표시가 풀려 다른 스위퍼가 뒷줄을 다시 집는다(마무리 47차 독립 리뷰). 쉰 줄 × 한 건 최대 + 여유 1분이다.
     */
    static final Duration CLAIM_EXPIRY = WebhookSender.MAX_EXCHANGE.multipliedBy(BATCH_SIZE).plus(Duration.ofMinutes(1));

    /** 펼칠 사건을 이만큼 거슬러 본다. 스위퍼가 이보다 오래 죽어 있었으면 그 사이 사건은 안 간다 */
    static final Duration FAN_OUT_WINDOW = Duration.ofDays(1);

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final WebhookSecretCipher cipher;
    private final WebhookUrlPolicy urls;
    private final WebhookSender sender;
    private final ObjectMapper objectMapper;

    WebhookSweeper(JdbcClient jdbc, TransactionTemplate transactions, WebhookSecretCipher cipher,
            WebhookUrlPolicy urls, WebhookSender sender, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.cipher = cipher;
        this.urls = urls;
        this.sender = sender;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "PT30S", initialDelayString = "PT1M")
    public void sweep() {
        if (!cipher.available()) {
            return;
        }
        try {
            int fanned = fanOut();
            int sent = deliverDue(OffsetDateTime.now());
            if (fanned + sent > 0) {
                log.info("웹훅 스위퍼 끝 펼침={}건 보냄={}건", fanned, sent);
            }
        } catch (RuntimeException e) {
            log.error("웹훅 스위퍼 회차가 실패했다. 다음 회차가 다시 집는다", e);
        }
    }

    /** 구독한 엔드포인트마다 발송 줄을 만든다. 이미 있으면 안 만든다 */
    public int fanOut() {
        return jdbc.sql("""
                        with candidate as (
                            select e.outbox_event_id, e.type, e.created_at,
                                   case e.type
                                       when 'shop.seller_order.status_changed' then
                                           (select seller_id from seller_order where seller_order_number = e.subject)
                                       when 'shop.return_request.status_changed' then
                                           (select seller_id from seller_order where seller_order_number = e.subject)
                                       when 'shop.refund.status_changed' then
                                           (select so.seller_id from refund r
                                              join seller_order so on so.seller_order_id = r.seller_order_id
                                             where r.refund_number = e.subject)
                                       when 'shop.settlement.payout_changed' then
                                           (select seller_id from settlement where settlement_number = e.subject)
                                   end as seller_id
                              from outbox_event e
                             where e.created_at >= now() - cast(:window as interval)
                        )
                        insert into webhook_delivery (webhook_endpoint_id, outbox_event_id)
                        select w.webhook_endpoint_id, c.outbox_event_id
                          from candidate c
                          join webhook_endpoint w on w.seller_id = c.seller_id
                                                 and c.type = any(w.event_types)
                                                 and c.created_at >= w.created_at
                        on conflict (webhook_endpoint_id, outbox_event_id) do nothing
                        """)
                .param("window", FAN_OUT_WINDOW.toSeconds() + " seconds")
                .update();
    }

    /**
     * 기다리는 줄을 보낸다. 테스트가 시각을 넘긴다.
     *
     * @return 2xx 를 받은 줄 수
     */
    public int deliverDue(OffsetDateTime now) {
        List<Due> due = transactions.execute(status -> claim(now));
        int sent = 0;
        for (Due delivery : due == null ? List.<Due>of() : due) {
            WebhookSender.Result result = sendOrFail(delivery);
            transactions.executeWithoutResult(status -> record(delivery, result, now));
            if (result.succeeded()) {
                sent++;
            }
        }
        return sent;
    }

    /** 집은 한 줄과 보내는 데 필요한 것 */
    private record Due(long deliveryId, int attemptCount, long eventId, long sellerId, String url,
            byte[] secretCiphertext, int keyVersion, String type, String source, String subject,
            OffsetDateTime occurredAt, String data) {
    }

    private List<Due> claim(OffsetDateTime now) {
        List<Due> due = jdbc.sql("""
                        select d.webhook_delivery_id, d.attempt_count, e.outbox_event_id, w.seller_id, w.url,
                               w.secret_ciphertext,
                               w.secret_key_version, e.type, e.source, e.subject, e.occurred_at, e.data::text as data
                          from webhook_delivery d
                          join webhook_endpoint w on w.webhook_endpoint_id = d.webhook_endpoint_id
                          join outbox_event e on e.outbox_event_id = d.outbox_event_id
                         where d.status = 'pending' and d.next_attempt_at <= :now
                         order by d.next_attempt_at, d.webhook_delivery_id
                         limit :limit
                           for update of d skip locked
                        """)
                .param("now", now)
                .param("limit", BATCH_SIZE)
                .query((rs, rowNum) -> new Due(
                        rs.getLong("webhook_delivery_id"),
                        rs.getInt("attempt_count"),
                        rs.getLong("outbox_event_id"),
                        rs.getLong("seller_id"),
                        rs.getString("url"),
                        rs.getBytes("secret_ciphertext"),
                        rs.getInt("secret_key_version"),
                        rs.getString("type"),
                        rs.getString("source"),
                        rs.getString("subject"),
                        rs.getObject("occurred_at", OffsetDateTime.class),
                        rs.getString("data")))
                .list();
        for (Due delivery : due) {
            // 집은 표시다. 보내다 죽으면 이 시각이 지나 다음 회차가 다시 집는다.
            jdbc.sql("update webhook_delivery set next_attempt_at = :until where webhook_delivery_id = :id")
                    .param("until", now.plus(CLAIM_EXPIRY))
                    .param("id", delivery.deliveryId())
                    .update();
        }
        return due;
    }

    /**
     * 한 줄을 보낸다. <b>이 줄에서 난 예외가 회차를 끊지 않는다</b> — 끊기면 그 줄이 적히지 못한 채 다음 회차 맨 앞에 다시 서서
     * 뒤의 모든 줄을 막는다(시도 수도 안 늘어 소진도 안 된다, 마무리 47차 독립 리뷰). 그래서 실패로 적고 다음 줄로 간다.
     */
    private WebhookSender.Result sendOrFail(Due delivery) {
        try {
            return send(delivery);
        } catch (RuntimeException e) {
            log.error("웹훅 줄을 보내기 전에 실패했다 webhook_delivery_id={}", delivery.deliveryId(), e);
            return new WebhookSender.Result(null, "보내기 전에 실패했다", true);
        }
    }

    private WebhookSender.Result send(Due delivery) {
        WebhookUrlPolicy.Target target;
        try {
            target = urls.require(delivery.url());
        } catch (ShopException e) {
            // 등록 뒤에 안쪽을 가리키게 된 주소다(`D14`). 다시 보내도 안 된다.
            return new WebhookSender.Result(null, "안쪽 주소로 바뀌었다", true);
        }
        byte[] secret;
        try {
            secret = cipher.decrypt(delivery.secretCiphertext(), delivery.keyVersion(),
                    cipher.bindingOf(delivery.sellerId(), delivery.url()));
        } catch (IllegalStateException e) {
            // 키 판이 바뀌었거나 암호문이 이 행의 것이 아니다. 셀러가 엔드포인트를 다시 걸어야 풀린다.
            log.warn("웹훅 시크릿을 못 풀었다 webhook_delivery_id={} 까닭={}", delivery.deliveryId(), e.getMessage());
            return new WebhookSender.Result(null, "시크릿을 못 풀었다 — 엔드포인트를 다시 등록해야 한다", true);
        }
        String body = EventEnvelope.of(objectMapper, delivery.eventId(), delivery.type(), delivery.source(),
                delivery.subject(), delivery.occurredAt(), delivery.data(), true);
        return sender.send(target, secret, String.valueOf(delivery.eventId()),
                OffsetDateTime.now().toEpochSecond(), body);
    }

    /**
     * 결과를 적는다(`31`). 2xx 면 끝이다. 다시 보낼 만한 실패면 다음 시각을 {@link WebhookRetry#backoff} 만큼 뒤로 두고, 횟수를 다 썼으면
     * {@code exhausted}, 다시 보내도 안 될 실패(4xx·안쪽 주소)면 {@code failed} 로 닫는다.
     */
    private void record(Due delivery, WebhookSender.Result result, OffsetDateTime now) {
        int attempts = delivery.attemptCount() + 1;
        WebhookDeliveryStatus status = WebhookRetry.next(result, attempts);
        int written = jdbc.sql("""
                        update webhook_delivery
                           set status = :status,
                               attempt_count = :attempts,
                               next_attempt_at = :next,
                               delivered_at = case when :status = 'sent' then now() end,
                               last_status_code = :code,
                               last_error = :error
                         where webhook_delivery_id = :id
                           and status = 'pending'
                           and attempt_count = :claimed
                        """)
                .param("status", status.code())
                .param("attempts", attempts)
                .param("next", status == WebhookDeliveryStatus.PENDING ? now.plus(WebhookRetry.backoff(attempts)) : null)
                .param("code", result.statusCode())
                .param("error", result.error())
                .param("id", delivery.deliveryId())
                .param("claimed", delivery.attemptCount())
                .update();
        if (written == 0) {
            // 집은 표시가 풀린 사이 다른 스위퍼가 이 줄을 다시 집어 먼저 적었다. 덮지 않는다.
            log.warn("웹훅 줄의 결과를 안 적었다 — 다른 회차가 먼저 적었다 webhook_delivery_id={}", delivery.deliveryId());
        }
    }
}
