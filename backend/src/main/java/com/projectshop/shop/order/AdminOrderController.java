package com.projectshop.shop.order;

import java.time.LocalDate;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;
import com.projectshop.shop.support.ListQuery.Paging;

/**
 * 관리자가 모든 주문을 훑는 입구(`Q176`).
 *
 * <p><b>{@code /api/orders} 를 안 넓히고 여기 둔다</b>(`D5` 「URL」). 그 경로는 「내가 산 것」으로 굳어서
 * 「내 주문」 화면이 부른다 — 넓히면 관리자 계정의 내 주문에 모든 고객의 주문이 섞인다.
 *
 * <p><b>상세는 여기 없다.</b> {@code GET /api/orders/{번호}} 가 판정으로 이미 관리자에게 열리고,
 * 필드 그룹(`4d`)도 거기서 갈린다 — 상세를 둘 두면 마스킹 규칙이 두 벌이 된다.
 */
@RestController
@RequestMapping("/api/admin/orders")
public class AdminOrderController {

    private final OrderQuery orderQuery;

    AdminOrderController(OrderQuery orderQuery) {
        this.orderQuery = orderQuery;
    }

    /**
     * 주문을 최신순으로 훑는다. {@code all} 범위가 아니면 403 이다.
     *
     * @param status 결제 층 상태 하나({@code PAYMENT_PENDING}·{@code PAID}·{@code PAYMENT_EXPIRED}·{@code PAYMENT_FAILED})
     * @param from   이날부터(포함). 한국 날짜다
     * @param to     이날 전까지(제외)
     */
    @GetMapping
    public OrderQuery.Page list(
            @AuthenticationPrincipal ShopUser user,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String sort,
            @ParameterObject Paging paging) {

        return orderQuery.findAll(user.id(), status, from, to, sort, paging);
    }
}
