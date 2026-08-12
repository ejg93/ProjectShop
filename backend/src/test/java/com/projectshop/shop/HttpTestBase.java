package com.projectshop.shop;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import com.projectshop.shop.auth.AuthFixture;

/**
 * 진짜 서블릿 컨테이너를 띄우고 실제 HTTP 로 요청을 보내는 바탕.
 *
 * <p>MockMvc 는 서블릿 컨테이너를 안 띄운다. 그래서 갈리는 자리가 실제로 있었다 —
 * 5-1 은 거부 코드가 달랐고, 5-2 는 테스트 17개가 전부 통과하는데 아무도 가입을 못 했으며,
 * 5 에서는 손대지 않은 테스트가 깨졌다. 셋 다 손으로 {@code curl} 을 걸어서 잡았다.
 * 이 바탕은 그 손 검증을 테스트로 옮긴 것이다.
 *
 * <h2>롤백이 없다</h2>
 *
 * <p>요청이 별도 스레드에서 자기 트랜잭션으로 돌기 때문에 {@code @Transactional} 이 안 먹는다.
 * 테스트가 만든 데이터는 <b>직접 지운다.</b> 그래서 이 층의 테스트는
 * 계정 이메일을 {@link #EMAIL_PREFIX} 로 시작하게 만들고, 아래가 그것만 지운다.
 *
 * <p>이 제약 때문에 여기 둘 것을 고른다. <b>관통하는 흐름</b>만 여기서 보고,
 * 규칙 하나하나는 롤백이 도는 통합 층({@link PostgresTestBase})에 둔다.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(PostgresTestBase.Containers.class)
public abstract class HttpTestBase {

    /** 이 층이 만든 계정임을 알아보는 표시. 정리가 이것만 지운다. */
    protected static final String EMAIL_PREFIX = "http-test-";

    /** 이 층이 만든 셀러임을 알아보는 표시. 계정과 같은 이유로 둔다 */
    protected static final String SELLER_PREFIX = "http-test-";

    /** 셀러 코드와 상품 이름이 안 겹치게 한다. 한 실행 안에서만 안 겹치면 된다 */
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbc;

    /**
     * 정리를 한 트랜잭션으로 묶는 데 쓴다.
     *
     * <p><b>나눠 지우면 지연 트리거가 중간에 터진다.</b> {@code order_item} 만 지우고 커밋하면
     * 그 시점에 {@code seller_order} 는 남아 있는데 항목 합이 0 이라 금액 등식이 깨진다
     * ({@code order_item_amounts_check}). 한 트랜잭션 안이면 커밋 시점에 주문이 이미 없어서
     * {@code assert_order_amounts} 가 그냥 돌아간다 — {@code TransactionPurgeService} 와 같은 모양이다.
     */
    @Autowired
    private TransactionTemplate transactions;

    /**
     * 이 층이 남긴 것만 지운다.
     *
     * <p>순서가 곧 외래키 순서다. 주문 축을 먼저 걷고 상품·셀러를 걷고 계정을 마지막에 지운다 —
     * {@code order_item.sku_id}·{@code product.created_by_user_id}·{@code shop_order.user_id} 가
     * 전부 {@code restrict} 라 거꾸로 가면 지워지지 않는다.
     *
     * <p>{@code audit_log} 는 계정에 외래키가 없어서(V10) 같이 안 지워진다. 따로 지운다 —
     * 안 지우면 감사 조회 테스트가 남의 층이 남긴 행을 세게 된다.
     */
    @AfterEach
    protected void cleanUp() {
        transactions.executeWithoutResult(status -> {
            String users = "select user_id from app_user where email like :prefix";
            String orders = "select order_id from shop_order where user_id in (" + users + ")";
            String sellers = "select seller_id from seller where code like :sellerPrefix";
            String products = "select product_id from product where seller_id in (" + sellers + ")";

            // 이력이 주문·묶음·행위자 셋을 가리킨다. 셋 다 restrict 라 이것부터 지운다.
            delete("""
                    delete from order_status_history
                     where actor_user_id in (%s)
                        or order_id in (%s)
                        or seller_order_id in (select seller_order_id from seller_order
                                                where order_id in (%s))
                    """.formatted(users, orders, orders));

            delete("""
                    delete from order_item
                     where seller_order_id in (select seller_order_id from seller_order
                                                where order_id in (%s))
                    """.formatted(orders));

            delete("delete from seller_order where order_id in (%s)".formatted(orders));
            delete("delete from shop_order where user_id in (%s)".formatted(users));

            // 장바구니는 계정·SKU 에서 cascade 로 따라온다. sku_option_value·product_option 도 같다.
            delete("delete from sku where product_id in (%s)".formatted(products));
            delete("delete from product where seller_id in (%s)".formatted(sellers));
            delete("delete from seller where code like :sellerPrefix");

            delete("delete from audit_log where actor_user_id in (%s)".formatted(users));
            delete("delete from app_user where email like :prefix");
        });
    }

    private void delete(String sql) {
        jdbc.sql(sql)
                .param("prefix", EMAIL_PREFIX + "%")
                .param("sellerPrefix", SELLER_PREFIX + "%")
                .update();
    }

    /**
     * 살 수 있는 상품 하나. <b>파는 쪽까지 통째로 세운다.</b>
     *
     * <p>여기까지 세우는 데 손이 많이 간다 — 셀러를 만들고, 신원정보를 채워 {@code active} 로 올리고
     * (`3c` 의 {@code seller_verified_fields_check}), 대표 계정을 붙이고, 상품을 {@code on_sale} 로
     * 넣어야 한다({@code check_product_sale_allowed} 트리거가 그 순서를 강제한다).
     * <b>그 벽 때문에 청크 9 가 장바구니 병합을 서비스 층에만 남겼다.</b>
     *
     * <p>시드(`db/seed`)를 안 쓴다. 그쪽은 {@code local} 프로필이라 테스트가 안 태우고,
     * 태우게 만들면 <b>테스트가 시드 데이터에 기대게 된다</b> — 시드를 고치는 날 테스트가 깨진다.
     *
     * @param price 조합 하나의 값. 금액 등식을 보는 테스트가 이 값으로 기대치를 만든다
     * @param stock 재고. 품절 경로를 보려면 작게 준다
     */
    protected SellableProduct givenSellableProduct(long price, int stock) {
        int sequence = SEQUENCE.incrementAndGet();
        AuthFixture fixture = new AuthFixture(jdbc);

        long sellerId = fixture.insertSeller(SELLER_PREFIX + sequence, "테스트 셀러 " + sequence);
        fixture.verifySeller(sellerId);

        long ownerUserId = fixture.insertUser(
                EMAIL_PREFIX + "seller-" + sequence + "@test.local", "테스트 대표");
        fixture.joinSeller(sellerId, ownerUserId);
        fixture.grantOrg(ownerUserId, "seller_owner", sellerId);

        long productId = jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name, status)
                        values (:sellerId, :userId, :name, :status)
                        returning product_id
                        """)
                .param("sellerId", sellerId)
                .param("userId", ownerUserId)
                .param("name", "테스트 상품 " + sequence)
                .param("status", "on_sale")
                .query(Long.class)
                .single();

        long skuId = jdbc.sql("""
                        insert into sku (product_id, price, stock_count)
                        values (:productId, :price, :stock)
                        returning sku_id
                        """)
                .param("productId", productId)
                .param("price", price)
                .param("stock", stock)
                .query(Long.class)
                .single();

        return new SellableProduct(sellerId, ownerUserId, productId, skuId, price);
    }

    /**
     * 이 계정을 그 셀러의 대표로 붙인다. <b>셀러 쪽 경로를 부르는 테스트가 쓴다</b> —
     * 소속만 있고 역할이 없으면 목록 조회가 거부된다({@code ORDER_FORBIDDEN}).
     */
    protected void givenSellerOwner(long userId, long sellerId) {
        AuthFixture fixture = new AuthFixture(jdbc);
        fixture.joinSeller(sellerId, userId);
        fixture.grantOrg(userId, "seller_owner", sellerId);
    }

    /**
     * 세워 둔 상품. <b>내부 id 를 담는다</b> — 응답이 아니라 테스트가 쓰는 값이라
     * 노출 번호 규칙(`D9`)이 안 걸린다.
     */
    protected record SellableProduct(long sellerId, long ownerUserId, long productId,
            long skuId, long price) {
    }

    /** 한 사람이 브라우저 하나로 하는 일. 쿠키를 들고 다닌다. */
    protected Session newSession() {
        return new Session(RestClient.create("http://localhost:" + port));
    }

    protected record Response(HttpStatusCode status, String body, HttpHeaders headers) {

        public boolean is(int code) {
            return status.value() == code;
        }
    }

    /**
     * 쿠키를 손으로 나른다.
     *
     * <p>브라우저가 하는 일을 흉내 내는 것이 이 층의 핵심이다. 세션 쿠키와 CSRF 토큰이
     * 실제로 오가는지를 보려는 것이라, 자동으로 붙여 주는 클라이언트를 쓰면 검증이 사라진다.
     */
    protected static final class Session {

        private static final String CSRF_COOKIE = "XSRF-TOKEN";
        private static final String CSRF_HEADER = "X-XSRF-TOKEN";

        private final RestClient client;
        private final Map<String, String> cookies = new LinkedHashMap<>();

        private Session(RestClient client) {
            this.client = client;
        }

        public Response get(String path) {
            return exchange(client.get().uri(path));
        }

        /** CSRF 토큰을 자동으로 싣는다. 토큰 없이 보내는 경우는 {@link #postWithoutToken} 이다. */
        public Response post(String path, String json) {
            RestClient.RequestBodySpec spec = client.post().uri(path)
                    .contentType(MediaType.APPLICATION_JSON);

            String token = cookies.get(CSRF_COOKIE);
            if (token != null) {
                spec = spec.header(CSRF_HEADER, token);
            }
            return exchange(json == null ? spec : spec.body(json));
        }

        /**
         * 멱등키를 실어 보낸다. <b>주문 생성이 이 헤더를 요구한다</b>(`D11`) —
         * 없으면 400 이라, 헤더를 안 싣는 클라이언트는 주문 경로 자체를 못 지난다.
         *
         * <p>필터로 강제하지 않은 것이 의도였다(`10-0`). 그래서 <b>이 층이 헤더가 실제로
         * 요구되는지를 확인하는 유일한 자리</b>다.
         */
        public Response postWithIdempotencyKey(String path, String json, String key) {
            RestClient.RequestBodySpec spec = client.post().uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", key);

            String token = cookies.get(CSRF_COOKIE);
            if (token != null) {
                spec = spec.header(CSRF_HEADER, token);
            }
            return exchange(json == null ? spec : spec.body(json));
        }

        /**
         * 프록시를 거쳐 온 것처럼 보낸다.
         *
         * <p>{@code server.forward-headers-strategy} 를 검증하는 유일한 층이다.
         * Tomcat 밸브가 하는 일이라 MockMvc 에서는 이 헤더가 아무 일도 안 한다.
         */
        public Response postForwardedFrom(String path, String json, String clientIp) {
            RestClient.RequestBodySpec spec = client.post().uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Forwarded-For", clientIp);

            String token = cookies.get(CSRF_COOKIE);
            if (token != null) {
                spec = spec.header(CSRF_HEADER, token);
            }
            return exchange(json == null ? spec : spec.body(json));
        }

        public Response postWithoutToken(String path, String json) {
            RestClient.RequestBodySpec spec = client.post().uri(path)
                    .contentType(MediaType.APPLICATION_JSON);
            return exchange(json == null ? spec : spec.body(json));
        }

        /** 쿠키는 제대로 들고 있는데 헤더 값만 남의 것인 경우 */
        public Response postWithForgedToken(String path, String json) {
            RestClient.RequestBodySpec spec = client.post().uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(CSRF_HEADER, "made-up-value");
            return exchange(json == null ? spec : spec.body(json));
        }

        public String cookie(String name) {
            return cookies.get(name);
        }

        private Response exchange(RestClient.RequestHeadersSpec<?> spec) {
            if (!cookies.isEmpty()) {
                spec = spec.header(HttpHeaders.COOKIE, joinCookies());
            }
            return spec.exchange((request, response) -> {
                HttpHeaders headers = response.getHeaders();
                storeCookies(headers.get(HttpHeaders.SET_COOKIE));
                return new Response(response.getStatusCode(),
                        new String(response.getBody().readAllBytes()), headers);
            }, false);
        }

        private String joinCookies() {
            return cookies.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("");
        }

        private void storeCookies(List<String> setCookies) {
            if (setCookies == null) {
                return;
            }
            for (String raw : setCookies) {
                String pair = raw.split(";", 2)[0];
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String name = pair.substring(0, eq);
                String value = pair.substring(eq + 1);
                // 값이 빈 Set-Cookie 는 삭제 지시다. 로그아웃이 세션 쿠키를 이렇게 지운다.
                if (value.isEmpty()) {
                    cookies.remove(name);
                } else {
                    cookies.put(name, value);
                }
            }
        }
    }
}
