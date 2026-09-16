package com.projectshop.shop.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import com.projectshop.shop.KafkaTestBase;
import com.projectshop.shop.support.OutboxPublisher;

/**
 * 같은 편지가 두 번 와도 결과가 같나(`34`, `D11`·`D12`).
 *
 * <h2>왜 재나</h2>
 *
 * <p>발행기를 <b>「최소 한 번」</b>으로 만들었다 — ack 직전에 죽으면 다음 회차가 같은 편지를 또 보낸다.
 * 안 오는 것보다 두 번 오는 쪽을 일부러 골랐고({@code event-catalog.md} 「전달」), 그 선택은
 * <b>두 번 와도 결과가 같다</b>는 믿음 위에 서 있다. 그 믿음을 여기서 처음 잰다 —
 * {@code 33a} 의 시험은 「한 번 보내면 한 행」까지만 보고 <b>두 번 보내는 상황 자체를 안 만든다.</b>
 *
 * <h2>약속 안 한 것은 안 잰다</h2>
 *
 * <p>순서는 <b>{@code subject} 안에서만</b> 약속한다. 다른 {@code subject} 끼리는 약속이 없고,
 * 「섞여도 통과」하는 시험은 <b>어떤 코드도 안 막는다</b> — 깨지려야 깨질 수가 없다. 그런 줄이
 * 게이트 표에 서면 막는 것처럼 보이는 빈 줄이 하나 는다(`D25`). 그래서 분할표가 적어 둔 셋째를 안 썼고,
 * 대신 그 사실을 여기 적는다.
 *
 * <h2>첫째는 부술 수가 없다</h2>
 *
 * <p>중복을 거르는 앱 조건({@code n.notification_id is null})을 걷어내도 <b>초록이다</b> — 실제로 해 봤다.
 * 막는 것이 {@code notification} 의 부분 유니크(강제 지점 2위)라 그것을 부수려면 마이그레이션을 고쳐야 한다.
 * 그래서 이 시험은 <b>제약이 이 경로에서 실제로 걸리는지</b>를 재는 자리고, 막는 것은 제약이다({@code D25}).
 */
