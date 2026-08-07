package com.projectshop.shop.me;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.projectshop.shop.audit.AuditLog;
import com.projectshop.shop.auth.PermissionRuleLoader;
import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;

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
    private final SessionRegistry sessionRegistry;
    private final AuditLog auditLog;

    WithdrawalService(JdbcClient jdbc, PasswordEncoder passwordEncoder,
            PermissionRuleLoader ruleLoader, SessionRegistry sessionRegistry, AuditLog auditLog) {

        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.ruleLoader = ruleLoader;
        this.sessionRegistry = sessionRegistry;
        this.auditLog = auditLog;
    }

    /**
     * 비밀번호를 다시 받는다. 되돌릴 수 없는 조작이라 세션만으로는 부족하다.
     *
     * @param actorIp 동의 철회 기록에 남는다. 탈퇴도 동의 상태를 바꾸는 사건이다
     */
    @Transactional
    public void withdraw(long userId, String password, String actorIp) {
        String stored = jdbc.sql(
                        "select password_hash from app_user where user_id = :id and deleted_at is null")
                .param("id", userId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "이미 탈퇴한 계정이다"));

        if (!passwordEncoder.matches(password, stored)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "비밀번호가 맞지 않는다");
        }

        revokeAllConsents(userId, actorIp);

        jdbc.sql("update app_user set deleted_at = now() where user_id = :id")
                .param("id", userId)
                .update();

        auditLog.record("user.withdrawn", userId, AuditLog.Target.of("user", userId), Map.of());

        // 트랜잭션 안에서 부른다. 롤백되면 계정이 살아 있는데 캐시만 비어 있는 상태가 되는데,
        // 그쪽은 한 번 더 조회할 뿐이라 틀리지 않는다. 반대로 두면 죽은 계정이 캐시에 남는다.
        ruleLoader.evict(userId);
        expireSessions(userId);
    }

    /**
     * 남아 있던 동의를 전부 거둔다. <b>필수 항목도 거둔다</b> — 탈퇴가 그 경로다(`5f`).
     *
     * <p>여기서도 사건을 적는다. {@code source} 가 {@code withdraw} 라서 나중에 이 철회가
     * 마이페이지에서 한 것인지 탈퇴로 일어난 것인지 갈린다.
     */
    private void revokeAllConsents(long userId, String actorIp) {
        jdbc.sql("""
                        insert into user_consent (user_id, consent_item_id, granted, source, acted_ip)
                        select :userId, cc.consent_item_id, false, 'withdraw', cast(:actorIp as inet)
                          from current_consent cc
                         where cc.user_id = :userId and cc.granted
                        """)
                .param("userId", userId)
                .param("actorIp", actorIp)
                .update();
    }

    /**
     * 이 사람의 세션을 전부 만료시킨다.
     *
     * <p>principal 객체로 찾지 않고 id 로 훑는다. {@code ShopUser} 는 record 라
     * 비밀번호 해시까지 같아야 {@code equals} 가 성립하는데, 비밀번호를 바꾼 뒤라면 어긋난다.
     *
     * <p>만료 표시가 실제 로그아웃이 되는 것은 {@code ConcurrentSessionFilter} 가 있어서다.
     * 그 필터가 없으면 <b>이 호출은 아무 일도 안 한다</b> — 부른 줄 알았는데 안 먹는 쪽이 제일 나쁘다.
     */
    private void expireSessions(long userId) {
        sessionRegistry.getAllPrincipals().stream()
                .filter(principal -> principal instanceof ShopUser user && user.id() == userId)
                .flatMap(principal -> sessionRegistry.getAllSessions(principal, false).stream())
                .forEach(SessionInformation::expireNow);
    }
}
