package com.projectshop.shop.auth;

import java.security.SecureRandom;
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

    /** 인증 앱에 보이는 발급자 이름 */
    private static final String ISSUER = "ProjectShop";

    private final JdbcClient jdbc;
    private final SecondFactorSecrets secrets;
    private final AuditLog auditLog;
    private final SecureRandom random = new SecureRandom();

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

    /** 새 비밀을 뽑아 싸고, 인증 앱에 넣을 키 문자열과 주소를 같이 낸다. 표에는 아직 안 넣는다 */
    Enrollment newEnrollment(long userId, String email) {
        byte[] secret = Totp.newSecret(random);
        SecondFactorSecrets.Sealed sealed = secrets.seal(userId, secret);
        return enrollmentOf(email, sealed.ciphertext(), sealed.keyVersion(), secret);
    }

    /** 세션에 싸 둔 등록 비밀을 다시 낸다 — 새로 고쳐도 앱에 넣은 것과 같은 비밀이다 */
    Enrollment reopen(long userId, String email, byte[] ciphertext, int keyVersion) {
        return enrollmentOf(email, ciphertext, keyVersion, secrets.open(userId, ciphertext, keyVersion));
    }

    /** 등록 중인 비밀로 첫 코드를 확인한다. 맞으면 그 스텝을 쓴 것으로 두고 등록한다 */
    @Transactional
    boolean confirmEnrollment(long userId, byte[] ciphertext, int keyVersion, String code, Instant now) {
        byte[] secret = secrets.open(userId, ciphertext, keyVersion);
        OptionalLong step = Totp.matchingStep(secret, code, Totp.stepAt(now));
        if (step.isEmpty()) {
            return false;
        }
        enroll(userId, secret, step.getAsLong());
        return true;
    }

    private Enrollment enrollmentOf(String email, byte[] ciphertext, int keyVersion, byte[] secret) {
        return new Enrollment(ciphertext, keyVersion, Totp.base32(secret), Totp.otpauthUri(ISSUER, email, secret));
    }

    /** 등록을 지운다. 탈퇴가 그 트랜잭션에서 부른다 — 인증정보는 유예가 없다 */
    @Transactional
    public void remove(long userId) {
        jdbc.sql("delete from user_second_factor where user_id = :userId")
                .param("userId", userId)
                .update();
    }

    /** 등록 중인 비밀 — 세션에 둘 암호문과, 화면에 낼 키 문자열·주소 */
    record Enrollment(byte[] ciphertext, int keyVersion, String secret, String otpauthUri) {
    }

    /** 저장된 암호문과 키 판 */
    private record Stored(byte[] ciphertext, int keyVersion) {
    }
}
