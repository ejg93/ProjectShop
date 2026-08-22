package com.projectshop.shop.notification;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 시행일이 지난 판 중 가장 최근 것을 골라 변수를 꽂는다(`D18` 「템플릿은 판과 시행일로 둔다」).
 *
 * <p><b>고르는 조건이 판 번호가 아니라 시행일이다.</b> 판 번호로 고르면 개정판을 미리 넣어 둔 순간
 * 시행 전 문안이 나간다 — `D2-7` 이 약관에서 밟은 함정이고, {@code policy_document} 가 같은 규칙을 쓴다.
 *
 * <p><b>안 채워진 자리가 남으면 던진다.</b> 환급 통지의 금액처럼 <b>법이 요구하는 항목</b>이
 * 빈 채로 나가면 제18조제3항 단서를 안 지킨 것이 된다(`D2` R20). 채우다 만 메일을 보내느니
 * 안 보내고 실패로 남기는 편이 낫다 — 실패는 이력에 남아서 다시 볼 수 있고,
 * 빈 자리가 박힌 메일은 나간 뒤에 되돌릴 수가 없다.
 */
@Component
public class NotificationTemplates {

    /** 판에 적는 자리표시자. {@code {{order_number}}} 꼴이다 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-z_]+)}}");

    private final JdbcClient jdbc;

    NotificationTemplates(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 지금 쓰는 판.
     *
     * @param id      이력이 가리킬 판
     * @param kind    거래인가 광고인가. 발송 이력에 박제된다
     * @param subject 자리표시자가 남아 있는 제목
     * @param body    자리표시자가 남아 있는 본문
     */
    public record Version(long id, String kind, String subject, String body) {}

    /** 완성된 글자 */
    public record Rendered(String subject, String body) {}

    /**
     * 시행일이 지난 판 중 가장 최근 것을 고른다.
     *
     * @param code 템플릿 코드
     * @param now  이 시각 기준으로 시행 중인 판을 찾는다
     */
    public Optional<Version> current(String code, java.time.OffsetDateTime now) {
        return jdbc.sql("""
                        select notification_template_id as id, kind, subject, body
                          from notification_template
                         where code = :code and effective_at <= :now
                         order by effective_at desc, version desc
                         limit 1
                        """)
                .param("code", code)
                .param("now", now)
                .query(Version.class)
                .optional();
    }

    /**
     * 변수를 꽂는다.
     *
     * @param version 고른 판
     * @param values  자리표시자 이름과 넣을 값
     * @throws MissingTemplateValueException 안 채워진 자리가 하나라도 남으면
     */
    public Rendered render(Version version, Map<String, String> values) {
        return new Rendered(fill(version.subject(), values), fill(version.body(), values));
    }

    private String fill(String text, Map<String, String> values) {
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder filled = new StringBuilder();

        while (matcher.find()) {
            String key = matcher.group(1);
            String value = values.get(key);
            if (value == null) {
                throw new MissingTemplateValueException(key);
            }
            matcher.appendReplacement(filled, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(filled);

        return filled.toString();
    }

    /**
     * 판이 부르는 값을 안 넘겼다.
     *
     * <p>자리 이름만 담는다 — <b>값에는 이름·주소·금액이 들어가고</b> 예외 메시지는 로그로 나간다(`D16`).
     */
    public static class MissingTemplateValueException extends RuntimeException {

        public MissingTemplateValueException(String key) {
            super("템플릿이 부르는 값이 없다: " + key);
        }
    }
}
