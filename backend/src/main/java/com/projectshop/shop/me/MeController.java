package com.projectshop.shop.me;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.projectshop.shop.auth.PermissionCatalog;
import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;

/**
 * 로그인한 사람이 자기에 대해 묻는 자리.
 *
 * <p>계정 조회·수정(5e)과 동의 조회·철회(5f)가 여기 붙는다.
 */
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final PermissionCatalog permissionCatalog;

    public MeController(PermissionCatalog permissionCatalog) {
        this.permissionCatalog = permissionCatalog;
    }

    /**
     * 지금 무엇을 할 수 있나.
     *
     * <p>화면은 이 목록으로 버튼을 보이고 감춘다. 역할 이름으로 판단하면 판정이 두 벌이 된다.
     *
     * <p><b>접근 허용에 쓰면 안 된다.</b> 목록은 대상 행이 없는 근사치고,
     * 실제 허용은 자원을 만질 때 판정이 다시 정한다.
     */
    @GetMapping("/permissions")
    public PermissionsResponse permissions(@AuthenticationPrincipal ShopUser user) {
        return new PermissionsResponse(user.id(), permissionCatalog.listFor(user.id()));
    }

    public record PermissionsResponse(long userId, List<PermissionCatalog.Entry> permissions) {
    }
}
