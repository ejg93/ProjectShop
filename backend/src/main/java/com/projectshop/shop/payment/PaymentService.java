package com.projectshop.shop.payment;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.order.IdempotencyService;
import com.projectshop.shop.order.OrderStatusService;
import com.projectshop.shop.support.Retries;

/**
 * 주문 하나를 결제한다.
 *
 * <p><b>이 클래스의 모양을 정한 것은 `D11` 의 두 규칙이 부딪히는 자리다.</b>
 * 「선점·처리·저장을 한 트랜잭션에 둔다」와 「트랜잭션 안에서 외부 호출을 안 한다」가
 * 결제에서 동시에 걸린다. PG 호출을 트랜잭션에 넣으면 응답을 기다리는 동안 멱등키 행의 락이
 * 유지돼서 <b>느린 PG 하나가 뒤 요청을 전부 409 로 만든다.</b>
 *
 * <p>그래서 <b>PG 호출은 트랜잭션 밖에서 먼저 하고, 결과 기록만 멱등 트랜잭션에 넣는다.</b>
 * 그 사이에 열리는 「재전송이 두 번 승인받는」 구간은 우리 트랜잭션이 못 닫는다 —
 * <b>PG 에 같은 멱등키를 넘겨서 PG 가 닫는다</b>(`MockPaymentGateway`). 실물 PG 도 같은 구조다.
 *
 * <p><b>감싸는 자리가 컨트롤러가 아니라 여기다.</b> {@code OrderController} 는 멱등을 컨트롤러에서
 * 감싸는데(`D11`), 결제는 순서가 「주문 조회 → PG → 기록」 이라 그 순서를 아는 쪽이 감싸야 한다.
 * 컨트롤러가 감싸면 PG 호출이 트랜잭션 안으로 들어간다.
 *
 * <p><b>거절은 예외가 아니라 결과다.</b> 예외로 던지면 멱등 기록과 주문 상태 변경이 같이 롤백돼서
 * `D7` 이 정한 {@code payment_failed} 가 코드에서 안 쓰이고, 셀러 주문 자동 취소와 재고 복구도
 * 안 돈다. 거절은 자원({@code payment} 행)이 생기므로 막을 중복이 있고, 그래서 저장한다 —
 * `D11` 「실패는 저장하지 않는다」가 말하는 실패(예외로 깨진 요청)와 다른 것이다.
 */
@Service
public class PaymentService {

    private final JdbcClient jdbc;
    private final MockPaymentGateway gateway;
    private final IdempotencyService idempotency;
    private final OrderStatusService orderStatuses;

