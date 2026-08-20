package com.projectshop.shop.order;

import java.net.URI;
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
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;
import com.projectshop.shop.support.Retries;

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

    /**
     * @param withdrawalRestrictionAgreed 주문제작 상품의 청약철회 제한에 동의했나(`Q5`).
     *        <b>안 보내면 안 받은 것</b>이고, 그러면 그 주문에는 제한이 안 걸린다 —
     *        시행령 제21조가 요구하는 것은 동의고 침묵은 동의가 아니다.
     *        <b>{@code Boolean} 인 이유는 Jackson 이 빠진 원시 타입을 못 채워서다</b> —
     *        {@code boolean} 으로 두면 이 칸을 안 보내는 기존 클라이언트가 400 을 받는다
     */
    public record CreateRequest(
            @NotEmpty @Size(max = 100) List<Long> cartItemIds,
            @NotNull @Valid ShippingRequest shipping,
            Boolean withdrawalRestrictionAgreed) {
    }

    public record ShippingRequest(
            @NotBlank @Size(max = 50) String receiverName,
            @NotBlank @Size(max = 30) @Pattern(regexp = "^[0-9-]+$",
                    message = "숫자와 하이픈만 쓸 수 있습니다") String receiverPhone,
            @NotBlank @Pattern(regexp = "^[0-9]{5}$",
                    message = "우편번호는 숫자 5자리입니다") String postalCode,
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

        // 재시도가 멱등 바깥이다(`D11`). 안쪽은 이미 깨진 트랜잭션이라 다음 문장부터 못 돈다.
        // 바깥이면 선점 기록도 같이 롤백돼 있어서 같은 키로 다시 들어오는 것이 맞다 —
        // 앞 시도가 아무것도 안 남겼으므로 막을 중복이 없다.
        CreateResponse response = Retries.onConflict(
                () -> idempotency.run(user.id(), idempotencyKey, request,
                        CreateResponse.class,
                        () -> {
                            OrderService.Created created =
                                    orderService.create(user.id(), toCommand(request));
                            return new CreateResponse(created.orderNumber(), created.payableAmount());
                        }));

        // 새 자원이 요청 URI 와 다른 경로에 선다. `Location` 이 없으면 만들어진 것이
        // `/api/orders` 자신이라는 뜻이 되고, 그건 사실이 아니다(`D5` 「헤더」, `Q11`).
        return ResponseEntity
                .created(URI.create("/api/orders/" + response.orderNumber()))
                .body(response);
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

    /**
     * 계약내용 서면 하나를 본문까지 펼친다(`Q4`, `D2` R22).
     *
     * <p><b>목록은 주문 상세가 이미 내린다.</b> 본문만 따로 받는 이유는 약관 전문이 조항 수만큼
     * 딸려 나오면 주문 상세가 무거워져서다(`5k` 가 동의 고지에서 같은 판단을 했다).
     *
     * <p>경로가 주문 아래에 붙는 이유는 <b>같은 조항이라도 주문마다 판이 다르기</b> 때문이다 —
     * {@code /api/policies/terms} 는 지금 효력 있는 판을 주고, 여기는 그 주문이 계약한 판을 준다.
     */
    @GetMapping("/{orderNumber}/contract-documents/{clause}")
    public OrderQuery.ContractDocumentBody contractDocument(
            @AuthenticationPrincipal ShopUser user,
            @PathVariable String orderNumber,
            @PathVariable String clause) {

        return orderQuery.contractDocument(user.id(), orderNumber, clause);
    }

    private static OrderService.Command toCommand(CreateRequest request) {
        ShippingRequest shipping = request.shipping();
        return new OrderService.Command(request.cartItemIds(),
                new OrderService.Shipping(shipping.receiverName(), shipping.receiverPhone(),
                        shipping.postalCode(), shipping.address1(), shipping.address2(),
                        shipping.deliveryMemo()),
                Boolean.TRUE.equals(request.withdrawalRestrictionAgreed()));
    }
}
