package com.projectshop.shop.support;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * 사건이 나가는 토픽을 만든다({@code 33b}, {@code event-catalog.md} 「전송」).
 *
 * <p><b>토픽이 하나다.</b> 소비자마다 토픽을 가르면 순서 보장 단위가 같이 갈린다 —
 * 종류는 헤더 {@code type} 으로 거른다.
 *
 * <p><b>파티션이 셋이다.</b> 키({@code subject}) 별 순서가 실제로 갈리는 것을 로컬에서 볼 수 있는 최소다.
 * 복제본은 하나 — 로컬 브로커가 단일 노드라 둘 이상은 못 만든다.
 *
 * <p>{@code shop.events.sink} 가 {@code kafka} 일 때만 선다. {@code none} 이면 토픽을 만들 일도 없다.
 */
@Configuration
@ConditionalOnProperty(name = "shop.events.sink", havingValue = "kafka")
class EventTopicConfig {

    /**
     * 소비자가 죽은 편지에 매달리지 않게 한다(`33a`).
     *
     * <p>예외를 그대로 올리면 같은 편지를 <b>무한히</b> 다시 받고 그동안 뒤의 사건이 전부 밀린다.
     * 세 번까지 해 보고 {@code ERROR} 를 남기고 넘어간다 — <b>넘어간 것은 스위퍼가 뒤에 집는다</b>.
     * 그래서 못 보낸 편지를 따로 모으는 큐를 안 둔다(`event-catalog.md` 「지금 안 하는 것」).
     *
     * <p><b>간격이 0이다.</b> 여기서 실패하는 것은 대개 DB 가 잠깐 안 되는 것이고, 그 경우
     * 세 번을 붙여서 해 보든 나눠서 해 보든 결과가 같다 — 진짜 재시도는 5분 뒤 스위퍼다.
     */
    @Bean
    DefaultErrorHandler eventErrorHandler() {
        DefaultErrorHandler handler = new DefaultErrorHandler(new FixedBackOff(0L, 2L));
        handler.setCommitRecovered(true);
        return handler;
    }

    @Bean
    NewTopic shopEventsTopic(@Value("${shop.events.topic}") String topic) {
        return new NewTopic(topic, 3, (short) 1);
    }
}
