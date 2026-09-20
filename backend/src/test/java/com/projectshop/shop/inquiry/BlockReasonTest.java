package com.projectshop.shop.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

/**
 * 문의 차단 사유가 바깥 값을 어떻게 받나(`Q123`).
 *
 * <p><b>관문이 둘인데도 잰다.</b> 요청 record 의 {@code @Pattern} 이 먼저 걸러서 정상 경로에서는
 * 여기까지 틀린 값이 안 온다 — 그래도 재는 이유는 그 애너테이션이 <b>그 입구에만</b> 걸려 있어서다.
 * 입구가 하나 더 생기면 이것만 남는다.
 *
 * <p>컨테이너를 안 탄다. 값을 고르는 것과 던지는 것뿐이라 DB 가 낄 자리가 없다(`D15`).
 */
@DisplayName("문의 차단 사유 값")
class BlockReasonTest {

    @Test
    @DisplayName("바깥에서 온 모르는 값은 400 이다")
    void 모르는_값은_요청_오류다() {
        assertThatThrownBy(() -> BlockReason.ofRequest("SPAM"))
                .as("경계에서 바꾸므로 이 예외가 곧 응답 코드다")
                .isInstanceOf(ShopException.class)
                .extracting(e -> ((ShopException) e).code())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("소문자 표기는 안 받는다")
    void 소문자는_안_받는다() {
        assertThatThrownBy(() -> BlockReason.ofRequest("advertisement"))
                .as("바깥 표기는 대문자다(`D5`). 저장값과 같은 글자를 받으면 두 표기가 섞인다")
                .isInstanceOf(ShopException.class);
    }

    @Test
    @DisplayName("아는 값은 고르고 저장값은 소문자다")
    void 아는_값은_고른다() {
        assertThat(BlockReason.ofRequest("ADVERTISEMENT")).isEqualTo(BlockReason.ADVERTISEMENT);
        assertThat(BlockReason.ADVERTISEMENT.code()).isEqualTo("advertisement");
        assertThat(BlockReason.ABUSE.code()).isEqualTo("abuse");
    }
}
