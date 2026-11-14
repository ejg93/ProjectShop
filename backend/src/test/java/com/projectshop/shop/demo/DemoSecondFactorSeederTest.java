package com.projectshop.shop.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.auth.SecondFactorLogin;
import com.projectshop.shop.auth.SecondFactorService;

/**
 * 연습 계정 시더가 예약 도메인의 고객 외 계정에만 고정 비밀을 등록하는지 잰다(`Q451`).
 *
 * <p><b>도메인이 고르는 기준이다.</b> {@code @example.com} 은 RFC 2606 예약 도메인이라 진짜 셀러의 주소일 수 없다 —
 * 그 밖의 계정에 공개된 고정 비밀이 붙으면 2차 인증이 없는 것과 같다.
 */
@DisplayName("연습 계정 2차 인증 시더")
class DemoSecondFactorSeederTest extends PostgresTestBase {

    private static final byte[] FIXED = DemoSecondFactorSeeder.FIXED_SECRET.getBytes(StandardCharsets.US_ASCII);

    @Autowired
    JdbcClient jdbc;

    @Autowired
    SecondFactorService service;

    AuthFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);
    }

    @Test
    @DisplayName("예약 도메인의 미등록 관리자는 고정 비밀로 등록된다")
    void enrollsDemoAdmin() {
        long admin = user("seed-admin@example.com", "admin");

        seeder().run(null);

        assertThat(service.enrolled(admin)).isTrue();
        Instant now = Instant.now();
        assertThat(service.verify(admin, SecondFactorLogin.codeAt(FIXED, now), now)).isTrue();
    }

    @Test
    @DisplayName("이미 등록된 계정은 안 바뀐다")
    void keepsExistingEnrollment() {
        long admin = user("seed-admin@example.com", "admin");
        byte[] own = "own-second-factor-00".getBytes(StandardCharsets.US_ASCII);
        service.enroll(admin, own, 0);

        seeder().run(null);

        Instant now = Instant.now();
        assertThat(service.verify(admin, SecondFactorLogin.codeAt(own, now), now))
                .as("사람이 등록한 비밀을 시더가 덮으면 안 된다")
                .isTrue();
    }

    @Test
    @DisplayName("예약 도메인 밖 관리자와 예약 도메인 고객은 안 건드린다")
    void skipsOthers() {
        long realAdmin = user("seed-admin@test.local", "admin");
        long customer = user("seed-customer@example.com", "customer");

        seeder().run(null);

        assertThat(service.enrolled(realAdmin))
                .as("공개된 고정 비밀이 진짜 계정에 붙으면 안 된다")
                .isFalse();
        assertThat(service.enrolled(customer)).isFalse();
    }

    private long user(String email, String role) {
        long id = fixture.insertUser(email, "시더");
        fixture.grantGlobal(id, "customer");
        if (!"customer".equals(role)) {
            fixture.grantGlobal(id, role);
        }
        return id;
    }

    private DemoSecondFactorSeeder seeder() {
        return new DemoSecondFactorSeeder(jdbc, service);
    }
}
