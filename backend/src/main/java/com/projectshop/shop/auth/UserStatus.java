package com.projectshop.shop.auth;

import java.util.Arrays;

/**
 * 계정의 업무 상태. {@code app_user.status} 의 값이다.
 *
 * <p><b>수명과 다르다.</b> 탈퇴는 {@code deleted_at} 이 답하고 여기는 「지금 쓸 수 있나」만 답한다
 * (`V8`). 탈퇴한 계정도 파기 전까지 {@code status} 는 {@code active} 그대로다.
 *
 * <p><b>{@code auth} 에 둔다.</b> 이 값을 자바에서 읽는 곳이 {@link ShopUserDetailsService}
 * 하나뿐이고, {@code account} 에 두면 {@code auth → account} 의존이 새로 생긴다 —
 * {@code account} 가 이미 {@code auth} 를 부르므로 순환이 하나 는다.
 * {@link StatusPolicy}·{@link FieldGroup} 이 같은 이유로 잡은 모양이다(`D23` 「의존」).
 */
enum UserStatus {

    ACTIVE, SUSPENDED;

    /** 저장값. DB 는 소문자다(`D5` 「형식」) */
    String code() {
        return name().toLowerCase();
    }

    /**
     * 모르는 값이면 터진다.
     *
     * <p><b>생 문자열 비교를 이걸로 바꾼 이유가 여기 있다.</b> {@code "active".equals(status)} 는
     * 모르는 값을 만나면 조용히 {@code false} 를 준다 — 새 상태가 제약에 늘면
     * <b>그 계정이 이유 없이 로그인만 막힌다.</b> 오류도 로그도 안 남는다.
     */
    static UserStatus of(String code) {
        return Arrays.stream(values())
                .filter(status -> status.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("모르는 계정 상태다: " + code));
    }
}
