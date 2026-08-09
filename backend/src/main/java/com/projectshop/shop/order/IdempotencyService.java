package com.projectshop.shop.order;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;

import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 같은 요청이 두 번 도착해도 결과가 하나이게 만든다.
 *
 * <p>네트워크가 끊겨서 클라이언트가 응답을 못 받으면 요청은 서버에 닿아 처리됐을 수 있다.
 * 클라이언트는 실패로 보고 재전송하고, 막지 않으면 <b>주문이 둘 생기고 재고가 두 번 빠진다.</b>
 *
 * <p><b>선점·처리·저장이 한 트랜잭션이다.</b> 이게 이 클래스의 핵심이고 서블릿 필터에 안 둔 이유다.
 * 필터는 컨트롤러 바깥이라 자기 트랜잭션을 따로 열어야 하고, 그러면 셋이 갈라져서
 * <b>주문은 커밋됐는데 기록은 진행중인 구간</b>이 생긴다. 그 구간에서 죽으면 재전송이 영원히 막히고,
 * 타임아웃으로 풀면 중복 주문이 다시 열린다.
 *
 * <p>여기서는 어디서 죽든 전부 롤백이라 <b>반쪽 상태가 존재하지 않는다.</b>
 *
 * <p><b>감싸는 것을 빠뜨리면 아무도 안 잡는다.</b> 멱등이 필요한 경로가 실제로 이걸 거치는지는
 * 커버리지 테스트가 지킨다(청크 10-2). 필터로 헤더만 강제하는 방법도 있었지만,
 * 그건 "헤더는 요구하는데 멱등은 안 걸린" 상태를 만들어 오히려 가린다.
 */
@Service
public class IdempotencyService {

    /**
     * 앞 요청을 기다리는 한도.
     *
     * <p>같은 키의 뒤 요청은 앞이 끝날 때까지 유니크 충돌로 대기한다. 앞이 커밋되면 재생을 읽고,
     * 롤백되면 자기가 처리한다 — <b>대기가 알아서 옳은 답을 낸다.</b>
     * 다만 앞 요청이 길면 뒤가 그만큼 붙잡히므로 여기서 끊고 409 를 준다.
     */
    private static final int LOCK_TIMEOUT_MS = 3_000;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    IdempotencyService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * 이 키로 아직 처리한 적이 없으면 {@code work} 를 돌리고, 있으면 그때 응답을 그대로 돌려준다.
     *
     * <p>{@code work} 안의 서비스가 {@code @Transactional} 이면 <b>새 트랜잭션을 안 열고
     * 이 트랜잭션에 참여한다</b>(전파 방식 기본값이 {@code REQUIRED}). 그래서 하나로 묶인다.
     *
     * <p><b>실패는 저장하지 않는다.</b> 예외가 나면 이 행도 같이 롤백된다.
     * 실패는 자원이 안 생기므로 막을 중복이 없고, 저장하려고 트랜잭션을 쪼개면
     * 위에 적은 반쪽 상태가 그대로 돌아온다.
     *
     * @param request      본문 비교에 쓴다. 같은 키로 다른 본문이 오면 422
     * @param responseType 재생할 때 무엇으로 되돌릴지. 저장은 JSON 이라 타입이 필요하다
     */
    @Transactional
    public <T> T run(long userId, String key, Object request, Class<T> responseType, Supplier<T> work) {
        String hash = sha256(toJson(request));

        if (!claim(userId, key, hash)) {
            return replay(userId, key, hash, responseType);
        }

        T result = work.get();

        jdbc.sql("""
                        update idempotency_key set response_body = :body::jsonb
                         where user_id = :userId and key_value = :key
                        """)
                .param("body", toJson(result))
                .param("userId", userId)
                .param("key", key)
                .update();

        return result;
    }

    /**
     * 이 키를 내가 처음 잡았나.
     *
     * <p><b>insert 가 곧 락이다.</b> 조회해서 검사하고 넣는 절차가 없어서 그 사이에 끼어들 틈이 없다 —
     * 재고 차감의 조건부 UPDATE 와 같은 논리다(`D11`).
     */
    private boolean claim(long userId, String key, String hash) {
        // SET 은 값 바인딩이 안 되는 자리다. 상수라 결합해도 되고, 들어갈 값을 우리가 정한다(`D23`).
        jdbc.sql("set local lock_timeout = " + LOCK_TIMEOUT_MS).update();

        try {
            return jdbc.sql("""
                            insert into idempotency_key (user_id, key_value, request_hash)
                            values (:userId, :key, :hash)
                            on conflict (user_id, key_value) do nothing
                            """)
                    .param("userId", userId)
                    .param("key", key)
                    .param("hash", hash)
                    .update() == 1;
        } catch (CannotAcquireLockException e) {
            // 앞 요청이 아직 커밋을 안 했다. 진행중이라는 뜻이지만 그 행은 우리에게 안 보인다.
            throw new ShopException(ErrorCode.IDEMPOTENCY_IN_PROGRESS);
        }
    }

    private <T> T replay(long userId, String key, String hash, Class<T> responseType) {
        Stored stored = jdbc.sql("""
                        select request_hash, response_body::text as response_body
                          from idempotency_key
                         where user_id = :userId and key_value = :key
                        """)
                .param("userId", userId)
                .param("key", key)
                .query((rs, rowNum) -> new Stored(rs.getString("request_hash"), rs.getString("response_body")))
                .single();

        if (!stored.requestHash().equals(hash)) {
            throw new ShopException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }

        try {
            return objectMapper.readValue(stored.responseBody(), responseType);
        } catch (JacksonException e) {
            throw new IllegalStateException("저장해 둔 멱등 응답을 못 읽는다: " + key, e);
        }
    }

    private record Stored(String requestHash, String responseBody) {}

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("멱등 처리 대상을 JSON 으로 못 바꾼다: " + value, e);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 이 없는 JVM 이다", e);
        }
    }
}
