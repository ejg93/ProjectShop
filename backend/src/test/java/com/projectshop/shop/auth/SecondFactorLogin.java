package com.projectshop.shop.auth;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 다른 패키지의 시험이 2차 인증을 등록하고 코드를 만드는 입구(`Q451`·`Q452`).
 *
 * <p>{@link Totp} 는 {@code auth} 밖에 안 연다 — 운영 코드가 계산기를 직접 부르면 재사용 금지(저장소의 조건부 UPDATE)를
 * 비껴간다. 시험만 이 입구로 코드를 얻는다.
 *
 * <p><b>코드는 아직 안 쓴 스텝으로 고른다</b>({@link #codeFor}). 같은 계정이 30초 안에 두 번 로그인하면 현재 스텝 코드는
 * 이미 쓰여 거부된다(RFC 6238 §5.2) — 허용 창(앞뒤 한 스텝) 안에서 {@code last_used_step} 보다 큰 첫 스텝을 쓴다.
 */
public final class SecondFactorLogin {

    /** 시험 계정의 고정 비밀(ASCII 20바이트). 연습 계정 비밀과 다르다 */
    private static final byte[] TEST_SECRET = "test-second-factor-0".getBytes(StandardCharsets.US_ASCII);

    private SecondFactorLogin() {
    }

    /** 그 비밀의 그 시각 코드 */
    public static String codeAt(byte[] secret, Instant now) {
        return Totp.codeAt(secret, Totp.stepAt(now));
    }

    /** 등록 응답이 준 Base32 비밀의 지금 코드 */
    public static String codeForBase32(String secretBase32) {
        return codeAt(Totp.fromBase32(secretBase32), Instant.now());
    }

    /** 시험 비밀로 등록한다. 등록 확인 스텝은 0 이라 지금 코드가 바로 통한다 */
    public static void enroll(SecondFactorService service, long userId) {
        service.enroll(userId, TEST_SECRET, 0);
    }

    /** {@link #enroll} 한 계정이 지금 낼 코드 — 허용 창 안에서 아직 안 쓴 첫 스텝 */
    public static String codeFor(JdbcClient jdbc, long userId) {
        long lastUsed = jdbc.sql("select last_used_step from user_second_factor where user_id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .single();
        long current = Totp.stepAt(Instant.now());
        long step = Math.max(current - 1, lastUsed + 1);
        if (step > current + 1) {
            throw new IllegalStateException("허용 창 안의 스텝을 다 썼다 — 같은 계정이 30초 안에 네 번 로그인했다");
        }
        return Totp.codeAt(TEST_SECRET, step);
    }
}
