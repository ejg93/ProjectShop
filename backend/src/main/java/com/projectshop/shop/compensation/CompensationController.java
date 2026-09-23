package com.projectshop.shop.compensation;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;

/**
 * 손해배상 판정의 입구(`43a-4c`). <b>셀러 묶음 아래 선다</b> — 배상의 단위가 묶음이다(`V69`).
 *
 * <p><b>판정은 동작이다</b>(`D5` 「동작」). 판정 행에 노출 번호가 없고 하나를 따로 부를 일이 없어서
 * {@code 201} 과 {@code Location} 을 줄 자원이 없다 — 발송·반품 판정과 같이 {@code 204} 로 답한다.
 */
@RestController
public class CompensationController {

    private final CompensationService compensations;

    CompensationController(CompensationService compensations) {
        this.compensations = compensations;
    }

    /**
     * 판정 요청.
     *
     * @param amount 물어 준 금액(원). 법이 액수를 안 줘서 사람이 정한다 — 상한은 오타를 막는 값이다
     * @param basis 왜 그 금액인가. 비울 수 없다 — 분쟁이 오면 이 글이 근거다({@code compensation_note.reason}).
     *              이름을 {@code reason} 으로 안 두는 것은 그 이름의 다른 요청 칸이 500 자라서다 — 같은 이름에 상한이 둘이면
     *              {@code ScreenLengthTest} 가 화면과 서버를 못 잇는다
     * @param inquiryNumber 어느 문의에서 왔나. 우리가 먼저 알고 무는 경우가 있어서 선택이다
     */
    public record DecideRequest(
            @NotNull CompensationKind kind,
            @NotNull CompensationBearer bearer,
            @NotNull @Positive @Max(100_000_000) Long amount,
            @NotBlank @Size(max = 2000) String basis,
            @Pattern(regexp = "^Q-[0-9]{8}-[2-9A-HJ-NP-Z]{6}$") String inquiryNumber) {
    }

    @GetMapping("/api/shipments/{sellerOrderNumber}/compensations")
    public CompensationService.Listing list(
            @AuthenticationPrincipal ShopUser user,
            @PathVariable String sellerOrderNumber) {

        return compensations.list(user.id(), sellerOrderNumber);
    }

    /** 배상을 판정한다. 관리자만이다({@code compensation:decide}, `V107`) */
    @PostMapping("/api/shipments/{sellerOrderNumber}/compensate")
    public ResponseEntity<Void> compensate(
            @AuthenticationPrincipal ShopUser user,
            @PathVariable String sellerOrderNumber,
            @Valid @RequestBody DecideRequest request) {

        compensations.decide(user.id(), sellerOrderNumber, new CompensationService.Command(
                request.kind(), request.bearer(), request.amount(), request.basis(), request.inquiryNumber()));
        return ResponseEntity.noContent().build();
    }
}
