package com.projectshop.shop.error;

/**
 * 질의 파라미터 하나가 틀렸다. {@code validation-failed} 로 나가고 {@code errors[].field} 가 그 파라미터를 짚는다.
 *
 * <p><b>{@link ShopException} 을 잇는다.</b> 번역은 같은 핸들러가 하고, 짚을 칸 이름만 더 든다 —
 * Bean Validation 을 안 거치는 파라미터(예: {@code fields})도 화면이 같은 모양으로 읽는다(`D5` 「검증 실패는 어느 필드가 틀렸는지 준다」).
 */
public class InvalidParameterException extends ShopException {

    private static final long serialVersionUID = 1L;

    private final String parameterName;

    /**
     * 틀린 파라미터를 짚는다.
     *
     * @param parameter 요청에 쓴 파라미터 이름
     * @param detail    응답에 그대로 나가는 설명. 개인정보를 넣지 않는다
     */
    public InvalidParameterException(String parameter, String detail) {
        super(ErrorCode.VALIDATION_FAILED, detail);
        this.parameterName = parameter;
    }

    public String parameter() {
        return parameterName;
    }
}
