package com.projectshop.shop.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 메일을 보내는 흉내를 낸다. 로컬 전용이라 실제로 나가는 곳이 없다(`D18` 「채널은 이메일 하나」).
 *
 * <p><b>인터페이스를 안 두었다.</b> {@code MockPaymentGateway} 와 같은 판단이다 —
 * 갈아끼울 것이 실물 SMTP 하나뿐이라 「세어 보고 하나뿐이면 안 만든다」에 걸린다.
 * 배포가 생기면 이 클래스를 SMTP 로 바꾸고, 그때 구현이 둘이 되면 그 자리에서 인터페이스를 판다.
 *
 * <p><b>주소도 본문도 로그에 안 넣는다</b>(`D16`). 남기는 것은 발송 id 와 결과뿐이고,
 * 무엇을 보냈는지는 {@code notification_body} 가 들고 있다.
 */
@Component
public class MockNotificationSender {

    /** 이 도메인으로 보내면 실패한다. 실패 경로가 실제로 도는지 보는 자리다 */
    private static final String FAILING_DOMAIN = "@bounce.invalid";

    /** 받는 곳이 없다는 뜻. 다시 보내도 같은 답이라 결정적 실패다(`D18` 「실패와 재시도」) */
    private static final String FAILURE_REASON = "unknown_recipient";

    private static final Logger log = LoggerFactory.getLogger(MockNotificationSender.class);

    /**
     * 보낸 결과.
     *
     * @param succeeded     나갔나
     * @param failureReason 못 나갔으면 종류. 나갔으면 {@code null}
     */
    public record Result(boolean succeeded, String failureReason) {

        /** 나갔다 */
        public static Result sent() {
            return new Result(true, null);
        }

        public static Result failed(String reason) {
            return new Result(false, reason);
        }
    }

    /**
     * 보낸다. 주소가 {@code @bounce.invalid} 로 끝나면 실패한다.
     *
     * @param address 받는 주소. 계정에서 온다
     * @param subject 완성된 제목
     * @param body    완성된 본문
     */
    public Result send(String address, String subject, String body) {
        if (address.endsWith(FAILING_DOMAIN)) {
            log.info("모의 발송 실패 이유={}", FAILURE_REASON);
            return Result.failed(FAILURE_REASON);
        }

        log.info("모의 발송 성공 제목길이={} 본문길이={}", subject.length(), body.length());
        return Result.sent();
    }
}
