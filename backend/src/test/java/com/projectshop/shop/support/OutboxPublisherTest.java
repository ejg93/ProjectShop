package com.projectshop.shop.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.kafka.KafkaContainer;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.order.OrderFixture;

/**
 * 아웃박스 표의 사건이 실제로 브로커까지 가나(`33b`, `event-catalog.md` 「전송」).
 *
 * <p><b>세 가지를 잰다.</b> 나가나 · 같은 {@code subject} 가 같은 파티션에 가나 ·
 * <b>브로커가 답을 안 주면 도장이 안 찍히나</b>. 셋째가 이 클래스의 값이다 —
 * 앞 둘은 발행기를 대충 써도 초록이 되지만, 셋째는 {@code get()} 으로 ack 를 기다리는
 * 그 한 줄이 빠지면 바로 빨개진다.
 *
 * <p><b>브로커를 여기서만 띄운다.</b> {@code PostgresTestBase.Containers} 에 빈으로 두면
 * 브로커를 안 쓰는 느린 레인 전부가 같이 띄운다 — 지금 그쪽이 90개가 넘는다.
 *
 * <p><b>컨텍스트가 하나 는다</b>({@code 2i-3}). {@code shop.events.sink} 를 켜야 발행기 빈이 서고
 * 그 값이 캐시 키라 바탕과 안 합쳐진다. 켜는 자리를 이 클래스 하나로 묶어서 하나로 막았다.
 */
@DisplayName("아웃박스 발행기")
@TestPropertySource(properties = "shop.events.sink=kafka")
class OutboxPublisherTest extends PostgresTestBase {

    /** 컴포즈와 같은 이미지다. 갈리면 테스트가 통과해도 로컬에서 깨진다(`stack.md` 버전 표) */
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1")
            .withReuse(true);

    static {
        KAFKA.start();
    }

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @AfterAll
    static void keepContainer() {
        // 일부러 안 멈춘다. `withReuse(true)` 가 다음 빌드에서 같은 컨테이너를 다시 쓴다.
    }

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private OutboxClaims claims;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${shop.events.topic}")
    private String topic;

    private long orderId;
    private String orderNumber;

    @BeforeEach
    void setUp() {
        // 앞선 회차가 남긴 편지를 안 집게 한다. 이 표는 롤백을 안 하는 테스트도 행을 남긴다
        // (`Q57` 이력) — 그것까지 집으면 아래 단언이 남의 사건을 센다.
        jdbc.sql("update outbox_event set published_at = now() where published_at is null").update();

        String suffix = suffix();
        orderNumber = "20260916-" + suffix;

        AuthFixture fixture = new AuthFixture(jdbc);
        long userId = fixture.insertUser("outbox-publisher-" + suffix + "@test.local", "구매자");
        orderId = jdbc.sql("""
                        insert into shop_order (order_number, user_id, total_amount,
                                                commission_total, shipping_fee_total, payable_amount)
                        values (:number, :userId, 0, 0, 0, 0)
                        returning order_id
                        """)
                .param("number", orderNumber)
                .param("userId", userId)
                .query(Long.class)
                .single();
        OrderFixture.attachContractDocuments(jdbc, orderId);
    }

    @Test
    @DisplayName("주문 전이 하나가 토픽에 한 건으로 간다")
    void oneTransitionGoesToTopicOnce() {
        insertOrderHistory("payment_pending", "paid");

        int published = publisher.publishOnce();

        assertThat(published).isEqualTo(1);
        ConsumerRecord<String, String> record = readOne();
        assertThat(record.key())
                .as("파티션 키는 subject 다 — 같은 주문의 사건이 같은 파티션에 간다")
                .isEqualTo(orderNumber);
        assertThat(header(record, "type")).isEqualTo("shop.order.status_changed");

        Map<String, Object> envelope = objectMapper.readValue(record.value(), new TypeReference<Map<String, Object>>() { });
        assertThat(envelope.get("specversion")).isEqualTo("1.0");
        assertThat(envelope.get("type")).isEqualTo("shop.order.status_changed");
        assertThat(envelope.get("subject")).isEqualTo(orderNumber);
        assertThat(envelope.get("datacontenttype")).isEqualTo("application/json");
        assertThat((String) envelope.get("time"))
                .as("봉투의 time 은 UTC Z 다(`D10`, `D12` 봉투)")
                .endsWith("Z");
        assertThat(envelope.get("data"))
                .as("data 는 JSON 그대로 실린다 — 문자열로 실으면 받는 쪽이 한 번 더 푼다")
                .isInstanceOf(Map.class);

        assertThat(stamped(record)).as("ack 를 받았으니 도장이 찍힌다").isEqualTo(1);
    }

    @Test
    @DisplayName("같은 subject 의 사건 셋이 같은 파티션에 간다")
    void sameSubjectGoesToSamePartition() {
        insertOrderHistory("payment_pending", "paid");
        // 주문 층은 결제 상태만 받는다(`V18` order_status_history_status_check) —
        // 배송 상태는 셀러 주문 층이다. 같은 주문 번호로 세 번 전이시킨다.
        insertOrderHistory("payment_pending", "payment_failed");
        insertOrderHistory("payment_failed", "payment_expired");

        assertThat(publisher.publishOnce()).isEqualTo(3);

        List<ConsumerRecord<String, String>> records = read(3);
        assertThat(records).hasSize(3);
        assertThat(records.stream().map(ConsumerRecord::partition).distinct())
                .as("같은 키는 한 파티션에만 간다 — 그래야 발행 순서대로 소비된다")
                .hasSize(1);
        assertThat(records.stream().map(ConsumerRecord::key).distinct()).containsExactly(orderNumber);
    }

