package com.projectshop.shop.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.projectshop.shop.KafkaTestBase;
import com.projectshop.shop.support.OutboxPublisher;

/**
 * 사건으로 온 거래 통지가 스위퍼와 <b>같은 결과</b>를 내나(`33a`).
 *
 * <h2>여기서 보는 것</h2>
 *
 * <p>즉시 경로(소비자)와 안전망 경로(스위퍼)가 <b>같은 한 행</b>을 남긴다는 것이다.
 * 둘 중 하나만 도는 날이 있고(소비자가 죽거나, 켜져 있지 않거나) 둘 다 도는 날도 있는데,
 * <b>세 경우의 결과가 같아야</b> 스위퍼를 안전망으로 남겨 둔 결정이 성립한다.
 *
 * <h2>롤백이 없다</h2>
 *
 * <p>소비자는 <b>다른 스레드</b>에서 돈다({@code @Transactional(NOT_SUPPORTED)}, `D15`).
 * 테스트가 트랜잭션을 열고 있으면 그 안에서 만든 주문을 소비자가 <b>아예 못 본다</b> —
 * 커밋 전이라 다른 연결에는 없는 행이다. 그래서 롤백을 끄고 만든 것을 직접 지운다.
 */
@DisplayName("거래 통지 소비자")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationConsumerTest extends KafkaTestBase {

    /** 이 테스트가 만든 것임을 알아보는 표시. 정리가 이것만 지운다 */
    private static final String PREFIX = "consumer-notice-";

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private NotificationSweeper sweeper;

    @Autowired
    private PlatformTransactionManager transactionManager;


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
    @DisplayName("발행기가 보낸 청약 접수를 소비자가 통지로 남긴다")
    void consumerLeavesNotice() {
        String orderNumber = fixture.placeOrder();

        assertThat(publisher.publishOnce())
                .as("생성 이력 행 하나가 사건 하나를 낳는다")
                .isGreaterThanOrEqualTo(1);

        assertThat(awaitNotices(orderNumber))
                .as("소비자가 사건을 받아 그 자리에서 남긴다 — 스위퍼를 안 기다린다")
                .containsExactly("order_placed");

        // **안전망이 같은 건을 또 잡아도 한 행이다.** 막는 것은 `notification` 의 부분 유니크고(`54a`)
        // 그래서 못 보낸 편지를 모으는 큐를 안 둔다(`event-catalog.md` 「지금 안 하는 것」).
        sweeper.sweepAll(OffsetDateTime.now());
        assertThat(noticesFor(orderNumber)).containsExactly("order_placed");
    }

    @Test
    @DisplayName("소비자가 못 받아도 스위퍼가 같은 결과를 낸다")
    void sweeperLeavesTheSameNotice() {
        // 발행기를 안 부른다. 사건은 표에 남아 있고 아무도 안 가져가는 상태다 —
        // 소비자가 죽어 있는 동안이 이 모습이다(회차는 `KafkaTestBase` 가 한 시간으로 밀어 뒀다).
        String orderNumber = fixture.placeOrder();

        sweeper.sweepAll(OffsetDateTime.now());

        assertThat(noticesFor(orderNumber))
                .as("두 경로가 같은 결과를 낸다 — 그래서 스위퍼를 안전망으로 남긴다")
                .containsExactly("order_placed");
    }

    /** 소비자는 다른 스레드라 바로 안 보인다. 나타날 때까지 본다 */
    private List<String> awaitNotices(String orderNumber) {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(20).toMillis();
        List<String> notices = noticesFor(orderNumber);
        while (notices.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            notices = noticesFor(orderNumber);
        }
        return notices;
    }

    private List<String> noticesFor(String orderNumber) {
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

}
