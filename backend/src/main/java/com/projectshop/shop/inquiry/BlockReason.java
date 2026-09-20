package com.projectshop.shop.inquiry;

import java.util.Arrays;
import java.util.Locale;

import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

/**
 * 문의를 가리는 사유(`Q123`, {@code inquiry.blocked_reason}).
 *
 * <p><b>DB 가 이미 값을 닫아 뒀는데</b>({@code inquiry_blocked_reason_check}) 서비스가 그것을
 * {@code String} 상수 둘로 들고 입구도 {@code String} 이었다 — 틀린 값이 컴파일에 안 걸린다
 * (`D23` 축 2: 타입 1위, 제약 2위).
 *
 * <h2>경계는 문자열, 안쪽은 열거형</h2>
 *
 * <p>사용자 결정 2026-09-20, `Q121` 이 굳힌 모양이다. 요청 record 는 {@code String} 을 그대로 들고
 * 컨트롤러가 여기서 바꾼다 — JSON 도 상태 코드도 안 바뀐다.
 *
 * <p><b>이 자리는 관문이 이미 둘이다.</b> 요청 record 의 {@code @Pattern} 이 먼저 걸러서
 * 정상 경로에서는 {@link #ofRequest} 가 실패할 일이 없다. 그래도 두는 이유는 그 애너테이션이
 * <b>그 입구에만</b> 걸려 있어서다 — 입구가 하나 더 생기면 따라오지 않는다.
 *
 * <h2>표기가 둘이다</h2>
 *
 * <p>바깥은 대문자({@code ADVERTISEMENT}, `D5` 가 정했다)고 DB 는 소문자다. 그 변환을
 * 컨트롤러의 도우미가 하던 것을 여기로 들인다 — 값과 그 표기를 같은 자리에 둔다.
 */
enum BlockReason {

    /** 광고성 정보. 정보통신망법 제50조의7이 게시 거부·삭제를 허용한 자리다 */
    ADVERTISEMENT,

    /** 욕설·비방. 약관에서 온다 */
    ABUSE;

    /**
     * DB 에 들어가는 값. {@code EnumConstraintTest} 가 이 이름의 메서드를 리플렉션으로 읽어
     * 제약 목록과 대조한다 — 그래서 이름을 바꾸지 않는다.
     */
    String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * 바깥에서 온 값으로 고른다. <b>대문자 표기를 받고 모르면 400 이다.</b>
     *
     * <p>{@link #of} 와 갈라 둔 이유는 `Q121` 과 같다 — 저쪽은 DB 값이라 모르는 값이 표가 깨진 것이고
     * 이쪽은 요청이 틀린 것이다.
     */
    static BlockReason ofRequest(String name) {
        return Arrays.stream(values())
                .filter(reason -> reason.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new ShopException(ErrorCode.VALIDATION_FAILED));
    }

    /** DB 에서 읽은 값으로 고른다. 제약이 값을 닫아 뒀으므로 모르는 값은 표가 깨진 것이다 */
    static BlockReason of(String code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(reason -> reason.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("모르는 차단 사유다: " + code));
    }
}
