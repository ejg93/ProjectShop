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
import org.springframework.dao.DuplicateKeyException;
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
        @DisplayName("노출 번호가 부딪히면 다시 뽑아서 결국 성공한다")
        void retriesExposedNumberConflict() {
            AtomicInteger attempts = new AtomicInteger();

            String result = Retries.onConflict(() -> ExposedNumber.insert(
                    "S-", "seller_order_number_unique",
                    number -> {
                        if (attempts.incrementAndGet() < 3) {
                            throw duplicateOf("seller_order_number_unique");
                        }
                        return number;
                    }));

            assertThat(result).startsWith("S-");
            assertThat(attempts)
                    .as("재시도가 트랜잭션 밖이라 실제로 돈다(`Q49`). 안쪽에서는 두 번째가 25P02 로 죽었다")
                    .hasValue(3);
        }

        /**
         * 충돌이 한 겹 감싸여 와도 찾는다(`Q190`). 변이 시험이 원인 사슬을 도는 조건을 뒤집어도 안 깨지는 것을 짚었다 —
         * 서비스가 예외를 감싸 던지는 날 재시도가 조용히 꺼진다.
         */
        @Test
        @DisplayName("감싼 노출 번호 충돌도 다시 돈다")
        void retriesWrappedExposedNumberConflict() {
            AtomicInteger attempts = new AtomicInteger();

            String result = Retries.onConflict(() -> {
                if (attempts.incrementAndGet() < 2) {
                    throw new IllegalStateException("감쌌다", new ExposedNumber.Conflict(
                            "seller_order_number_unique", duplicateOf("seller_order_number_unique")));
                }
                return "됐다";
            });

            assertThat(result).isEqualTo("됐다");
            assertThat(attempts).hasValue(2);
        }

        /** 곧바로 다시 부딪치지 않게 기다린다. 하한만 잰다 — 상한은 기계가 바쁘면 흔들린다 */
        @Test
        @DisplayName("다시 돌기 전에 기다린다")
        void waitsBeforeRetrying() {
            AtomicInteger attempts = new AtomicInteger();
            long started = System.nanoTime();

            Retries.onConflict(() -> {
                if (attempts.incrementAndGet() < 2) {
                    throw deadlock();
                }
                return "됐다";
            });

            assertThat(java.time.Duration.ofNanos(System.nanoTime() - started))
                    .as("첫 대기가 50ms 다")
                    .isGreaterThanOrEqualTo(java.time.Duration.ofMillis(50));
        }

        /** 기다리다 끊기면 끊김 표시를 되살리고 멈춘다 — 삼키면 스레드를 멈추려던 쪽이 그 사실을 잃는다 */
        @Test
        @DisplayName("기다리다 끊기면 멈추고 끊김을 되살린다")
        void stopsAndKeepsTheInterruptWhenInterrupted() {
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> Retries.onConflict(() -> {
                    throw deadlock();
                }))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("끊겼다");
            } finally {
                assertThat(Thread.interrupted()).as("끊김 표시가 남아 있어야 한다").isTrue();
            }
        }

        @Test
        @DisplayName("같은 insert 의 다른 유일 제약을 어긴 것은 다시 돌지 않는다")
        void doesNotRetryWhenAnotherConstraintFailed() {
            AtomicInteger attempts = new AtomicInteger();

            assertThatThrownBy(() -> Retries.onConflict(() -> ExposedNumber.insert(
                    "S-", "seller_order_number_unique",
                    number -> {
                        attempts.incrementAndGet();
                        throw duplicateOf("seller_order_one_per_seller_unique");
                    })))
                    .isInstanceOf(DuplicateKeyException.class)
                    .isNotInstanceOf(ExposedNumber.Conflict.class);

            assertThat(attempts)
                    .as("번호를 다시 뽑아도 그 제약은 그대로 걸린다")
                    .hasValue(1);
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
        @DisplayName("결제사가 준 번호가 부딪힌 것은 다시 돌지 않는다")
        void doesNotRetryGatewaySuppliedNumbers() {
            AtomicInteger attempts = new AtomicInteger();

            // `payment_approval_number_unique` 도 이름이 `_number_unique` 로 끝난다.
            // **꼬리로 갈랐으면 여기가 다시 돌았다** — 결제사가 같은 승인번호를 또 주므로
            // 네 번 다 실패하고, 게다가 진짜 중복 승인을 재시도가 덮는다(`Q49`).
            assertThatThrownBy(() -> Retries.onConflict(() -> {
                attempts.incrementAndGet();
                throw new DuplicateKeyException(
                        "ERROR: duplicate key value violates unique constraint "
                                + "\"payment_approval_number_unique\"",
                        new SQLException("duplicate key", "23505"));
            })).isInstanceOf(DuplicateKeyException.class);

            assertThat(attempts)
                    .as("바깥에서 받은 값은 다시 뽑을 것이 없다")
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

    /**
     * Postgres 가 내는 유일 위반을 그대로 흉내 낸다.
     *
     * <p><b>메시지 꼴이 판정의 입력이다</b>(`Q49`). 제약 이름을 타입으로 읽으려면
     * {@code PSQLException} 이 필요한데 드라이버가 {@code runtimeOnly} 라 컴파일로 못 붙는다.
     * 이 문자열이 실물과 같은지는 {@code ExposedNumberConflictTest} 가 진짜 DB 로 고정한다.
     */
    private static RuntimeException duplicateOf(String constraint) {
        return new DuplicateKeyException(
                "ERROR: duplicate key value violates unique constraint \"" + constraint + "\"",
                new SQLException("duplicate key", "23505"));
    }
}
