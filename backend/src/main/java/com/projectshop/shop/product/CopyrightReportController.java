package com.projectshop.shop.product;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;

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

    CopyrightReportController(CopyrightReportService service) {
        this.service = service;
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

    @PostMapping("/{reportId}/decision")
    public void decide(
            @AuthenticationPrincipal ShopUser user,
            @PathVariable long reportId,
            @Valid @RequestBody CopyrightDecisionRequest request) {

        service.decide(user.id(), reportId, request.decision());
    }
}
