package com.projectshop.shop.auth.managed;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import com.projectshop.shop.auth.Allowed;
import com.projectshop.shop.auth.Effect;
import com.projectshop.shop.auth.Scope;
import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;

/**
 * 판정 명세(`D6`)를 Spring Security 의 {@link AuthorizationManager} 로 다시 짠 두 번째 구현이다(청크 `74`).
 * 같은 표를 {@link GrantSource} 로 읽고, 대상을 {@link RequestAuthorizationContext} 의 변수 맵으로 받아
 * {@code PermissionEvaluator} 와 같은 답을 내는지 {@code PermissionMatrixTest} 가 견준다.
 *
 * <p><b>필터체인에 안 건다.</b> 비교가 목적이고, 두 엔진이 같이 판정하면 감사 기록이 둘이 된다.
 * 어느 쪽을 거나는 `75` 의 ADR 이 정한다. 그래서 거부를 감사에 안 남기고 지표도 안 잰다 —
 * 그 둘은 판정이 아니라 {@code decide} 에 붙은 일이다.
 *
 * <p><b>권한을 {@code Authentication} 에 안 싣는다.</b> Spring 의 흔한 길은 권한을 {@code GrantedAuthority}
 * 로 세션에 넣는 것인데, {@code ShopUser} 가 「누구인가」까지만 답하기로 정해 뒀다 — 세션에 실으면 역할을
 * 바꿔도 다시 로그인할 때까지 옛 권한이 돈다. 그래서 판정마다 {@link GrantSource} 로 표를 읽는다.
 *
 * <p><b>대상은 변수 맵({@code Map<String, String>})으로 받는다.</b> 7.1.1 의 {@code RequestAuthorizationContext}
 * 가 값을 싣는 전용 자리는 그것이고 값이 문자열이다 — 요청 속성({@code getRequest()})에도 닿지만 그것은 요청 전체에
 * 퍼지는 자리다. 키가 없으면 그 칸이 비어 있는 대상이다.
 */
