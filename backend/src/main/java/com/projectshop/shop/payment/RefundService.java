package com.projectshop.shop.payment;

import java.time.LocalDate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectshop.shop.auth.PermissionEvaluator;
import com.projectshop.shop.auth.PermissionEvaluator.Target;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.support.BusinessCalendar;
import com.projectshop.shop.support.ExposedNumber;
import com.projectshop.shop.support.Retries;

/**
 * 셀러 묶음 하나의 대금을 돌려준다. <b>요청과 승인이 갈려 있다.</b>
 *
 * <p><b>단위가 둘인 것이 이 클래스의 전제다.</b> 결제는 주문 하나에 승인 하나인데
 * (`V22` {@code payment_approved_unique}) 취소·반품은 셀러 묶음에서 일어난다(`D7`).
 * 그래서 환불은 묶음을 가리키고, 상한만 결제를 본다 — 그 상한은 앱이 아니라
 * {@code assert_refund_within_payment} 가 지킨다(`money-invariants`).
 *
 * <p><b>PG 호출이 트랜잭션 밖이다.</b> {@link PaymentService} 와 같은 이유고(`D11`),
 * 같은 이유로 <b>우리 환불번호를 PG 에 멱등키로 넘긴다</b> — 그 사이에 열리는
 * 「재시도가 두 번 환불하는」 구간을 우리 트랜잭션이 못 닫는다.
 *
 * <p><b>거절이 없다.</b> 결제와 갈리는 자리다. 승인은 카드 판정이라 거절이 정상 결과지만,
 * 환불은 이미 받은 돈을 돌려주는 것이라 결제사가 거부할 사유가 없다 —
 * 넘치는 요청은 PG 가 아니라 우리 상한이 막고, 그 상한은 요청 시점에 이미 걸린다.
 */
@Service
public class RefundService {

    /** 환불 노출 번호의 접두어(`D9`). 주문·셀러 묶음과 형식이 같아서 이것이 종류를 가른다 */
    private static final String NUMBER_PREFIX = "R-";

    /** 환급 기한. 전자상거래법 제18조제2항(`D2` R5) */
    private static final int DUE_BUSINESS_DAYS = 3;


    /** {@code refund.requested_by_type} 에 들어가는 값(`V25`) */
    static final String BY_SYSTEM = "system";

    /**
     * {@code refund.approved_by_type} 에 들어가는 값(`V51`).
     *
     * <p><b>요청자 목록보다 좁다.</b> 승인·반려는 관리자와 시스템만 한다 —
     * {@code V24} 의 do 블록이 {@code payment:refund} 를 관리자 밖으로 못 나가게 지킨다(`D2` R5).
     */
    static final String BY_ADMIN = "admin";

    /**
     * 사유마다 묶음이 어느 상태여야 하나.
     *
     * <p>안 걸면 배송 중인 묶음에 환불 요청이 붙는다. 상태 축(`11a`)으로는 표현이 안 되는데
     * 근거가 요청 본문의 사유와 대상 상태의 <b>조합</b>이라 권한 판정이 볼 것이 아니다.
     *
     * <p>{@code payment_error} 는 상태를 안 본다. 그 사유 자체가
     * <b>상태와 결제가 어긋난 것을 고치는 것</b>이라 맞는 상태를 요구하면 쓸 수가 없다.
     */
    private static final Map<String, String> REQUIRED_BUNDLE_STATUS = Map.of(
            RefundReason.CANCELLED.code(), "cancelled",
            RefundReason.SUPPLY_FAILED.code(), "cancelled",
            RefundReason.ADMIN_CANCELLED.code(), "cancelled",
            RefundReason.WITHDRAWAL.code(), "returned");

    /**
     * 기산점이 <b>묶음이 닫힌 날</b>인 사유.
     *
     * <p>여기 없는 사유는 <b>대금을 지급한 날</b>에서 센다. 목록을 이쪽으로 만든 이유는
     * 새 사유가 늘 때 안전한 쪽으로 떨어지게 하려는 것이다 — 빠뜨리면 기한이 이른 쪽으로
     * 잡히고, 그건 우리가 손해를 보는 방향이지 위반이 아니다.
     *
     * <table>
     *   <caption>법이 정한 기산점</caption>
     *   <tr><th>사유</th><th>조문</th><th>기산점</th></tr>
     *   <tr><td>{@code withdrawal}</td><td>제18조제2항 1호</td><td>재화를 반환받은 날</td></tr>
     *   <tr><td>{@code cancelled}</td><td>제18조제2항 3호</td><td>청약철회를 한 날</td></tr>
     *   <tr><td>{@code supply_failed}</td><td><b>제15조제2항</b></td><td><b>대금을 지급한 날</b></td></tr>
     *   <tr><td>{@code admin_cancelled}</td><td>판단 불가</td><td>이른 쪽인 대금 지급일</td></tr>
     *   <tr><td>{@code payment_error}</td><td>제18조 밖(부당이득)</td><td>법정 기한 없음. 결제일에서 센다</td></tr>
     * </table>
     */
    private static final Set<String> DUE_FROM_CLOSED_AT =
            Set.of(RefundReason.CANCELLED.code(), RefundReason.WITHDRAWAL.code());

