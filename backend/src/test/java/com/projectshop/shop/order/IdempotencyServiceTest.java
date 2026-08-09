package com.projectshop.shop.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

/**
 * 같은 요청이 두 번 와도 결과가 하나인가.
 *
 * <p>여기서 보는 것은 <b>작업이 실제로 몇 번 실행됐나</b>다. 응답이 같게 나오는 것만 봐서는
 * 두 번 돌고 같은 답이 나온 경우와 구분이 안 된다 — 주문이라면 그 사이 재고가 두 번 빠진다.
 */
@DisplayName("멱등 처리")
class IdempotencyServiceTest extends PostgresTestBase {

    @Autowired
    private IdempotencyService idempotency;

    @Autowired
    private JdbcClient jdbc;

    private long userId;
    private long otherUserId;

    /** 몇 번 실행됐나. 재전송이 이 값을 안 올려야 멱등이다 */
    private AtomicInteger runs;

    public record Request(String item, int quantity) {}

    public record Response(String orderNumber) {}

    @BeforeEach
    void setUp() {
        AuthFixture fixture = new AuthFixture(jdbc);
        userId = fixture.insertUser("idem@test.local", "구매자");
        otherUserId = fixture.insertUser("idem-other@test.local", "다른 구매자");
        runs = new AtomicInteger();
    }

    @Nested
    @DisplayName("같은 키로 두 번 오면")
    class SameKey {

        @Test
        @DisplayName("작업은 한 번만 돈다")
        void runsOnlyOnce() {
            idempotency.run(userId, "key-1", request(), Response.class, work());
            idempotency.run(userId, "key-1", request(), Response.class, work());

            assertThat(runs.get())
                    .as("두 번 돌면 주문이 둘 생기고 재고가 두 번 빠진다")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("첫 응답을 그대로 돌려준다")
        void replaysTheFirstResponse() {
            Response first = idempotency.run(userId, "key-2", request(), Response.class, work());
            Response second = idempotency.run(userId, "key-2", request(), Response.class, work());

            assertThat(second.orderNumber())
                    .as("클라이언트는 재전송인 줄 모르고 첫 결과를 받아야 한다")
                    .isEqualTo(first.orderNumber());
        }

        @Test
        @DisplayName("본문이 다르면 422 다")
        void rejectsDifferentBody() {
            idempotency.run(userId, "key-3", request(), Response.class, work());

            assertThatThrownBy(() ->
                    idempotency.run(userId, "key-3", new Request("다른 물건", 5), Response.class, work()))
                    .as("재전송이 아니라 키를 재사용한 것이다")
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
        }
    }

    @Nested
    @DisplayName("키의 범위는")
    class Scope {

        @Test
        @DisplayName("계정별이다 — 남과 겹쳐도 각자 돈다")
        void isPerAccount() {
            idempotency.run(userId, "shared-key", request(), Response.class, work());
            idempotency.run(otherUserId, "shared-key", request(), Response.class, work());

            assertThat(runs.get())
                    .as("키는 요청을 가르는 값이지 식별자가 아니다(`D11`)")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("다른 키면 다시 돈다")
        void isPerKey() {
            idempotency.run(userId, "key-a", request(), Response.class, work());
            idempotency.run(userId, "key-b", request(), Response.class, work());

            assertThat(runs.get()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("응답은")
    class StoredResponse {

        @Test
        @DisplayName("커밋 전에 반드시 채워진다")
        void mustBeFilledBeforeCommit() {
            idempotency.run(userId, "key-4", request(), Response.class, work());

            jdbc.sql("update idempotency_key set response_body = null where key_value = 'key-4'")
                    .update();

            assertThatThrownBy(() -> jdbc.sql("set constraints all immediate").update())
                    .as("응답이 안 붙은 채로 커밋되면 재전송이 빈 답을 받는다")
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        }

        @Test
        @DisplayName("본문 전체가 아니라 해시로 비교한다")
        void comparesByHash() {
            idempotency.run(userId, "key-5", request(), Response.class, work());

            String hash = jdbc.sql("select request_hash from idempotency_key where key_value = 'key-5'")
                    .query(String.class)
                    .single();

            assertThat(hash)
                    .as("본문 전체를 보관하지 않는다(`D11`)")
                    .hasSize(64);
        }
    }

    private Request request() {
        return new Request("검정 티셔츠", 2);
    }

    private java.util.function.Supplier<Response> work() {
        return () -> {
            runs.incrementAndGet();
            return new Response("20260809-7QX4M2");
        };
    }
}
