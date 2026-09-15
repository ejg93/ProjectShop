package com.projectshop.shop.account;

import java.time.OffsetDateTime;

/**
 * 이메일 확인 링크를 보내는 자리(`5e-1`).
 *
 * <p><b>왜 인터페이스인가.</b> {@code account} 가 {@code notification} 을 직접 부르면
 * 자원 패키지끼리 순환이 생긴다 — `5c-1` 이 같은 자리에서 걸렸고 같은 방법으로 푼다.
 * 부르는 쪽이 필요한 모양을 여기 적고 <b>보내는 쪽이 그것을 구현한다.</b>
 *
 * <p><b>받는 주소가 계정에 적힌 것과 다르다.</b> 다른 통지는 계정 주소로 가는데 이것만
 * <b>아직 확인 안 된 새 주소</b>로 간다 — 받을 수 있다는 것이 곧 확인이라서다.
 */
public interface EmailChangeMailer {

    /**
     * @param newEmail   보낼 주소. 계정에 적힌 것이 아니라 <b>바꾸려는 주소</b>다
     * @param confirmUrl 토큰 원문이 실린 링크
     * @param expiresAt  링크가 죽는 시각
     */
    void send(long userId, String newEmail, String confirmUrl, OffsetDateTime expiresAt);
}
