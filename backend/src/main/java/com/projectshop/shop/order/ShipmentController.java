package com.projectshop.shop.order;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
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

    /** 셀러가 돌아온 물건을 확인했다 */
    @PostMapping("/{sellerOrderNumber}/complete-return")
    public ResponseEntity<Void> completeReturn(
            @AuthenticationPrincipal ShopUser user,
            @PathVariable String sellerOrderNumber,
            @Valid @RequestBody(required = false) ActionRequest request) {

        return run(user, sellerOrderNumber, Action.COMPLETE_RETURN, request);
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

    /** 고객이 반품을 접수한다. 기한과 제한 상품을 전이가 본다(`D2` R3·R4) */
    @PostMapping("/{sellerOrderNumber}/request-return")
    public ResponseEntity<Void> requestReturn(
            @AuthenticationPrincipal ShopUser user,
            @PathVariable String sellerOrderNumber,
            @Valid @RequestBody(required = false) ActionRequest request) {

        return run(user, sellerOrderNumber, Action.REQUEST_RETURN, request);
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
