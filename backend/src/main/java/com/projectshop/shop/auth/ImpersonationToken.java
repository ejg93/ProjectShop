package com.projectshop.shop.auth;

import java.io.Serial;
import java.time.OffsetDateTime;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;

/**
 * 대행 중인 세션의 인증(`16b`).
 *
 * <p><b>주인은 대상 사용자다</b> — {@code @AuthenticationPrincipal} 이 그 사람을 받고, 판정도 그 사람으로 돈다.
 * 그래서 화면이 그 사람에게 보이는 그대로 보인다. <b>시킨 관리자의 인증을 안에 들고 있다</b> — 끝낼 때
 * 그것을 되돌려 끼우고, 감사 기록은 그 사람을 {@code impersonator_user_id} 에 남긴다.
 *
 * <p><b>세션에 JDK 직렬화로 실린다</b>(Spring Session Redis 의 기본). 그래서 {@code serialVersionUID} 를 고정한다 —
 * 모양을 바꾸는 날 이 값을 올리면 그때 대행 중이던 세션은 풀리고, 그것이 맞다.
 */
public final class ImpersonationToken extends AbstractAuthenticationToken {

    @Serial
    private static final long serialVersionUID = 1L;

    private final ShopUser target;
    private final long impersonatorUserId;
    private final Authentication original;
    private final OffsetDateTime startedAt;

    ImpersonationToken(ShopUser target, long impersonatorUserId, Authentication original,
            OffsetDateTime startedAt) {
        super(target.getAuthorities());
        this.target = target;
        this.impersonatorUserId = impersonatorUserId;
        this.original = original;
        this.startedAt = startedAt;
        setAuthenticated(true);
    }

    @Override
    public Object getPrincipal() {
        return target;
    }

    /** 비밀번호를 안 든다. 대행은 대상의 비밀번호를 모른 채로 선다 */
    @Override
    public Object getCredentials() {
        return null;
    }

    public long impersonatorUserId() {
        return impersonatorUserId;
    }

    /** 대행 대상. 주인({@link #getPrincipal})과 같은 사람이다 — 형을 안 거치고 읽으려고 둔다 */
    public long targetUserId() {
        return target.id();
    }

    Authentication original() {
        return original;
    }

    public OffsetDateTime startedAt() {
        return startedAt;
    }

    /**
     * 지금 요청이 대행 중이면 시킨 관리자, 아니면 {@code null}. 감사 기록이 이것을 부른다 —
     * 기록을 남기는 자리마다 넘기게 하면 한 자리가 빠뜨리는 날 그 기록은 대상이 한 것처럼 읽힌다.
     */
    public static Long currentImpersonatorId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication instanceof ImpersonationToken token ? token.impersonatorUserId : null;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ImpersonationToken that
                && super.equals(that)
                && impersonatorUserId == that.impersonatorUserId
                && startedAt.equals(that.startedAt);
    }

    @Override
    public int hashCode() {
        return 31 * super.hashCode() + Long.hashCode(impersonatorUserId);
    }
}
