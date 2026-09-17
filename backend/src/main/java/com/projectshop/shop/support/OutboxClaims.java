package com.projectshop.shop.support;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code outbox_event} 에서 보낼 편지를 집고 도장을 찍는다({@code 33b}).
 *
 * <h2>왜 {@link OutboxPublisher} 와 갈라져 있나</h2>
 *
 * <p><b>같은 클래스 안에서 부르면 {@code @Transactional} 이 안 먹는다.</b> 트랜잭션은 프록시가 여는데
 * 자기 메서드를 직접 부르면 그 프록시를 안 지난다 — 애너테이션은 그대로 있고 트랜잭션만 없는,
 * <b>읽어서는 안 보이는</b> 자리가 된다. 여기서는 그것이 조용히 안 끝난다:
 * 트랜잭션이 없으면 {@code for update} 의 잠금이 <b>그 select 문이 끝나는 순간</b> 풀려서
 * {@code skip locked} 가 아무것도 안 막는다.
 *
 * <p>가른 덕에 경계가 눈에 보인다 — <b>이 클래스 안이 트랜잭션이고 밖이 브로커다</b>({@code D11}).
 */
@Component
class OutboxClaims {

    private final JdbcClient jdbc;

    OutboxClaims(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 안 보낸 편지를 집어서 {@code claimed_at} 을 찍는다.
     *
     * <p><b>{@code skip locked} 다.</b> 남이 집는 중인 행은 기다리지 않고 건너뛴다 —
     * 기다리면 두 대가 같은 줄에 서서 결국 한 대씩 도는 것과 같아진다.
     *
     * <p><b>집은 표시를 남기고 트랜잭션을 닫는다.</b> 잠금은 여기서 끝나고, 보내는 동안은
     * 아무것도 안 잠겨 있다. 잠금만으로 하면 보내는 내내 트랜잭션을 열어 둬야 한다.
     *
     * @param staleBefore 이 시각 이전에 집힌 편지는 다시 집는다. 보내다 죽은 행이 그 대상이다
     * @param size 한 회차에 집는 수
     */
    @Transactional
    List<OutboxPublisher.Event> claim(OffsetDateTime staleBefore, int size) {
        List<OutboxPublisher.Event> events = jdbc.sql("""
                        select outbox_event_id, type, source, subject, occurred_at, data::text as data
                        from outbox_event
                        where published_at is null
                          and (claimed_at is null or claimed_at < :staleBefore)
                        order by outbox_event_id
                        limit :size
                        for update skip locked
                        """)
                .param("staleBefore", staleBefore)
                .param("size", size)
                .query((rs, rowNum) -> new OutboxPublisher.Event(
                        rs.getLong("outbox_event_id"),
                        rs.getString("type"),
                        rs.getString("source"),
                        rs.getString("subject"),
                        rs.getObject("occurred_at", OffsetDateTime.class),
                        rs.getString("data")))
                .list();
        if (events.isEmpty()) {
            return events;
        }
        jdbc.sql("update outbox_event set claimed_at = now() where outbox_event_id in (:ids)")
                .param("ids", events.stream().map(OutboxPublisher.Event::id).toList())
                .update();
        return events;
    }

    /** 브로커가 받은 편지에만 찍는다. 보내기가 끝난 뒤의 짧은 트랜잭션이다 */
    @Transactional
    void markPublished(long outboxEventId) {
        jdbc.sql("update outbox_event set published_at = now() where outbox_event_id = :id")
                .param("id", outboxEventId)
                .update();
    }

    /** 집어만 놓고 안 보낸 편지를 다시 집기까지 기다리는 시간 */
    static OffsetDateTime staleBefore(Duration claimExpiry) {
        return OffsetDateTime.now().minus(claimExpiry);
    }
}