    PaymentService(JdbcClient jdbc, MockPaymentGateway gateway, IdempotencyService idempotency,
            OrderStatusService orderStatuses) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.idempotency = idempotency;
        this.orderStatuses = orderStatuses;
    }

    /**
     * 결제 요청.
     *
     * <p><b>금액이 없다.</b> 낼 돈은 주문에 이미 박제돼 있어서 클라이언트가 정할 것이 아니다 —
     * 받으면 그 값이 맞는지 검사하는 코드가 따로 필요해지고, 빠뜨리면 원하는 금액으로 결제된다.
     */
    public record Command(String orderNumber, String method, String cardNumber) {}

    /**
     * 결제 결과.
     *
     * <p>{@code status} 가 {@code approved} 면 승인번호가, {@code failed} 면 거절 사유가 찬다.
     * 카드번호는 어디에도 없다(`D2` R18).
     */
    public record Result(String orderNumber, String status, String method, long amount,
            String approvalNumber, String cardIssuer, String cardLast4, String declineReason) {

        static final String APPROVED = "approved";
        static final String FAILED = "failed";
    }

    /**
     * 결제한다.
     *
     * <p><b>{@code @Transactional} 이 없어야 한다.</b> 이 메서드 안에서 PG 를 부른다.
     *
     * @param idempotencyKey 클라이언트가 만든 키(`D11`). 우리 표와 PG 가 같은 값을 쓴다
     */
    public Result pay(long userId, String idempotencyKey, Command command) {
        Fingerprint fingerprint = fingerprint(command);

        // 재생 확인이 주문 상태 검사보다 앞이다. 재전송 시점에는 그 주문이 이미 결제완료라
        // 순서를 바꾸면 재전송이 「낼 수 없는 주문」으로 막힌다 — 앞 요청이 성공했다는 사실 때문에
        // 그 응답을 못 받는다.
        Optional<Result> replayed = idempotency.replayIfPresent(userId, idempotencyKey,
                fingerprint, Result.class);
        if (replayed.isPresent()) {
            return replayed.get();
        }

        Payable payable = findPayable(userId, command.orderNumber());
        MockPaymentGateway.Result verdict = askGateway(idempotencyKey, payable, command);

        // 재시도가 멱등 바깥이다(`D11`). PG 는 이미 답했고 그 답은 키에 묶여 있어서
        // 다시 돌아도 같은 승인이 재생된다 — 두 번 청구되지 않는다.
        return Retries.onConflict(() -> idempotency.run(userId, idempotencyKey, fingerprint,
                Result.class, () -> settle(payable, command.method(), verdict)));
    }

    /** 결제할 주문. 금액은 여기서만 온다 */
    private record Payable(long orderId, String orderNumber, long amount) {}

    /**
     * 낼 수 있는 주문인가.
     *
     * <p><b>PG 를 부르기 전에 본다.</b> 나중에 보면 이미 승인된 카드를 되돌려야 한다.
     *
     * <p>남의 주문과 없는 주문이 같은 404 다(`D5`) — 가르면 번호를 두드려서 주문 수를 셀 수 있다.
     *
     * <p><b>여기 통과한 뒤에도 경합은 남는다.</b> 조회와 기록 사이에 만료 배치가 같은 주문을
     * {@code payment_expired} 로 옮길 수 있고, 그러면 기록 쪽 전이표가 막아서 승인만 PG 에 남는다.
     * 알고 남기는 구멍이다 — 되돌리는 자리가 환불(청크 12a)이고 그것이 서기 전에는 막을 수단이 없다.
     */
    private Payable findPayable(long userId, String orderNumber) {
        Order order = jdbc.sql("""
                        select order_id, payable_amount, status
                          from shop_order
                         where order_number = :orderNumber and user_id = :userId
                        """)
                .param("orderNumber", orderNumber)
                .param("userId", userId)
                .query((rs, rowNum) -> new Order(rs.getLong("order_id"),
                        rs.getLong("payable_amount"), rs.getString("status")))
                .optional()
                .orElseThrow(() -> new ShopException(ErrorCode.ORDER_NOT_FOUND));

        if (!"payment_pending".equals(order.status())) {
            throw new ShopException(ErrorCode.ORDER_TRANSITION_NOT_ALLOWED,
                    "결제를 기다리는 주문이 아니다: " + order.status());
        }
        return new Payable(order.orderId(), orderNumber, order.payableAmount());
    }

    private record Order(long orderId, long payableAmount, String status) {}

    /**
     * PG 에 승인을 요청한다. 응답이 없으면 <b>같은 키로</b> 다시 부른다.
     *
     * <p>{@code Retries.onConflict} 이 아닌 이유는 그쪽이 SQLSTATE 로 가르기 때문이다.
     * 결제사 무응답은 DB 예외가 아니라 그 판정에 안 걸린다.
     *
     * <p>같은 키로 다시 부르는 것이 안전한 근거가 PG 의 멱등 계약이다. 키를 새로 만들어 재시도하면
     * <b>PG 가 다른 요청으로 보고 두 번 승인한다.</b>
     */
    private MockPaymentGateway.Result askGateway(String idempotencyKey, Payable payable,
            Command command) {

        MockPaymentGateway.Request request = new MockPaymentGateway.Request(idempotencyKey,
                payable.amount(), command.method(), command.cardNumber());

        try {
            return Retries.on(() -> gateway.approve(request),
                    thrown -> thrown instanceof MockPaymentGateway.TimedOut ? "결제사 무응답" : null);
        } catch (MockPaymentGateway.TimedOut e) {
            // 재시도를 다 썼다. 승인이 났는지 우리가 모르는 상태라 주문은 그대로 둔다 —
            // 같은 키로 다시 오면 PG 가 그때 답을 준다.
            throw new ShopException(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE);
        }
    }

    /**
     * 결과를 적고 주문 상태를 옮긴다. <b>멱등 트랜잭션 안이다.</b>
     *
     * <p>{@code payment} 행과 주문 상태가 한 트랜잭션에 있어야 한다. 갈라지면
     * <b>승인은 적혔는데 주문은 결제 대기</b>인 행이 남고, 그 주문은 만료 배치가 취소한다.
     */
    private Result settle(Payable payable, String method, MockPaymentGateway.Result verdict) {
        String status = verdict.approved() ? Result.APPROVED : Result.FAILED;

        jdbc.sql("""
                        insert into payment (order_id, status, method, amount,
                                             approval_number, card_issuer, card_last4, decline_reason)
                        values (:orderId, :status, :method, :amount,
                                :approvalNumber, :cardIssuer, :cardLast4, :declineReason)
                        """)
                .param("orderId", payable.orderId())
                .param("status", status)
                .param("method", method)
                .param("amount", payable.amount())
                .param("approvalNumber", verdict.approvalNumber())
                .param("cardIssuer", verdict.cardIssuer())
                .param("cardLast4", verdict.cardLast4())
                .param("declineReason", verdict.declineReason())
                .update();

        if (verdict.approved()) {
            orderStatuses.markPaid(payable.orderId(), "결제 승인 " + verdict.approvalNumber());
        } else {
            orderStatuses.markPaymentFailed(payable.orderId(), "결제 거절 " + verdict.declineReason());
        }

        return new Result(payable.orderNumber(), status, method, payable.amount(),
                verdict.approvalNumber(), verdict.cardIssuer(), verdict.cardLast4(),
                verdict.declineReason());
    }

    /**
     * 같은 키로 다른 요청이 왔는지 가릴 값(`D11`).
     *
     * <p><b>요청 본문 전체를 안 쓴다.</b> 멱등 표에 남는 것은 이 값의 SHA-256 인데,
     * 카드번호는 자릿수가 정해진 값이라 <b>해시를 되짚을 수 있다</b> —
     * 카드번호의 해시를 보관하는 것은 카드번호를 보관하는 것과 같다(`D2` R18).
     *
     * <p>그래서 남는 것으로 「같은 키로 다른 주문」을 가린다. 같은 주문에 카드만 바꿔 같은 키를
     * 재사용하는 경우는 안 걸리지만, 그때 일어나는 일은 <b>앞의 결과가 재생되는 것</b>이라
     * 두 번 청구되지 않는다. 막을 것은 이미 막힌다.
     */
    private static Fingerprint fingerprint(Command command) {
        return new Fingerprint(command.orderNumber(), command.method());
    }

    private record Fingerprint(String orderNumber, String method) {}
}
