package com.projectshop.shop.seller;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.swagger.v3.oas.annotations.media.Schema;

import com.projectshop.shop.audit.AuditLog;
import com.projectshop.shop.auth.PermissionEvaluator;
import com.projectshop.shop.auth.PermissionEvaluator.Target;
import com.projectshop.shop.auth.PermissionRuleLoader;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.support.CommaCodes;

/**
 * 셀러에 사람을 붙인다 — 초대를 내고, 받은 사람이 수락하면 소속과 역할이 같이 생긴다(`5a`).
 *
 * <h2>소속과 역할이 한 번에 들어간다</h2>
 *
 * <p>둘을 따로 넣을 수 있게 두면 <b>소속만 있고 역할이 없는 계정</b>이 생긴다. 그 계정은
 * 셀러 사람으로 세어지면서 아무것도 못 한다. 반대 순서는 아예 실패한다 —
 * {@code V4} 의 {@code check_user_role_target} 이 소속 없는 사용자에게 조직 역할을 주는 것을
 * 막는다. 그래서 {@link #accept} 하나가 둘을 같은 트랜잭션에서 넣는다.
 *
 * <h2>표를 직접 만지는 길을 안 연다</h2>
 *
 * <p>{@code seller_member} 와 {@code user_role} 에 직접 넣는 코드가 늘어날수록 조직 경계가
 * 새는 자리가 는다 — 그 자리마다 캐시를 비우는 것을 기억해야 한다. 이 클래스가 유일한 경로고,
 * 판정 캐시를 버리는 것도 여기 한 곳이다.
 *
 * <h2>원문은 초대 링크에만 있다</h2>
 *
 * <p>표에는 <b>해시만</b> 넣는다. 표가 새면 그 값으로 남의 셀러에 들어갈 수 있어서
 * 비밀번호와 같은 취급을 한다({@code PasswordResetService} 와 같은 판단, `D14`).
 * 로그에도 안 남긴다(`D16` 의 {@code A09}).
 */
@Service
public class SellerMemberService {

    /**
     * 초대 수명. 비밀번호 재설정(30분)보다 길다 — <b>급한 일이 아니고 받는 사람이
     * 계정부터 만들어야 할 수도 있다.</b> `D14` 가 「유효 시간이 짧다」만 정해서
     * 그 안에서 고른 값이다.
     */
    static final Duration LIFETIME = Duration.ofDays(7);

    /** 32바이트. 추측으로 맞힐 수 있는 크기가 아니다 */
    private static final int TOKEN_BYTES = 32;

    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final PermissionRuleLoader ruleLoader;
    private final PermissionEvaluator evaluator;
    private final AuditLog auditLog;
    private final SecureRandom random = new SecureRandom();