    /**
     * 판정에 쓰는 자원 이름과 동작 이름(`V3`·`V24`).
     *
     * <p><b>둘로 갈린 것이 이 워크플로의 뼈대다.</b> 하나면 요청을 여는 순간 승인이 같이 열려서
     * 고객이 남의 환불을 승인한다 — {@code refund_self_approval_check} 는 자기 것만 막는다.
     *
     * <p>{@link #APPROVE} 는 관리자에게만 있고 근거가 법이다(`D2` R5) —
     * <b>제18조제2항 첫 문장 괄호</b>가 「소비자로부터 재화등의 대금을 받은 자」를
     * 통신판매업자에 넣어서 <b>환급 의무자가 우리</b>다.
     *
     * <p><b>제20조의2제3항이 아니다</b>(`Q14` 에서 고쳤다). 그 조항은
     * 「통신판매업자인 통신판매중개자」에게만 걸려서 우리를 직접 지목하지 않는다 —
     * 결론은 같고 근거가 한 칸 비켜 있었다.
     */
    private static final String RESOURCE = "payment";
    private static final String REQUEST = "request_refund";
    private static final String APPROVE = "refund";

    private final JdbcClient jdbc;
    private final MockPaymentGateway gateway;
    private final BusinessCalendar calendar;
    private final PermissionEvaluator evaluator;

