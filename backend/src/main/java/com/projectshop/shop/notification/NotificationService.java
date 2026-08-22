package com.projectshop.shop.notification;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 거래 통지를 보내고 보낸 것을 남긴다(청크 54b, `D18`).
 *
 * <p><b>광고성 정보는 여기로 안 온다.</b> 광고는 동의와 야간 제한을 지나야 하고
 * 그 관문은 청크 `55` 가 세운다. 이 클래스가 보내는 것은 법이 우리에게 지운 통지뿐이라
 * <b>동의를 안 본다</b> — 안 보내면 우리가 위반이고, 사용자가 껐다는 사실이 그 위반을 안 덮는다.
 *
 * <p><b>중개자라고 피해 가지 못한다.</b> 제20조의3 이 청약 확인과 대금지급 통지를
 * 통신판매중개자의 의무로 못박았다(`D2` R23).
 *
 * <p><b>{@code @Transactional} 이 없다.</b> 이 메서드 안에서 발송기를 부른다(`D11` 「트랜잭션 경계」).
 * 트랜잭션으로 감싸면 메일이 나가는 동안 DB 연결이 잡혀 있고, 나간 뒤에 롤백되면
 * <b>보냈는데 안 보낸 것으로 남는다.</b> 대신 남기는 일만 한 트랜잭션으로 묶는다.
 */
@Service
public class NotificationService {

    private static final String PENDING = "pending";
    private static final String SUCCEEDED = "succeeded";
    private static final String FAILED = "failed";

    /** 주소가 없는 계정. 파기된 계정에 보내려 할 때다 — 다시 보내도 같은 답이라 결정적 실패다 */
    private static final String NO_ADDRESS = "no_address";

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final JdbcClient jdbc;
    private final NotificationTemplates templates;
    private final MockNotificationSender sender;
    private final TransactionTemplate tx;

