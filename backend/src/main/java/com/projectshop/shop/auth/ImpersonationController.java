package com.projectshop.shop.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;

/**
 * 관리자의 대행 보기(`16b`). 판정과 규칙은 {@link ImpersonationService} 가 든다.
 */
@RestController
@RequestMapping("/api/admin/impersonation")
public class ImpersonationController {

    private final ImpersonationService impersonation;

    ImpersonationController(ImpersonationService impersonation) {
        this.impersonation = impersonation;
    }

    record StartRequest(@Min(1) long userId) {}

    /** 이 세션을 그 사람의 시점으로 바꾼다. 본문이 없다 — 다음 요청부터 그 사람으로 보인다 */
    @PostMapping
    ResponseEntity<Void> start(@Valid @RequestBody StartRequest request,
            HttpServletRequest http, HttpServletResponse response) {
        impersonation.start(request.userId(), http, response);
        return ResponseEntity.noContent().build();
    }

    /** 대행을 끝낸다. 대행 중에도 열린 몇 안 되는 쓰기다({@link ImpersonationReadOnlyFilter#EXITS}) */
    @PostMapping("/end")
    ResponseEntity<Void> end(HttpServletRequest http, HttpServletResponse response) {
        impersonation.end(http, response);
        return ResponseEntity.noContent().build();
    }
}
