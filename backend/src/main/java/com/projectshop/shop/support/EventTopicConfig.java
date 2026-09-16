package com.projectshop.shop.support;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

    @Bean
    NewTopic shopEventsTopic(@Value("${shop.events.topic}") String topic) {
        return new NewTopic(topic, 3, (short) 1);
    }
}
