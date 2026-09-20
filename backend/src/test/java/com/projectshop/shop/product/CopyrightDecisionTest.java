package com.projectshop.shop.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

/**
 * 저작권 판정이 바깥 값을 어떻게 받나(`Q121`).
 *
 * <p><b>서비스가 아니라 여기를 잰다.</b> 서비스 서명이 열거형이 되면서 잘못된 값을 넘기는 것이
 * 컴파일에 걸리고, 그래서 「모르는 값이 400 이다」를 재던 자리가 서비스에서 사라졌다 —
 * 그 답은 이제 변환하는 이 자리가 낸다.
 *
 * <p>컨테이너를 안 탄다. 값을 고르는 것과 던지는 것뿐이라 DB 가 낄 자리가 없다(`D15`).
 */
@DisplayName("저작권 판정 값")
class CopyrightDecisionTest {

    @Test
    @DisplayName("바깥에서 온 모르는 값은 400 이다")
    void 모르는_값은_요청_오류다() {
        assertThatThrownBy(() -> CopyrightDecision.ofRequest("taken-down"))
                .as("경계에서 바꾸므로 이 예외가 곧 응답 코드다. 500 이 되면 고치기 전과 답이 달라진다")
                .isInstanceOf(ShopException.class)
                .extracting(e -> ((ShopException) e).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        assertThat(ErrorCode.VALIDATION_FAILED.status().value())
                .as("그 오류가 400 이 아니게 되면 이 청크가 지키려던 것이 조용히 바뀐다")
                .isEqualTo(400);
    }

    @Test
    @DisplayName("아는 값은 그대로 고른다")
    void 아는_값은_고른다() {
        assertThat(CopyrightDecision.ofRequest("taken_down")).isEqualTo(CopyrightDecision.TAKEN_DOWN);
        assertThat(CopyrightDecision.ofRequest("rejected")).isEqualTo(CopyrightDecision.REJECTED);
    }

    @Test
    @DisplayName("DB 에서 읽은 모르는 값은 표가 깨진 것이라 다르게 터진다")
    void DB_값은_상태_오류다() {
        assertThatThrownBy(() -> CopyrightDecision.of("taken-down"))
                .as("제약이 값을 닫아 뒀으므로 이쪽은 요청이 틀린 것이 아니다 — 둘을 같은 실패로 묶으면 400 과 500 이 섞인다")
                .isInstanceOf(IllegalStateException.class);
    }
}
