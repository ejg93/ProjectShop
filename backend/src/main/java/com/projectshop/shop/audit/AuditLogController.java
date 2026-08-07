package com.projectshop.shop.audit;

import java.time.OffsetDateTime;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.projectshop.shop.audit.AuditLogQuery.Criteria;
import com.projectshop.shop.audit.AuditLogQuery.Page;
import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;

/**
 * 감사 기록을 찾는 입구.
 *
 * <p>권한 판정은 {@link AuditLogQuery} 가 조회 전에 한다. 여기서 한 번 더 보지 않는다 —
 * 두 군데서 보면 한쪽만 고치는 날이 온다.
 */
@RestController
public class AuditLogController {

    private final AuditLogQuery auditLogQuery;

    AuditLogController(AuditLogQuery auditLogQuery) {
        this.auditLogQuery = auditLogQuery;
    }

    /**
     * 정렬은 최신순으로 고정한다. 감사 기록을 오래된 것부터 보는 화면이 없고,
     * `sort` 를 열면 정렬 컬럼을 허용 목록으로 걸러야 한다(`D14`).
     *
     * <p>쿼리 파라미터도 snake_case 다. Jackson 의 이름 규칙은 본문에만 걸려서 여기 직접 적는다 —
     * 안 적으면 같은 값이 URL 에서는 camelCase, 본문에서는 snake_case 가 된다.
     *
     * @param from 이 시각부터(포함). `2026-08-07T00:00:00+09:00` 처럼 오프셋을 붙여 보낸다(`D10`)
     * @param to   이 시각 전까지(제외). 끝을 여는 이유는 하루 단위로 이어 붙일 때 겹치지 않게 하려는 것이다
     */
    @GetMapping("/api/audit-logs")
    public Page find(
            @AuthenticationPrincipal ShopUser viewer,
            @RequestParam(name = "actor_user_id", required = false) Long actorUserId,
            @RequestParam(name = "target_type", required = false) String targetType,
            @RequestParam(name = "target_id", required = false) Long targetId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return auditLogQuery.find(viewer.id(),
                new Criteria(actorUserId, targetType, targetId, from, to, page, size));
    }
}
