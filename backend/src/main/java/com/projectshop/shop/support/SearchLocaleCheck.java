package com.projectshop.shop.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 기동 때 한 번, 이 DB 가 한글을 trigram 조각으로 내는지 재서 로그에 남긴다(`Q246`).
 *
 * <p>상품 검색의 3-gram 인덱스(`V123`)는 DB 의 {@code ctype} 이 한글을 낱말 글자로 볼 때만 거른다 —
 * {@code C} 로케일이면 한글이 조각에서 빠져 검색이 순차 스캔이 된다. <b>답은 맞고 느릴 뿐이라</b> 아무것도 안 깨진다
 * (`stack.md` 「`pg_trgm` 은 3자 미만이면」). 운영 Postgres 에 직접 닿는 길이 없어서(TCP 프록시 없음)
 * <b>모든 환경이 스스로 보고하게 했다</b> — 2026-10-10 사용자 결정 (가).
 *
 * <p><b>기동은 안 막는다.</b> 검색 결과는 맞고, 막으면 로케일 하나 때문에 쇼핑몰 전체가 안 뜬다.
 * 로그에는 로케일 이름과 조각 수만 나간다 — 개인정보가 없다(`D16`).
 */
@Component
final class SearchLocaleCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SearchLocaleCheck.class);

    /** 조각을 재는 견본. 상품 이름에 흔한 낱말이면 된다 */
    private static final String SAMPLE = "운동화";

    private final JdbcClient jdbc;

    SearchLocaleCheck(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 잰 값. {@code hangulTrigrams} 가 0 이면 한글이 조각에서 빠진다 */
    record Result(String ctype, int hangulTrigrams) {
    }

    @Override
    public void run(ApplicationArguments args) {
        Result result;
        try {
            result = measure(jdbc);
        } catch (DataAccessException e) {
            // 확장이 없으면 `V123` 이 먼저 죽었을 것이라 있을 수 없는 길이다. 그래도 기동은 간다.
            log.warn("검색 로케일을 못 쟀다 — show_trgm 이 실패했다(Q246): {}", e.getMessage());
            return;
        }
        log.info("검색 로케일 datctype={} 한글_조각={}", result.ctype(), result.hangulTrigrams());
        if (result.hangulTrigrams() == 0) {
            log.warn("한글이 trigram 조각에서 빠진다 — V123 인덱스가 못 거른다(Q246)");
        }
    }

    /** 기동 없이 재려고 정적으로 둔다 — 판정이 스프링을 안 탄다 */
    static Result measure(JdbcClient jdbc) {
        String ctype = jdbc.sql("select datctype from pg_database where datname = current_database()")
                .query(String.class)
                .single();
        int trigrams = jdbc.sql("select coalesce(array_length(show_trgm(:sample), 1), 0)")
                .param("sample", SAMPLE)
                .query(Integer.class)
                .single();
        return new Result(ctype, trigrams);
    }
}
