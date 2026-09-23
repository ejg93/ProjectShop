package com.projectshop.shop.product;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.support.ListQuery.Paging;

import org.springdoc.core.annotations.ParameterObject;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 남의 저작물 신고 입구({@code Q94}, {@code D2} {@code R42}).
 *
 * <h2>접수는 로그인을 안 받는다</h2>
 *
 * <p>저작권자가 우리 회원일 이유가 없다. 회원만 신고할 수 있게 하면
 * <b>법이 요구한 절차에 가입이라는 관문이 하나 붙는다.</b>
 *
 * <p>판정은 반대다 — 누가 언제 무엇을 했는지가 증거라 로그인한 사람만 한다.
 */
@RestController
@RequestMapping("/api/copyright-reports")
public class CopyrightReportController {

    private final CopyrightReportService service;
    private final CopyrightReportQuery query;

    CopyrightReportController(CopyrightReportService service, CopyrightReportQuery query) {
        this.service = service;
        this.query = query;
    }

    /**
     * 판정할 신고(`Q183`). <b>기본은 아직 안 본 것이다</b> — 관리자가 여는 이유가 그것이다.
     *
     * @param status {@code PENDING}(판정 전) 또는 {@code DECIDED}(판정 후)
     */
    @GetMapping
    public CopyrightReportQuery.Result list(@AuthenticationPrincipal ShopUser user,
            @RequestParam(defaultValue = "PENDING") String status, @ParameterObject Paging paging) {
        boolean pending = switch (status) {
            case "PENDING" -> true;
            case "DECIDED" -> false;
            default -> throw new ShopException(ErrorCode.VALIDATION_FAILED, "모르는 상태다: " + status);
        };
        return query.find(user.id(), pending, paging);
    }

    /**
     * @param claimedWork 어떤 저작물에 대한 권리인지. 법이 특정할 수 있는 정보를 요구한다
     */
    public record ReportRequest(
            @NotBlank @Size(max = 100) String reporterName,
            @NotBlank @Email @Size(max = 320) String reporterEmail,
            @NotBlank @Size(max = 2000) String claimedWork) {
    }

    public record CopyrightDecisionRequest(@NotBlank String decision) {
    }

    @PostMapping("/images/{productImageId}")
    @ResponseStatus(HttpStatus.CREATED)
    public CopyrightReportService.Received report(
            @PathVariable long productImageId,
            @Valid @RequestBody ReportRequest request) {

        return service.report(productImageId, new CopyrightReportService.Command(
                request.reporterName(), request.reporterEmail(), request.claimedWork()));
    }

    /** 후기 사진을 신고한다(`Q196`). 상품 사진과 같은 요청이고 로그인 없이 받는다 */
    @PostMapping("/review-images/{reviewImageId}")
    @ResponseStatus(HttpStatus.CREATED)
    public CopyrightReportService.Received reportReviewImage(
            @PathVariable long reviewImageId,
            @Valid @RequestBody ReportRequest request) {

        return service.reportReviewImage(reviewImageId, new CopyrightReportService.Command(
                request.reporterName(), request.reporterEmail(), request.claimedWork()));
    }

    @PostMapping("/{reportId}/decision")
    public void decide(
            @AuthenticationPrincipal ShopUser user,
            @PathVariable long reportId,
            @Valid @RequestBody CopyrightDecisionRequest request) {

        // **경계에서 바꾼다**(`Q121`, 사용자 결정 2026-09-20). 요청 record 는 문자열을 그대로 들고
        // 여기서 열거형이 된다 — 모르는 값은 `ofRequest` 가 400 을 내서 고치기 전과 답이 같다.
        service.decide(user.id(), reportId, CopyrightDecision.ofRequest(request.decision()));
    }
}
