package com.projectshop.shop.support;

import java.sql.SQLException;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 충돌로 깨진 트랜잭션을 그 자리에서 다시 돌린다.
 *
 * <p><b>트랜잭션 바깥에서만 부른다</b>(`D11`). 안쪽에서 걸면 이미 깨진 트랜잭션을 붙들고 다시 도는데,
 * Postgres 는 {@code 40001}·{@code 40P01} 이 난 트랜잭션을 abort 시켜서
 * 다음 문장이 {@code 25P02} 로 죽는다. 즉 안쪽 재시도는 <b>성공할 수 없는 자리</b>다.
 *
 * <p><b>SQLSTATE 를 직접 본다. 예외 타입으로 안 가른다.</b> Postgres 는 SQLSTATE 앞 두 자리로
 * 번역돼서 데드락이 {@code DeadlockLoserDataAccessException} 이 아니라 상위 타입으로 온다
 * (`stack.md`). 타입으로 잡으면 데드락을 놓치거나, 넓게 잡아서 <b>다시 해도 같은 실패</b>까지 반복한다.
 */
public final class Retries {

    private static final Logger log = LoggerFactory.getLogger(Retries.class);

    /**
     * 최초 시도가 실패한 뒤의 대기 간격(`D11`). 길이가 곧 재시도 횟수라 총 4번 시도한다.
     *
     * <p>3회에도 안 되면 실패로 떨어뜨린다. 더 끄는 것은 충돌이 아니라 부하라서,
     * 계속 물고 있으면 뒤에 쌓인 요청까지 같이 느려진다.
     */
    private static final long[] BACKOFF_MS = {50, 100, 200};

    /** 직렬화 실패와 데드락. <b>다시 해서 결과가 달라질 수 있는 것은 이 둘뿐이다</b>(`D11`) */
    private static final Set<String> CONFLICT_STATES = Set.of("40001", "40P01");

    private Retries() {
    }

    /**
     * {@code work} 를 돌리고, 충돌로 깨지면 간격을 두고 다시 돌린다.
     *
     * <p>값을 안 돌려주는 자리도 이걸 쓴다. {@code Runnable} 을 받는 짝을 같이 두면
     * <b>값을 돌려주는 람다가 둘 다에 맞아서 컴파일이 모호해진다</b> — 부르는 쪽에서
     * {@code return null} 을 적는 편이 낫다.
     *
     * @throws RuntimeException 충돌이 아닌 예외는 그대로 올린다. 재시도 횟수를 다 쓰면 마지막 것을 올린다
     */
    public static <T> T onConflict(Supplier<T> work) {
        return on(work, thrown -> {
            String state = conflictState(thrown);
            return state == null ? null : "충돌 sqlstate=" + state;
        });
    }

    /**
     * {@code work} 를 돌리고, {@code reason} 이 사유를 짚어 주면 간격을 두고 다시 돌린다.
     *
     * <p><b>무엇을 다시 돌릴지는 부르는 쪽이 정한다.</b> 충돌 말고도 다시 해서 결과가 달라지는 자리가
     * 있어서다 — 결제사 무응답이 그것이고, 그건 SQLSTATE 로 안 갈린다(`D11`).
     * 간격과 횟수는 여기 하나뿐이라 자리마다 다른 값이 생기지 않는다.
     *
     * @param reason 다시 돌릴 예외면 로그에 남길 사유를, 아니면 {@code null}
     * @throws RuntimeException 사유가 없는 예외는 그대로 올린다. 횟수를 다 쓰면 마지막 것을 올린다
     */
    public static <T> T on(Supplier<T> work, Function<RuntimeException, String> reason) {
        RuntimeException last = null;

        for (int attempt = 0; attempt <= BACKOFF_MS.length; attempt++) {
            if (attempt > 0) {
                // 재시도는 WARN 이다 — 지금은 도는데 이상한 것이다(`D16`)
                log.warn("{} 라 다시 돈다 {}번째", reason.apply(last), attempt);
                sleep(BACKOFF_MS[attempt - 1]);
            }

            try {
                return work.get();
            } catch (RuntimeException e) {
                if (reason.apply(e) == null) {
                    throw e;
                }
                last = e;
            }
        }

        throw last;
    }

    /**
     * 이 예외가 충돌인가. 충돌이면 그 SQLSTATE 를, 아니면 null 을 준다.
     *
     * <p>원인 사슬을 타고 내려간다. Spring 이 {@code SQLException} 을 감싸서 올리므로
     * 맨 위 예외에는 SQLSTATE 가 없다.
     */
    private static String conflictState(Throwable thrown) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && CONFLICT_STATES.contains(sql.getSQLState())) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    /**
     * 끊기면 재시도를 접는다.
     *
     * <p>표시만 복구하고 삼키면 <b>멈추라는 신호를 무시한 채로 다시 돈다.</b>
     * 종료 중인 스레드가 새 트랜잭션을 여는 것이라 그쪽이 더 나쁘다.
     */
    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("재시도를 기다리다 끊겼다", e);
        }
    }
}
