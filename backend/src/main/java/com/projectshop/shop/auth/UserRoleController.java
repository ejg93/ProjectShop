package com.projectshop.shop.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 관리자가 사람 하나를 보고 전역 역할을 주고 회수한다(`16`).
 *
 * <p><b>{@code /api/users/{id}} 가 여기서 생긴다.</b> 가입 응답(`5-2`)의 {@code Location} 이
 * 그때 붙는다 — {@code /api/me} 는 id 로 가리키는 경로가 아니라 <b>지목할 자원이 못 된다</b>(`D5`).
 *
 * <p><b>역할을 자원 경로 아래 둔다.</b> {@code POST /api/users/{id}/roles} 는 「이 사람에게
 * 역할을 더한다」고 읽히고, 회수는 그 아래 한 칸을 지우는 {@code DELETE} 다 —
 * 동사를 경로에 안 넣는다(`D5`).
 */
@RestController
@RequestMapping("/api/users")
class UserRoleController {

    private final UserRoleService roles;

    UserRoleController(UserRoleService roles) {
        this.roles = roles;
    }

    /**
     * 줄 역할 하나.
     *
     * <p><b>목록이 아니라 하나다.</b> 여럿을 한 번에 받으면 그중 하나가 거부될 때
     * 「어디까지 됐나」를 응답이 답해야 하고, 그 모양을 정하는 것이 이 청크의 일이 아니다.
     */
    record GrantRequest(@NotBlank @Size(max = 50) String roleCode) {}

    @GetMapping("/{userId}")
    UserRoleService.Detail find(@AuthenticationPrincipal ShopUser actor,
            @PathVariable long userId) {
        return roles.find(actor.id(), userId);
    }

    /**
     * <b>201 이 아니라 204 다.</b> 부여는 새 자원을 만드는 것이 아니라
     * 그 사람의 역할 목록을 바꾸는 것이고, 가리킬 새 주소가 없다(`D5` 「헤더」).
     */
    @PostMapping("/{userId}/roles")
    ResponseEntity<Void> grant(@AuthenticationPrincipal ShopUser actor,
            @PathVariable long userId, @Valid @RequestBody GrantRequest request) {
        roles.grant(actor.id(), userId, request.roleCode());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{userId}/roles/{roleCode}")
    ResponseEntity<Void> revoke(@AuthenticationPrincipal ShopUser actor,
            @PathVariable long userId, @PathVariable String roleCode) {
        roles.revoke(actor.id(), userId, roleCode);
        return ResponseEntity.noContent().build();
    }
}
