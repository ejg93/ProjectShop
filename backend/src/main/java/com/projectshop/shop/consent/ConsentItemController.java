package com.projectshop.shop.consent;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 동의 항목의 고지 내용을 읽는 입구. <b>로그인 없이 부른다.</b>
 *
 * <p>{@code /api/me} 아래에 두지 않은 이유가 있다. 이 자원은 <b>내 것이 아니라 모두의 것</b>이다 —
 * 지금 효력 있는 판 하나이고 누가 물어도 같은 답이 나온다.
 *
 * <p>가입 화면이 이걸 쓴다. 동의하려면 먼저 읽어야 하는데 그 시점은 로그인 전이다.
 * 로그인이 필요한 경로만 두면 <b>가입할 때 약관을 못 본다</b>(약관규제법 제3조제2항·제3항).
 *
 * <p>내가 동의한 판의 사본은 {@link MeController} 가 준다. 그쪽은 사람마다 답이 다르다.
 */
@RestController
@RequestMapping("/api/consent-items")
public class ConsentItemController {

    private final ConsentService consentService;

    ConsentItemController(ConsentService consentService) {
        this.consentService = consentService;
    }

    @GetMapping("/{code}")
    public ConsentService.Notice read(@PathVariable String code) {
        return consentService.readCurrent(code);
    }
}
