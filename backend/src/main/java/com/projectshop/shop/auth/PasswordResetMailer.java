package com.projectshop.shop.auth;

import java.time.OffsetDateTime;

/**
 * 재설정 링크를 보내는 자리(`5c-1`).
 *
 * <p><b>왜 인터페이스인가.</b> {@code auth} 가 {@code notification} 을 직접 부르면
 * <b>auth → notification → consent → auth</b> 순환이 생긴다({@code ArchitectureTest} 가 잡았다).
 * 부르는 쪽이 필요한 모양을 여기 적고 <b>보내는 쪽이 그것을 구현한다</b> — 의존이 한 방향이 된다.
 *
 * <p>구현은 {@code notification} 패키지에 있다. 광고 관문을 건너뛸 자리가 안 생기게
 * 그 패키지가 무엇을 보낼지 계속 쥔다.
 */
public interface PasswordResetMailer {

    /**
     * @param resetUrl  토큰 원문이 실린 링크. <b>원문이 나가는 유일한 자리다</b>
     * @param expiresAt 링크가 죽는 시각
     */
    void send(long userId, String resetUrl, OffsetDateTime expiresAt);
}
