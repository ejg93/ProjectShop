package com.projectshop.shop.consent;

import java.util.List;

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

    /**
     * 동의받을 항목 전부. <b>가입 화면이 무엇을 물어야 하는지를 여기서 안다</b>(`13d-1`).
     *
     * <p>목록 규약(`D5`)의 `items`·`page`·`size`·`total` 을 안 쓴다. <b>쪽을 넘길 대상이 아니다</b> —
     * 항목이 다섯이고 가입 화면은 전부를 한 번에 그려야 한다. 쪽으로 자르면 2쪽의 필수 항목을
     * 안 보여준 채로 동의를 받게 된다.
     *
     * <p>본문은 없다. 펼칠 때 {@link #read} 로 그 항목만 받는다.
     */
    @GetMapping
    public List<ConsentService.Notice> list() {
        return consentService.listCurrent();
    }

    @GetMapping("/{code}")
    public ConsentService.Notice read(@PathVariable String code) {
        return consentService.readCurrent(code);
    }
}
