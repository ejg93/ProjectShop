package com.projectshop.shop;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.kafka.KafkaContainer;

/**
 * 브로커가 필요한 테스트의 바탕(`33b`).
 *
 * <h2>왜 따로 있나</h2>
 *
 * <p><b>브로커를 쓰는 테스트에서만 띄운다.</b> {@code PostgresTestBase.Containers} 에 빈으로 두면
 * 그것을 안 쓰는 느린 레인 전부가 같이 띄운다 — 지금 그쪽이 90개가 넘는다.
 *
 * <p><b>설정을 한 자리에 모은 이유는 컨텍스트 캐시다</b>({@code 2i-3}). {@code shop.events.sink} 가
 * 캐시 키라 켠 테스트는 바탕과 컨텍스트를 못 나눈다. 켜는 자리를 여기 하나로 묶으면
 * <b>켠 컨텍스트도 하나</b>다 — 클래스마다 적으면 같은 값인데도 키가 갈릴 자리가 생긴다.
 *
 * <h2>발행 주기를 한 시간으로 민다</h2>
 *
 * <p>스케줄러는 테스트 컨텍스트에서도 돈다({@code SchedulingConfig}). 2초 회차를 그대로 두면
 * <b>테스트가 부르기 전에 회차가 먼저 편지를 집어 간다</b> — 「내가 부른 회차가 몇 건 보냈나」가
 * 실행 속도에 따라 갈린다. 여기서는 회차를 사실상 끄고 <b>테스트가 직접 부른다.</b>
 */
@TestPropertySource(properties = {
        "shop.events.sink=kafka",
        "shop.events.publish-delay=PT1H",
        "shop.events.publish-initial-delay=PT1H"
})
public abstract class KafkaTestBase extends PostgresTestBase {

    /** 컴포즈와 같은 이미지다. 갈리면 테스트가 통과해도 로컬에서 깨진다(`stack.md` 버전 표) */
    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1")
            .withReuse(true);

    static {
        KAFKA.start();
    }

    /**
     * 브로커 주소와 <b>이 JVM 만의 토픽</b>을 넣는다.
     *
     * <p><b>토픽을 안 가르면 포크끼리 편지를 뺏는다</b>(`34` 실측). 느린 레인은 fork 가 여럿이고
     * 컨테이너는 재사용이라 <b>브로커 하나를 나눠 쓴다</b> — 같은 그룹({@code shop-notification})으로
     * 붙으면 파티션이 fork 들에 나뉘어서, <b>자기가 보낸 편지를 남의 fork 가 받는다.</b> DB 는 fork 마다
     * 갈려 있어서(`2i-2`) 받은 쪽에는 그 주문이 없고, 보낸 쪽은 통지가 안 생긴 채로 기다린다.
     *
     * <p>토픽 이름이 갈리면 그 일이 성립하지 않는다 — 소비자도 발행기도 이 값을 읽는다.
     * <b>지난 빌드가 남긴 레코드도 같이 사라진다</b>(이름이 매번 새것이라).
     *
     * <p>컨테이너는 일부러 안 멈춘다. {@code withReuse(true)} 가 다음 빌드에서 같은 것을 다시 쓴다.
     */
    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("shop.events.topic", () -> TEST_TOPIC);
    }

    /** fork 마다 하나. 이름에 pid 를 넣어 같은 브로커를 쓰는 다른 fork 와 안 겹친다 */
    private static final String TEST_TOPIC = "shop.events.test-" + ProcessHandle.current().pid();
}
