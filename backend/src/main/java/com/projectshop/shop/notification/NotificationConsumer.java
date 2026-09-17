package com.projectshop.shop.notification;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 사건을 받아 거래 통지를 <b>그 자리에서</b> 남긴다(`33a`, `D2` R20).
 *
 * <h2>왜 스위퍼로 부족한가</h2>
 *
 * <p>전자상거래법 제14조제1항이 청약 확인을 <b>「신속하게」</b> 하라고 한다. 5분마다 표를 훑는
 * {@link NotificationSweeper} 로는 최악에 5분이 걸린다. 사건을 받으면 보통 2초 안이다.
 *
 * <h2>스위퍼를 안 없앤다</h2>
 *
 * <p>여기가 죽어 있는 동안의 사건은 아무도 안 집는다. 스위퍼가 <b>안전망</b>으로 남아서 뒤를 집고,
 * 둘이 같은 건을 잡아도 {@code notification} 의 부분 유니크가 하나만 남긴다({@code 54a}).
 * <b>못 보낸 편지를 따로 모으는 큐(데드레터)를 안 두는 이유가 이것이다</b>(`event-catalog.md` 「전송」).
 *
 * <h2>넷 중 셋이 여기로 온다</h2>
 *
 * <table border="1">
 *   <caption>거래 통지 넷의 경로 — 셋은 사건으로, 공급 지연만 스위퍼로</caption>
 *   <tr><th>통지</th><th>사건으로 오나</th><th>왜</th></tr>
 *   <tr><td>청약 접수 확인</td><td>온다</td>
 *       <td>주문 생성이 이력 행을 남긴다(`33a` 에서 더했다) → {@code from_status} 가 비어 있다</td></tr>
 *   <tr><td>대금 지급</td><td>온다</td><td>{@code to_status} 가 {@code paid} 인 전이</td></tr>
 *   <tr><td>공급 지연</td><td><b>안 온다</b></td>
 *       <td>「기한이 지났다」는 시각 조건이라 전이가 아니다. 스위퍼 몫이다</td></tr>
 *   <tr><td>환급</td><td>온다</td><td>환불이 {@code approved} 로 바뀌는 전이</td></tr>
 * </table>
 *
 * <h2>실패하면 건너뛴다</h2>
 *
 * <p>예외를 그대로 올리면 같은 편지를 무한히 다시 받는다 — 그동안 뒤의 사건이 전부 밀린다.
 * {@code DefaultErrorHandler} 가 <b>시도 셋</b>(처음 한 번과 재시도 둘)까지 해 보고 {@code ERROR} 를 남기고 넘어간다.
 * <b>넘어간 사건은 스위퍼가 5분 뒤 집는다</b>({@code notification-rules.md} 「실패와 재시도」).
 */
@Component
@ConditionalOnProperty(name = "shop.events.sink", havingValue = "kafka")
class NotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    private static final String ORDER_STATUS_CHANGED = "shop.order.status_changed";
    private static final String REFUND_STATUS_CHANGED = "shop.refund.status_changed";

    /** 주문이 막 생겼다는 표시. 앞 상태가 없는 전이는 생성뿐이다 */
    private static final String PAYMENT_PENDING = "payment_pending";
    private static final String PAID = "paid";
    private static final String REFUND_APPROVED = "approved";

    private final NotificationSweeper sweeper;
    private final ObjectMapper objectMapper;

    NotificationConsumer(NotificationSweeper sweeper, ObjectMapper objectMapper) {
        this.sweeper = sweeper;
        this.objectMapper = objectMapper;
    }

    /**
     * 토픽 하나를 받아 <b>헤더로</b> 가른다.
     *
     * <p><b>본문을 늘 열지 않는다</b>(`33b` 가 헤더 `type`·`id` 를 실어 둔 이유). 지금 토픽에 오는 일곱 중
     * 통지가 붙는 것은 둘이고, 나머지 다섯은 <b>헤더만 보고 넘긴다</b> — 사건이 늘수록 값이 커진다.
     * {@code subject} 도 본문이 아니라 <b>파티션 키</b>에서 온다(`event-catalog.md` 「전송」).
     *
     * <p><b>오프셋은 처리 뒤에 커밋된다</b>({@code spring.kafka.listener.ack-mode: record}).
     * 먼저 커밋하면 처리 중에 죽은 사건이 안 돌아온다.
     *
     * @param record 봉투와 헤더가 같이 온다. 헤더 `type` 과 파티션 키만으로 거른다
     */
    @KafkaListener(topics = "${shop.events.topic}", groupId = "shop-notification")
    void onEvent(ConsumerRecord<String, String> record) {
        String type = headerOf(record, "type");
        // 파티션 키가 subject 다(`33b` 가 그렇게 싣는다). 본문을 안 열고 얻는다.
        String subject = record.key();

        if (!ORDER_STATUS_CHANGED.equals(type) && !REFUND_STATUS_CHANGED.equals(type)) {
            // **여기서 끝난다.** 본문을 안 연다 — 종류는 헤더가 답하고 파티션 키가 subject 다
            // (`event-catalog.md` 「전송」, `33b` 가 헤더 둘을 실어 둔 이유다).
            return;
        }

        JsonNode data = objectMapper.readTree(record.value()).path("data");

        int sent = switch (type) {
            case ORDER_STATUS_CHANGED -> onOrderStatusChanged(subject, data);
            case REFUND_STATUS_CHANGED -> onRefundStatusChanged(subject, data);
            // 나머지 다섯은 통지 대상이 아니다. 받아서 버리는 것이 아니라 **아직 소비자가 없는 것**이라
            // 새 소비자가 생기면 그쪽이 같은 토픽을 자기 그룹으로 읽는다(`event-catalog.md` 「전송」).
            default -> 0;
        };

        if (sent > 0) {
            log.info("사건으로 통지를 남겼다 type={} subject={}", type, subject);
        }
    }

    /**
     * 주문 층 전이. 둘만 통지가 붙는다.
     *
     * <p><b>앞 상태가 없으면 생성이다.</b> 그 전이는 {@code OrderService} 가 주문을 만들 때만 남긴다.
     */
    private int onOrderStatusChanged(String orderNumber, JsonNode data) {
        String from = data.path("from_status").asString(null);
        String to = data.path("to_status").asString(null);

        if (from == null && PAYMENT_PENDING.equals(to)) {
            return sweeper.sendOrderPlaced(orderNumber);
        }
        if (PAID.equals(to)) {
            return sweeper.sendPaymentCompleted(orderNumber);
        }
        return 0;
    }

    /** 없으면 빈 문자열. 헤더가 없는 편지는 우리 것이 아니라 거르는 쪽이 맞다 */
    private String headerOf(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? "" : new String(header.value(), StandardCharsets.UTF_8);
    }

    /** 환불 전이. 승인된 것만 통지 대상이다 — 그 시점이 「환급에 필요한 조치를 했다」다 */
    private int onRefundStatusChanged(String refundNumber, JsonNode data) {
        if (REFUND_APPROVED.equals(data.path("to_status").asString(null))) {
            return sweeper.sendRefundCompleted(refundNumber);
        }
        return 0;
    }
}
