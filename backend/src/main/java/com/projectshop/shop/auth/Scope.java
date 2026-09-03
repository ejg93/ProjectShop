package com.projectshop.shop.auth;

import java.util.Arrays;

/**
 * 규칙 한 줄이 덮는 범위. 저장값은 {@code role_permission.scope} 이고
 * 목록은 {@code role_permission_scope_check} 다(`V2`).
 *
 * <p><b>순서가 있다.</b> 같은 동작에 허용 규칙이 여럿 걸리면 {@link #width()} 가 큰 쪽이
 * 이긴다(`D6` 「스코프 해석」) — 넓은 규칙을 받은 사람이 좁은 규칙 때문에 막히면 안 된다.
 * 넓이를 {@code ordinal()} 로 안 읽는다. 상수 순서를 바꾸는 순간 판정이 조용히 뒤집힌다.
 *
 * <p>{@link #SELLER} 의 뜻이 <b>부여 방식에 따라 갈린다</b> — 조직 역할로 받았으면 받은 그
 * 셀러만, 전역으로 받았으면 사용자가 속한 모든 셀러다. 그 갈림은 값이 아니라
 * {@code user_role.seller_id} 가 들어서 {@link PermissionEvaluator} 가 판단한다.
 *
 * <p>열거형으로 두는 근거는 `D23` 「가르는 물음」이다 — <b>값이 하나 늘면 코드를 고쳐야 한다.</b>
 * 넓이 순서와 덮는 조건이 값마다 따로라, 표에 행만 넣으면 <b>넓이 0에 아무것도 안 덮는</b>
 * 도달 불가능한 값이 된다. 전에 실제로 그 모양이었다({@code default -> 0}, {@code default -> false}).
 *
 * <p><b>{@code public} 인 이유는 응답에 실려서다.</b> {@code GET /api/me/permissions} 가
 * {@code PermissionCatalog.Entry.scopes} 로 내리고, 그 자리를 {@code String} 으로 두면
 * 아무 문자열이나 들어간다 — 타입이 1위 강제 지점인 자리를 문서로 내리는 것이 된다
 * (`D23` 축 2). 다른 열거형과 달리 {@code auth} 밖에서도 보인다.
 */
public enum Scope {

    /** 자기 것만. 대상 행의 주인이 그 사용자일 때만 덮는다 */
    OWN(1),

    /** 그 셀러의 것. 부여 방식이 「어느 셀러냐」를 정한다 */
    SELLER(2),

    /** 제한이 없다. 대상을 안 본다 */
    ALL(3);

    private final int width;

    Scope(int width) {
        this.width = width;
    }

    /** 넓을수록 크다. 허용 규칙이 여럿 걸릴 때 어느 쪽이 이기는지를 정한다 */
    int width() {
        return width;
    }

    /** 저장값. DB 는 소문자고 응답은 대문자다(`D5` 「형식」) */
    String code() {
        return name().toLowerCase();
    }

    /**
     * 저장값을 범위로 되돌린다.
     *
     * <p><b>모르는 값이면 터진다.</b> {@code check} 가 이미 막고 있으므로 여기 오는 모르는 값은
     * <b>마이그레이션과 이 enum 이 어긋났다</b>는 뜻이다.
     *
     * <p>전에는 {@code switch} 의 {@code default} 가 그 값을 <b>넓이 0에 아무것도 안 덮는</b>
     * 규칙으로 읽었다. 그러면 권한을 준 사람은 줬다고 믿는데 아무도 안 통과하고,
     * <b>오류도 로그도 안 남는다.</b>
     */
    static Scope of(String code) {
        return Arrays.stream(values())
                .filter(scope -> scope.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("모르는 권한 범위다: " + code));
    }
}