    NotificationService(JdbcClient jdbc, NotificationTemplates templates,
            MockNotificationSender sender, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.templates = templates;
        this.sender = sender;
        this.tx = new TransactionTemplate(txManager);
        // 부르는 쪽이 트랜잭션 안이면 세이브포인트로 들어간다. 중복이 걸렸을 때
        // 그 예외가 바깥 트랜잭션까지 죽이면 「이미 보냈다」를 삼킬 수가 없다 — `53a` 가 확인한 자리다.
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    /**
     * 어느 사건 때문에 보내나. <b>셋 중 많아야 하나</b>를 채운다(`V43` 의 {@code notification_target_check}).
     *
     * <p>비밀번호 재설정처럼 걸리는 자원이 없는 통지는 {@link #none()} 이다.
     */
    public record Target(Long orderId, Long sellerOrderId, Long refundId) {

        public static Target order(long orderId) {
            return new Target(orderId, null, null);
        }

        public static Target sellerOrder(long sellerOrderId) {
            return new Target(null, sellerOrderId, null);
        }

        public static Target refund(long refundId) {
            return new Target(null, null, refundId);
        }

        public static Target none() {
            return new Target(null, null, null);
        }
    }

    /**
     * 보내고 남긴다.
     *
     * <p><b>두 번 불러도 한 번만 나간다.</b> 앱이 「이미 보냈나」를 조회해서 판단하면 그 사이에
     * 끼어들 틈이 생겨서, 막는 것은 {@code notification} 의 부분 유니크다(`54a`).
     * 걸리면 예외를 받아 <b>빈 값을 돌려준다</b> — 두 번째 호출은 실패가 아니라 할 일이 없는 것이다.
     *
     * @param eventType 사건. 템플릿 코드와 같은 값이다
     * @param target    어느 자원 때문인가
     * @param userId    받는 사람. 주소는 계정에서 가져온다
     * @param values    판이 부르는 자리표시자와 넣을 값
     * @return 남긴 발송 id. 이미 보낸 사건이면 빈 값
     * @throws NotificationTemplates.MissingTemplateValueException 판이 부르는 값을 안 넘겼으면
     */
    public Optional<Long> send(String eventType, Target target, long userId,
            Map<String, String> values) {
        NotificationTemplates.Version version = templates.current(eventType, OffsetDateTime.now())
                .orElseThrow(() -> new IllegalStateException(
                        "시행 중인 알림 템플릿이 없다: " + eventType));

        // 꽂는 것이 남기는 것보다 먼저다. 안 채워진 자리가 있으면 여기서 던져서
        // 이력도 안 남는다 — 보낼 수 없는 것을 「보내는 중」으로 남기면 재시도가 그것을 집는다.
        NotificationTemplates.Rendered rendered = templates.render(version, values);

        Optional<String> address = addressOf(userId);
        Optional<Long> recorded = record(eventType, target, userId, version, rendered);
        if (recorded.isEmpty()) {
            return Optional.empty();
        }

        long notificationId = recorded.get();
        MockNotificationSender.Result result = address
                .map(to -> sender.send(to, rendered.subject(), rendered.body()))
                .orElseGet(() -> MockNotificationSender.Result.failed(NO_ADDRESS));

        settle(notificationId, result);
        return Optional.of(notificationId);
    }

    /**
     * 대기 상태로 이력과 본문을 남긴다. <b>둘이 한 트랜잭션이다</b> —
     * 본문 없는 이력이 생기면 「그때 무엇을 보냈나」에 못 답한다.
     *
     * @return 남긴 id. 같은 사건이 이미 있으면 빈 값
     */
    private Optional<Long> record(String eventType, Target target, long userId,
            NotificationTemplates.Version version, NotificationTemplates.Rendered rendered) {
        try {
            return Optional.ofNullable(tx.execute(status -> {
                long notificationId = insertNotification(eventType, target, userId, version);
                insertBody(notificationId, rendered);
                return notificationId;
            }));
        } catch (DuplicateKeyException alreadySent) {
            log.debug("이미 보낸 사건이라 건너뛴다 사건={} 받는이={}", eventType, userId);
            return Optional.empty();
        }
    }

    private long insertNotification(String eventType, Target target, long userId,
            NotificationTemplates.Version version) {
        return jdbc.sql("""
                        insert into notification (user_id, event_type, kind,
                                                  notification_template_id, channel, status,
                                                  order_id, seller_order_id, refund_id)
                        values (:userId, :eventType, :kind, :templateId, 'email', :status,
                                :orderId, :sellerOrderId, :refundId)
                        returning notification_id
                        """)
                .param("userId", userId)
                .param("eventType", eventType)
                .param("kind", version.kind())
                .param("templateId", version.id())
                .param("status", PENDING)
                .param("orderId", target.orderId())
                .param("sellerOrderId", target.sellerOrderId())
                .param("refundId", target.refundId())
                .query(Long.class)
                .single();
    }

    private void insertBody(long notificationId, NotificationTemplates.Rendered rendered) {
        jdbc.sql("""
                        insert into notification_body (notification_id, subject, body)
                        values (:id, :subject, :body)
                        """)
                .param("id", notificationId)
                .param("subject", rendered.subject())
                .param("body", rendered.body())
                .update();
    }

    /**
     * 결과를 이력에 적는다.
     *
     * <p>성공에는 보낸 시각이, 실패에는 이유가 있어야 한다 — 둘 다 `V43` 의 {@code check} 가 본다.
     * 실패 이유는 <b>종류까지</b>고 주소는 안 적는다(`D18` 「개인정보」).
     */
    private void settle(long notificationId, MockNotificationSender.Result result) {
        jdbc.sql("""
                        update notification
                           set status = :status,
                               sent_at = :sentAt,
                               failure_reason = :failureReason
                         where notification_id = :id
                        """)
                .param("id", notificationId)
                .param("status", result.succeeded() ? SUCCEEDED : FAILED)
                .param("sentAt", result.succeeded() ? OffsetDateTime.now() : null)
                .param("failureReason", result.failureReason())
                .update();
    }

    /**
     * 받는 주소. <b>이력에 복사하지 않는다</b> — 복사하면 개인정보가 한 벌 더 쌓이고
     * 계정에서 파기해도 그 사본이 남는다(`D18`).
     *
     * <p>파기된 계정은 주소가 비어 있다. 그 경우에도 <b>이력은 남긴다</b> —
     * 보내려 했다는 사실과 못 보낸 이유가 남아야 다음에 왜 안 갔는지에 답할 수 있다.
     */
    private Optional<String> addressOf(long userId) {
        return jdbc.sql("select email from app_user where user_id = :id and email is not null")
                .param("id", userId)
                .query(String.class)
                .optional();
    }
}
