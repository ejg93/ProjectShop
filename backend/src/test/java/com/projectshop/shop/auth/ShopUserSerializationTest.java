package com.projectshop.shop.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ObjectInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;

/**
 * 세션에 든 옛 {@code ShopUser} 가 필드를 더한 뒤에도 읽히는지 잰다(`Q452`).
 *
 * <p><b>고정물은 필드를 더하기 전의 직렬화 바이트다</b>({@code fixtures/shop-user-v1.ser}, id 42). 세션은 Redis 에
 * JDK 직렬화로 든다(`Q52`) — 배포 직후 옛 세션을 못 읽으면 로그인한 사람이 다 500 을 본다. 같은 {@code serialVersionUID} 에
 * 필드를 더하는 것은 JDK 직렬화의 호환 변경이라 새 칸이 기본값(false)으로 읽힌다 — 그것을 기억이 아니라 바이트로 잰다.
 */
@DisplayName("ShopUser 직렬화 호환")
class ShopUserSerializationTest {

    private static final Path FIXTURE = Path.of("src", "test", "resources", "fixtures", "shop-user-v1.ser");

    @Test
    @DisplayName("필드를 더하기 전 세션이 읽히고 2차 인증 표시는 false 다")
    void readsOldSession() throws Exception {
        ShopUser user;
        try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(FIXTURE))) {
            user = (ShopUser) in.readObject();
        }

        assertThat(user.id()).isEqualTo(42);
        assertThat(user.secondFactorVerified())
                .as("옛 세션이 통과 표시를 들고 나오면 2차 인증을 안 지난 셈이다")
                .isFalse();
    }

    @Test
    @DisplayName("verified 사본은 표시가 true 이고 같은 사람이다")
    void verifiedCopy() {
        ShopUser user = new ShopUser(7, "a@test.local", "hash", true);

        ShopUser verified = user.verified();

        assertThat(verified.secondFactorVerified()).isTrue();
        assertThat(user.secondFactorVerified()).isFalse();
        assertThat(verified).isEqualTo(user);
        assertThat(verified.getPassword()).as("세션에 앉는 사본이 해시를 들면 안 된다").isNull();
    }
}