public final class ManagedAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    /** 대상 행의 주인 계정 */
    public static final String OWNER_USER_ID = "owner_user_id";
    /** 대상 행이 속한 셀러 */
    public static final String SELLER_ID = "seller_id";
    /** 대상 행의 지금 상태 */
    public static final String STATUS = "status";

    private final GrantSource grants;
    private final String resource;
    private final String action;
    private final Allowed<String> openStatuses;

    /**
     * 동작 하나를 맡는 관리자를 만든다. Spring 의 관례대로 입구마다 하나다.
     *
     * @param openStatuses 이 동작이 열리는 대상 상태. 상태 축이 안 걸린 동작이면 {@link Allowed#everything()}
     */
    public ManagedAuthorizationManager(GrantSource grants, String resource, String action,
            Allowed<String> openStatuses) {
        this.grants = grants;
        this.resource = resource;
        this.action = action;
        this.openStatuses = openStatuses;
    }

    /** {@code role_permission} 과 {@code seller_member} 를 읽는 자리. 운영 구현은 필터체인에 걸 때 만든다(`75`) */
    public interface GrantSource {

        /** 이 사용자의 역할에 달린 {@code resource:action} 권한 줄 */
        List<ScopedGrant> grantsOf(long userId, String resource, String action);

        /** 이 사용자가 속한 셀러 */
        Set<Long> sellersOf(long userId);
    }

    /**
     * 역할 하나에 달린 권한 한 줄.
     *
     * @param grantSellerId 이 역할을 어느 셀러에서 받았나. 전역 부여면 null
     * @param fieldGroups   볼 수 있는 필드 그룹. 빈 집합이면 제한이 없다({@code role_permission_field} 에 행이 없다)
     */
    public record ScopedGrant(String roleCode, Long grantSellerId, Scope scope, Effect effect,
            Set<String> fieldGroups) {
    }

    /**
     * 판정 결과. {@link AuthorizationResult} 는 {@code isGranted()} 하나뿐이라 <b>왜 그렇게 났는지를 못 싣는다</b> —
     * 그래서 갈래마다 record 를 둔다. {@code sealed} 라 받는 쪽 {@code switch} 가 갈래를 빠뜨리면 컴파일이 안 된다.
     */
    public sealed interface Verdict extends AuthorizationResult {

        @Override
        default boolean isGranted() {
            return this instanceof Granted;
        }

        /** 열렸다. {@code winner} 는 가장 넓은 허용 */
        record Granted(ScopedGrant winner, Allowed<String> visibleFieldGroups) implements Verdict {
        }

        /** 거부 규칙이 대상을 덮었다 */
        record DeniedBy(ScopedGrant grant) implements Verdict {
        }

        /** 이 동작에 걸린 권한 줄이 없다 */
        record NoGrant() implements Verdict {
        }

        /** 허용 줄은 있는데 대상이 그 범위 밖이다 */
        record OutOfScope() implements Verdict {
        }

        /** 상태가 걸린 동작인데 대상의 상태가 없다 */
        record StatusMissing() implements Verdict {
        }

        /** 대상의 상태에서 이 동작이 닫혀 있다 */
        record StatusClosed(String status) implements Verdict {
        }

        /** 로그인한 계정이 아니다 */
        record Anonymous() implements Verdict {
        }
    }

    @Override
    public Verdict authorize(Supplier<? extends Authentication> authentication,
            RequestAuthorizationContext context) {
        Authentication auth = authentication.get();
        if (auth == null || !(auth.getPrincipal() instanceof ShopUser user)) {
            return new Verdict.Anonymous();
        }
        List<ScopedGrant> lines = grants.grantsOf(user.id(), resource, action);
        if (lines.isEmpty()) {
            return new Verdict.NoGrant();
        }
        Map<String, String> target = context.getVariables();
        Verdict byGrants = judge(lines, grants.sellersOf(user.id()), user.id(), target);
        if (!byGrants.isGranted() || !openStatuses.restricted()) {
            return byGrants;
        }
        // 상태는 권한 뒤에 본다 — 권한이 없는 사람에게 「상태 때문에 막혔다」를 주지 않는다(`D6`).
        String status = target.get(STATUS);
        if (status == null) {
            return new Verdict.StatusMissing();
        }
        return openStatuses.covers(status) ? byGrants : new Verdict.StatusClosed(status);
    }

    /** 덮는 줄 가운데 거부가 하나라도 있으면 거부, 없으면 가장 넓은 허용이 이긴다 */
    private static Verdict judge(List<ScopedGrant> lines, Set<Long> memberOf, long userId,
            Map<String, String> target) {
        List<ScopedGrant> reaching = lines.stream()
                .filter(grant -> reaches(grant, userId, memberOf, target))
                .toList();

        Optional<ScopedGrant> deny = reaching.stream()
                .filter(grant -> grant.effect() == Effect.DENY)
                .findFirst();
        if (deny.isPresent()) {
            return new Verdict.DeniedBy(deny.get());
        }

        List<ScopedGrant> allows = reaching.stream()
                .filter(grant -> grant.effect() == Effect.ALLOW)
                .toList();
        // max 는 같은 넓이면 앞의 것을 남긴다 — 「먼저 걸린 가장 넓은 줄」이 근거가 된다.
        Optional<ScopedGrant> widest = allows.stream()
                .max(Comparator.comparingInt(grant -> width(grant.scope())));
        if (widest.isEmpty()) {
            return new Verdict.OutOfScope();
        }

        boolean unrestricted = allows.stream().anyMatch(grant -> grant.fieldGroups().isEmpty());
        Set<String> union = allows.stream()
                .flatMap(grant -> grant.fieldGroups().stream())
                .collect(Collectors.toUnmodifiableSet());
        return new Verdict.Granted(widest.get(),
                unrestricted ? Allowed.everything() : Allowed.only(union));
    }

    /**
     * 이 줄의 범위가 대상에 닿나. {@code seller} 는 조직 부여면 받은 셀러만, 전역 부여면 속한 셀러 전부다.
     * {@code default} 가 없어서 범위가 하나 늘면 컴파일이 안 된다.
     */
    private static boolean reaches(ScopedGrant grant, long userId, Set<Long> memberOf,
            Map<String, String> target) {
        return switch (grant.scope()) {
            case ALL -> true;
            case OWN -> idOf(target, OWNER_USER_ID).filter(owner -> owner == userId).isPresent();
            case SELLER -> idOf(target, SELLER_ID)
                    .filter(seller -> grant.grantSellerId() == null
                            ? memberOf.contains(seller)
                            : grant.grantSellerId().equals(seller))
                    .isPresent();
        };
    }

    /** 넓을수록 크다. {@code Scope} 의 넓이를 빌리지 않고 명세(`D6`)에서 다시 적는다 — 두 구현을 견주는 자리다 */
    private static int width(Scope scope) {
        return switch (scope) {
            case OWN -> 1;
            case SELLER -> 2;
            case ALL -> 3;
        };
    }

    private static Optional<Long> idOf(Map<String, String> target, String key) {
        return Optional.ofNullable(target.get(key)).map(Long::valueOf);
    }
}
