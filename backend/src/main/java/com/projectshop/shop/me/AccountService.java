package com.projectshop.shop.me;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectshop.shop.audit.AuditLog;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.auth.PermissionEvaluator;
import com.projectshop.shop.auth.PermissionEvaluator.Decision;
import com.projectshop.shop.auth.PermissionEvaluator.Target;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 자기 계정을 보고 고친다.
 *
 * <p><b>필드 마스킹(4d)이 실제 응답에 걸리는 첫 자리다.</b> 지금까지 판정은 허용 여부만 쓰였고
 * {@code visibleFieldGroups} 를 응답에 반영한 곳이 없었다.
 */
@Service
public class AccountService {

    private final JdbcClient jdbc;
    private final PermissionEvaluator evaluator;
    private final PasswordEncoder passwordEncoder;
    private final AuditLog auditLog;

    AccountService(JdbcClient jdbc, PermissionEvaluator evaluator,
            PasswordEncoder passwordEncoder, AuditLog auditLog) {

        this.jdbc = jdbc;
        this.evaluator = evaluator;
        this.passwordEncoder = passwordEncoder;
        this.auditLog = auditLog;
    }

    /**
     * 볼 수 있는 것만 담는다. <b>못 보는 필드는 null 이 아니라 응답에서 빠진다</b>(`D5`).
     *
     * <p>null 로 내리면 "값이 없다" 와 "볼 수 없다" 가 같아 보인다.
     * 어느 쪽인지는 {@code _visible_field_groups} 가 답한다.
     *
     * @param displayName {@code basic} 그룹
     * @param createdAt   {@code basic} 그룹
     * @param email       {@code contact} 그룹
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Account(
            long userId,
            String displayName,
            OffsetDateTime createdAt,
            String email,
            @JsonProperty("_visible_field_groups") List<String> visibleFieldGroups) {
    }

    /** DB 에서 읽은 그대로. 무엇을 내릴지는 판정이 정한다. */
    private record Row(String displayName, String email, OffsetDateTime createdAt) {
    }

    public Account read(long userId) {
        Decision decision = evaluator.decide(userId, "user", "read", Target.ownedBy(userId));
        if (!decision.allowed()) {
            throw new ShopException(ErrorCode.ACCOUNT_FORBIDDEN, "계정을 볼 권한이 없다");
        }

        // RowMapper 로 읽는다. Map 으로 받으면 timestamptz 가 java.sql.Timestamp 로 와서
        // OffsetDateTime 으로 캐스팅할 수 없다.
        Row row = jdbc.sql("""
                        select display_name, email, created_at
                          from app_user
                         where user_id = :id and deleted_at is null
                        """)
                .param("id", userId)
                .query((rs, rowNum) -> new Row(
                        rs.getString("display_name"),
                        rs.getString("email"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .single();

        return new Account(
                userId,
                decision.canSee("basic") ? row.displayName() : null,
                decision.canSee("basic") ? row.createdAt() : null,
                decision.canSee("contact") ? row.email() : null,
                // 제한이 없으면 빈 배열로 나간다. 그 값이 응답에서 "전부 본다" 를 뜻하는 것은
                // 안쪽에서 타입으로 가른 것과 달리 여전히 모호하다 — 화면 청크(13b)가 그걸 정한다.
                List.copyOf(new TreeSet<>(decision.visibleFieldGroups().values())));
    }

    @Transactional
    public Account changeDisplayName(long userId, String displayName) {
        requireUpdatePermission(userId);

        jdbc.sql("update app_user set display_name = :name where user_id = :id and deleted_at is null")
                .param("name", displayName)
                .param("id", userId)
                .update();

        auditLog.record("user.display_name_changed", userId,
                AuditLog.Target.of("user", userId), Map.of());

        return read(userId);
    }

    /**
     * 현재 비밀번호를 다시 받는다.
     *
     * <p>세션을 훔친 사람이 비밀번호까지 바꾸면 주인이 계정을 영영 잃는다.
     * 현재 비밀번호를 물어서 <b>세션을 가진 것과 비밀번호를 아는 것</b>을 가른다.
     */
    @Transactional
    public void changePassword(long userId, String currentPassword, String newPassword) {
        requireUpdatePermission(userId);

        String stored = jdbc.sql(
                        "select password_hash from app_user where user_id = :id and deleted_at is null")
                .param("id", userId)
                .query(String.class)
                .single();

        if (!passwordEncoder.matches(currentPassword, stored)) {
            // 로그인 실패와 같은 문구를 쓸 이유가 없다. 여기는 이미 본인이 로그인해 있는 자리라
            // 계정 존재 여부가 새지 않는다(D14).
            throw new ShopException(ErrorCode.PASSWORD_MISMATCH, "현재 비밀번호가 맞지 않는다");
        }

        jdbc.sql("update app_user set password_hash = :hash where user_id = :id")
                .param("hash", passwordEncoder.encode(newPassword))
                .param("id", userId)
                .update();

        // 무엇으로 바꿨는지는 안 남긴다. 남길 것은 바꿨다는 사실뿐이다(D16).
        auditLog.record("user.password_changed", userId,
                AuditLog.Target.of("user", userId), Map.of());

        // 다른 기기의 세션은 그대로 살아 있다. 끊으려면 SessionRegistry 를 부르면 되지만,
        // 비밀번호 변경이 곧 도난 대응이라는 전제가 없어서 지금은 안 한다(ADR 0010 은 탈퇴만 다뤘다).
    }

    private void requireUpdatePermission(long userId) {
        Decision decision = evaluator.decide(userId, "user", "update", Target.ownedBy(userId));
        if (!decision.allowed()) {
            throw new ShopException(ErrorCode.ACCOUNT_FORBIDDEN, "계정을 고칠 권한이 없다");
        }
    }
}
