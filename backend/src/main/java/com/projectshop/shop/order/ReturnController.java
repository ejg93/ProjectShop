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
 * <p>하나뿐인 이유는 검수를 입고 안에서 받아서다(`43a-5`) — 소견을 적으면 한 번에 검수까지 간다.
 * 수거지는 배송지를 쓴다(2026-09-23 결정).
 */
@RestController
@RequestMapping("/api/returns")
public class ReturnController {

    private final OrderActionService actions;

    ReturnController(OrderActionService actions) {
        this.actions = actions;
    }

    /**
     * @param reason         관리자가 대신 적을 때만 채운다(`D7`)
     * @param inspectionNote 검수 소견(`43a-5`). 적으면 한 번에 검수까지 가고, 비우면 입고만 적는다.
     *                       상한은 {@code return_note_inspection_note_length_check} 와 같다
     */
    public record ReceiveRequest(@Size(max = 500) String reason, @Size(max = 500) String inspectionNote) {
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
                request == null ? null : request.reason(),
                request == null ? null : request.inspectionNote());

        return ResponseEntity.noContent().build();
    }
}
