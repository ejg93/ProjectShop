package com.projectshop.shop.product;

import java.util.Arrays;

import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

/**
 * 저작권 신고에 내리는 판정(`Q121`, {@code copyright_report.decision}).
 *
 * <p><b>DB 가 이미 값을 닫아 뒀는데</b>({@code copyright_report_decision_check}) 서비스가 그것을
 * {@code String} 으로 받고 본문에서 목록으로 막고 있었다 — 부르는 쪽이 아무 글자나 넘길 수 있고
 * 틀린 값은 <b>실행해 봐야</b> 걸린다(`D23` 축 2: 타입 1위, 제약 2위).
 *
 * <h2>경계는 문자열, 안쪽은 열거형</h2>
 *
 * <p>사용자 결정 2026-09-20. 요청 record 는 {@code String} 을 그대로 들고 <b>컨트롤러가 여기서
 * 바꾼다</b>. 그래서 JSON 도 상태 코드도 안 바뀐다 — 모르는 값은 {@link #ofRequest} 가
 * {@code VALIDATION_FAILED} 로 400 을 내고, 그것이 고치기 전과 같은 답이다.
 *
 * <p>요청 record 에 열거형을 들면 Jackson 이 대신 막아 주지만 JSON 값이 소문자·밑줄이라
 * 직렬화 애너테이션이 붙고, <b>이 저장소에 그 선례가 없다.</b>
 */
enum CopyrightDecision {

    /** 신고를 받아들여 사진을 내린다 */
    TAKEN_DOWN,

    /** 신고를 물리친다. 사진은 그대로 둔다 */
    REJECTED;

    /**
     * DB 에 들어가는 값. {@code EnumConstraintTest} 가 이 이름의 메서드를 리플렉션으로 읽어
     * 제약 목록과 대조한다 — 그래서 이름을 바꾸지 않는다.
     */
    String code() {
        return name().toLowerCase();
    }

    /**
     * 바깥에서 온 값으로 고른다. <b>모르면 400 이다.</b>
     *
     * <p>{@link #of} 와 갈라 둔 이유: 저쪽은 DB 에서 읽은 값이라 모르는 값이 <b>표가 깨진 것</b>이고,
     * 이쪽은 남이 보낸 값이라 <b>요청이 틀린 것</b>이다. 같은 실패로 묶으면 500 과 400 이 섞인다.
     */
    static CopyrightDecision ofRequest(String code) {
        return Arrays.stream(values())
                .filter(decision -> decision.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new ShopException(ErrorCode.VALIDATION_FAILED));
    }

    /** DB 에서 읽은 값으로 고른다. 제약이 값을 닫아 뒀으므로 모르는 값은 표가 깨진 것이다 */
    static CopyrightDecision of(String code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(decision -> decision.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("모르는 저작권 판정이다: " + code));
    }
}
