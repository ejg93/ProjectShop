package com.projectshop.shop.error;

/**
 * 서비스가 던지는 유일한 업무 예외.
 *
 * <p><b>서비스는 무엇이 잘못됐는지만 말하고 그것이 몇 번인지는 안 정한다</b>(`D23`).
 * 상태 코드를 서비스가 정하면 웹을 아는 서비스가 되고, 배치나 다른 입구에서 재사용할 때 어색해진다.
 * 번역은 {@link ApiExceptionHandler} 한 곳에서 한다.
 *
 * <p>클래스를 종류마다 만들지 않은 이유는 {@link ErrorCode} 에 적었다 —
 * 오류 목록이 한 화면에 있어야 `D5` 의 표와 대조된다.
 */
public class ShopException extends RuntimeException {

    private final transient ErrorCode code;

    public ShopException(ErrorCode code) {
        super(code.title());
        this.code = code;
    }

    /**
     * 기본 문구 대신 이 자리에서만 쓰는 설명을 붙인다.
     *
     * <p><b>개인정보나 내부 구조를 넣지 않는다</b>(`D14`·`D16`). 이 값은 그대로 응답에 나간다 —
     * 식별자는 괜찮고, 이메일·SQL·스택은 안 된다.
     */
    public ShopException(ErrorCode code, String detail) {
        super(detail);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
