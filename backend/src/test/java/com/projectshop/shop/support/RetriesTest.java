package com.projectshop.shop.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;

import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

/**
 * 무엇을 다시 돌리고 무엇을 안 돌리나.
 *
 * <p>Spring 도 DB 도 안 띄운다. 가르는 기준이 SQLSTATE 하나라 그 판단만 떼어 볼 수 있다.
 * 실제 충돌이 이 SQLSTATE 로 오는지는 {@code OrderConcurrencyTest} 가 DB 로 확인한다.
 */
class RetriesTest {

    @Nested
    @DisplayName("다시 도나")
    class Retrying {

        @Test
        @DisplayName("충돌이면 다시 돌아서 결국 성공한다")
        void retriesUntilSuccess() {
            AtomicInteger attempts = new AtomicInteger();

            String result = Retries.onConflict(() -> {
                if (attempts.incrementAndGet() < 3) {
                    throw deadlock();
                }
                return "됐다";
            });

            assertThat(result).isEqualTo("됐다");
            assertThat(attempts).hasValue(3);
        }

        @Test
        @DisplayName("직렬화 실패도 다시 돈다")
        void retriesSerializationFailure() {
            AtomicInteger attempts = new AtomicInteger();

            Retries.onConflict(() -> {
                if (attempts.incrementAndGet() < 2) {
                    throw new ConcurrencyFailureException("직렬화 실패",
                            new SQLException("could not serialize access", "40001"));
                }
                return null;
            });

            assertThat(attempts).hasValue(2);
        }

        @Test
        @DisplayName("세 번 더 해도 안 되면 마지막 예외를 올린다")
        void givesUpAfterThreeRetries() {
            AtomicInteger attempts = new AtomicInteger();

            assertThatThrownBy(() -> Retries.onConflict(() -> {
                attempts.incrementAndGet();
                throw deadlock();
            })).isInstanceOf(PessimisticLockingFailureException.class);

            assertThat(attempts)
                    .as("최초 1회 + 재시도 3회다(`D11`). 무한히 물면 뒤에 쌓인 요청까지 느려진다")
                    .hasValue(4);
        }
    }

    @Nested
    @DisplayName("안 도는 것")
    class NotRetrying {

        @Test
        @DisplayName("충돌이 아닌 DB 오류는 그대로 올린다")
        void doesNotRetryOtherDatabaseErrors() {
            AtomicInteger attempts = new AtomicInteger();

            assertThatThrownBy(() -> Retries.onConflict(() -> {
                attempts.incrementAndGet();
                throw new DataIntegrityViolationException("유니크 위반",
                        new SQLException("duplicate key", "23505"));
            })).isInstanceOf(DataIntegrityViolationException.class);

            assertThat(attempts)
                    .as("다시 해도 같은 결과인 것을 반복하면 실패가 네 배 느려진다")
                    .hasValue(1);
        }

        @Test
        @DisplayName("업무 예외는 그대로 올린다")
        void doesNotRetryBusinessFailures() {
            AtomicInteger attempts = new AtomicInteger();

            assertThatThrownBy(() -> Retries.onConflict(() -> {
                attempts.incrementAndGet();
                throw new ShopException(ErrorCode.OUT_OF_STOCK);
            })).isInstanceOf(ShopException.class);

            assertThat(attempts)
                    .as("재고 부족·권한 없음은 다시 해도 같다(`D11`)")
                    .hasValue(1);
        }
    }

    /**
     * 데드락을 <b>예외 이름이 아니라 SQLSTATE 로</b> 가른다는 것을 고정한다.
     *
     * <p>Postgres 의 {@code 40P01} 은 {@code DeadlockLoserDataAccessException} 이 아니라
     * 상위 타입으로 온다(`stack.md`). 타입으로 잡게 바꾸면 이 테스트가 깨진다.
     */
    private static RuntimeException deadlock() {
        return new PessimisticLockingFailureException("데드락",
                new SQLException("deadlock detected", "40P01"));
    }
}
