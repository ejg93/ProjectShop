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

/**
 * 반품 진행을 적는 입구(`43a-2`).
 *
 * <p><b>{@link ShipmentController} 와 갈라 둔다.</b> 저쪽은 묶음 상태를 옮기는 자리라
 * 경로 이름이 {@code allowed_actions} 의 동작 이름과 짝이다(`D5`). 여기 있는 것은
 * <b>묶음을 안 옮기는 진행</b>이라 그 목록에 실릴 수 없고, 같이 두면 짝이 깨진 경로가 하나 생긴다.
 *
 * <p><b>판정 둘은 여기 없다.</b> 승인·거절은 묶음을 옮기므로 {@code ShipmentController} 가
 * {@code approve-return}·{@code reject-return} 으로 받는다.
 *
 * <p>지금 하나뿐인 이유는 수거지·검수 소견이 아직 없어서다 — `43a-3` 이 세운다.
 */
@RestController
@RequestMapping("/api/returns")
public class ReturnController {

    private final OrderActionService actions;

    ReturnController(OrderActionService actions) {
        this.actions = actions;
    }

    /** 관리자가 대신 적을 때만 채운다(`D7`) */
    public record ReceiveRequest(@Size(max = 500) String reason) {
    }

    /**
     * 돌아온 물건이 들어왔다.
     *
     * <p><b>셀러가 부른다.</b> 물건이 실제로 도착했는지는 받아 본 쪽이 안다.
     * 판정은 여기서 안 한다 — 제17조제5항이 훼손 책임의 입증을 우리에게 지웠다(`D2` R37).
     *
     * <p><b>이 시각이 환급 기산점이다</b> — 제18조제2항 1호가 「재화등을 반환받은 날」이라
     * 정했고(`D2` R5), 그래서 입고 없는 승인이 `V63` 에서 막힌다.
     */
    @PostMapping("/{sellerOrderNumber}/receive")
    public ResponseEntity<Void> receive(
            @AuthenticationPrincipal ShopUser user,
            @PathVariable String sellerOrderNumber,
            @Valid @RequestBody(required = false) ReceiveRequest request) {

        actions.receiveReturn(user.id(), sellerOrderNumber,
                request == null ? null : request.reason());

        return ResponseEntity.noContent().build();
    }
}
