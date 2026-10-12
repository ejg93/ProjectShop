package com.projectshop.shop.error;

import java.time.Duration;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * 바깥 의존(DB·Redis)에 못 닿은 실패를 예외에서 가려낸다(`Q260`).
 *
 * <p><b>가려 내는 자리가 둘이라 판정을 여기 하나에 둔다.</b> MVC 안에서 터진 것은
 * {@link ApiExceptionHandler} 가, 그 앞 필터(세션 조회·계정 생존 검사)에서 터진 것은
 * {@link DependencyFailureFilter} 가 받는다 — 둘이 고르는 예외가 갈리면 같은 장애가 입구에 따라 500 과 503 으로 갈린다.
 *
 * <table>
 *   <caption>고르는 셋</caption>
 *   <tr><th>예외</th><th>언제</th></tr>
 *   <tr><td>{@code CannotGetJdbcConnectionException}</td><td>{@code JdbcClient} 가 풀에서 연결을 못 얻었다</td></tr>
 *   <tr><td>{@code RedisConnectionFailureException}</td><td>세션·카운터가 Redis 에 못 닿았다</td></tr>
 *   <tr><td>{@link CannotCreateTransactionException}</td><td>{@code @Transactional} 이 트랜잭션을 열려다 연결을 못 얻었다
 *       — 이것만 {@code DataAccessException} 이 아니다</td></tr>
 * </table>
 *
 * <p>앞 둘은 {@link DataAccessResourceFailureException} 하나로 받는다. <b>질의가 틀린 것은 안 고른다</b> —
 * 문법·제약 위반은 우리 코드 탓이라 500 이 맞다.
 */
final class DependencyFailure {

    /**
     * 다시 걸어 볼 때까지 기다릴 초. `68` 실험 A 에서 DB 를 되살리면 풀이 4초 안에 회복했다.
     * 값이 없으면 받는 쪽이 언제 다시 걸지 몰라 바로 다시 친다(RFC 9110 제10.2.3절).
     */
    static final Duration RETRY_AFTER = Duration.ofSeconds(5);

    private DependencyFailure() {
    }

    /**
     * 예외나 그 원인 줄에 의존 실패가 있으면 그것을 돌려준다. 필터를 지나며 {@code ServletException} 에 싸여 오는 것도 본다.
     */
    static Throwable find(Throwable thrown) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof DataAccessResourceFailureException
                    || cause instanceof CannotCreateTransactionException) {
                return cause;
            }
        }
        return null;
    }
}
