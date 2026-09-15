package com.projectshop.shop.notification;

import java.time.OffsetDateTime;

import org.springframework.stereotype.Component;

import com.projectshop.shop.auth.PasswordResetMailer;

/**
 * 재설정 링크를 통지 판으로 보낸다(`5c-1`).
 *
 * <p><b>의존이 여기서 뒤집힌다.</b> {@code auth} 가 이 패키지를 부르면 순환이 되므로
 * ({@code auth → notification → consent → auth}), 부르는 쪽이 적은 {@link PasswordResetMailer} 를
 * 이쪽이 구현한다. 그래서 <b>무엇을 보낼지는 여전히 이 패키지가 쥔다.</b>
 */
@Component
class PasswordResetMail implements PasswordResetMailer {

    private final NotificationService notifications;

    PasswordResetMail(NotificationService notifications) {
        this.notifications = notifications;
    }

    @Override
    public void send(long userId, String resetUrl, OffsetDateTime expiresAt) {
        notifications.sendPasswordReset(userId, resetUrl, expiresAt);
    }
}
