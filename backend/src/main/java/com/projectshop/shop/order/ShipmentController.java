package com.projectshop.shop.order;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;
import com.projectshop.shop.order.OrderActionService.Action;

/**
 * 셀러 묶음의 상태를 옮기는 입구.
 *
 * <p><b>노출 번호가 전역에서 하나를 가리킨다</b>({@code seller_order_number_unique}, `D9`).
 * 그래서 주문 밑에 중첩하지 않는다 — 앞에 주문번호를 붙여도 가리키는 대상이 안 좁아지고,
 * 이 단위를 부르는 곳 넷(부분 취소·환불, 정산 명세, 반품 접수, 송장)이 전부 긴 경로를 지고 간다.
 *
 * <p><b>조회와 달리 경로를 안 가른다.</b> {@link OrderQuery} 와 {@link SellerOrderQuery} 가
 * 갈린 이유는 조회 조건이 스코프를 손으로 적는 자리라 섞이면 남의 주문이 새기 때문이다.
 * 전이는 그런 조건이 없다 — 행을 읽고 {@code decide} 한 번을 지날 뿐이라
 * 갈라 두면 같은 판정 호출이 두 벌이 된다.
 *
 * <p>동작마다 경로가 다르다(`D5` 「동작」). {@code PATCH} 로 {@code status} 를 받지 않는다 —
 * 전이표를 안 거치는 상태 변경 경로가 생긴다(`ADR 0009`).
 *
 * <p><b>경로 이름이 동작 이름의 소문자·하이픈이다.</b> 상세 응답의 {@code allowed_actions} 가
 * {@code ["CONFIRM", "REQUEST_RETURN"]} 을 내리면 화면은 그것을 그대로 바꿔 부른다 —
 * 동작과 경로를 잇는 표를 화면이 따로 들고 있으면 동작이 늘 때 한쪽만 고쳐진다.
 */
@RestController
@RequestMapping("/api/shipments")
public class ShipmentController {

    private final OrderActionService actions;

    ShipmentController(OrderActionService actions) {
        this.actions = actions;
    }

    /**
     * 관리자가 옮길 때만 채운다(`D7`).
     *
     * <p>본문 자체가 선택이라 고객·셀러는 빈 요청을 보낸다. 필수로 두면 버튼 하나 누르는 데
     * 본문을 만들어야 하고, 그 자리를 채우려고 화면이 빈 문자열을 넣기 시작한다.
     */
    public record ActionRequest(@Size(max = 500) String reason) {
    }

    /**
     * 반품 접수에만 쓰는 요청.
     *
     * <p><b>{@code returnReason} 이 조항을 가른다</b>(`D2` R3). 비우면 단순 변심이다 —
     * 기본값을 하자로 두면 7일과 청약철회 제한이 아무에게도 안 걸린다.
     *
     * @param returnReason {@code CHANGE_OF_MIND} 또는 {@code DEFECT}. 열거값이라 대문자다(`D5`)
     */
    public record ReturnRequest(
            @Size(max = 500) String reason,
            OrderStatusService.ReturnReason returnReason) {
    }

    /**
     * 반품 승인 요청(`43a-2`).
     *
     * <p><b>본문이 필수다.</b> 다른 동작과 다른 이유는 {@code restock} 이 판단이라서다 —
     * 빈 요청을 허용하면 그 판단이 기본값으로 대체된다.
     *
     * @param restock 돌아온 물건을 다시 팔 수 있나
     * @param reason  관리자 전이의 근거(`D7`). 판정 사유가 아니다
     */
    public record ApproveReturnRequest(
            @NotNull Boolean restock,
            @Size(max = 500) String reason) {
    }

    /**
     * 반품 거절 요청(`43a-2`).
     *
     * <p><b>{@code decisionReason} 에 {@code @NotBlank} 를 안 건다.</b> 걸면 빈 사유가
     * 400 으로 떨어지는데, 형식은 맞고 값이 규칙에 안 맞는 것이라 422 다(`D5`).
     * 막는 것은 {@link ReturnRequestService} 고 그쪽이 {@code RETURN_DECISION_REASON_REQUIRED} 를 던진다 —
     * <b>새 입구가 생겨도 같은 자리를 지난다.</b>
     *
     * @param decisionReason 왜 인정하지 않았나. {@code return_note} 에 남는다(6개월, `D13`)
     * @param reason         관리자 전이의 근거(`D7`)
     */
    public record RejectReturnRequest(
            @Size(max = 500) String decisionReason,
            @Size(max = 500) String reason) {
    }

    /** 셀러가 물건을 보냈다 */
    @PostMapping("/{sellerOrderNumber}/ship")
    public ResponseEntity<Void> ship(
            @AuthenticationPrincipal ShopUser user,
            @PathVariable String sellerOrderNumber,
            @Valid @RequestBody(required = false) ActionRequest request) {

        return run(user, sellerOrderNumber, Action.SHIP, request);
    }

    /** 셀러가 배송을 끝냈다. 청약철회·자동확정 기한이 이 시점에 박제된다(`D10`) */
    @PostMapping("/{sellerOrderNumber}/deliver")
    public ResponseEntity<Void> deliver(
            @AuthenticationPrincipal ShopUser user,
            @PathVariable String sellerOrderNumber,
            @Valid @RequestBody(required = false) ActionRequest request) {

        return run(user, sellerOrderNumber, Action.DELIVER, request);
    }

