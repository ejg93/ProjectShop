package com.projectshop.shop.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

/**
 * 어떤 실패를 10분 뒤에 다시 해 보나(`D19` 2층).
 *
 * <p><b>이 판단이 코드에만 있었다.</b> 분류기를 직접 재는 테스트가 없어서,
 * 「이 실패는 재시도 대상이다」라는 결정이 맞는지 아무도 안 봤다 —
 * {@code BatchRetrySweeperTest} 는 <b>이미 분류된 행</b>을 넣고 스위퍼만 잰다.
 *
 * <p><b>마무리 17차가 그 틈을 실물로 찾았다.</b> `Q49` 가 「정산 번호가 부딪히면
 * 배치 재시도 스위퍼가 받으니 {@code Retries.onConflict} 로 안 감싼다」고 정했는데,
 * 분류기는 그 충돌을 {@code PERMANENT} 로 보내고 있었다 — <b>스위퍼가 그 회차를 안 집는다.</b>
 * 빌드도 테스트도 초록이었고 충돌 확률이 32⁶ 분의 1 이라 실행 중에도 안 드러난다.
 *
 * <p>Spring 도 DB 도 안 띄운다. 가르는 기준이 예외 사슬 하나라 그 판단만 떼어 볼 수 있다.
 */
@DisplayName("배치 실패의 종류")
class BatchFailureKindTest {

    @Test
    @DisplayName("노출 번호 충돌은 다시 해 볼 것이다")
    void exposedNumberConflictIsTransient() {
        // 다시 뽑으면 다른 번호가 나온다. **SQLSTATE 로는 안 갈린다** —
        // 23505 는 「다시 해도 같은 유일 위반」과 구분이 안 된다.
        RuntimeException thrown = conflictOf("settlement_number_unique");

        assertThat(BatchRuns.failureKindOf(thrown))
                .as("`Q49` 가 정산 배치를 Retries.onConflict 로 안 감싼 근거가 이 분류다")
                .isEqualTo(FailureKind.TRANSIENT);
    }

    @Test
    @DisplayName("그냥 유일 위반은 다시 해도 같다")
    void plainUniqueViolationIsPermanent() {
        RuntimeException thrown = new DataIntegrityViolationException("유니크 위반",
                new SQLException("duplicate key", "23505"));

        assertThat(BatchRuns.failureKindOf(thrown))
                .as("같은 값을 다시 넣는 것이라 세 번 죽고 로그만 세 배가 된다")
                .isEqualTo(FailureKind.PERMANENT);
    }

    @Test
    @DisplayName("충돌과 연결 끊김은 다시 해 볼 것이다")
    void conflictsAndDisconnectsAreTransient() {
        assertThat(BatchRuns.failureKindOf(new ConcurrencyFailureException("직렬화 실패",
                new SQLException("could not serialize access", "40001"))))
                .isEqualTo(FailureKind.TRANSIENT);

        assertThat(BatchRuns.failureKindOf(new DataIntegrityViolationException("끊김",
                new SQLException("connection failure", "08006"))))
                .as("연결 끊김은 앞 두 자리로 본다")
                .isEqualTo(FailureKind.TRANSIENT);
    }

    @Test
    @DisplayName("SQL 이 아닌 실패는 다시 해도 같다")
    void businessFailuresArePermanent() {
        assertThat(BatchRuns.failureKindOf(new IllegalStateException("본체가 터졌다")))
                .as("모르는 것을 재시도로 두면 같은 자리에서 세 번 죽는다")
                .isEqualTo(FailureKind.PERMANENT);
    }

    /**
     * {@link ExposedNumber#insert} 가 <b>실제로 내는 것</b>을 받아 온다.
     *
     * <p>손으로 조립하지 않는다 — 조립한 것으로 조립한 것을 재면 감싸는 모양이 바뀌어도 안 걸린다.
     */
    private static RuntimeException conflictOf(String constraint) {
        try {
            ExposedNumber.insert("", constraint, number -> {
                throw new DuplicateKeyException(
                        "ERROR: duplicate key value violates unique constraint \""
                                + constraint + "\"",
                        new SQLException("duplicate key", "23505"));
            });
            throw new IllegalStateException("충돌이 안 났다 — ExposedNumber.insert 가 바뀌었다");
        } catch (ExposedNumber.Conflict e) {
            return e;
        }
    }
}
