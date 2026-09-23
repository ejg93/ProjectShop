package com.projectshop.shop.account;

import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectshop.shop.audit.AuditLog;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.auth.PermissionRuleLoader;
import com.projectshop.shop.consent.ConsentService;
import com.projectshop.shop.seller.SellerMemberService;

/**
 * 탈퇴. 계정의 수명을 끊는다.
 *
 * <p><b>탈퇴가 곧 삭제가 아니다</b>(`D13`). 주문 기록은 5년 남고 개인정보 파기는 배치(10a)가 한다.
 * 여기서 하는 것은 수명 컬럼을 채우고, 그 사실이 <b>지금 즉시</b> 먹게 만드는 것뿐이다.
 *
 * <p>즉시 먹게 하는 데 세 가지가 필요하다(`ADR 0010`). 하나라도 빠지면 증상이 다르게 나온다.
 * <ul>
 *   <li>캐시 무효화 — 안 하면 TTL 60초 동안 죽은 계정이 살아 있는 것으로 보인다</li>
 *   <li>세션 만료 — 안 하면 다른 기기가 로그인 상태로 남는다</li>
 *   <li>동의 철회 기록 — 안 하면 계약이 끝났는데 동의가 유효한 채로 남는다</li>
 * </ul>
 */
@Service
public class WithdrawalService {

    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final PermissionRuleLoader ruleLoader;
    private final FindByIndexNameSessionRepository<? extends Session> sessions;
    private final AuditLog auditLog;
    private final ConsentService consentService;
    private final SellerMemberService sellerMembers;

    WithdrawalService(JdbcClient jdbc, PasswordEncoder passwordEncoder,
            PermissionRuleLoader ruleLoader,
            FindByIndexNameSessionRepository<? extends Session> sessions, AuditLog auditLog,
            ConsentService consentService, SellerMemberService sellerMembers) {

        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.ruleLoader = ruleLoader;
        this.sessions = sessions;
        this.auditLog = auditLog;
        this.consentService = consentService;
        this.sellerMembers = sellerMembers;
    }

    /**
     * 비밀번호를 다시 받는다. 되돌릴 수 없는 조작이라 세션만으로는 부족하다.
     *
     * @param actorIp 동의 철회 기록에 남는다. 탈퇴도 동의 상태를 바꾸는 사건이다
     */
    @Transactional
    public void withdraw(long userId, String password, String actorIp) {
        // 이메일도 같이 읽는다. 세션 색인의 열쇠가 그것이다(`Q52`) — 아래 `expireSessions`.
        Account account = jdbc.sql(
                        "select password_hash, email from app_user"
                                + " where user_id = :id and deleted_at is null")
                .param("id", userId)
                .query((rs, rowNum) -> new Account(rs.getString("password_hash"),
                        rs.getString("email")))
                .optional()
                .orElseThrow(() -> new ShopException(ErrorCode.ALREADY_WITHDRAWN));

        if (!passwordEncoder.matches(password, account.passwordHash())) {
            throw new ShopException(ErrorCode.PASSWORD_MISMATCH);
        }

        // 마지막 대표는 대표를 넘긴 뒤에 나간다(`Q169`, 사용자 선택). 탈퇴는 역할 행을 안 지워서
        // 멤버 관리 쪽 검사로는 안 걸린다. DB 트리거가 같은 것을 막고 여기는 그것을 422 로 답한다.
        sellerMembers.requireNotLastOwnerAnywhere(userId);

        // 동의를 어떻게 거두느냐는 여기서 안 정한다. 남의 자원이라 규칙도 그쪽에 있다(`5m`).
        consentService.revokeAll(userId, actorIp);

        jdbc.sql("update app_user set deleted_at = now() where user_id = :id")
                .param("id", userId)
                .update();

        auditLog.record(AuditLog.Kind.OUTCOME, "user.withdrawn", userId,
                AuditLog.Target.of("user", userId), Map.of());

        // 트랜잭션 안에서 부른다. 롤백되면 계정이 살아 있는데 캐시만 비어 있는 상태가 되는데,
        // 그쪽은 한 번 더 조회할 뿐이라 틀리지 않는다. 반대로 두면 죽은 계정이 캐시에 남는다.
        ruleLoader.evict(userId);
        expireSessions(account.email());
    }

    /** 비밀번호 확인과 세션 색인에 필요한 것만 읽는다 */
    private record Account(String passwordHash, String email) {
    }

    /**
     * 이 사람의 세션을 전부 지운다.
     *
     * <p><b>색인으로 찾는다</b>(`Q52`). 전에는 세션 명부에서 등록된 사람을 <b>전부 받아 훑었는데</b>,
     * 세션이 Redis 로 가면서 그 물음이 사라졌다 — 색인이 「사람 하나 → 세션들」 방향뿐이라
     * {@code SessionRegistry.getAllPrincipals()} 가 예외를 던진다. 열쇠는 principal 이름,
     * 즉 {@code ShopUser.getUsername()} 이고 그것이 이메일이다.
     *
     * <p><b>만료 표시가 아니라 삭제다.</b> 표시만 남기면 세션이 무활동 만료(30분)까지 Redis 에
     * 그대로 있고 그 안에 이메일이 들어 있다 — 탈퇴는 개인정보를 거두기 시작하는 자리라
     * 남겨 둘 이유가 없다. 지우면 다음 요청이 세션을 못 찾아 그대로 401 이다.
     *
     * <p>이 조작은 인스턴스를 안 가린다. 저장소가 Redis 라 <b>다른 대에 있는 세션도 지워진다</b> —
     * 그전에는 자기 프로세스의 명부만 봐서 두 대가 되면 반쪽만 먹었다.
     */
    private void expireSessions(String email) {
        sessions.findByPrincipalName(email).keySet().forEach(sessions::deleteById);
    }
}
