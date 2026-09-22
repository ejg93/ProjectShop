package com.projectshop.shop.seller;

import java.net.URI;

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
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 셀러 안의 사람과 초대(`16a`).
 *
 * <p><b>셀러 번호가 경로에 있다.</b> 화면이 그 번호를 넘기는 것을 막지 않는다 — 막을 수가
 * 없고(주소에 실려 온다) 막을 필요도 없다. <b>남의 번호를 넣으면 판정이 거부한다</b> —
 * 조직 역할로 받은 사람은 받은 그 셀러에서만 통과한다({@code Scope.SELLER}).
 *
 * <p><b>초대 원문 토큰이 응답에 실린다.</b> 메일 배관이 아직 없어서 부른 사람이 그 링크를
 * 직접 전한다 — 그 사람은 이미 초대를 낼 수 있는 사람이라 <b>자기가 만든 값을 보는 것</b>이다.
 * 메일을 붙이는 날 이 칸을 빼고 그때 이 문장을 지운다.
 */
@RestController
@RequestMapping("/api/sellers/{sellerId}")
class SellerMemberController {

    private final SellerMemberService members;

    SellerMemberController(SellerMemberService members) {
        this.members = members;
    }

    /**
     * @param roleCode 수락하면 줄 역할. <b>조직 역할만 온다</b> — 아닌 것은 표의 트리거가 막는다
     */
    record InviteRequest(@NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 50) String roleCode) {}

    /** 낸 초대. <b>토큰은 여기 한 번 실리고 어디에도 저장되지 않는다</b> */
    record InviteResponse(long invitationId, String token) {}

    @GetMapping("/members")
    SellerMemberService.Members find(@AuthenticationPrincipal ShopUser actor,
            @PathVariable long sellerId) {
        return members.find(actor.id(), sellerId);
    }

    /**
     * 내가 속한 셀러의 번호들.
     *
     * <p><b>화면이 번호를 알 이유가 없어서 둔다.</b> 이 응답으로 위 경로를 만든다 —
     * 사람이 번호를 적게 하면 <b>남의 번호를 적는 길</b>이 같이 열린다.
     *
     * <p><b>경로가 `/api/sellers/{sellerId}` 밖이다.</b> 「어느 셀러인가」를 묻기 전에 부르는
     * 것이라 그 안에 둘 수가 없다 — 이 한 자리만 셀러 없이 답한다.
     */
    @RestController
    static class MembershipController {

        private final SellerMemberService members;

        MembershipController(SellerMemberService members) {
            this.members = members;
        }

        /** 내가 속한 셀러 */
        record Memberships(java.util.List<Long> sellerIds) {}

        @GetMapping("/api/seller/memberships")
        Memberships mine(@AuthenticationPrincipal ShopUser actor) {
            return new Memberships(members.mySellerIds(actor.id()));
        }

        /**
         * 받은 초대를 수락한다.
         *
         * <p><b>토큰이 경로에 있다.</b> 링크를 눌러 오는 자리라 그것 말고 실을 곳이 없다 —
         * 원문은 <b>표에 없고</b>(해시만 있다) 링크에만 있어서, 이 값이 곧 본인 확인이다.
         *
         * <p><b>권한을 안 본다.</b> 초대를 받은 사람은 아직 그 셀러의 아무것도 아니다 —
         * 자격은 토큰과 계정 주소가 맞는지로 판단한다({@code SellerMemberService.accept}).
         */
        @PostMapping("/api/invitations/{token}/acceptance")
        ResponseEntity<Void> accept(@AuthenticationPrincipal ShopUser actor,
                @PathVariable String token) {
            members.accept(token, actor.id());
            return ResponseEntity.noContent().build();
        }
    }

    /**
     * <b>201 이다.</b> 새 자원(초대)이 서고 가리킬 주소가 있다(`D5` 「상태 코드」).
     * 역할 부여가 204 인 것과 갈리는 자리 — 그쪽은 목록을 바꾸는 것이고 새 자원이 아니다.
     */
    @PostMapping("/invitations")
    ResponseEntity<InviteResponse> invite(@AuthenticationPrincipal ShopUser actor,
            @PathVariable long sellerId, @Valid @RequestBody InviteRequest request) {
        SellerMemberService.Invitation issued =
                members.invite(sellerId, request.email(), request.roleCode(), actor.id());
        return ResponseEntity
                .created(URI.create("/api/sellers/" + sellerId + "/invitations/"
                        + issued.invitationId()))
                .body(new InviteResponse(issued.invitationId(), issued.token()));
    }

    @DeleteMapping("/invitations/{invitationId}")
    ResponseEntity<Void> revoke(@AuthenticationPrincipal ShopUser actor,
            @PathVariable long sellerId, @PathVariable long invitationId) {
        members.revoke(sellerId, invitationId, actor.id());
        return ResponseEntity.noContent().build();
    }
}
