package com.projectshop.shop.payment;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;

/**
 * 결제를 거는 입구.
 *
 * <p><b>멱등을 여기서 안 감싼다.</b> {@code OrderController} 와 다른 자리인데,
 * 결제는 순서가 「주문 조회 → PG 호출 → 기록」 이고 <b>PG 호출이 트랜잭션 밖이어야 해서</b>
 * 그 순서를 아는 {@link PaymentService} 가 감싼다(`D11` 「바깥 시스템을 부르는 자리」).
 * 여기서 감싸면 PG 응답을 기다리는 동안 멱등키 행의 락이 유지된다.
 *
 * <p><b>권한 축이 없다.</b> 결제는 자기 주문에만 걸리고 그 확인은 주문 조회의 {@code user_id} 조건이
 * 이미 한다 — 남의 주문은 없는 주문과 같은 404 다(`D5`). {@code payment} 자원의 권한
 * ({@code read}·{@code refund}, `V3`)은 남이 낸 결제를 보거나 되돌리는 자리에 걸리고,
 * 그건 주문 상세의 필드 그룹(`V6`)과 환불(청크 12a)이 쓴다.
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService payments;

    PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    /**
     * 결제 요청.
     *
     * <p><b>금액이 없다.</b> 낼 돈은 주문에 박제돼 있어서 서버가 읽는다 —
     * 받으면 그 값이 맞는지 검사하는 코드가 따로 필요해지고, 빠뜨리면 원하는 금액으로 결제된다.
     *
     * <p><b>카드번호는 여기까지만 온다.</b> 서비스를 지나 모의 PG 까지 가고 우리 표에는 안 닿는다
     * (`D2` R18). 형식 검사도 여기서 끝낸다 — 게이트웨이 쪽 검사는 그 뒤의 방벽이라
     * 거기까지 가면 우리 코드가 틀린 것이다.
     *
     * <p><b>{@code method} 는 열거형으로 받는다</b>(`43a-16`). 정규식으로 받으면 값 목록이
     * {@code check}·enum·정규식 셋이 되고, 모르는 값은 <b>역직렬화에서 걸려 400 이다</b>
     * (`D5` 「열거값이 목록 밖이면 422 가 아니라 400 이다」).
     *
     * @param cardNumber 하이픈과 공백을 허용한다. 사람이 화면에 입력한 모양 그대로 받는다
     */
    public record PayRequest(
            @NotBlank @Size(max = 20) String orderNumber,
            @NotNull PaymentMethod method,
            @Pattern(regexp = "[0-9][0-9 -]{10,23}[0-9]") String cardNumber) {
    }

    /**
     * 낸다.
     *
     * <p><b>거절도 201 이다.</b> 요청은 성공했고 결과가 거절인 것이라, 본문의 {@code status} 가
     * 그것을 말한다(`D11` 「거절은 저장한다」). 4xx 로 던지면 멱등 기록과 주문 상태가 같이
     * 롤백돼서 {@code payment_failed} 로 못 간다.
     *
     * <p><b>{@code Location} 이 결제가 아니라 주문을 가리킨다</b>(`D5` 「상태 코드」).
     * 결제 결과를 다시 보는 경로가 주문 상세뿐이다 — 결제만 여는 경로를 따로 내면
     * 같은 것을 두 군데서 그리게 된다.
     *
     * <p>응답이 {@link PaymentService.Result} 그대로다. 입구용 record 를 따로 두면
     * <b>멱등 재생이 되돌리는 타입과 두 벌이 되고</b>, 한쪽에 필드를 더하는 날 재생 응답만 낡는다.
     */
    @PostMapping
    public ResponseEntity<PaymentService.Result> pay(
            @AuthenticationPrincipal ShopUser user,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 255) String idempotencyKey,
            @Valid @RequestBody PayRequest request) {

        PaymentService.Result result = payments.pay(user.id(), idempotencyKey,
                new PaymentService.Command(request.orderNumber(), request.method(),
                        request.cardNumber()));

        return ResponseEntity.created(URI.create("/api/orders/" + result.orderNumber()))
                .body(result);
    }
}
