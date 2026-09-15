package com.projectshop.shop.notification;

import java.time.OffsetDateTime;

import org.springframework.stereotype.Component;

import com.projectshop.shop.account.EmailChangeMailer;

/**
 * 이메일 확인 링크를 통지 판으로 보낸다(`5e-1`).
 *
 * <p>의존을 여기서 뒤집는다 — `5c-1` 의 {@code PasswordResetMail} 과 같은 모양이다.
 * <b>무엇을 어디로 보낼지는 이 패키지가 계속 쥔다.</b>
 */
@Component
class EmailChangeMail implements EmailChangeMailer {

    private final NotificationService notifications;

    EmailChangeMail(NotificationService notifications) {
        this.notifications = notifications;
    }

    @Override
    public void send(long userId, String newEmail, String confirmUrl, OffsetDateTime expiresAt) {
        notifications.sendEmailChange(userId, newEmail, confirmUrl, expiresAt);
    }
}
