package com.projectshop.shop.auth;

import java.time.Instant;
import java.util.Map;
import java.util.OptionalLong;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectshop.shop.audit.AuditLog;
import com.projectshop.shop.audit.AuditTargetType;

/**
 * 2차 인증(TOTP) 등록을 {@code user_second_factor} 에 두고 코드를 검증한다(`Q451`, 안전성 확보조치 기준 제6조② `D2` R45).
 *
 * <p><b>재사용 금지는 조건부 UPDATE 가 쥔다</b>(RFC 6238 §5.2). 맞은 스텝이 저장된 {@code last_used_step} 보다 클 때만
 * 1행이 바뀌고 그때만 통과다 — 동시에 같은 코드를 둘이 내도 하나만 통과한다.
 */
@Service
public class SecondFactorService {

    private final JdbcClient jdbc;
    private final SecondFactorSecrets secrets;
    private final AuditLog auditLog;

    SecondFactorService(JdbcClient jdbc, SecondFactorSecrets secrets, AuditLog auditLog) {
        this.jdbc = jdbc;
        this.secrets = secrets;
        this.auditLog = auditLog;
    }

    /** 키가 있어 비밀을 싸고 풀 수 있나. 연습 계정 시더가 키 없는 기동에서 경고만 남기고 나가려고 본다 */
    public boolean available() {
        return secrets.available();
    }

    public boolean enrolled(long userId) {
        return jdbc.sql("select exists(select 1 from user_second_factor where user_id = :userId)")
                .param("userId", userId)
                .query(Boolean.class)
                .single();
    }

    /**
     * 확인된 비밀을 등록한다.
     *
     * @param confirmedStep 등록을 확인한 코드의 스텝. 그 코드를 로그인에 다시 못 쓰게 {@code last_used_step} 으로 둔다
     */
    @Transactional
    public void enroll(long userId, byte[] secret, long confirmedStep) {
        SecondFactorSecrets.Sealed sealed = secrets.seal(userId, secret);
        jdbc.sql("""
                        insert into user_second_factor (user_id, secret_ciphertext, secret_key_version, last_used_step)
                        values (:userId, :ciphertext, :keyVersion, :step)
                        """)
                .param("userId", userId)
                .param("ciphertext", sealed.ciphertext())
                .param("keyVersion", sealed.keyVersion())
                .param("step", confirmedStep)
                .update();
        auditLog.record(AuditLog.Kind.OUTCOME, "user.second_factor_enrolled", userId,
                AuditLog.Target.of(AuditTargetType.USER, userId), Map.of());
    }

    /** 코드가 맞고 처음 쓰는 스텝이면 true. 등록이 없거나 틀리거나 이미 쓴 스텝이면 false */
    @Transactional
    public boolean verify(long userId, String code, Instant now) {
        Stored stored = jdbc.sql("""
                        select secret_ciphertext, secret_key_version
                          from user_second_factor
                         where user_id = :userId
                        """)
                .param("userId", userId)
                .query((rs, rowNum) -> new Stored(rs.getBytes("secret_ciphertext"), rs.getInt("secret_key_version")))
                .optional()
                .orElse(null);
        if (stored == null) {
            return false;
        }
        byte[] secret = secrets.open(userId, stored.ciphertext(), stored.keyVersion());
        OptionalLong step = Totp.matchingStep(secret, code, Totp.stepAt(now));
        if (step.isEmpty()) {
            return false;
        }
        return jdbc.sql("""
                        update user_second_factor
                           set last_used_step = :step
                         where user_id = :userId
                           and last_used_step < :step
                        """)
                .param("step", step.getAsLong())
                .param("userId", userId)
                .update() == 1;
    }

    /** 등록을 지운다. 탈퇴가 그 트랜잭션에서 부른다 — 인증정보는 유예가 없다 */
    @Transactional
    public void remove(long userId) {
        jdbc.sql("delete from user_second_factor where user_id = :userId")
                .param("userId", userId)
                .update();
    }

    /** 저장된 암호문과 키 판 */
    private record Stored(byte[] ciphertext, int keyVersion) {
    }
}
