package com.projectshop.shop.support;

import java.util.List;

/**
 * 쉼표로 이어 온 코드 문자열을 목록으로 가른다.
 *
 * <p><b>왜 이어 오나.</b> {@code string_agg} 로 한 줄에 묶어 오면 조회가 한 번이다 —
 * 사람마다 역할을 따로 읽으면 목록 길이만큼 질의가 는다.
 *
 * <p><b>서비스 안에 안 둔다.</b> 입력만으로 답이 정해지는 계산이라 자기 클래스로 나가야
 * 시험된다(`D15`) — {@code ArchitectureTest} 가 「서비스의 {@code static} 이 바깥을 하나도
 * 안 부르면 그것은 계산이다」로 그것을 막는다.
 *
 * <p><b>빈 문자열과 없는 것을 같이 본다.</b> {@code string_agg} 는 짝이 없으면 {@code null} 을
 * 주고 {@code coalesce} 를 씌우면 빈 문자열을 준다 — 둘 다 「하나도 없다」라 가를 이유가 없다.
 */
public final class CommaCodes {

    private CommaCodes() {
    }

    public static List<String> split(String joined) {
        return joined == null || joined.isEmpty() ? List.of() : List.of(joined.split(","));
    }
}
