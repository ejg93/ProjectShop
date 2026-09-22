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

import com.projectshop.shop.audit.AuditLog;
import com.projectshop.shop.auth.PermissionRuleLoader;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

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
    private final AuditLog auditLog;
    private final SecureRandom random = new SecureRandom();

    SellerMemberService(JdbcClient jdbc, PasswordEncoder passwordEncoder,
            PermissionRuleLoader ruleLoader, AuditLog auditLog) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.ruleLoader = ruleLoader;
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
     * <b>내보내는 것이 다른 일</b>이고 그 입구는 `16a` 가 만든다.
     */
    @Transactional
    public void revoke(long invitationId, long actorUserId) {
        int closed = jdbc.sql("""
                        update seller_invitation set revoked_at = now()
                         where seller_invitation_id = :id
                           and accepted_at is null and revoked_at is null
                        """)
                .param("id", invitationId)
                .update();
        if (closed == 0) {
            throw new ShopException(ErrorCode.SELLER_INVITATION_INVALID, "거둘 수 없는 초대다");
        }

        auditLog.record(AuditLog.Kind.OUTCOME, "seller_member.invitation_revoked", actorUserId,
                AuditLog.Target.of("seller_invitation", invitationId), Map.of());
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
