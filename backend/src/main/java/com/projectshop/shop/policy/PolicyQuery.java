package com.projectshop.shop.policy;

import java.time.OffsetDateTime;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

/**
 * 정책 문서를 찾아 본다.
 *
 * <p>판정이 없다. 알리라고 법이 요구한 문서라 <b>누구에게나 같은 것이 나가고</b>,
 * 조건은 코드와 시행 시각 둘뿐이다.
 */
@Service
public class PolicyQuery {

    private final JdbcClient jdbc;

    PolicyQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 화면이 그릴 한 판.
     *
     * @param body        마크다운이다. 화면이 강조를 살려 그린다(`D20` 약관규제법 제3조제1항)
     * @param effectiveAt 이 판이 언제부터 적용되나. <b>개정 고지가 이 값을 쓴다</b> —
     *                    개인정보처리방침은 시행 7일 전부터 알려야 한다(개인정보법 시행령 제31조제3항)
     */
    public record Policy(String code, String title, int version, String body,
            OffsetDateTime effectiveAt) {
    }

    /**
     * 지금 효력 있는 판. <b>미래 판이 들어 있어도 안 나간다.</b>
     *
     * <p>개정판을 미리 넣어 두고 시점에 갈아 끼우는 설계라(`V21`), 시행 시각을 안 보면
     * 넣는 순간 바뀐다 — 사전 고지 기간이 사라진다.
     *
     * <p>{@code consent_item} 쪽 조회와 조건이 같다. <b>같은 개정판 설계를 쓰기 때문</b>이고,
     * 한쪽만 고치면 같은 표에서 다른 판이 나가는 자리가 생긴다.
     */
    public Policy readCurrent(String code) {
        return jdbc.sql("""
                        select code, title, version, body, effective_at
                          from policy_document
                         where code = :code and effective_at <= now()
                         order by effective_at desc, version desc
                         limit 1
                        """)
                .param("code", code)
                .query((rs, rowNum) -> new Policy(
                        rs.getString("code"),
                        rs.getString("title"),
                        rs.getInt("version"),
                        rs.getString("body"),
                        rs.getObject("effective_at", OffsetDateTime.class)))
                .optional()
                .orElseThrow(() -> new ShopException(
                        ErrorCode.POLICY_NOT_FOUND, "그런 정책 문서가 없다: " + code));
    }
}