    SellerMemberService(JdbcClient jdbc, PasswordEncoder passwordEncoder,
            PermissionRuleLoader ruleLoader, PermissionEvaluator evaluator, AuditLog auditLog) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.ruleLoader = ruleLoader;
        this.evaluator = evaluator;
        this.auditLog = auditLog;
    }

    /**
     * 초대를 낸다. <b>원문 토큰을 돌려준다 — 저장하지 않는다.</b>
     *
     * <p>링크를 어디로 보내는지는 이 클래스가 안 정한다. 부르는 쪽이 메일로 보내든 화면에
     * 띄우든 그 결정은 입구의 것이고, 입구는 `16a` 가 만든다.
     *
     * <p>살아 있는 초대가 (셀러, 주소) 당 하나라는 것은 {@code seller_invitation_live_unique}
     * 가 든다. 두 번 눌러 두 건이 되는 것을 앱에서 세지 않는다.
     */
    @Transactional
    public Invitation invite(long sellerId, String email, String roleCode, long invitedByUserId) {
        requireManage(invitedByUserId, sellerId);

        long roleId = jdbc.sql("select role_id from role where code = :code")
                .param("code", roleCode)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new ShopException(ErrorCode.SELLER_INVITATION_INVALID,
                        "그런 역할이 없다"));

        String token = newToken();
        OffsetDateTime expiresAt = OffsetDateTime.now().plus(LIFETIME);

        long invitationId = jdbc.sql("""
                        insert into seller_invitation
                            (seller_id, email, role_id, invited_by_user_id, token_hash, expires_at)
                        values (:sellerId, :email, :roleId, :invitedBy, :hash, :expiresAt)
                        returning seller_invitation_id
                        """)
                .param("sellerId", sellerId)
                .param("email", email)
                .param("roleId", roleId)
                .param("invitedBy", invitedByUserId)
                .param("hash", passwordEncoder.encode(token))
                .param("expiresAt", expiresAt)
                .query(Long.class)
                .single();

        // 토큰은 안 남긴다. 남길 것은 누가 누구를 불렀나까지다(D16 A09).
        auditLog.record(AuditLog.Kind.OUTCOME, "seller_member.invited", invitedByUserId,
                AuditLog.Target.of("seller", sellerId),
                Map.of("invitation_id", invitationId, "role_code", roleCode));

        return new Invitation(invitationId, token, expiresAt);
    }

    /** 밖으로 나가는 것. <b>원문이 여기 잠깐 실린다</b> — 저장은 안 한다 */
    public record Invitation(long invitationId, String token, OffsetDateTime expiresAt) {}

    /**
     * 초대를 수락한다. 소속과 역할이 같이 생긴다.
     *
     * <p><b>수락 표시를 먼저 한다.</b> 같은 토큰으로 두 요청이 겹쳐 들어와도 한 번만
     * 통과한다 — 그것을 막는 것은 유니크 인덱스가 아니라 이 갱신의 조건이다.
     *
     * <p>주소가 계정과 다르면 거절한다. 링크를 주운 사람이 자기 계정으로 들어오는 것을
     * 막는 자리고, <b>여기서만 막힌다</b> — 표의 주소와 계정을 잇는 제약을 걸 수가 없다
     * (초대를 낼 때는 그 계정이 없을 수 있어서 외래키가 안 선다).
     */
    @Transactional
    public void accept(String token, long userId) {
        Pending found = livePending().stream()
                .filter(candidate -> passwordEncoder.matches(token, candidate.hash()))
                .findFirst()
                .orElseThrow(() -> new ShopException(ErrorCode.SELLER_INVITATION_INVALID,
                        "쓸 수 없는 초대 토큰이다"));

        String accountEmail = jdbc.sql("""
                        select email from app_user where user_id = :id and deleted_at is null
                        """)
                .param("id", userId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new ShopException(ErrorCode.SELLER_INVITATION_INVALID,
                        "쓸 수 없는 초대 토큰이다"));

        if (accountEmail == null || !accountEmail.equalsIgnoreCase(found.email())) {
            // 「주소가 다르다」를 따로 안 말한다 — 가르면 그 초대가 누구 앞으로 갔는지 드러난다.
            throw new ShopException(ErrorCode.SELLER_INVITATION_INVALID, "쓸 수 없는 초대 토큰이다");
        }

        int closed = jdbc.sql("""
                        update seller_invitation
                           set accepted_at = now(), accepted_user_id = :userId
                         where seller_invitation_id = :id
                           and accepted_at is null and revoked_at is null
                        """)
                .param("id", found.id())
                .param("userId", userId)
                .update();
        if (closed == 0) {
            throw new ShopException(ErrorCode.SELLER_INVITATION_INVALID, "이미 쓴 초대 토큰이다");
        }

        // 소속이 먼저다. V4 의 트리거가 소속 없는 사용자에게 조직 역할을 주는 것을 막는다.
        jdbc.sql("""
                        insert into seller_member (seller_id, user_id)
                        values (:sellerId, :userId)
                        on conflict (seller_id, user_id) do nothing
                        """)
                .param("sellerId", found.sellerId())
                .param("userId", userId)
                .update();

        jdbc.sql("""
                        insert into user_role (user_id, role_id, seller_id)
                        values (:userId, :roleId, :sellerId)
                        on conflict do nothing
                        """)
                .param("userId", userId)
                .param("roleId", found.roleId())
                .param("sellerId", found.sellerId())
                .update();

        // 소속과 역할이 둘 다 바뀌었다. 안 부르면 TTL 동안 새 멤버가 아무것도 못 한다.
        ruleLoader.evict(userId);

        auditLog.record(AuditLog.Kind.OUTCOME, "seller_member.joined", userId,
                AuditLog.Target.of("seller", found.sellerId()),
                Map.of("invitation_id", found.id()));
    }

    /**
     * 초대를 거둬들인다. 수락된 것은 못 거둔다 — 그건 이미 멤버라
     * <b>내보내는 것이 다른 일</b>이고 {@link #remove} 가 한다(`Q165`).
     */
    @Transactional
    public void revoke(long sellerId, long invitationId, long actorUserId) {
        requireManage(actorUserId, sellerId);

        // **셀러를 조건에 넣는다.** 번호만 보고 지우면 판정을 지난 사람이 **남의 셀러 초대**를
        // 거둔다 — 판정은 「이 셀러를 다룰 수 있나」를 답했지 「이 번호가 그 셀러 것인가」를
        // 안 봤다. 인자를 안 주면 못 부르는 자리라 강제 지점이 1위다(`Q162`).
        int closed = jdbc.sql("""
                        update seller_invitation set revoked_at = now()
                         where seller_invitation_id = :id and seller_id = :sellerId
                           and accepted_at is null and revoked_at is null
                        """)
                .param("id", invitationId)
                .param("sellerId", sellerId)
                .update();
        if (closed == 0) {
            throw new ShopException(ErrorCode.SELLER_INVITATION_INVALID, "거둘 수 없는 초대다");
        }

        auditLog.record(AuditLog.Kind.OUTCOME, "seller_member.invitation_revoked", actorUserId,
                AuditLog.Target.of("seller_invitation", invitationId), Map.of());
    }

    /**
     * 셀러에 속한 사람 하나와 그 셀러에서 받은 역할.
     *
     * <p><b>주소를 안 싣는다</b>(마무리 43차 독립 리뷰). `V7` 이 감사자의 {@code user:read} 를
     * {@code basic} 으로 묶어 연락처를 뺐는데, {@code seller_member:read} 로 그것을 꺼내면
     * <b>자원 이름을 바꿔 같은 값에 닿는</b> 것이 된다 — 감사자는 이 권한이 {@code all} 스코프라
     * 더 넓다(`V82`).
     *
     * <p><b>마스킹이 아니라 계약에서 뺐다.</b> 같이 일하는 사람을 알아보는 데 이름과 번호면
     * 충분하고, 갈리지 않는데 마스킹을 걸면 새 칸을 더할 때 그 규칙을 빠뜨린다(`D23`).
     * <b>부른 주소는 초대 목록에 있고</b> 그쪽은 관리 권한이 있어야 보인다.
     */
    @Schema(name = "SellerMemberRow")
    public record Member(long userId, String displayName, List<String> roleCodes) {}

    /** 아직 살아 있는 초대 하나 */
    @Schema(name = "SellerInvitationRow")
    public record PendingInvitation(long invitationId, String email, String roleCode,
            OffsetDateTime expiresAt) {}

    /**
     * 멤버 화면이 한 번에 받는 것.
     *
     * @param canManage 부르고 거둘 수 있나. <b>빈 초대 목록과 권한 없음이 같은 모양이라</b>
     *        칸으로 가른다 — 화면이 그것을 못 가르면 못 누를 버튼을 그린다(`D20`)
     */
    @Schema(name = "SellerMembers")
    public record Members(List<Member> members, List<PendingInvitation> invitations,
            boolean canManage) {}

    /**
     * 멤버의 조직 역할을 바꾼다(`Q165`).
     *
     * <p><b>소속은 안 건드린다.</b> 역할만 바꾸는 것이라 그 사람은 계속 이 셀러 사람이다 —
     * 내보내는 것은 {@link #remove} 고, 둘을 한 입구에 두면 <b>역할을 바꾸려다 내보내는</b>
     * 실수가 성립한다.
     *
     * <p><b>마지막 대표는 못 내린다.</b> 대표가 0이 되면 그 셀러는 <b>멤버를 부를 수도 뺄 수도
     * 없는 상태</b>로 잠긴다 — 푸는 길이 관리자의 직접 개입뿐이라 그 자리를 안 만든다.
     */
    @Transactional
    public void changeRole(long sellerId, long userId, String roleCode, long actorUserId) {
        requireManage(actorUserId, sellerId);
        requireMember(sellerId, userId);

        long roleId = orgRoleId(roleCode);
        if (!"seller_owner".equals(roleCode)) {
            requireAnotherOwnerRemains(sellerId, userId);
        }

        jdbc.sql("delete from user_role where user_id = :userId and seller_id = :sellerId")
                .param("userId", userId)
                .param("sellerId", sellerId)
                .update();

        jdbc.sql("""
                        insert into user_role (user_id, role_id, seller_id)
                        values (:userId, :roleId, :sellerId)
                        """)
                .param("userId", userId)
                .param("roleId", roleId)
                .param("sellerId", sellerId)
                .update();

        ruleLoader.evict(userId);
        auditLog.record(AuditLog.Kind.OUTCOME, "seller_member.role_changed", actorUserId,
                AuditLog.Target.of("seller", sellerId),
                Map.of("user_id", userId, "role_code", roleCode));
    }

    /**
     * 멤버를 내보낸다(`Q165`).
     *
     * <p><b>소속과 역할을 같이 지운다.</b> 역할만 지우면 <b>아무것도 못 하는 소속</b>이 남고,
     * 소속만 지우면 {@code V4} 의 트리거가 걸린 조직 역할이 갈 곳을 잃는다 —
     * {@link #accept} 가 둘을 같이 넣는 것과 짝이다.
     *
     * <p><b>마지막 대표는 못 나간다.</b> 나가면 그 셀러가 잠긴다.
     */
    @Transactional
    public void remove(long sellerId, long userId, long actorUserId) {
        requireManage(actorUserId, sellerId);
        requireMember(sellerId, userId);
        requireAnotherOwnerRemains(sellerId, userId);

        jdbc.sql("delete from user_role where user_id = :userId and seller_id = :sellerId")
                .param("userId", userId)
                .param("sellerId", sellerId)
                .update();

        jdbc.sql("delete from seller_member where seller_id = :sellerId and user_id = :userId")
                .param("sellerId", sellerId)
                .param("userId", userId)
                .update();

        ruleLoader.evict(userId);
        auditLog.record(AuditLog.Kind.OUTCOME, "seller_member.removed", actorUserId,
                AuditLog.Target.of("seller", sellerId), Map.of("user_id", userId));
    }

    private void requireMember(long sellerId, long userId) {
        boolean member = Boolean.TRUE.equals(jdbc.sql("""
                        select exists(select 1 from seller_member
                                       where seller_id = :sellerId and user_id = :userId)
                        """)
                .param("sellerId", sellerId)
                .param("userId", userId)
                .query(Boolean.class)
                .single());
        if (!member) {
            throw new ShopException(ErrorCode.SELLER_MEMBER_NOT_FOUND);
        }
    }

    /**
     * 이 사람 말고 대표가 하나라도 남나.
     *
     * <p><b>0이 되면 그 셀러가 잠긴다</b> — 멤버를 부를 수도 뺄 수도 없고, 푸는 길이
     * 관리자의 직접 개입뿐이다.
     */
    private void requireAnotherOwnerRemains(long sellerId, long userId) {
        requireAnotherLiveOwner(sellerId, userId, ErrorCode.SELLER_LAST_OWNER);
    }

    /**
     * 탈퇴하려는 사람이 어느 살아 있는 셀러의 마지막 대표인가(`Q169`).
     *
     * <p><b>탈퇴는 역할을 안 지운다</b> — {@code app_user.deleted_at} 만 채운다. 그래서 내보내기·역할
     * 변경의 검사로는 안 걸리고, 마지막 대표가 탈퇴하면 그 셀러가 잠긴다. <b>대표를 넘긴 뒤에만
     * 탈퇴한다</b>(사용자 선택). DB 는 {@code app_user_withdrawal_keeps_seller_owner} 가 같은 것을 막고,
     * 여기는 그것을 500 이 아니라 422 로 답하는 자리다.
     *
     * <p><b>셀러 번호 순으로 잠근다</b> — 트리거와 같은 순서라야 교착이 안 난다.
     */
    @Transactional
    public void requireNotLastOwnerAnywhere(long userId) {
        List<Long> ownedSellerIds = jdbc.sql("""
                        select ur.seller_id
                          from user_role ur
                          join role r on r.role_id = ur.role_id
                          join seller s on s.seller_id = ur.seller_id
                         where ur.user_id = :userId
                           and r.code = 'seller_owner'
                           and s.deleted_at is null
                         order by ur.seller_id
                        """)
                .param("userId", userId)
                .query(Long.class)
                .list();

        for (long sellerId : ownedSellerIds) {
            requireAnotherLiveOwner(sellerId, userId, ErrorCode.WITHDRAWAL_LAST_OWNER);
        }
    }

    /**
     * 이 사람 말고 <b>살아 있는</b> 대표가 하나라도 남나.
     *
     * <p><b>셀러 행을 먼저 잠근다</b>(`Q169`). 잠금 없이 세면 대표 둘이 동시에 서로를 내보낼 때
     * 둘 다 「하나 남는다」를 보고 통과해서 대표가 0이 된다. 잠그면 뒤에 온 쪽이 앞의 커밋을
     * 기다렸다가 새로 센다 — DB 트리거({@code user_role_keeps_seller_owner})가 같은 자리를 잠그는데,
     * 여기서 먼저 잡아야 그 트리거의 500 이 아니라 이 422 로 답한다.
     *
     * <p><b>탈퇴한 대표는 안 센다.</b> 탈퇴는 역할 행을 남기므로 역할만 세면 없는 사람이 「남은 대표」가 된다.
     */
    private void requireAnotherLiveOwner(long sellerId, long userId, ErrorCode whenNone) {
        jdbc.sql("select seller_id from seller where seller_id = :sellerId for update")
                .param("sellerId", sellerId)
                .query(Long.class)
                .optional();

        boolean remains = Boolean.TRUE.equals(jdbc.sql("""
                        select exists(
                            select 1 from user_role ur
                              join role r on r.role_id = ur.role_id
                              join app_user u on u.user_id = ur.user_id
                             where ur.seller_id = :sellerId and ur.user_id <> :userId
                               and r.code = 'seller_owner'
                               and u.deleted_at is null)
                        """)
                .param("sellerId", sellerId)
                .param("userId", userId)
                .query(Boolean.class)
                .single());
        if (!remains) {
            throw new ShopException(whenNone);
        }
    }

    /** 조직 역할만 온다. 전역 역할은 관리자 화면이 든다(`16`) */
    private long orgRoleId(String roleCode) {
        return jdbc.sql("select role_id from role where code = :code and is_org_role")
                .param("code", roleCode)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new ShopException(ErrorCode.SELLER_MEMBER_FORBIDDEN,
                        "조직 역할이 아니거나 없는 역할이다"));
    }

    /**
     * 내가 속한 셀러.
     *
     * <p><b>화면이 셀러 번호를 알 이유가 없다.</b> 사람은 「내 셀러」를 알지 번호를 모르고,
     * 번호를 화면에 적게 하면 <b>남의 번호를 적는 길</b>이 같이 열린다 — 판정이 그것을 거부하지만
     * 거부를 보는 것이 화면의 정상 상태가 되면 안 된다.
     *
     * <p>여럿이면 전부 돌려준다. 고르는 칸은 <b>실제로 둘 이상인 계정이 생길 때</b> 만든다 —
     * 지금 만들면 무엇을 고르는지 모르는 채로 만든다.
     */
    public List<Long> mySellerIds(long userId) {
        return jdbc.sql("""
                        select seller_id from seller_member
                         where user_id = :id order by seller_id
                        """)
                .param("id", userId)
                .query(Long.class)
                .list();
    }

    /**
     * 그 셀러의 사람과 살아 있는 초대.
     *
     * <p><b>초대는 관리 권한이 있어야 보인다.</b> 주소가 실려서고, 그것은 아직 회원이 아닐 수
     * 있는 사람의 개인정보다(`D13`) — 멤버 목록을 보는 것과 <b>누구를 불렀는지 보는 것</b>은
     * 다른 일이다.
     */
    public Members find(long actorUserId, long sellerId) {
        requirePermission(actorUserId, sellerId, "read");

        List<Member> members = jdbc.sql("""
                        select u.user_id, u.display_name,
                               coalesce(string_agg(r.code, ',' order by r.code), '') as role_codes
                          from seller_member sm
                          join app_user u on u.user_id = sm.user_id
                          left join user_role ur
                                 on ur.user_id = sm.user_id and ur.seller_id = sm.seller_id
                          left join role r on r.role_id = ur.role_id
                         where sm.seller_id = :sellerId
                         group by u.user_id, u.display_name
                         order by u.user_id
                        """)
                .param("sellerId", sellerId)
                .query((rs, rowNum) -> new Member(
                        rs.getLong("user_id"),
                        rs.getString("display_name"),
                        CommaCodes.split(rs.getString("role_codes"))))
                .list();

        boolean manages = allowed(actorUserId, sellerId, "manage");
        List<PendingInvitation> invitations = manages ? pendingOf(sellerId) : List.of();

        return new Members(members, invitations, manages);
    }

    private List<PendingInvitation> pendingOf(long sellerId) {
        return jdbc.sql("""
                        select si.seller_invitation_id, si.email, r.code, si.expires_at
                          from seller_invitation si
                          join role r on r.role_id = si.role_id
                         where si.seller_id = :sellerId
                           and si.accepted_at is null and si.revoked_at is null
                           and si.expires_at > now()
                         order by si.issued_at desc
                        """)
                .param("sellerId", sellerId)
                .query((rs, rowNum) -> new PendingInvitation(
                        rs.getLong("seller_invitation_id"),
                        rs.getString("email"),
                        rs.getString("code"),
                        rs.getObject("expires_at", OffsetDateTime.class)))
                .list();
    }

    /**
     * <b>판정 엔진이 조직 경계를 자른다.</b> 대상에 셀러를 실어 보내면 조직 역할로 받은 사람은
     * <b>받은 그 셀러에서만</b> 통과한다({@code Scope.SELLER} 의 뜻이 부여 방식에 따라 갈린다).
     *
     * <p><b>화면이 셀러 번호를 넘기는 것을 막지 않는다.</b> 막을 수가 없고 — 번호는 주소에
     * 실려 온다 — 막을 필요도 없다. 남의 번호를 넣으면 <b>판정이 거부한다.</b>
     */
    private void requirePermission(long actorUserId, long sellerId, String action) {
        if (!allowed(actorUserId, sellerId, action)) {
            throw new ShopException(ErrorCode.SELLER_MEMBER_FORBIDDEN);
        }
    }

    private void requireManage(long actorUserId, long sellerId) {
        requirePermission(actorUserId, sellerId, "manage");
    }

    private boolean allowed(long actorUserId, long sellerId, String action) {
        return evaluator.decide(actorUserId, "seller_member", action, Target.ofSeller(sellerId))
                .allowed();
    }

    /**
     * 아직 살아 있는 초대들.
     *
     * <p><b>해시로는 검색이 안 된다.</b> 같은 원문이라도 인코더가 매번 다른 해시를 만들어서
     * {@code where token_hash = ?} 로 못 찾는다 — 살아 있는 것만 꺼내 하나씩 맞춰 본다
     * ({@code PasswordResetService.liveTokens} 와 같은 이유).
     */
    private List<Pending> livePending() {
        return jdbc.sql("""
                        select seller_invitation_id, seller_id, role_id, email, token_hash
                          from seller_invitation
                         where accepted_at is null and revoked_at is null and expires_at > now()
                        """)
                .query((rs, rowNum) -> new Pending(
                        rs.getLong("seller_invitation_id"),
                        rs.getLong("seller_id"),
                        rs.getLong("role_id"),
                        rs.getString("email"),
                        rs.getString("token_hash")))
                .list();
    }

    private record Pending(long id, long sellerId, long roleId, String email, String hash) {}

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