    RefundService(JdbcClient jdbc, MockPaymentGateway gateway, BusinessCalendar calendar,
            PermissionEvaluator evaluator) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.calendar = calendar;
        this.evaluator = evaluator;
    }

    /** 돌려줄 항목 하나. {@code quantity} 는 그 주문 항목에서 이번에 돌려줄 개수다 */
    public record Line(long orderItemId, int quantity) {}

    /**
     * 환불 요청.
     *
     * @param lines 비어 있으면 <b>남은 것 전부</b>다. 부분 환불일 때만 채운다
     */
    public record RequestCommand(String sellerOrderNumber, String reasonCode, List<Line> lines,
            String reason) {}

    /**
     * 환불 하나의 지금 모습.
     *
     * <p>열거값은 대문자 스네이크로 올린다(`D5`). 저장값과 다르다.
     */
    public record Refund(String refundNumber, String sellerOrderNumber, String status,
            String reasonCode, long amount, long shippingFeeRefund, OffsetDateTime dueAt,
            String gatewayRefundNumber) {}

    /**
     * 환불을 요청한다. 돈은 아직 안 나간다.
     *
     * <p><b>금액을 요청자가 안 정한다.</b> 주문 항목에 박제된 단가·수수료에서 계산한다 —
     * 받으면 그 값이 맞는지 검사하는 코드가 따로 필요하고, 빠뜨리면 원하는 금액이 나간다
     * (`PaymentService` 가 금액을 안 받는 것과 같은 이유).
     */
    @Transactional
    public Refund request(long userId, RequestCommand command) {
        Bundle bundle = findRefundableBundle(command.sellerOrderNumber());

        // 거부는 404 다(`D5` 의 자원별 표). 403 을 주면 노출 번호를 훑어서 실재하는 묶음의
        // 지도를 그릴 수 있고, 그게 곧 셀러별 거래 건수다 — `OrderActionService` 와 같은 판단.
        if (!evaluator.decide(userId, RESOURCE, REQUEST,
                Target.of(bundle.buyerUserId(), bundle.sellerId())).allowed()) {
            throw new ShopException(ErrorCode.SELLER_ORDER_NOT_FOUND,
                    "그런 셀러 주문이 없다: " + command.sellerOrderNumber());
        }

        return place(requesterOf(userId, bundle), bundle, command);
    }

    /**
     * 이 사람이 이 묶음에 대해 무엇인가.
     *
     * <p><b>역할 이름을 안 쓴다.</b> 같은 사람이 자기 가게에서 살 수도 있어서 역할만으로는
     * 이 요청에서 무엇이었는지가 안 갈린다 — 대상 행과의 관계로 정한다
     * ({@code OrderActionService.actorOf} 와 같은 판단).
     */
    private Requester requesterOf(long userId, Bundle bundle) {
        if (userId == bundle.buyerUserId()) {
            return new Requester("customer", userId);
        }

        boolean member = jdbc.sql("""
                        select exists (select 1 from seller_member
                                        where user_id = :userId and seller_id = :sellerId)
                        """)
                .param("userId", userId)
                .param("sellerId", bundle.sellerId())
                .query(Boolean.class)
                .single();

        return new Requester(member ? "seller" : "admin", userId);
    }

    /**
     * 배치가 요청을 만든다. <b>판정을 안 지난다.</b>
     *
     * <p><b>법이 청약철회만으로 환급 의무를 발생시킨다</b>(`D2` R5) — 별도 요청을 요구할 근거가
     * 없어서, 사람이 안 내면 3영업일이 그냥 흐르고 지연배상금이 붙는다.
     * 그것을 닫는 것이 {@link RefundSweeper} 이고 이 입구가 그 자리다.
     *
     * <p>판정이 없는 이유는 <b>부르는 쪽이 사람이 아니어서</b>다. 권한은 「이 사람이 이것을 해도
     * 되나」를 묻는데 여기에는 사람이 없다. 대신 <b>패키지 밖에서 못 부른다</b> —
     * 공개하면 HTTP 입구가 이것을 부르는 날 판정이 통째로 빠진다.
     */
    @Transactional
    Refund requestBySystem(Bundle bundle, RequestCommand command) {
        return place(Requester.system(), bundle, command);
    }

    /**
     * <b>금액을 요청자가 안 정한다.</b> 주문 항목에 박제된 단가·수수료에서 계산한다 —
     * 받으면 그 값이 맞는지 검사하는 코드가 따로 필요하고, 빠뜨리면 원하는 금액이 나간다
     * ({@code PaymentService} 가 금액을 안 받는 것과 같은 이유).
     */
    private Refund place(Requester requester, Bundle bundle, RequestCommand command) {
        requireBundleStatus(bundle, command.reasonCode());

        List<Item> items = itemsOf(bundle.sellerOrderId());
        List<Portion> portions = resolvePortions(items, command.lines());
        long shippingRefund = shippingFeeRefund(bundle, items, portions);

        long amount = portions.stream().mapToLong(Portion::amount).sum() + shippingRefund;

        String refundNumber = insertRefund(requester, bundle, command, amount, shippingRefund);
        insertItems(refundNumber, portions);

        return read(refundNumber);
    }

    /**
     * 요청을 낸 주체(`V25`).
     *
     * <p>{@code order_status_history} 의 {@code actor_type} 과 같은 목록을 쓴다 — 둘이 다르면
     * 「이 행을 누가 만들었나」를 묻는 코드가 표마다 갈린다.
     *
     * @param userId 시스템이면 {@code null}. 제약이 그 짝을 강제한다
     */
    record Requester(String type, Long userId) {

        static Requester system() {
            return new Requester(BY_SYSTEM, null);
        }
    }

    /**
     * 요청을 승인하고 돈을 내보낸다.
     *
     * <p><b>{@code @Transactional} 이 없어야 한다.</b> 이 메서드 안에서 PG 를 부른다(`D11`).
     *
     * <p>PG 가 성공했는데 우리 갱신이 실패하면 <b>돈은 나갔고 기록은 없는</b> 구간이 열린다.
     * 그것을 닫는 것은 재시도이고, 재시도가 두 번 환불하지 않는 근거가
     * <b>환불번호를 멱등키로 넘긴 것</b>이다 — PG 가 같은 키에 같은 답을 준다.
     */
    public Refund approve(long userId, String refundNumber, String reason) {
        Pending pending = findPending(userId, refundNumber);
        requireNotSelf(userId, pending);

        return settle(refundNumber, pending, userId, BY_ADMIN, blankToNull(reason));
    }

    /**
     * 스위퍼가 자기가 만든 요청을 승인한다. <b>판정을 안 지난다.</b>
     *
     * <p><b>승인 대기가 법정 기한을 먹기 때문이다</b>(`D2` R5, 전자상거래법 제18조제2항).
     * {@code due_at} 은 요청이 만들어진 시각이 아니라 사건이 일어난 날에서 세므로, 사람이
     * 안 누르는 동안 3영업일이 그냥 흐르고 {@code 12a-4} 가 <b>우리에게</b> 연 15%를 물린다.
     * 법은 요청·승인 2단계를 요구하지 않는다 — 대기는 {@code 12a-1} 이 만든 상태다.
     *
     * <p>판정이 없는 이유는 {@link #requestBySystem} 과 같다 — <b>부르는 쪽이 사람이 아니다.</b>
     * 자기승인 검사도 없다: 낸 것이 시스템이라 지목할 사람이 양쪽 다 없다.
     *
     * <p><b>시스템이 만든 요청만 받는다.</b> 스위퍼는 항목을 안 고르므로 그 요청은 전량 환불이고
     * 사람이 판단할 여지가 애초에 없다. 사람이 낸 요청은 부분 환불일 수 있어 검토가 남는다 —
     * {@code refund_system_approval_scope_check} 가 그것을 한 층 아래에서 막는다(`D23` 축 2).
     *
     * <p>{@code @Transactional} 이 없는 것은 {@link #approve} 와 같은 이유다. PG 를 부른다.
     */
    Refund approveBySystem(String refundNumber) {
        Pending pending = loadPending(refundNumber);
        requireRequested(pending);

        return settle(refundNumber, pending, null, BY_SYSTEM, null);
    }

    /**
     * 돈을 내보내고 결정을 적는다. 사람이 승인하든 시스템이 승인하든 여기가 한 곳이다.
     *
     * <p><b>가르면 이자 계산이 두 벌이 된다.</b> 그 둘이 어긋나면 어느 쪽이 법정 금액인지를
     * 우리가 못 정한다({@link #delayInterest} 가 한 곳인 것과 같은 이유).
     *
     * @param approverUserId 시스템이면 {@code null}. {@code refund_approved_by_user_check} 가 짝을 강제한다
     * @param approverType   {@link #BY_ADMIN} 또는 {@link #BY_SYSTEM}
     */
    private Refund settle(String refundNumber, Pending pending, Long approverUserId,
            String approverType, String decisionReason) {
        // 시각을 한 번 잡아서 이자 계산과 `decided_at` 이 같은 순간을 쓰게 한다.
        // 각자 `now()` 를 부르면 그 사이의 간격만큼 이자와 기록이 어긋난다.
        OffsetDateTime decidedAt = OffsetDateTime.now();
        long delayInterest = delayInterest(pending.amount(), pending.dueAt(), decidedAt);

        // **이자를 더한 금액을 보낸다.** 제18조제3항이 「이자를 더한 금액의 환급 조치」라고 해서,
        // 이자를 기록만 하고 안 보내면 조치를 안 한 것이다.
        MockPaymentGateway.RefundResult result =
                askGateway(refundNumber, pending.amount() + delayInterest);

        int updated = jdbc.sql("""
                        update refund
                           set status                = :approved,
                               approved_by_type      = :approverType,
                               approved_by_user_id   = :userId,
                               decided_at            = :decidedAt,
                               delay_interest        = :delayInterest,
                               gateway_refund_number = :gatewayNumber,
                               updated_at            = now()
                         where refund_number = :number and status = :requested
                        """)
                .param("approved", RefundStatus.APPROVED.code())
                .param("decidedAt", decidedAt)
                .param("delayInterest", delayInterest)
                .param("approverType", approverType)
                .param("userId", approverUserId)
                .param("gatewayNumber", result.refundNumber())
                .param("number", refundNumber)
                .param("requested", RefundStatus.REQUESTED.code())
                .update();

        // 0 이면 그 사이에 남이 처리했다. 조건부 UPDATE 라 둘이 동시에 와도 하나만 통과한다 —
        // 읽고 나서 쓰는 사이를 우리가 못 잠그므로 갱신 자체가 판정이어야 한다.
        if (updated == 0) {
            throw new ShopException(ErrorCode.REFUND_ALREADY_DECIDED);
        }
        writeNote(refundNumber, null, decisionReason);
        return read(refundNumber);
    }

    /**
     * 요청을 반려한다. 돈이 안 나가므로 PG 를 안 부른다.
     *
     * <p>사유가 필수다. 반려는 고객에게 답해야 하는 결정이라 왜 그랬는지가 없으면 할 말이 없다.
     * {@code refund_rejection_reason_check} 가 같은 것을 한 층 아래에서 막는다(`D23` 축 2).
     */
    @Transactional
    public Refund reject(long userId, String refundNumber, String reason) {
        Pending pending = findPending(userId, refundNumber);
        requireNotSelf(userId, pending);

        if (blankToNull(reason) == null) {
            throw new ShopException(ErrorCode.TRANSITION_REASON_REQUIRED, "반려에는 사유가 필요하다");
        }

        int updated = jdbc.sql("""
                        update refund
                           set status              = :rejected,
                               approved_by_type    = :approverType,
                               approved_by_user_id = :userId,
                               decided_at          = now(),
                               updated_at          = now()
                         where refund_number = :number and status = :requested
                        """)
                .param("rejected", RefundStatus.REJECTED.code())
                .param("approverType", BY_ADMIN)
                .param("userId", userId)
                .param("number", refundNumber)
                .param("requested", RefundStatus.REQUESTED.code())
                .update();

        if (updated == 0) {
            throw new ShopException(ErrorCode.REFUND_ALREADY_DECIDED);
        }
        writeNote(refundNumber, null, reason);
        return read(refundNumber);
    }

    /**
     * 환불할 묶음. {@code closedAt} 이 환급 기한의 기산점이다.
     *
     * <p>{@code buyerUserId}·{@code sellerId} 는 판정에 쓴다 — 스코프 {@code own} 과
     * {@code seller} 가 각각 그 둘을 본다(`D6`).
     */
    record Bundle(long sellerOrderId, long orderId, String sellerOrderNumber,
            String status, long shippingFee, OffsetDateTime closedAt,
            long buyerUserId, long sellerId) {}

    /**
     * 돌려줄 돈이 있는 묶음인가.
     *
     * <p><b>주문 상태가 아니라 결제 승인을 본다.</b> 이 둘이 갈리는 경우가 실제로 있다 —
     * 결제 조회와 기록 사이에 만료 배치가 끼면 PG 에는 승인이 남고 우리 주문은
     * {@code payment_expired} 가 된다({@code PaymentService.findPayable} 이 남긴 구멍).
     * {@code paid} 만 받으면 <b>그 돈을 영영 못 돌려준다.</b>
     *
     * <p>{@code assert_refund_within_payment} 가 같은 것을 본다. 여기서 막는 것은
     * 이유를 담은 422 를 주기 위해서고, 트리거는 다른 입구를 막는다(`D23` 축 2 의 두 겹).
     */
    Bundle findRefundableBundle(String sellerOrderNumber) {
        Bundle bundle = jdbc.sql("""
                        select so.seller_order_id, so.order_id, so.seller_order_number,
                               so.status, so.shipping_fee, so.closed_at, so.seller_id,
                               o.user_id as buyer_user_id
                          from seller_order so
                          join shop_order o on o.order_id = so.order_id
                         where so.seller_order_number = :number
                        """)
                .param("number", sellerOrderNumber)
                .query((rs, rowNum) -> new Bundle(
                        rs.getLong("seller_order_id"),
                        rs.getLong("order_id"),
                        rs.getString("seller_order_number"),
                        rs.getString("status"),
                        rs.getLong("shipping_fee"),
                        rs.getObject("closed_at", OffsetDateTime.class),
                        rs.getLong("buyer_user_id"),
                        rs.getLong("seller_id")))
                .optional()
                .orElseThrow(() -> new ShopException(ErrorCode.SELLER_ORDER_NOT_FOUND));

        boolean paid = jdbc.sql("""
                        select exists (select 1 from payment
                                        where order_id = :orderId and status = 'approved')
                        """)
                .param("orderId", bundle.orderId())
                .query(Boolean.class)
                .single();

        if (!paid) {
            throw new ShopException(ErrorCode.REFUND_NOT_PAYABLE,
                    "승인된 결제가 없는 주문이다: " + sellerOrderNumber);
        }
        return bundle;
    }

    private static void requireBundleStatus(Bundle bundle, String reasonCode) {
        String required = REQUIRED_BUNDLE_STATUS.get(reasonCode);

        if (required != null && !required.equals(bundle.status())) {
            throw new ShopException(ErrorCode.ORDER_TRANSITION_NOT_ALLOWED,
                    "%s 환불은 %s 상태에서만 된다. 지금은 %s 다"
                            .formatted(reasonCode, required, bundle.status()));
        }
    }

    /** 주문 항목 하나와 그 항목에서 이미 나간 누계 */
    private record Item(long orderItemId, int quantity, long unitPriceInclVat,
            long commissionAmount, int refundedQuantity, long refundedCommission) {

        int remaining() {
            return quantity - refundedQuantity;
        }
    }

    /**
     * 묶음의 항목과 누계를 읽는다.
     *
     * <p>반려된 요청은 누계에 안 넣는다. 트리거의 상한 계산과 같은 조건이라야
     * <b>앱이 통과시킨 것을 트리거가 거부하는</b> 일이 안 생긴다.
     */
    private List<Item> itemsOf(long sellerOrderId) {
        return jdbc.sql("""
                        select oi.order_item_id, oi.quantity, oi.unit_price_incl_vat,
                               oi.commission_amount,
                               coalesce(done.refunded_quantity, 0)   as refunded_quantity,
                               coalesce(done.refunded_commission, 0) as refunded_commission
                          from order_item oi
                          left join (select ri.order_item_id,
                                            sum(ri.quantity)          as refunded_quantity,
                                            sum(ri.commission_refund) as refunded_commission
                                       from refund_item ri
                                       join refund r on r.refund_id = ri.refund_id
                                      where r.status <> :rejected
                                      group by ri.order_item_id) done
                                 on done.order_item_id = oi.order_item_id
                         where oi.seller_order_id = :sellerOrderId
                         order by oi.order_item_id
                        """)
                .param("rejected", RefundStatus.REJECTED.code())
                .param("sellerOrderId", sellerOrderId)
                .query((rs, rowNum) -> new Item(
                        rs.getLong("order_item_id"),
                        rs.getInt("quantity"),
                        rs.getLong("unit_price_incl_vat"),
                        rs.getLong("commission_amount"),
                        rs.getInt("refunded_quantity"),
                        rs.getLong("refunded_commission")))
                .list();
    }

    /** 이번에 돌려줄 한 항목 */
    private record Portion(long orderItemId, int quantity, long amount, long commissionRefund) {}

    /**
     * 무엇을 몇 개 돌려줄지 정하고 금액을 계산한다.
     *
     * <p>요청이 항목을 안 주면 <b>남은 것 전부</b>다. 전액 환불이 흔한 경로라
     * 화면이 항목을 세어 보내게 하면 그 계산이 두 곳에 생긴다.
     */
    private static List<Portion> resolvePortions(List<Item> items, List<Line> lines) {
        Map<Long, Item> byId = new LinkedHashMap<>();
        for (Item item : items) {
            byId.put(item.orderItemId(), item);
        }

        List<Line> wanted = lines == null || lines.isEmpty()
                ? items.stream().filter(item -> item.remaining() > 0)
                        .map(item -> new Line(item.orderItemId(), item.remaining())).toList()
                : lines;

        if (wanted.isEmpty()) {
            throw new ShopException(ErrorCode.REFUND_EXCEEDS_LIMIT, "이미 전부 환불된 묶음이다");
        }

        List<Portion> portions = new ArrayList<>(wanted.size());
        for (Line line : wanted) {
            Item item = byId.get(line.orderItemId());

            // 남의 묶음 항목을 끼워 넣는 요청이다. 없는 항목과 같은 답을 준다 —
            // 가르면 항목 번호를 두드려서 남의 주문 구성을 셀 수 있다(`D5`).
            if (item == null) {
                throw new ShopException(ErrorCode.ORDER_NOT_FOUND,
                        "이 묶음의 항목이 아니다: " + line.orderItemId());
            }
            if (line.quantity() <= 0 || line.quantity() > item.remaining()) {
                throw new ShopException(ErrorCode.REFUND_EXCEEDS_LIMIT,
                        "환불할 수 있는 수량은 %d 다: order_item_id=%d"
                                .formatted(item.remaining(), item.orderItemId()));
            }
            portions.add(new Portion(item.orderItemId(), line.quantity(),
                    item.unitPriceInclVat() * line.quantity(), commissionRefund(item, line.quantity())));
        }
        return portions;
    }

    /**
     * 이 항목에서 포기할 수수료.
     *
     * <p><b>절사 잔액을 마지막 수량에 몰아 준다</b>(사용자 선택). {@code commission_amount} 가
     * 항목 단위로 이미 잘린 값이라(`D8`) 수량으로 또 나누면 1원씩 남는데, 그것을 그대로 두면
     * 통째로 환불했는데 수수료가 덜 돌아가서 <b>정산에 우리 몫이 남는다.</b>
     *
     * <p>마지막 수량인지는 <b>누계로 판단한다</b> — 3개를 1개씩 세 번 돌려주는 것과
     * 한 번에 세 개 돌려주는 것이 같은 값이어야 하고, 그 등식을 테스트가 지킨다
     * (`money-invariants` 「통째로 환불하면 {@code commission_refund = commission_amount}」).
     */
    private static long commissionRefund(Item item, int quantity) {
        boolean last = item.refundedQuantity() + quantity == item.quantity();

        return last
                ? item.commissionAmount() - item.refundedCommission()
                : item.commissionAmount() * quantity / item.quantity();
    }

    /**
     * 배송비를 돌려주나.
     *
     * <p><b>묶음이 이번 환불로 비워질 때만</b> 전액이다. 배송비는 {@code seller_order} 단위라
     * 항목별로 못 나눈다 — 부분 환불에서 얼마를 돌려줄지 정할 근거가 없다.
     *
     * <p>이미 나간 적이 있으면 안 준다. 전부 환불한 뒤 그 요청이 반려되고 다시 요청하는 경로에서
     * 반려된 것은 누계에서 빠지므로, 이 검사도 같은 조건으로 세야 두 번 안 나간다.
     */
    private long shippingFeeRefund(Bundle bundle, List<Item> items, List<Portion> portions) {
        if (bundle.shippingFee() == 0) {
            return 0;
        }

        Map<Long, Integer> now = new LinkedHashMap<>();
        for (Portion portion : portions) {
            now.merge(portion.orderItemId(), portion.quantity(), Integer::sum);
        }

        boolean emptied = items.stream().allMatch(item ->
                item.refundedQuantity() + now.getOrDefault(item.orderItemId(), 0) == item.quantity());

        if (!emptied) {
            return 0;
        }

        long alreadyRefundedShipping = jdbc.sql("""
                        select coalesce(sum(shipping_fee_refund), 0)
                          from refund
                         where seller_order_id = :sellerOrderId and status <> :rejected
                        """)
                .param("sellerOrderId", bundle.sellerOrderId())
                .param("rejected", RefundStatus.REJECTED.code())
                .query(Long.class)
                .single();

        return alreadyRefundedShipping > 0 ? 0 : bundle.shippingFee();
    }

    /**
     * 환급 기한을 박제한다(`D2` R5). <b>기산점이 사유마다 다르다.</b>
     *
     * <p><b>요청 시각에서 세면 안 된다.</b> 늦게 요청할수록 기한이 밀려서 법이 정한 것보다
     * 늦게 줘도 안 늦은 것이 된다. 그래서 사건이 일어난 날에서 센다.
     *
     * <p>어느 사건이냐가 조문에 따라 갈린다({@link #DUE_FROM_CLOSED_AT} 의 표).
     * <ul>
     *   <li><b>고객 취소·반품</b> — 청약철회에 따른 환급이라 제18조제2항이고,
     *       기산점이 묶음이 닫힌 날이다({@code closed_at})</li>
     *   <li><b>셀러 공급 불능</b> — 청약철회가 아니라 <b>제15조제2항</b>이고,
     *       기산점이 <b>대금을 지급한 날</b>이다. 취소 시각에서 세면 결제일보다 뒤로 밀린다</li>
     * </ul>
     *
     * <p><b>결제일을 못 찾으면 오늘에서 센다.</b> 승인이 없는 주문은 애초에 여기 못 오지만
     * ({@code findRefundableBundle}), 그 검사가 언젠가 갈릴 수 있고 그때 기한이 <b>없는 것보다는
     * 이른 것</b>이 낫다.
     */
    private OffsetDateTime dueAt(Bundle bundle, String reasonCode) {
        LocalDate from = DUE_FROM_CLOSED_AT.contains(reasonCode)
                ? dateOf(bundle.closedAt())
                : dateOf(paidAt(bundle.orderId()));

        return BusinessCalendar.endOfDay(calendar.plusBusinessDays(from, DUE_BUSINESS_DAYS));
    }

    /** 대금을 지급한 날. 승인은 주문마다 하나뿐이다({@code payment_approved_unique}) */
    private OffsetDateTime paidAt(long orderId) {
        return jdbc.sql("""
                        select created_at from payment
                         where order_id = :orderId and status = 'approved'
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> rs.getObject("created_at", OffsetDateTime.class))
                .optional()
                .orElse(null);
    }

    /** 값이 없으면 오늘. 기한이 없는 것보다 이른 것이 낫다 */
    private static LocalDate dateOf(OffsetDateTime moment) {
        return moment == null
                ? LocalDate.now(BusinessCalendar.ZONE)
                : moment.atZoneSameInstant(BusinessCalendar.ZONE).toLocalDate();
    }

    private String insertRefund(Requester requester, Bundle bundle, RequestCommand command,
            long amount, long shippingRefund) {

        OffsetDateTime dueAt = dueAt(bundle, command.reasonCode());

        String refundNumber = ExposedNumber.insertWith(NUMBER_PREFIX, "환불번호", number -> jdbc.sql("""
                                insert into refund (refund_number, seller_order_id, status,
                                                    reason_code, amount, shipping_fee_refund,
                                                    requested_by_type, requested_by_user_id,
                                                    due_at)
                                values (:number, :sellerOrderId, :status, :reasonCode, :amount,
                                        :shippingRefund, :byType, :userId, :dueAt)
                                returning refund_number
                                """)
                .param("number", number)
                .param("sellerOrderId", bundle.sellerOrderId())
                .param("status", RefundStatus.REQUESTED.code())
                .param("reasonCode", command.reasonCode())
                .param("amount", amount)
                .param("shippingRefund", shippingRefund)
                .param("byType", requester.type())
                .param("userId", requester.userId())
                .param("dueAt", dueAt)
                .query(String.class)
                .single());

        writeNote(refundNumber, blankToNull(command.reason()), null);
        return refundNumber;
    }

    private void insertItems(String refundNumber, List<Portion> portions) {
        for (Portion portion : portions) {
            jdbc.sql("""
                            insert into refund_item (refund_id, order_item_id, quantity,
                                                     amount, commission_refund)
                            select r.refund_id, :orderItemId, :quantity, :amount, :commission
                              from refund r
                             where r.refund_number = :number
                            """)
                    .param("orderItemId", portion.orderItemId())
                    .param("quantity", portion.quantity())
                    .param("amount", portion.amount())
                    .param("commission", portion.commissionRefund())
                    .param("number", refundNumber)
                    .update();
        }
    }

    /** 아직 처리 안 된 요청. 뒤 둘은 판정에 쓴다 */
    /**
     * 사유 글을 { refund_note} 에 남긴다.
     *
     * <p><b>5년 표에 안 둔다</b>(`5i-2`). 사람이 쓴 글이라 「집 앞에 두세요, 010-…」 같은 것이
     * 섞여 들어오고, 그것이 섞이면 거래기록이 5년을 사는 동안 그 연락처도 같이 산다.
     * 표를 가른 것은 { order_shipping}·{ payment_card} 가 쓴 수단과 같다.
     *
     * <p><b>빈 글은 행을 안 만든다.</b> 사유가 선택인 자리가 있어서, 빈 행을 만들면
     * 파기가 셀 대상만 늘고 「사유가 있었나」에 답이 흐려진다.
     */
    private void writeNote(String refundNumber, String requestReason, String decisionReason) {
        if (requestReason == null && decisionReason == null) {
            return;
        }

        jdbc.sql("""
                        insert into refund_note (refund_id, request_reason, decision_reason)
                        select refund_id, :requestReason, :decisionReason
                          from refund where refund_number = :number
                        on conflict (refund_id) do update
                           set request_reason  = coalesce(excluded.request_reason,
                                                          refund_note.request_reason),
                               decision_reason = coalesce(excluded.decision_reason,
                                                          refund_note.decision_reason),
                               updated_at      = now()
                        """)
                .param("number", refundNumber)
                .param("requestReason", requestReason)
                .param("decisionReason", decisionReason)
                .update();
    }

    /** 지연배상금 이율. 연 100분의 15(전자상거래법 시행령 제21조의3, `D2` R5) */
    private static final BigDecimal DELAY_RATE = new BigDecimal("0.15");

    /** 이율이 연 단위라 일수로 쪼갤 때 나누는 수. 윤년을 안 가른다 */
    private static final BigDecimal DAYS_IN_YEAR = new BigDecimal(365);

    /**
     * 기한을 넘긴 만큼 붙는 지연배상금.
     *
     * <p><b>계산이 여기 한 곳이다.</b> 화면이 다시 계산하면 청구액과 표시액이 갈리고,
     * 갈리는 쪽이 법정 금액이라 어느 쪽이 맞는지를 우리가 못 정한다. 결과를 { refund} 에
     * 박제해서 나중에 이율이 바뀌어도 지나간 건의 금액이 안 움직이게 한다.
     *
     * <p><b>일 단위로 세고 하루가 안 찼어도 1일로 본다</b>(사용자 선택). 법이 「기간」이라고만 해서
     * 실무 관례를 따랐다 — 한 시간 늦은 것에 0원을 물리면 「늦었는데 배상금이 0」이 된다.
     *
     * <p><b>원 미만은 올린다</b>(사용자 선택). `D8` 은 버림이지만 그것은 <b>우리가 받는 돈</b>의 규칙이고,
     * 이것은 우리가 늦어서 <b>물어 주는 돈</b>이라 방향이 반대다. 버리면 법이 정한 금액보다 적게 준다.
     *
     *  amount   돌려줄 대금. 이자는 여기에 안 들어 있다
     *  dueAt    환급 기한. `12a-3` 이 사유별 기산점으로 박아 둔 값이다
     *  decidedAt 실제로 조치한 시각
     *  붙는 이자. 기한 안에 처리했으면 0
     */
    static long delayInterest(long amount, OffsetDateTime dueAt, OffsetDateTime decidedAt) {
        if (!decidedAt.isAfter(dueAt)) {
            return 0;
        }

        long days = ChronoUnit.DAYS.between(dueAt, decidedAt);
        if (Duration.between(dueAt, decidedAt).minusDays(days).isPositive()) {
            days++;
        }

        return BigDecimal.valueOf(amount)
                .multiply(DELAY_RATE)
                .multiply(BigDecimal.valueOf(days))
                .divide(DAYS_IN_YEAR, 0, RoundingMode.CEILING)
                .longValueExact();
    }

    private record Pending(long requestedByUserId, long amount, RefundStatus status,
            long buyerUserId, long sellerId, OffsetDateTime dueAt) {}

    /**
     * 처리할 수 있는 요청인가.
     *
     * <p><b>판정이 상태 검사보다 앞이다.</b> 순서를 바꾸면 남의 환불에 승인을 시도한 사람이
     * 「이미 처리됐다」(409)를 받는데, 그것만으로 <b>그 번호가 실재한다는 것</b>이 드러난다.
     */
    private Pending findPending(long userId, String refundNumber) {
        Pending pending = loadPending(refundNumber);

        if (!evaluator.decide(userId, RESOURCE, APPROVE,
                Target.of(pending.buyerUserId(), pending.sellerId())).allowed()) {
            throw notFound(refundNumber);
        }

        requireRequested(pending);
        return pending;
    }

    /**
     * 행을 읽기만 한다. <b>판정도 상태 검사도 안 한다.</b>
     *
     * <p>갈라 둔 이유는 시스템 승인에 사람이 없어서다({@link #approveBySystem}) —
     * 판정은 「이 사람이 이것을 해도 되나」를 묻는데 물을 사람이 없다.
     */
    private Pending loadPending(String refundNumber) {
        return jdbc.sql("""
                        select r.requested_by_user_id, r.amount, r.status, so.seller_id,
                               o.user_id as buyer_user_id, r.due_at
                          from refund r
                          join seller_order so on so.seller_order_id = r.seller_order_id
                          join shop_order o    on o.order_id = so.order_id
                         where r.refund_number = :number
                        """)
                .param("number", refundNumber)
                .query((rs, rowNum) -> new Pending(
                        rs.getLong("requested_by_user_id"),
                        rs.getLong("amount"),
                        RefundStatus.of(rs.getString("status")),
                        rs.getLong("buyer_user_id"),
                        rs.getLong("seller_id"),
                        rs.getObject("due_at", OffsetDateTime.class)))
                .optional()
                .orElseThrow(() -> notFound(refundNumber));
    }

    /**
     * 아직 아무도 안 처리한 요청인가. 처리된 것을 다시 처리하면 돈이 두 번 나간다.
     *
     * <p><b>{@link Pending} 이 상태를 열거형으로 든다</b>(`43a-16`). 문자열이던 동안에는
     * {@code RefundStatus.APPROVED} 와 {@code PaymentStatus.APPROVED} 가 <b>글자가 같아서</b>
     * 한쪽 값을 다른 쪽 자리에 넣어도 컴파일이 통과했다.
     */
    private static void requireRequested(Pending pending) {
        if (pending.status() != RefundStatus.REQUESTED) {
            throw new ShopException(ErrorCode.REFUND_ALREADY_DECIDED,
                    "이미 " + pending.status().code() + " 인 요청이다");
        }
    }

    private static ShopException notFound(String refundNumber) {
        return new ShopException(ErrorCode.REFUND_NOT_FOUND, "그런 환불 요청이 없다: " + refundNumber);
    }

    /**
     * 자기가 낸 요청은 자기가 승인·반려 못 한다.
     *
     * <p>{@code refund_self_approval_check} 가 같은 것을 막는다. 여기 있는 것은 이유를 담은
     * 403 을 주기 위해서고, 제약은 다른 입구를 막는다 — 앱만 있으면 새 입구가 빠뜨리고,
     * 제약만 있으면 사용자가 받는 것이 500 이다(`D23` 축 2, `7c` 와 같은 두 겹).
     */
    private static void requireNotSelf(long userId, Pending pending) {
        if (pending.requestedByUserId() == userId) {
            throw new ShopException(ErrorCode.REFUND_SELF_APPROVAL);
        }
    }

    /**
     * PG 에 환불을 요청한다. 응답이 없으면 <b>같은 환불번호로</b> 다시 부른다.
     *
     * <p>{@code Retries.onConflict} 이 아닌 이유는 그쪽이 SQLSTATE 로 가르기 때문이다 —
     * 결제사 무응답은 DB 예외가 아니라 그 판정에 안 걸린다({@code PaymentService.askGateway} 와 같다).
     */
    private MockPaymentGateway.RefundResult askGateway(String refundNumber, long amount) {
        try {
            return Retries.on(() -> gateway.refund(refundNumber, amount),
                    thrown -> thrown instanceof MockPaymentGateway.TimedOut ? "결제사 무응답" : null);
        } catch (MockPaymentGateway.TimedOut e) {
            // 재시도를 다 썼다. 나갔는지 우리가 모르는 상태라 요청은 requested 로 둔다 —
            // 같은 번호로 다시 승인하면 PG 가 그때 답을 준다. 기한은 계속 흐르고 그것이 맞다.
            throw new ShopException(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE);
        }
    }

    private Refund read(String refundNumber) {
        return jdbc.sql("""
                        select r.refund_number, so.seller_order_number, r.status, r.reason_code,
                               r.amount, r.shipping_fee_refund, r.due_at, r.gateway_refund_number
                          from refund r
                          join seller_order so on so.seller_order_id = r.seller_order_id
                         where r.refund_number = :number
                        """)
                .param("number", refundNumber)
                .query((rs, rowNum) -> new Refund(
                        rs.getString("refund_number"),
                        rs.getString("seller_order_number"),
                        upper(rs.getString("status")),
                        upper(rs.getString("reason_code")),
                        rs.getLong("amount"),
                        rs.getLong("shipping_fee_refund"),
                        rs.getObject("due_at", OffsetDateTime.class),
                        rs.getString("gateway_refund_number")))
                .single();
    }

    /** 응답의 열거값은 대문자 스네이크다(`D5`). 저장값을 그대로 올리면 그 규칙이 깨진다 */
    private static String upper(String code) {
        return code == null ? null : code.toUpperCase(Locale.ROOT);
    }

    /** 빈 문자열과 없음을 가르지 않는다(`D23` 「빈 값에 뜻을 싣지 않는다」) */
    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }
}
