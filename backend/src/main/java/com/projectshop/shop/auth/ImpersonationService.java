package com.projectshop.shop.auth;

import java.time.OffsetDateTime;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;

import com.projectshop.shop.audit.AuditLog;
import com.projectshop.shop.auth.PermissionEvaluator.Target;
import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 관리자가 사용자 시점으로 보기 시작하고 끝낸다(`16b`).
 *
 * <h2>보기만 한다</h2>
 *
 * <p>대행 중의 쓰기는 {@link ImpersonationReadOnlyFilter} 가 한 곳에서 막는다. 입구마다 막게 두면 새 입구가
 * 빠뜨리고, 그 입구에서 관리자가 <b>그 사람 이름으로</b> 주문하고 탈퇴한다.
 *
 * <h2>누구를 대행하나</h2>
 *
 * <p><b>관리자는 대행하지 않는다.</b> 관리자가 관리자를 대행하면 누가 무엇을 봤는지가 흐려지고, 권한이 다른
 * 관리자가 생기는 날 그것이 권한 세탁의 길이 된다. 자기 자신과 탈퇴한 계정도 안 된다.
 *
 * <h2>감사</h2>
 *
 * <p>시작과 끝을 관리자 이름으로 남기고, 그 사이의 기록은 {@code impersonator_user_id} 가 관리자를 든다
 * ({@link ImpersonationToken#currentImpersonatorId}).
 */
@Service
public class ImpersonationService {

    private final JdbcClient jdbc;
    private final PermissionEvaluator evaluator;
    private final ShopUserDetailsService users;
    private final SecurityContextRepository contexts;
    private final AuditLog auditLog;

    ImpersonationService(JdbcClient jdbc, PermissionEvaluator evaluator, ShopUserDetailsService users,
            SecurityContextRepository contexts, AuditLog auditLog) {
        this.jdbc = jdbc;
        this.evaluator = evaluator;
        this.users = users;
        this.contexts = contexts;
        this.auditLog = auditLog;
    }

    /** 대상 사용자의 시점으로 바꾼다. 이 세션만 바뀐다 — 그 사람의 다른 세션은 그대로다 */
    public void start(long targetUserId, HttpServletRequest request, HttpServletResponse response) {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        if (current instanceof ImpersonationToken) {
            throw new ShopException(ErrorCode.IMPERSONATION_CONFLICT, "이미 대행 중이다. 먼저 끝낸다");
        }
        if (!(current != null && current.getPrincipal() instanceof ShopUser admin)) {
            throw new ShopException(ErrorCode.IMPERSONATION_FORBIDDEN);
        }
        if (!evaluator.decide(admin.id(), "user", "impersonate", Target.ownedBy(targetUserId)).allowed()) {
            throw new ShopException(ErrorCode.IMPERSONATION_FORBIDDEN);
        }

        ShopUser target = impersonableUser(admin.id(), targetUserId);

        // 시작 기록은 바꾸기 전에 남긴다 — 행위자가 관리자로 적힌다.
        auditLog.record(AuditLog.Kind.OUTCOME, "user.impersonation_started", admin.id(),
                AuditLog.Target.of("user", targetUserId), Map.of());

        swap(new ImpersonationToken(target, admin.id(), current, OffsetDateTime.now()), request, response);
    }

    /** 대행을 끝내고 관리자로 돌아간다. <b>대행 중에도 부를 수 있는 쓰기 입구다</b> — 필터가 이것만 연다 */
    public void end(HttpServletRequest request, HttpServletResponse response) {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof ImpersonationToken token)) {
            throw new ShopException(ErrorCode.IMPERSONATION_CONFLICT, "대행 중이 아니다");
        }

        swap(token.original(), request, response);

        auditLog.record(AuditLog.Kind.OUTCOME, "user.impersonation_ended", token.impersonatorUserId(),
                AuditLog.Target.of("user", ((ShopUser) token.getPrincipal()).id()),
                Map.of("started_at", token.startedAt().toString()));
    }

    /**
     * 대행할 수 있는 계정인가. <b>관리자·자기 자신·탈퇴·정지 계정은 안 된다.</b> 가르지 않고 같은 답을 준다 —
     * 가르면 계정 번호를 두드려 누가 관리자인지를 셀 수 있다.
     */
    private ShopUser impersonableUser(long adminId, long targetUserId) {
        if (adminId == targetUserId) {
            throw new ShopException(ErrorCode.IMPERSONATION_FORBIDDEN);
        }

        record Candidate(String email, boolean admin) {}
        Candidate candidate = jdbc.sql("""
                        select u.email,
                               exists(select 1 from user_role ur
                                        join role r on r.role_id = ur.role_id
                                       where ur.user_id = u.user_id and r.code = 'admin') as admin
                          from app_user u
                         where u.user_id = :id and u.deleted_at is null
                        """)
                .param("id", targetUserId)
                .query(Candidate.class)
                .optional()
                .orElseThrow(() -> new ShopException(ErrorCode.IMPERSONATION_FORBIDDEN));
        if (candidate.admin()) {
            throw new ShopException(ErrorCode.IMPERSONATION_FORBIDDEN);
        }

        ShopUser target = (ShopUser) users.loadUserByUsername(candidate.email());
        if (!target.isEnabled()) {
            throw new ShopException(ErrorCode.IMPERSONATION_FORBIDDEN);
        }
        // 대행은 그 사람의 비밀번호를 모른 채로 선다 — 해시도 세션에 싣지 않는다.
        target.eraseCredentials();
        return target;
    }

    private void swap(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
    }
}
