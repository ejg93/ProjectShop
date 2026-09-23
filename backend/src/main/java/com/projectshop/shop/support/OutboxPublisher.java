package com.projectshop.shop.support;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/**
 * {@code outbox_event} 에 쌓인 사건을 Kafka 로 내보낸다(`33b`, {@code event-catalog.md} 「전송」).
 *
 * <h2>순서가 규칙이다</h2>
 *
 * <p><b>집기(짧은 트랜잭션) → 보내기(트랜잭션 밖) → 도장(짧은 트랜잭션)</b> 셋이다.
 * 하나로 묶으면 브로커 응답을 기다리는 동안 행 잠금이 유지된다 — {@code D11} 이 막는 자리고
 * {@code ArchitectureTest} 의 「트랜잭션 안에서 바깥을 안 부른다」가 잰다.
 *
 * <p><b>도장이 ack 뒤다.</b> 먼저 찍고 보내면 보내기 직전에 죽은 편지가 <b>영영 안 간다</b> —
 * 도장이 있어서 다음 회차가 안 집는다. 반대로 두면 <b>같은 편지가 두 번 갈 수 있고</b>,
 * 두 번 오는 것은 받는 쪽이 거르지만 안 오는 것은 아무도 못 고친다.
 * 이것이 「최소 한 번」의 근원이다({@code event-catalog.md} 「전달」).
 *
 * <h2>두 대가 떠도 같은 편지를 안 보낸다</h2>
 *
 * <p>집을 때 {@code for update skip locked} 로 남이 집는 중인 행을 건너뛰고,
 * 집은 표시를 {@code claimed_at} 에 남긴다. <b>잠금은 집는 트랜잭션에서 끝난다</b> —
 * 그래서 보내는 동안 아무것도 안 잠겨 있다. 잠금만으로 하면 보내는 내내 트랜잭션을 열어 둬야 해서
 * 위 「순서」와 부딪친다.
 *
 * <p><b>집었는데 안 보낸 편지는 {@link #CLAIM_EXPIRY} 뒤에 다시 집힌다.</b> 보내는 중에 죽으면
 * {@code claimed_at} 만 찍힌 행이 남는데, 그것을 영영 안 집으면 그 사건은 아무에게도 안 간다.
 *
 * <h2>회차를 안 남긴다</h2>
 *
 * <p>{@link BatchRuns} 를 안 탄다. 2초 주기라 회차라는 단위가 없다({@code D19} — 자주 도는 것은
 * 다음 회차가 곧 재시도다). 실패한 편지는 도장이 안 찍혀서 다음 회차가 다시 집는다.
 *
 * <h2>언제 도나</h2>
 *
 * <p>{@code shop.events.sink} 가 {@code kafka} 일 때만 이 빈이 선다. 기본은 {@code none} 이고
 * 그때는 브로커로 나가는 연결이 아예 안 열린다 — 배포와 빠른 레인이 그 자리다.
 */
@Component
@ConditionalOnProperty(name = "shop.events.sink", havingValue = "kafka")
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    /** 한 회차에 집는 편지 수. 늘리면 집는 트랜잭션이 한 번에 잠그는 행이 같이 는다 */
    static final int BATCH_SIZE = 200;

    /** 집어만 놓고 안 보낸 편지를 다시 집기까지 기다리는 시간 */
    static final Duration CLAIM_EXPIRY = Duration.ofMinutes(1);

    /** 브로커 답을 이만큼 기다린다. 넘으면 도장을 안 찍고 다음 회차로 넘긴다 */
    static final Duration ACK_TIMEOUT = Duration.ofSeconds(10);

    private final OutboxClaims claims;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;
    private final String topic;

    OutboxPublisher(OutboxClaims claims,
                    KafkaTemplate<String, String> kafka,
                    ObjectMapper objectMapper,
                    @Value("${shop.events.topic}") String topic) {
        this.claims = claims;
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    /**
     * 안 보낸 편지를 집어서 보낸다.
     *
     * <p>{@code fixedDelay} 라 앞 회차가 늦어져도 겹치지 않는다.
     */
    @Scheduled(fixedDelayString = "${shop.events.publish-delay}",
            initialDelayString = "${shop.events.publish-initial-delay}")
    public void publish() {
        try {
            publishOnce();
        } catch (RuntimeException e) {
            // 여기서 예외가 나가면 스케줄러가 이 배치를 멈춘다. 2초 뒤 회차가 같은 편지를 다시 집는다.
            log.error("아웃박스 발행 회차가 실패했다", e);
        }
    }

    /**
     * 한 회차를 돈다. 테스트가 시각 대신 이것을 직접 부른다.
     *
     * @return 도장까지 찍힌 편지 수
     */
    public int publishOnce() {
        List<Event> claimed = claims.claim(OutboxClaims.staleBefore(CLAIM_EXPIRY), BATCH_SIZE);
        if (claimed.isEmpty()) {
            return 0;
        }
        int published = 0;
        for (Event event : claimed) {
            if (send(event)) {
                claims.markPublished(event.id());
                published++;
            }
        }
        if (published < claimed.size()) {
            log.warn("아웃박스 {}건 중 {}건만 보냈다. 나머지는 다음 회차가 다시 집는다",
                    claimed.size(), published);
        }
        return published;
    }

    /**
     * 봉투를 만들어 보내고 브로커 답을 기다린다.
     *
     * <p><b>파티션 키가 {@code subject} 다.</b> 같은 주문의 사건이 같은 파티션에 들어가서
     * 발행 순서대로 소비된다({@code event-catalog.md} 「전송」).
     *
     * @return 브로커가 받았다고 답했으면 참
     */
    private boolean send(Event event) {
        try {
            SendResult<String, String> result = kafka.send(record(event))
                    .get(ACK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            log.debug("아웃박스 {}번을 {}-{} 에 보냈다", event.id(),
                    result.getRecordMetadata().topic(), result.getRecordMetadata().partition());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("아웃박스 {}번을 보내다 끊겼다", event.id());
            return false;
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            // 도장을 안 찍는다. 다음 회차가 다시 집어서 같은 편지를 다시 보낸다 — 「최소 한 번」이다.
            log.warn("아웃박스 {}번을 못 보냈다. 다음 회차가 다시 집는다", event.id(), e);
            return false;
        }
    }

    /** 본문을 안 열고 거르라고 헤더에 둘을 싣는다({@code event-catalog.md} 「전송」) */
    private ProducerRecord<String, String> record(Event event) {
        ProducerRecord<String, String> producerRecord =
                new ProducerRecord<>(topic, event.subject(), envelope(event));
        producerRecord.headers().add("type", event.type().getBytes(StandardCharsets.UTF_8));
        producerRecord.headers().add("id", String.valueOf(event.id()).getBytes(StandardCharsets.UTF_8));
        return producerRecord;
    }

    /**
     * CloudEvents 1.0 봉투를 만든다({@link EventEnvelope} — 웹훅과 한 곳이다).
     *
     * <p><b>{@code data} 는 표에 있는 JSON 그대로 실린다.</b> 문자열로 실으면 받는 쪽이 한 번 더 푼다.
     * 안쪽 소비자라 표기도 저장값 그대로다.
     */
    private String envelope(Event event) {
        return EventEnvelope.of(objectMapper, event.id(), event.type(), event.source(), event.subject(),
                event.occurredAt(), event.data(), false);
    }

    /** 표에서 읽은 한 건. {@code data} 는 JSON 문자열 그대로다 */
    record Event(long id, String type, String source, String subject,
                 OffsetDateTime occurredAt, String data) {
    }
}
