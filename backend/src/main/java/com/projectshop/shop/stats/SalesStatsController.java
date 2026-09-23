package com.projectshop.shop.stats;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;

/**
 * 매출 통계 입구(`41`).
 *
 * <p><b>셀러와 관리자가 같은 입구를 쓴다.</b> 누구의 합인지는 판정 스코프가 정한다 — 대표는 자기 셀러, 관리자·감사자는
 * 전부({@code V103}). 입구를 역할마다 가르면 스코프를 경로가 한 번, 판정이 또 한 번 정하게 된다.
 */
@RestController
public class SalesStatsController {

    private final SalesStatsQuery query;

    SalesStatsController(SalesStatsQuery query) {
        this.query = query;
    }

    /**
     * 날짜별 합과 기간 합.
     *
     * @param from 이날부터(포함). 한국 날짜다(`time-rules.md`)
     * @param to   이날 전까지(제외). 기간은 1일부터 {@value SalesStatsQuery#MAX_DAYS}일까지다
     */
    @GetMapping("/api/sales-stats")
    public SalesStatsQuery.Report find(
            @AuthenticationPrincipal ShopUser viewer,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        return query.find(viewer.id(), from, to);
    }
}
