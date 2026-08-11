package com.projectshop.shop.order;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;

/**
 * 주문을 만들고 산 사람이 그것을 보는 입구.
 *
 * <p><b>멱등을 여기서 건다.</b> {@code Idempotency-Key} 는 HTTP 재전송을 가르는 값이라
 * 서비스가 알 개념이 아니고, 감싸는 자리가 컨트롤러여야 주문 생성과 멱등 기록이
 * 한 트랜잭션에 들어간다(`D11`).
 *
 * <p>헤더를 필터로 강제하지 않는다. 필터는 {@code run()} 이 실제로 불렸는지 모르므로
 * <b>"헤더는 요구하는데 멱등은 안 걸린" 상태</b>를 만들 수 있고, 그건 아예 없는 것보다 나쁘다.
 * 대신 {@code OrderIdempotencyTest} 가 이 경로가 실제로 멱등한지 확인한다.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final OrderQuery orderQuery;
    private final IdempotencyService idempotency;

    OrderController(OrderService orderService, OrderQuery orderQuery,
            IdempotencyService idempotency) {
        this.orderService = orderService;
        this.orderQuery = orderQuery;
        this.idempotency = idempotency;
    }

    public record CreateRequest(
            @NotEmpty @Size(max = 100) List<Long> cartItemIds,
            @NotNull @Valid ShippingRequest shipping) {
    }

    public record ShippingRequest(
            @NotBlank @Size(max = 50) String receiverName,
            @NotBlank @Size(max = 30) String receiverPhone,
            @NotBlank @Size(max = 10) String postalCode,
            @NotBlank @Size(max = 200) String address1,
            @Size(max = 200) String address2,
            @Size(max = 200) String deliveryMemo) {
    }

    public record CreateResponse(String orderNumber, long payableAmount) {}

    /**
     * 장바구니에서 고른 것을 주문으로 굳힌다.
     *
     * <p>응답에 내부 ID 를 안 담는다. 노출 번호가 있는 자원은 그것을 쓴다(`D9`).
     */
    @PostMapping
    public ResponseEntity<CreateResponse> create(
            @AuthenticationPrincipal ShopUser user,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 255) String idempotencyKey,
            @Valid @RequestBody CreateRequest request) {

        CreateResponse response = idempotency.run(user.id(), idempotencyKey, request,
                CreateResponse.class,
                () -> {
                    OrderService.Created created = orderService.create(user.id(), toCommand(request));
                    return new CreateResponse(created.orderNumber(), created.payableAmount());
                });

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 내가 산 것을 훑는다.
     *
     * <p>기본 크기를 20 으로 둔다. 상한은 {@code ListQuery.MAX_SIZE} 가 걸고 여기서 다시 안 적는다 —
     * 두 군데 적으면 한쪽만 올리는 날이 온다.
     */
    @GetMapping
    public OrderQuery.Page list(
            @AuthenticationPrincipal ShopUser user,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return orderQuery.findMine(user.id(), sort, page, size);
    }

    /**
     * 주문 하나를 펼친다. <b>경로에 노출 번호를 쓴다</b>(`D9`).
     *
     * <p>못 보는 주문에는 404 가 나간다(`D5`). 그것이 이 자원의 민감도에서 온 답이라
     * 여기서 403 으로 되돌리면 주문 번호를 훑는 것만으로 주문 수가 샌다.
     */
    @GetMapping("/{orderNumber}")
    public OrderQuery.Detail detail(
            @AuthenticationPrincipal ShopUser user,
            @PathVariable String orderNumber) {

        return orderQuery.findByNumber(user.id(), orderNumber);
    }

    private static OrderService.Command toCommand(CreateRequest request) {
        ShippingRequest shipping = request.shipping();
        return new OrderService.Command(request.cartItemIds(),
                new OrderService.Shipping(shipping.receiverName(), shipping.receiverPhone(),
                        shipping.postalCode(), shipping.address1(), shipping.address2(),
                        shipping.deliveryMemo()));
    }
}
