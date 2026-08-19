package com.projectshop.shop.payment;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;

/**
 * 환불을 요청하고 처리하는 입구.
 *
 * <p><b>최상위 경로다.</b> {@code refund_number} 가 전역에서 하나를 가리키므로
 * ({@code refund_number_unique}, `D9`) 앞에 주문번호나 묶음번호를 붙여도 대상이 안 좁아진다 —
 * {@code ShipmentController} 와 같은 판단이다.
 *
 * <p><b>요청과 처리가 다른 권한이다</b>(`V24`). {@code payment:request_refund} 는 고객·셀러·관리자가
 * 갖고, 승인·반려의 {@code payment:refund} 는 관리자만 갖는다. 하나로 두면 요청을 여는 순간
 * 승인이 같이 열려서 고객이 남의 환불을 승인한다 — {@code refund_self_approval_check} 는
 * <b>자기 것만</b> 막는다.
 *
 * <p><b>승인이 관리자에게만 있는 근거는 법이다</b>(`D2` R5). 전자상거래법 제18조제2항 괄호가
 * 통신판매업자에 「소비자로부터 재화등의 대금을 받은 자」를 포함시키고, 결제가 주문 단위라
 * <b>우리가 그 자</b>다. 제20조의2제3항은 중개자라고 고지해도 제17조·제18조 책임을 못 면한다고 한다.
 * 셀러에게 승인을 넘기면 우리 법적 의무의 이행 여부를 남이 정하게 된다.
 *
 * <p><b>멱등키를 안 받는다.</b> {@code PaymentController} 와 다른 자리인데, 환불은 요청과 승인이
 * 갈려 있어서 <b>중복이 상태로 막힌다</b> — 두 번째 승인은 조건부 UPDATE 가 0행을 고쳐 409 가 되고,
 * PG 쪽 중복은 우리 환불번호를 멱등키로 넘긴 것이 막는다({@link RefundService#approve}).
 * 요청이 두 번 들어오는 것은 상한 검사가 잡는다.
 */
@RestController
@RequestMapping("/api/refunds")
public class RefundController {

    private final RefundService refunds;
    private final RefundQuery query;

    RefundController(RefundService refunds, RefundQuery query) {
        this.refunds = refunds;
        this.query = query;
    }

    /**
     * 돌려줄 항목 하나.
     *
     * @param quantity 그 주문 항목에서 이번에 돌려줄 개수. 남은 수량을 넘으면 422
     */
    public record LineRequest(@Positive long orderItemId, @Positive int quantity) {
    }

    /**
     * 환불 요청.
     *
     * <p><b>금액이 없다.</b> 돌려줄 돈은 주문 항목에 박제된 단가·수수료에서 서버가 계산한다 —
     * 받으면 그 값이 맞는지 검사하는 코드가 따로 필요하고, 빠뜨리면 원하는 금액이 나간다
     * ({@code PaymentController} 가 금액을 안 받는 것과 같은 이유).
     *
     * @param lines  비어 있으면 <b>남은 것 전부</b>다. 전액 환불이 흔한 경로라 화면이 항목을
     *               세어 보내게 하면 그 계산이 두 곳에 생긴다
     * @param reason 요청자가 적는 사유. <b>응답으로 다시 안 나간다</b>(`RefundQuery`)
     */
    public record RefundRequest(
            @NotBlank @Size(max = 30) String sellerOrderNumber,
            @NotBlank @Pattern(regexp = "cancelled|withdrawal|payment_error") String reasonCode,
            List<@Valid LineRequest> lines,
            @Size(max = 500) String reason) {
    }

    /**
     * 승인·반려에 붙이는 것.
     *
     * <p><b>반려는 사유가 필수다.</b> 여기서 {@code @NotBlank} 를 안 거는 이유는 승인과 record 를
     * 공유해서고, 반려 쪽 검사는 {@link RefundService#reject} 와
     * {@code refund_rejection_reason_check} 두 겹이 한다(`D23` 축 2).
     */
    public record DecisionRequest(@Size(max = 500) String reason) {
    }

    /**
     * 환불을 요청한다. 돈은 아직 안 나간다.
     *
     * <p><b>201 이다.</b> 요청이라는 자원이 생기고 그것을 가리키는 경로가 있다(`D5` 「상태 코드」).
     */
    @PostMapping
    public ResponseEntity<RefundService.Refund> request(
            @AuthenticationPrincipal ShopUser user,
            @Valid @RequestBody RefundRequest request) {

        RefundService.Refund refund = refunds.request(user.id(),
                new RefundService.RequestCommand(request.sellerOrderNumber(), request.reasonCode(),
                        toLines(request.lines()), request.reason()));

        return ResponseEntity.created(URI.create("/api/refunds/" + refund.refundNumber()))
                .body(refund);
    }

    /**
     * 승인한다. <b>여기서 실제로 돈이 나간다.</b>
     *
     * <p>200 이다. 옮긴 뒤의 모양을 같이 내리는 것이 {@code ShipmentController} 의 204 와 다른데,
     * <b>이 응답에만 있는 값이 생겨서</b>다 — PG 거래번호는 조회를 한 번 더 하기 전에는 없다.
     */
    @PostMapping("/{refundNumber}/approve")
    public RefundService.Refund approve(
            @AuthenticationPrincipal ShopUser user,
            @PathVariable String refundNumber,
            @Valid @RequestBody(required = false) DecisionRequest request) {

        return refunds.approve(user.id(), refundNumber, reasonOf(request));
    }

    /** 반려한다. 돈이 안 나가므로 PG 를 안 부른다. 사유가 없으면 422 다 */
    @PostMapping("/{refundNumber}/reject")
    public RefundService.Refund reject(
            @AuthenticationPrincipal ShopUser user,
            @PathVariable String refundNumber,
            @Valid @RequestBody(required = false) DecisionRequest request) {

        return refunds.reject(user.id(), refundNumber, reasonOf(request));
    }

    /**
     * 볼 수 있는 환불을 훑는다.
     *
     * <p><b>기본 정렬이 기한 임박순이다</b>(`RefundQuery`). 이 목록을 여는 사람이 묻는 것이
     * "무엇이 새로 왔나" 가 아니라 "무엇이 늦고 있나" 라서고, 그 물음이 곧 법 요건이다(`D2` R5).
     *
     * @param status {@code REQUESTED}·{@code APPROVED}·{@code REJECTED}. 승인 대기만 보는 것이 주 용도다
     */
    @GetMapping
    public RefundQuery.Page list(
            @AuthenticationPrincipal ShopUser user,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return query.find(user.id(), status, sort, page, size);
    }

    /** 환불 하나를 펼친다. 못 보는 것은 없는 것과 같은 404 다(`D5`) */
    @GetMapping("/{refundNumber}")
    public RefundQuery.Detail detail(
            @AuthenticationPrincipal ShopUser user,
            @PathVariable String refundNumber) {

        return query.findByNumber(user.id(), refundNumber);
    }

    private static List<RefundService.Line> toLines(List<LineRequest> lines) {
        return lines == null ? List.of()
                : lines.stream()
                        .map(line -> new RefundService.Line(line.orderItemId(), line.quantity()))
                        .toList();
    }

    private static String reasonOf(DecisionRequest request) {
        return request == null ? null : request.reason();
    }
}