    /**
     * 반품을 인정한다. 묶음이 닫힌다(`43a-2`).
     *
     * <p><b>관리자만 부른다.</b> 제17조제5항이 훼손 책임의 입증을 통신판매업자에게 지워서,
     * 물건을 받아 본 셀러가 소견을 내도 그것이 곧 결론이 되면 안 된다(`D2` R37).
     *
     * <p><b>부담 주체를 안 받는다.</b> 판정과 사유에서 계산된다(`D2` R36) —
     * 하자 승인은 판매자, 그 밖은 소비자다. 칸을 두면 법이 정한 값을 고를 수 있게 된다.
     *
     * <p><b>{@code restock} 은 필수다.</b> 다시 팔 수 있는지는 검수 결과라 계산이 안 되고,
     * 기본값을 두면 그 값이 곧 거짓이 된다(`V63` 의 {@code return_request_restock_check}).
     */
    @PostMapping("/{sellerOrderNumber}/approve-return")
    public ResponseEntity<Void> approveReturn(
            @AuthenticationPrincipal ShopUser user,
            @PathVariable String sellerOrderNumber,
            @Valid @RequestBody ApproveReturnRequest request) {

        actions.run(user.id(), sellerOrderNumber, Action.APPROVE_RETURN, request.reason(), null,
                new ReturnRequestService.Decision.Approve(request.restock()));

        return ResponseEntity.noContent().build();
    }

    /**
     * 반품을 인정하지 않는다. 묶음이 배송완료로 돌아간다(`43a-2`).
     *
     * <p><b>사유가 필수다.</b> `V63` 의 {@code return_requires_rejection_reason} 이 커밋에서
     * 같은 것을 보지만 그것은 지연이라 500 으로 나간다 — 여기서 받아야 422 가 된다.
     */
    @PostMapping("/{sellerOrderNumber}/reject-return")
    public ResponseEntity<Void> rejectReturn(
            @AuthenticationPrincipal ShopUser user,
            @PathVariable String sellerOrderNumber,
            @Valid @RequestBody RejectReturnRequest request) {

        actions.run(user.id(), sellerOrderNumber, Action.REJECT_RETURN, request.reason(), null,
                new ReturnRequestService.Decision.Reject(request.decisionReason()));

        return ResponseEntity.noContent().build();
    }

    /** 배송 전에 접는다. 고객과 셀러 둘 다 부른다(`D7`) */
    @PostMapping("/{sellerOrderNumber}/cancel")
    public ResponseEntity<Void> cancel(
            @AuthenticationPrincipal ShopUser user,
            @PathVariable String sellerOrderNumber,
            @Valid @RequestBody(required = false) ActionRequest request) {

        return run(user, sellerOrderNumber, Action.CANCEL, request);
    }

    /** 고객이 받은 것을 확정한다. 안 누르면 8일 뒤 배치가 대신 옮긴다(`D19`) */
    @PostMapping("/{sellerOrderNumber}/confirm")
    public ResponseEntity<Void> confirm(
            @AuthenticationPrincipal ShopUser user,
            @PathVariable String sellerOrderNumber,
            @Valid @RequestBody(required = false) ActionRequest request) {

        return run(user, sellerOrderNumber, Action.CONFIRM, request);
    }

    /**
     * 고객이 반품을 접수한다. 기한과 제한 상품을 전이가 본다(`D2` R3·R4).
     *
     * <p><b>사유의 종류로 조항이 갈린다.</b> 제17조제3항이 「제1항 및 제2항에도 불구하고」로
     * 시작해서, 하자 반품은 <b>7일도 청약철회 제한도 안 걸리고 3개월</b>이다.
     * 안 받으면 들어오는 것을 전부 단순 변심으로 볼 수밖에 없고, 그러면
     * <b>8일째 하자 신고가 거부된다.</b>
     *
     * <p><b>하자 주장을 안 거른다.</b> 제17조제5항이 훼손에 소비자 책임이 있는지의 입증을
     * 통신판매업자에게 지웠다 — 접수를 막을 근거가 없고 다툼은 사후다.
     */
    @PostMapping("/{sellerOrderNumber}/request-return")
    public ResponseEntity<Void> requestReturn(
            @AuthenticationPrincipal ShopUser user,
            @PathVariable String sellerOrderNumber,
            @Valid @RequestBody(required = false) ReturnRequest request) {

        actions.run(user.id(), sellerOrderNumber, Action.REQUEST_RETURN,
                request == null ? null : request.reason(),
                request == null ? null : request.returnReason());

        return ResponseEntity.noContent().build();
    }

    /**
     * 204 다. 옮긴 뒤의 모양은 상세 조회가 답한다 — 여기서 같이 내리면
     * 같은 응답이 두 군데서 만들어지고 한쪽만 고치는 날이 온다(`D5` 「상태 코드」).
     */
    private ResponseEntity<Void> run(ShopUser user, String sellerOrderNumber, Action action,
            ActionRequest request) {

        actions.run(user.id(), sellerOrderNumber, action,
                request == null ? null : request.reason());

        return ResponseEntity.noContent().build();
    }
}
