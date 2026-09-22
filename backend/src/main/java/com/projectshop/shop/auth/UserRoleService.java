package com.projectshop.shop.auth;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectshop.shop.audit.AuditLog;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 사용자에게 역할을 주고 회수한다(`16`).
 *
 * <h2>캐시를 버리는 자리가 여기 하나다</h2>
 *
 * <p>판정 규칙이 사용자마다 60초 캐시된다({@code PermissionCacheConfig}). <b>역할을 회수하고
 * 캐시를 안 버리면 그 60초 동안 회수가 안 먹는다</b> — 화면은 「뺐다」고 하고 서버는 아직
 * 허용한다. 권한을 준 것보다 <b>못 뺀 것</b>이 사고다.
 *
 * <p>그래서 표를 직접 만지는 길을 안 연다. 부여·회수가 이 클래스를 지나고,
 * <b>지나는지를 {@code UserRoleWriteTest} 가 원천에서 센다</b> — 새 입구가 생기는 날
 * 그 시험이 먼저 빨갛다.
 *
 * <h2>조직 역할은 여기로 안 온다</h2>
 *
 * <p>셀러 역할은 소속과 함께 들어가야 해서 {@code SellerMemberService} 가 든다(`5a`).
 * 여기서 받으면 소속 없는 계정에 조직 역할을 주는 길이 열리고, 그것을 막는 것은
 * {@code V4} 의 트리거뿐이라 <b>실패로만 알게 된다.</b>
 */
@Service
public class UserRoleService {

    /** 역할 편집은 전역 동작이라 대상이 없다. 스코프가 갈릴 것이 없다(`D6`) */
    private static final PermissionEvaluator.Target NO_TARGET =
            new PermissionEvaluator.Target(null, null, null);

    private final JdbcClient jdbc;
    private final PermissionEvaluator evaluator;
    private final PermissionRuleLoader ruleLoader;
    private final AuditLog auditLog;

    UserRoleService(JdbcClient jdbc, PermissionEvaluator evaluator,
            PermissionRuleLoader ruleLoader, AuditLog auditLog) {
        this.jdbc = jdbc;
        this.evaluator = evaluator;
        this.ruleLoader = ruleLoader;
        this.auditLog = auditLog;
    }

    /** 한 사람이 가진 것. <b>조직 역할은 어느 셀러인지까지 실린다</b> */
    @Schema(name = "UserRoleGrant")
    public record Grant(String roleCode, String roleName, Long sellerId, String sellerName) {}

    /** 관리자 화면이 보는 한 사람 */
    @Schema(name = "UserDetail")
    public record Detail(long userId, String email, String displayName, boolean deleted,
            List<Grant> roles) {}

    /**
     * 한 사람과 그가 가진 역할.
     *
     * <p><b>탈퇴한 계정도 보인다.</b> 감사에서 「누가 무엇을 가졌었나」를 물으면 그 계정이
     * 이미 나갔을 수 있다 — 안 보이면 그 물음에 답할 자리가 없다. 나간 것은 칸으로 밝힌다.
     */
    public Detail find(long actorUserId, long userId) {
        requirePermission(actorUserId, "read");

        Detail user = jdbc.sql("""
                        select user_id, email, display_name, deleted_at
                          from app_user where user_id = :id
                        """)
                .param("id", userId)
                .query((rs, rowNum) -> new Detail(
                        rs.getLong("user_id"),
                        rs.getString("email"),
                        rs.getString("display_name"),
                        rs.getTimestamp("deleted_at") != null,
                        List.of()))
                .optional()
                .orElseThrow(() -> new ShopException(ErrorCode.USER_NOT_FOUND));

        return new Detail(user.userId(), user.email(), user.displayName(), user.deleted(),
                grantsOf(userId));
    }

    /**
     * 전역 역할을 준다. <b>이미 있으면 아무 일도 안 일어난다</b> —
     * 두 번 눌러 두 행이 되는 것은 {@code user_role_unique_grant} 가 막고,
     * 그 실패를 오류로 올리면 <b>화면이 「이미 있다」를 오류로 그린다.</b>
     */
    @Transactional
    public void grant(long actorUserId, long userId, String roleCode) {
        requirePermission(actorUserId, "assign");

        long roleId = globalRoleId(roleCode);
        int granted = jdbc.sql("""
                        insert into user_role (user_id, role_id)
                        values (:userId, :roleId)
                        on conflict do nothing
                        """)
                .param("userId", userId)
                .param("roleId", roleId)
                .update();

        if (granted > 0) {
            evictAndRecord("role.granted", actorUserId, userId, roleCode);
        }
    }

    /**
     * 전역 역할을 회수한다.
     *
     * <p><b>없는 것을 뺐다고 해도 오류가 아니다.</b> 부르는 쪽이 원한 상태(그 역할이 없는 것)가
     * 이미 사실이라, 실패로 답하면 화면이 고칠 것이 없는 오류를 그린다.
     */
    @Transactional
    public void revoke(long actorUserId, long userId, String roleCode) {
        requirePermission(actorUserId, "assign");

        int revoked = jdbc.sql("""
                        delete from user_role
                         where user_id = :userId and seller_id is null
                           and role_id = (select role_id from role where code = :code)
                        """)
                .param("userId", userId)
                .param("code", roleCode)
                .update();

        if (revoked > 0) {
            evictAndRecord("role.revoked", actorUserId, userId, roleCode);
        }
    }

    /**
     * <b>캐시를 버리고 기록을 남긴다.</b> 둘을 한 자리에 묶는 이유는 <b>같이 빠뜨리기 쉬워서</b>다 —
     * 부여 경로가 늘 때 하나만 복사하면 그쪽만 조용히 다르게 군다.
     */
    private void evictAndRecord(String eventType, long actorUserId, long userId, String roleCode) {
        ruleLoader.evict(userId);
        auditLog.record(AuditLog.Kind.OUTCOME, eventType, actorUserId,
                AuditLog.Target.of("user", userId), Map.of("role_code", roleCode));
    }

    /**
     * <b>조직 역할은 여기서 못 준다.</b> 셀러를 안 받는 입구라 주면 {@code V4} 의 트리거가
     * 막는데, 그때 나는 것은 「조직 역할은 셀러를 지정해야 한다」는 <b>500</b> 이다 —
     * 부르는 쪽이 고칠 수 있는 오류로 먼저 답한다.
     */
    private long globalRoleId(String roleCode) {
        return jdbc.sql("select role_id from role where code = :code and not is_org_role")
                .param("code", roleCode)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new ShopException(ErrorCode.ROLE_NOT_ASSIGNABLE,
                        "전역 역할이 아니거나 없는 역할이다"));
    }

    private List<Grant> grantsOf(long userId) {
        return jdbc.sql("""
                        select r.code, r.name, ur.seller_id, s.name as seller_name
                          from user_role ur
                          join role r on r.role_id = ur.role_id
                          left join seller s on s.seller_id = ur.seller_id
                         where ur.user_id = :id
                         order by r.code
                        """)
                .param("id", userId)
                .query((rs, rowNum) -> new Grant(
                        rs.getString("code"),
                        rs.getString("name"),
                        (Long) rs.getObject("seller_id"),
                        rs.getString("seller_name")))
                .list();
    }

    private void requirePermission(long actorUserId, String action) {
        if (!evaluator.decide(actorUserId, "role", action, NO_TARGET).allowed()) {
            throw new ShopException(ErrorCode.ROLE_FORBIDDEN);
        }
    }
}
