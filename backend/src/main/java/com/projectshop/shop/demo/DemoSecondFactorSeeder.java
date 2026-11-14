package com.projectshop.shop.demo;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.projectshop.shop.auth.SecondFactorService;

/**
 * 연습 계정 중 셀러·관리자·감사자에 고정 2차 인증 비밀을 기동할 때 등록한다(`Q451`). {@code local}·{@code demo} 프로필에서만 돈다.
 *
 * <p><b>면제 분기를 안 둔다</b>(2026-11-13 사용자 결정). 연습 계정도 운영과 같은 두 단계 로그인을 지나고, 로그인 화면이
 * 이 비밀로 코드를 채운다(`Q454`). 인증 코드에 「이 계정은 건너뛴다」가 없으니 그 분기가 운영에서 열릴 길도 없다.
 *
 * <p><b>예약 도메인만 고른다.</b> {@code @example.com} 은 RFC 2606 예약 도메인이라 진짜 셀러의 주소일 수 없다 —
 * 계정 목록 상수를 여기 두면 시드와 갈린다. 이미 등록된 계정은 안 건드린다(사람이 등록한 비밀을 덮지 않는다).
 */
@Component
@Profile({"local", "demo"})
public class DemoSecondFactorSeeder implements ApplicationRunner {

    /** 연습 계정의 고정 비밀(ASCII 20바이트). 로그인 화면이 같은 값의 Base32 를 든다 — {@code demo-accounts.ts} 와 대조한다 */
    public static final String FIXED_SECRET = "projectshop-demo-2fa";

    private static final Logger log = LoggerFactory.getLogger(DemoSecondFactorSeeder.class);

    private final JdbcClient jdbc;
    private final SecondFactorService secondFactors;

    DemoSecondFactorSeeder(JdbcClient jdbc, SecondFactorService secondFactors) {
        this.jdbc = jdbc;
        this.secondFactors = secondFactors;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!secondFactors.available()) {
            log.warn("연습 계정 2차 인증 시드를 건너뛴다. SECOND_FACTOR_SECRET_KEY 가 없다");
            return;
        }
        List<Long> targets = jdbc.sql("""
                        select u.user_id
                          from app_user u
                         where u.email like '%@example.com'
                           and u.deleted_at is null
                           and exists (select 1
                                         from user_role ur
                                         join role r on r.role_id = ur.role_id
                                        where ur.user_id = u.user_id
                                          and r.code <> 'customer')
                         order by u.user_id
                        """)
                .query(Long.class)
                .list();
        byte[] secret = FIXED_SECRET.getBytes(StandardCharsets.US_ASCII);
        int enrolled = 0;
        for (long userId : targets) {
            if (!secondFactors.enrolled(userId)) {
                secondFactors.enroll(userId, secret, 0);
                enrolled++;
            }
        }
        int total = targets.size();
        log.info("연습 계정 2차 인증 시드: 대상 {}, 새로 등록 {}", total, enrolled);
    }
}