    /**
     * <b>ack 를 안 기다리면 초록인 테스트를 한 번 썼다</b>(`33b` 실측). 없는 주소를 물리면
     * {@code send()} 가 <b>그 자리에서</b> 던진다 — 메타데이터를 못 받아서다. 그래서
     * {@code get()} 을 통째로 지워도 잡혔고, <b>부순 증거가 안 됐다.</b>
     *
     * <p>지금은 <b>보내기는 되고 ack 만 실패하는</b> 자리를 만든다. 브로커가 <b>받는 크기 상한</b>을
     * 1바이트로 잡은 토픽에 보내면, 프로듀서는 보내 놓고 <b>브로커가 거절한 답</b>을 받는다 —
     * 실패가 <b>future 안에서만</b> 드러나는 모양이다. ({@code min.insync.replicas} 로도 해 봤는데
     * Kafka 4.3 은 그 값을 복제본 수에 묶어서 단일 노드에서는 안 걸린다 — CLI 로 확인했다.)
     */
    @Test
    @DisplayName("브로커가 답을 안 주면 published_at 이 안 찬다")
    void noAckLeavesRowUnpublished() {
        insertOrderHistory("payment_pending", "paid");
        String unackableTopic = createUnackableTopic();

        OutboxPublisher noAck =
                new OutboxPublisher(claims, ackAllTemplate(), objectMapper, unackableTopic);

        assertThat(noAck.publishOnce())
                .as("ack 가 안 왔으니 보낸 것으로 안 센다")
                .isZero();
        assertThat(unpublishedCount())
                .as("도장이 ack 뒤라야 이 행이 다음 회차에 다시 집힌다. "
                        + "먼저 찍고 보내면 이 편지는 영영 안 간다")
                .isEqualTo(1);
    }

    /**
     * 받아는 주고 ack 는 거절로 주는 토픽을 만든다.
     *
     * <p>이 토픽이 받는 메시지 상한이 1바이트다. 어떤 봉투도 그보다 크다.
     */
    private String createUnackableTopic() {
        String name = "shop.events.noack." + UUID.randomUUID();
        Map<String, Object> config =
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        try (Admin admin = Admin.create(config)) {
            NewTopic unackable = new NewTopic(name, 1, (short) 1)
                    .configs(Map.of(TopicConfig.MAX_MESSAGE_BYTES_CONFIG, "1"));
            admin.createTopics(List.of(unackable)).all().get(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException(e);
        }
        return name;
    }

    /** 실제 브로커를 물린 발행 경로. 붙는 것은 되고 <b>답만</b> 실패로 온다 */
    private KafkaTemplate<String, String> ackAllTemplate() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        // 크기 거절은 재시도해도 결과가 같다. 막아야 답이 빨리 온다.
        config.put(ProducerConfig.RETRIES_CONFIG, 0);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 2_000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 1_000);
        config.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));
    }

    private ConsumerRecord<String, String> readOne() {
        List<ConsumerRecord<String, String>> records = read(1);
        assertThat(records).hasSize(1);
        return records.getFirst();
    }

    /** 이 주문의 사건만 골라 읽는다. 다른 테스트가 남긴 것이 같은 토픽에 있다 */
    private List<ConsumerRecord<String, String>> read(int expected) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "outbox-test-" + UUID.randomUUID());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        List<ConsumerRecord<String, String>> mine = new java.util.ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + Duration.ofSeconds(20).toMillis();
            while (System.currentTimeMillis() < deadline && mine.size() < expected) {
                ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(500));
                polled.forEach(record -> {
                    if (orderNumber.equals(record.key())) {
                        mine.add(record);
                    }
                });
            }
        }
        return mine;
    }

    private String header(ConsumerRecord<String, String> record, String key) {
        return new String(record.headers().lastHeader(key).value(), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 봉투의 {@code id} 가 표의 기본키다. 그 행에 도장이 찍혔나 */
    private int stamped(ConsumerRecord<String, String> record) {
        return jdbc.sql("""
                        select count(*) from outbox_event
                        where outbox_event_id = :id and published_at is not null
                        """)
                .param("id", Long.parseLong(header(record, "id")))
                .query(Integer.class)
                .single();
    }

    private int unpublishedCount() {
        return jdbc.sql("""
                        select count(*) from outbox_event
                        where published_at is null and subject = :subject
                        """)
                .param("subject", orderNumber)
                .query(Integer.class)
                .single();
    }

    /**
     * 노출 번호의 뒷자리를 만든다. {@code shop_order_number_format_check} 가
     * {@code [2-9A-HJ-NP-Z]} 만 받는다 — 0·1·I·O 가 빠진 것은 눈으로 안 갈려서다(`D9`).
     */
    private String suffix() {
        String alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
        StringBuilder suffix = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            suffix.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        }
        return suffix.toString();
    }

    private void insertOrderHistory(String from, String to) {
        jdbc.sql("""
                        insert into order_status_history (order_id, from_status, to_status, actor_type)
                        values (:orderId, :from, :to, 'system')
                        """)
                .param("orderId", orderId)
                .param("from", from)
                .param("to", to)
                .update();
    }
}
