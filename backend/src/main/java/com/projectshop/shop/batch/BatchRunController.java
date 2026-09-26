package com.projectshop.shop.batch;

import java.time.LocalDate;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;

import jakarta.validation.Valid;

/**
 * 기준일 배치를 손으로 돌리는 관리자 입구(`Q241`). 판정과 규칙은 {@link BatchRunService} 가 든다.
 *
 * <p><b>화면이 없다.</b> 부르는 쪽이 측정·부하·e2e 라 {@code curl} 이다.
 *
 * <p><b>운영에서는 경로가 없다</b>(마무리 55차 독립 리뷰). 관리자 연습 계정이 로그인 화면에 공개라 운영에 열면
 * 누구나 회차를 돌린다. {@code shop.batch.manual-run} 이 {@code true} 일 때만 이 빈이 선다 — 로컬에서 `BATCH_MANUAL_RUN=true` 로 띄울 때다.
 *
 * <p><b>200 이다</b> — 회차 줄을 새로 남기지만 다시 볼 경로가 없고, 이미 성공한 회차면 줄을 안 남긴다.
 */
@ConditionalOnProperty(name = "shop.batch.manual-run", havingValue = "true")
@RestController
@RequestMapping("/api/admin/batches")
public class BatchRunController {

    private final BatchRunService service;

    BatchRunController(BatchRunService service) {
        this.service = service;
    }

    /** @param baselineDate 본문 {@code baseline_date}. 오늘(KST) 앞이어야 하고 없으면 어제 */
    record RunRequest(LocalDate baselineDate) {}

    @PostMapping("/{name}/runs")
    public BatchRunService.BatchRun run(@AuthenticationPrincipal ShopUser user,
            @PathVariable String name,
            @Valid @RequestBody(required = false) RunRequest request) {
        return service.run(user.id(), name, request == null ? null : request.baselineDate());
    }
}
