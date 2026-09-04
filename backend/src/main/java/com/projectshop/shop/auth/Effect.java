package com.projectshop.shop.auth;

import java.util.Arrays;

/**
 * 규칙 한 줄이 여는 것인가 닫는 것인가. 저장값은 {@code role_permission.effect} 이고
 * 목록은 {@code role_permission_effect_check} 다(`V2`).
 *
 * <p><b>이 값이 판정의 첫 갈림이다.</b> {@link PermissionEvaluator} 가 {@link #DENY} 를 먼저
 * 훑고 하나라도 걸리면 거기서 끝낸다 — 나머지 허용 규칙을 아무리 넓게 줘도 안 뒤집힌다
 * (`D6` 「판정 순서」).
 *
 * <p>열거형으로 두는 근거는 `D23` 「가르는 물음」이다 — <b>값이 하나 늘면 코드를 고쳐야 한다.</b>
 * 세 번째 효과가 생기면 훑는 순서부터 다시 정해야 하므로, 표에 행만 넣는 것으로는 안 돈다.
 *
 * <p>{@code role_permission_field.effect} 는 자기 {@code check} 가 없다. 그 열은
 * {@code role_permission} 을 가리키는 <b>복합 외래키의 일부</b>라 값 목록이 구조로 닫혀 있다
 * (`D23` 축 2 의 1위) — 같은 목록을 두 번 적을 이유가 없다.
 */
enum Effect {

    /** 연다. 여럿이 걸리면 가장 넓은 범위가 이긴다 */
    ALLOW,

    /** 닫는다. <b>하나만 걸려도 이긴다</b> — 범위 넓이를 안 본다 */
    DENY;

    /** 저장값. DB 는 소문자고 응답은 대문자다(`D5` 「형식」) */
    String code() {
        return name().toLowerCase();
    }

    /**
     * 저장값을 효과로 되돌린다.
     *
     * <p><b>모르는 값이면 터진다.</b> {@code check} 가 이미 막고 있으므로 여기 오는 모르는 값은
     * <b>마이그레이션과 이 enum 이 어긋났다</b>는 뜻이다.
     *
     * <p>전에는 {@code "deny".equals(effect)} 였다. 그 비교는 모르는 값을 <b>허용으로 읽는다</b> —
     * 닫으려고 넣은 규칙이 조용히 여는 규칙이 되고, 아무 데도 안 남는다.
     */
    static Effect of(String code) {
        return Arrays.stream(values())
                .filter(effect -> effect.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("모르는 규칙 효과다: " + code));
    }
}
