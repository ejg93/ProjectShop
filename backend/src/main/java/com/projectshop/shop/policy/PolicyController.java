package com.projectshop.shop.policy;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 알리기만 하는 정책 문서를 내준다(13a).
 *
 * <p><b>동의 항목과 경로를 가른다.</b> {@code /api/consent-items} 는 동의받을 것을 주고
 * 여기는 알리기만 하는 것을 준다. 한 경로로 합치면 화면이 응답을 보고 그 둘을 갈라야 하는데,
 * <b>빠뜨리면 가입 화면이 개인정보처리방침을 동의 항목으로 띄운다.</b>
 *
 * <p><b>비로그인이 부른다.</b> 개인정보처리방침은 개인정보법 제30조제2항이 공개를 요구하고,
 * 청약철회 안내는 전자상거래법 제13조제2항이 청약 이전에 알리라고 한다 —
 * 둘 다 로그인 전에 읽는 것이다.
 */
@RestController
@RequestMapping("/api/policies")
public class PolicyController {

    private final PolicyQuery policyQuery;

    PolicyController(PolicyQuery policyQuery) {
        this.policyQuery = policyQuery;
    }

    /**
     * 지금 효력 있는 판을 준다.
     *
     * @param code {@code privacy_policy} 또는 {@code withdrawal_guide}
     */
    @GetMapping("/{code}")
    public PolicyQuery.Policy read(@PathVariable String code) {
        return policyQuery.readCurrent(code);
    }
}