@DisplayName("사건이 두 번 와도")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EventRedeliveryTest extends KafkaTestBase {

    /** 이 테스트가 만든 것임을 알아보는 표시. 정리가 이것만 지운다 */
    private static final String PREFIX = "redelivery-";

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Value("${shop.events.topic}")
    private String topic;

    private CommittedOrderFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new CommittedOrderFixture(jdbc, transactionManager, PREFIX);
        fixture.prepare();
    }

    @AfterEach
    void tearDown() {
        fixture.cleanUp();
    }

    @Test
    @DisplayName("같은 사건을 두 번 발행해도 통지는 하나다")
    void redeliveryLeavesOneNotice() {
        String orderNumber = fixture.placeOrder();

        try (TopicTail tail = TopicTail.open(KAFKA.getBootstrapServers(), topic, orderNumber)) {
            redeliver(orderNumber, tail);
        }
    }

    private void redeliver(String orderNumber, TopicTail tail) {
        publisher.publishOnce();
        assertThat(awaitNotices(orderNumber, 1, Duration.ofSeconds(20)))
                .containsExactly("order_placed");

        // **중복을 DB 로 못 만든다.** 도장을 지우려 했더니 `V70` 의 트리거가 막았다 —
        // 「발행 표시를 되돌리면 같은 사건이 다시 나간다」가 그 자리의 규칙이다.
        //
        // **그래서 전송 계층에서 만든다.** 중복은 원래 거기서 난다: 브로커는 받았는데 ack 가 못 돌아와
        // 다음 회차가 같은 봉투를 다시 보내는 것이라, **토픽에 같은 봉투가 두 번 실리는 것**이 실제 모습이다.
        ConsumerRecord<String, String> sent = tail.await(1).getFirst();
        tail.resend(sent);

        assertThat(tail.await(2))
                .as("브로커에는 같은 사건이 두 건 있다 — 중복은 실제로 일어난다")
                .hasSize(2);
        // 둘째 통지가 생기기를 5초 기다린다. **안 생기는 것을 확인하는 자리**라 기다림이 곧 단언이다.
        assertThat(awaitNotices(orderNumber, 2, Duration.ofSeconds(5)))
                .as("받는 쪽이 거른다. 막는 것은 notification 의 부분 유니크고(`54a`) "
                        + "그래서 못 보낸 편지를 모으는 큐를 안 둔다")
                .containsExactly("order_placed");
    }

    @Test
    @DisplayName("한 subject 의 전이 셋은 발행 순서대로 소비된다")
    void sameSubjectKeepsOrder() {
        String orderNumber = fixture.placeOrder();
        fixture.moveOrder(orderNumber, "payment_pending", "paid");
        fixture.moveOrder(orderNumber, "paid", "payment_expired");

        List<String> arrived;
        try (TopicTail tail = TopicTail.open(KAFKA.getBootstrapServers(), topic, orderNumber)) {
            publisher.publishOnce();
            arrived = tail.await(3).stream()
                    .map(this::toStatusOf)
                    .toList();
        }

        assertThat(arrived)
                .as("파티션 키가 subject 라 같은 주문의 사건이 한 파티션에 줄을 선다. "
                        + "순서가 갈리면 소비자가 「결제됨」 뒤에 「생성됨」을 본다")
                .containsExactly("payment_pending", "paid", "payment_expired");
    }

    /** 봉투의 {@code data.to_status}. 순서를 보려면 봉투를 열어야 한다 */
    private String toStatusOf(ConsumerRecord<String, String> record) {
        Map<String, Object> envelope =
                objectMapper.readValue(record.value(), new TypeReference<Map<String, Object>>() { });
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) envelope.get("data");
        return String.valueOf(data.get("to_status"));
    }

    /** 소비자는 다른 스레드라 바로 안 보인다. 나타날 때까지 본다 */
    private List<String> awaitNotices(String orderNumber, int expected, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        List<String> notices = notices(orderNumber);
        while (notices.size() < expected && System.currentTimeMillis() < deadline) {
            sleep();
            notices = notices(orderNumber);
        }
        return notices;
    }

    private List<String> notices(String orderNumber) {
        return jdbc.sql("""
                        select n.event_type
                          from notification n
                          join shop_order o on o.order_id = n.order_id
                         where o.order_number = :number
                         order by n.notification_id
                        """)
                .param("number", orderNumber)
                .query(String.class)
                .list();
    }

    /**
     * 토픽의 <b>지금 끝</b>에 붙어서 이 주문의 사건만 모은다.
     *
     * <h2>왜 끝에 붙나</h2>
     *
     * <p>토픽이 발행된 행을 7일 두는데, <b>주문 번호는 빌드마다 되풀이된다</b>({@code OrderFixture} 의
     * 일련번호가 날짜와 함께 돈다). 앞에서부터 읽으면 <b>지난 빌드가 같은 번호로 남긴 사건</b>까지 세어서,
     * 세 건을 기대한 자리에 일곱 건이 잡힌다 — 실제로 그렇게 빨갰다.
     *
     * <p><b>그룹을 매번 새로 만든다.</b> 앱의 소비자({@code shop-notification})와 그룹이 다르면
     * 오프셋이 따로 흘러서 서로를 안 막는다 — 토픽을 하나로 둔 이유이기도 하다.
     */
    private static final class TopicTail implements AutoCloseable {

        private final KafkaConsumer<String, String> consumer;
        private final String bootstrap;
        private final String topic;
        private final String key;
        private final List<ConsumerRecord<String, String>> mine = new ArrayList<>();

        private TopicTail(String bootstrap, String topic, String key) {
            this.bootstrap = bootstrap;
            this.topic = topic;
            this.key = key;

            Map<String, Object> config = new HashMap<>();
            config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
            config.put(ConsumerConfig.GROUP_ID_CONFIG, "redelivery-test-" + UUID.randomUUID());
            config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            this.consumer = new KafkaConsumer<>(config);

            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                    .map(info -> new TopicPartition(info.topic(), info.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToEnd(partitions);
            // 자리를 물어봐야 실제로 옮겨 앉는다. `seekToEnd` 는 그 전까지 예약일 뿐이다.
            partitions.forEach(consumer::position);
        }

        static TopicTail open(String bootstrap, String topic, String key) {
            return new TopicTail(bootstrap, topic, key);
        }

        List<ConsumerRecord<String, String>> await(int expected) {
            long deadline = System.currentTimeMillis() + Duration.ofSeconds(20).toMillis();
            while (System.currentTimeMillis() < deadline && mine.size() < expected) {
                consumer.poll(Duration.ofMillis(500)).forEach(record -> {
                    if (key.equals(record.key())) {
                        mine.add(record);
                    }
                });
            }
            return List.copyOf(mine);
        }

        /** 같은 봉투를 그대로 한 번 더 싣는다. ack 를 못 받은 회차가 다시 보낸 것과 같은 모양이다 */
        void resend(ConsumerRecord<String, String> record) {
            Map<String, Object> config = new HashMap<>();
            config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
            config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            try (KafkaProducer<String, String> producer = new KafkaProducer<>(config)) {
                ProducerRecord<String, String> copy =
                        new ProducerRecord<>(topic, record.key(), record.value());
                record.headers().forEach(header -> copy.headers().add(header));
                producer.send(copy);
                producer.flush();
            }
        }

        @Override
        public void close() {
            consumer.close();
        }
    }

    private void sleep() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
