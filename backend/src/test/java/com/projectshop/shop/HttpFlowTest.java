package com.projectshop.shop;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 요청 하나가 인증·판정·감사를 실제로 지나가는지를 <b>진짜 HTTP 로</b> 본다.
 *
 * <p>지금까지 이 확인은 청크마다 손으로 {@code curl} 을 걸어서 했다. 값을 했지만 —
 * 세 번의 실제 결함을 그렇게 잡았다 — 자동이 아니라서 <b>다음 청크가 깨뜨려도 모른다.</b>
 * 여기가 그 손 검증을 대체한다.
 */
class HttpFlowTest extends HttpTestBase {

    private static final String PASSWORD = "hunter2-and-then-some";

    /** 모의 PG 가 승인하는 카드. 뒷 4자리가 결과를 가른다(`MockPaymentGateway`) */
    private static final String GOOD_CARD = "4242-4242-4242-4242";

    /** 한도 초과로 거절되는 카드 */
    private static final String DECLINED_CARD = "4242-4242-4242-0000";

    @Autowired
    JdbcClient jdbc;

    @Nested
    @DisplayName("CSRF 토큰")
    class Csrf {

        @Test
        @DisplayName("아무 GET 에나 토큰 쿠키가 실려 온다")
        void issuesTokenCookie() {
            Session session = newSession();
            session.get("/api/health");

            assertThat(session.cookie("XSRF-TOKEN"))
                    .as("토큰을 못 받으면 클라이언트는 POST 를 아예 못 부른다")
                    .isNotBlank();
        }

        @Test
        @DisplayName("토큰 없는 POST 는 401 이다")
        void rejectsPostWithoutToken() {
            Session session = newSession();
            session.get("/api/health");

            Response response = session.postWithoutToken("/api/auth/signup", "{}");

            // MockMvc 에서는 같은 요청이 403 으로 나온다. 실제 서버의 답은 이쪽이다.
            // 어느 쪽이 진짜인지 여기서 확정한다.
            assertThat(response.is(401))
                    .as("실제 상태 코드는 %s 였다", response.status())
                    .isTrue();
        }

        @Test
        @DisplayName("받은 토큰을 실으면 지나간다")
        void passesWithToken() {
            Session session = newSession();
            session.get("/api/health");

            assertThat(signUp(session, "csrf").is(201)).isTrue();
        }

        @Test
        @DisplayName("쿠키만 있고 헤더가 없으면 막힌다")
        void cookieAloneIsNotEnough() {
            Session session = newSession();
            session.get("/api/health");

            // 쿠키는 브라우저가 자동으로 붙인다. 그것만으로 통과하면 남의 사이트에서 쏜
            // 요청도 지나가고 CSRF 방어가 없는 것과 같아진다.
            assertThat(session.postWithoutToken("/api/auth/signup", "{}").is(401)).isTrue();
        }

        @Test
        @DisplayName("남의 값을 헤더에 넣으면 막힌다")
        void forgedTokenIsRejected() {
            Session session = newSession();
            session.get("/api/health");

            assertThat(session.postWithForgedToken("/api/auth/signup", "{}").is(401)).isTrue();
        }
    }

    @Nested
    @DisplayName("가입부터 감사까지")
    class Vertical {

        @Test
        @DisplayName("가입하면 계정·역할·동의·감사가 함께 남는다")
        void signUpWritesEverything() {
            Session session = newSession();
            session.get("/api/health");

            Response signUp = signUp(session, "vertical");

            assertThat(signUp.is(201)).isTrue();
            long userId = userIdOf("vertical");

            assertThat(count("select count(*) from user_role where user_id = " + userId))
                    .as("역할이 없으면 로그인해도 할 수 있는 것이 없다").isEqualTo(1);
            assertThat(count("select count(*) from user_consent where user_id = " + userId))
                    .isEqualTo(2);
            assertThat(count("select count(*) from audit_log where actor_user_id = " + userId))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("로그인 없이 장바구니를 연다")
        void opensCartWithoutLogin() {
            // 병합은 CartServiceTest 가 덮는다. 여기서 보는 것은 경로가 실제로 열려 있느냐다 —
            // PUBLIC_PATHS 는 MockMvc 로 못 잡는 자리다.
            Response response = newSession().get("/api/cart");

            assertThat(response.is(200))
                    .as("사는 사람은 로그인 전에 담는다. 막으면 담을 방법이 없다")
                    .isTrue();
            assertThat(response.body()).contains("\"items\"");
        }

        @Test
        @DisplayName("로그인 없이 상품 목록을 본다")
        void readsPublicProductsWithoutLogin() {
            Session session = newSession();

            Response response = session.get("/api/products");

            assertThat(response.is(200))
                    .as("사는 사람은 로그인 전에 물건을 고른다. 막으면 아무도 안 산다")
                    .isTrue();
            assertThat(response.body()).contains("\"items\"").contains("\"total\"");
        }

        @Test
        @DisplayName("셀러 목록은 로그인이 필요하다")
        void sellerListNeedsLogin() {
            assertThat(newSession().get("/api/seller/products").is(401))
                    .as("여기는 보는 사람에 따라 답이 달라지는 경로다")
                    .isTrue();
        }

        @Test
        @DisplayName("가입 전에 약관 본문을 읽을 수 있다")
        void readsTermsBeforeSignUp() {
            Session session = newSession();

            Response response = session.get("/api/consent-items/terms_of_service");

            assertThat(response.is(200))
                    .as("동의하려면 먼저 읽어야 하는데 그 시점은 로그인 전이다(약관규제법 제3조)")
                    .isTrue();
            assertThat(response.body()).contains("통신판매중개자");
        }

        @Test
        @DisplayName("프록시를 거쳐도 동의 이력에 진짜 클라이언트 IP 가 남는다")
        void recordsForwardedClientIp() {
            Session session = newSession();
            session.get("/api/health");

            session.postForwardedFrom("/api/auth/signup", """
                    {
                      "email": "%s",
                      "password": "%s",
                      "display_name": "http",
                      "consents": {"terms_of_service": true, "privacy_collect": true}
                    }
                    """.formatted(email("forwarded"), PASSWORD), "203.0.113.9");

            // host() 를 쓴다. cast(inet as text) 는 마스크를 붙여서 203.0.113.9/32 로 나온다.
            String ip = jdbc.sql("""
                            select host(acted_ip) from user_consent
                             where user_id = :id limit 1
                            """)
                    .param("id", userIdOf("forwarded"))
                    .query(String.class)
                    .single();

            assertThat(ip)
                    .as("프록시 IP 를 동의자의 IP 로 적으면 틀린 개인정보고 입증에도 못 쓴다")
                    .isEqualTo("203.0.113.9");
        }

        @Test
        @DisplayName("로그인하면 세션이 생기고 권한 목록이 열린다")
        void loginOpensPermissions() {
            Session session = newSession();
            session.get("/api/health");
            signUp(session, "login");

            assertThat(logIn(session, "login").is(200)).isTrue();
            assertThat(session.cookie("SHOPSESSION")).isNotBlank();

            Response permissions = session.get("/api/me/permissions");

            assertThat(permissions.is(200)).isTrue();
            assertThat(permissions.body())
                    .as("응답은 snake_case 다(D5)")
                    .contains("\"user_id\"")
                    .contains("\"visible_field_groups\"");
        }

        @Test
        @DisplayName("권한 없는 조회가 거부되고 그 거부가 기록된다")
        void deniedAccessIsRecorded() {
            Session session = newSession();
            session.get("/api/health");
            signUp(session, "denied");
            logIn(session, "denied");

            Response denied = session.get("/api/audit-logs");

            assertThat(denied.is(403)).isTrue();
            assertThat(denied.body())
                    .as("오류 본문은 RFC 9457 형식이다(D5)")
                    .contains("\"status\":403")
                    .contains("\"title\"");

            long userId = userIdOf("denied");
            assertThat(count("""
                    select count(*) from audit_log
                     where actor_user_id = %d and event_type = 'permission.denied'
                    """.formatted(userId)))
                    .as("거부가 안 남으면 그 경로가 감사에서 통째로 사라진다")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("감사자는 쌓인 기록을 꺼내 본다")
        void auditorReadsTheLog() {
            Session session = newSession();
            session.get("/api/health");
            signUp(session, "auditor");
            grantAuditor("auditor");
            logIn(session, "auditor");

            Response logs = session.get("/api/audit-logs?size=5");

            assertThat(logs.is(200)).isTrue();
            assertThat(logs.body())
                    .contains("\"items\"")
                    .contains("\"total\"")
                    .as("자기 가입 기록이 보여야 한다")
                    .contains("user.signed_up");
        }

        @Test
        @DisplayName("로그아웃하면 다시 막힌다")
        void logoutCloses() {
            Session session = newSession();
            session.get("/api/health");
            signUp(session, "logout");
            logIn(session, "logout");

            assertThat(session.get("/api/me/permissions").is(200)).isTrue();
            assertThat(session.post("/api/auth/logout", null).is(204)).isTrue();
            assertThat(session.get("/api/me/permissions").is(401))
                    .as("로그아웃 뒤에도 열려 있으면 세션이 안 끊긴 것이다")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("계정 관리")
    class AccountManagement {

        @Test
        @DisplayName("자기 계정을 보면 볼 수 있는 그룹이 같이 온다")
        void readsOwnAccount() {
            Session session = loggedIn("account");

            Response response = session.get("/api/me");

            assertThat(response.is(200)).isTrue();
            assertThat(response.body())
                    .contains("\"email\"")
                    .contains("\"_visible_field_groups\"");
        }

        @Test
        @DisplayName("채널을 거두면 야간 수신도 같이 거둬진다")
        void revokingChannelCascades() {
            Session session = loggedIn("consent");
            assertThat(session.post("/api/me/consents/marketing_email/grant", null).is(204)).isTrue();
            assertThat(session.post("/api/me/consents/marketing_night/grant", null).is(204)).isTrue();

            assertThat(session.post("/api/me/consents/marketing_email/revoke", null).is(204))
                    .isTrue();

            assertThat(session.get("/api/me/consents").body())
                    .as("채널 없는 야간 동의가 남으면 나중에 채널만 켜질 때 야간까지 열린다")
                    .contains("\"code\":\"marketing_night\",\"title\":\"야간 광고성 정보 수신 (21시~08시)\","
                            + "\"is_required\":false,\"granted\":false");
        }

        @Test
        @DisplayName("필수 동의는 거둘 수 없다")
        void requiredConsentCannotBeRevoked() {
            Session session = loggedIn("required");

            Response response = session.post("/api/me/consents/terms_of_service/revoke", null);

            assertThat(response.is(422)).isTrue();
            assertThat(response.body()).contains("\"status\":422");
        }

        /**
         * `ADR 0010` 의 핵심이다. 로그인 시점 검사만으로는 <b>이미 열린 다른 기기</b>가 안 막힌다.
         *
         * <p>이 시나리오는 손 {@code curl} 로만 확인돼 있었다. 2차 점검이 그걸 부채로 잡았다.
         */
        @Test
        @DisplayName("한 기기에서 탈퇴하면 다른 기기 세션도 끊긴다")
        void withdrawalKillsOtherDevices() {
            Session deviceA = loggedIn("twodev");

            Session deviceB = newSession();
            deviceB.get("/api/health");
            assertThat(logIn(deviceB, "twodev").is(200)).isTrue();

            assertThat(deviceA.get("/api/me").is(200)).isTrue();
            assertThat(deviceB.get("/api/me").is(200)).isTrue();

            assertThat(deviceA.post("/api/me/withdraw",
                    "{\"password\": \"%s\"}".formatted(PASSWORD)).is(204)).isTrue();

            assertThat(deviceA.get("/api/me").is(401)).isTrue();
            assertThat(deviceB.get("/api/me").is(401))
                    .as("다른 기기가 살아 있으면 탈퇴가 반쪽이다")
                    .isTrue();
        }

        @Test
        @DisplayName("탈퇴하면 수명만 끊기고 동의는 거둬진다")
        void withdrawalEndsLifetimeAndConsents() {
            Session session = loggedIn("bye");

            assertThat(session.post("/api/me/withdraw",
                    "{\"password\": \"%s\"}".formatted(PASSWORD)).is(204)).isTrue();

            long userId = userIdOf("bye");
            assertThat(count("select count(*) from app_user where user_id = " + userId))
                    .as("주문 기록이 5년 남아야 해서 행은 지우지 않는다(D13)")
                    .isEqualTo(1);
            assertThat(count("""
                    select count(*) from current_consent where user_id = %d and granted
                    """.formatted(userId)))
                    .as("계약이 끝났는데 동의가 유효한 채로 남으면 안 된다")
                    .isZero();
        }

        @Test
        @DisplayName("비밀번호가 틀리면 탈퇴가 안 된다")
        void withdrawalNeedsThePassword() {
            Session session = loggedIn("guard");

            assertThat(session.post("/api/me/withdraw",
                    "{\"password\": \"wrong-but-long-enough\"}").is(422)).isTrue();
            assertThat(session.get("/api/me").is(200))
                    .as("되돌릴 수 없는 조작이라 세션만으로는 부족하다")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("인증 실패")
    class Failures {

        @Test
        @DisplayName("로그인 없이 잠긴 경로는 401 이고 리다이렉트가 없다")
        void unauthenticatedIsUnauthorized() {
            Response response = newSession().get("/api/me/permissions");

            assertThat(response.is(401)).isTrue();
            assertThat(response.headers().getFirst("Location"))
                    .as("폼 로그인이 켜져 있으면 여기에 /login 이 들어온다")
                    .isNull();
        }

        @Test
        @DisplayName("탈퇴한 계정은 로그인되지 않는다")
        void deletedAccountCannotLogIn() {
            Session session = newSession();
            session.get("/api/health");
            signUp(session, "gone");

            jdbc.sql("update app_user set deleted_at = now() where email = :email")
                    .param("email", email("gone"))
                    .update();

            assertThat(logIn(session, "gone").is(401)).isTrue();
        }
    }

    /**
     * 사는 사람이 담고 주문하고, 파는 사람이 그것을 처리한다.
     *
     * <p><b>여기가 상품 픽스처의 첫 호출자다</b>(`35c`). 그 전까지 이 층은 상품이 필요한 흐름을
     * 하나도 못 덮었다 — 셀러·신원확인·검수까지 세워야 해서 청크 9 가 장바구니 병합을
     * 서비스 층에만 남겼다.
     *
     * <p>규칙 하나하나는 통합 층이 본다. 여기서 보는 것은 <b>여섯 경로가 실제 HTTP 로 이어지느냐</b>다 —
     * 담기·주문·구매자 조회·셀러 조회·발송·확정. 하나라도 끊기면 화면이 못 만든다.
     */
    @Nested
    @DisplayName("사고 파는 관통")
    class Commerce {

        @Test
        @DisplayName("담고 주문하면 주문번호가 나오고 내 주문에서 보인다")
        void buysAndSeesOwnOrder() {
            SellableProduct product = givenSellableProduct(12000, 5);
            Session buyer = loggedIn("buyer");

            Response added = addToCart(buyer, product.skuId(), 2);
            assertThat(added.status().value())
                    .as("담기는 돌려줄 본문이 없다(`D5`). 본문: %s", added.body())
                    .isEqualTo(204);


            // 커밋까지 가는 유일한 층이다. 지연 트리거가 실제로 도는 자리라 상태 코드만 보지 않고
            // 본문을 같이 남긴다 — 500 이 나면 그 본문의 trace_id 로 로그를 찾는다.
            Response created = placeOrder(buyer, cartItemIdOf("buyer"));
            assertThat(created.status().value())
                    .as("주문 생성이 커밋까지 가야 한다. 본문: %s", created.body())
                    .isEqualTo(201);
            assertThat(created.body()).contains("\"order_number\"");

            Response mine = buyer.get("/api/orders");
            assertThat(mine.is(200)).isTrue();
            assertThat(mine.body())
                    .as("배송비까지 더한 값이 결제할 금액이다")
                    .contains("\"payable_amount\"");
        }

        @Test
        @DisplayName("셀러가 자기 묶음을 보고 발송한다")
        void sellerShipsOwnBundle() {
            SellableProduct product = givenSellableProduct(9000, 3);
            Session buyer = loggedIn("shipbuyer");
            addToCart(buyer, product.skuId(), 1);
            placeOrder(buyer, cartItemIdOf("shipbuyer"));
            pay(buyer, orderNumberOf("shipbuyer"), GOOD_CARD);

            Session seller = loggedIn("shipseller");
            givenSellerOwner(userIdOf("shipseller"), product.sellerId());

            Response list = seller.get("/api/seller/orders");
            assertThat(list.is(200)).isTrue();
            assertThat(list.body()).contains("\"seller_order_number\"");

            String number = sellerOrderNumberOf(product.sellerId());
            assertThat(seller.post("/api/shipments/" + number + "/ship", null).is(204))
                    .as("셀러 처리 경로가 안 열리면 주문이 영원히 준비중이다")
                    .isTrue();
        }

        /**
         * `11c-3b` 가 내리기 시작한 값이다. <b>화면이 이걸로 버튼을 그린다</b> —
         * 안 나오면 화면이 역할 이름으로 판단하게 된다(`D20`).
         */
        @Test
        @DisplayName("상세가 지금 할 수 있는 것을 같이 내린다")
        void detailCarriesAllowedActions() {
            SellableProduct product = givenSellableProduct(15000, 2);
            Session buyer = loggedIn("actions");
            addToCart(buyer, product.skuId(), 1);
            placeOrder(buyer, cartItemIdOf("actions"));
            pay(buyer, orderNumberOf("actions"), GOOD_CARD);

            Response detail = buyer.get("/api/orders/" + orderNumberOf("actions"));

            assertThat(detail.is(200)).isTrue();
            assertThat(detail.body())
                    .as("묶음을 가리킬 번호가 없으면 화면이 동작을 못 부른다")
                    .contains("\"seller_order_number\"")
                    .contains("\"allowed_actions\":[\"CANCEL\"]");
        }

        /**
         * <b>결제가 실제 HTTP 로 도는 유일한 자리다</b>(`12-2`).
         *
         * <p>여기서만 보이는 것이 둘이다. 하나는 <b>지연 트리거가 실제로 도는 것</b> —
         * 통합 층은 전부 롤백해서 멱등키의 응답 검사 트리거가 한 번도 안 돈다(`V17` 이 겪었다).
         * 다른 하나는 <b>카드번호가 응답에 안 실리는 것</b>이다. 표에 없다는 것과
         * 응답에 없다는 것은 다른 자리라 따로 봐야 한다(`D2` R18).
         */
        @Test
        @DisplayName("결제하면 승인이 나고 주문 상세에 붙는다")
        void paysAndSeesApproval() {
            SellableProduct product = givenSellableProduct(11000, 4);
            Session buyer = loggedIn("payer");
            addToCart(buyer, product.skuId(), 1);
            placeOrder(buyer, cartItemIdOf("payer"));
            String orderNumber = orderNumberOf("payer");

            Response paid = pay(buyer, orderNumber, GOOD_CARD);

            assertThat(paid.status().value())
                    .as("결제가 커밋까지 가야 한다. 본문: %s", paid.body())
                    .isEqualTo(201);
            assertThat(paid.body())
                    .as("열거값은 대문자 스네이크다(`D5`)")
                    .contains("\"status\":\"APPROVED\"")
                    .contains("\"approval_number\"");
            assertThat(paid.headers().getFirst("Location"))
                    .as("결제 결과를 다시 보는 경로가 주문 상세다(`D5`)")
                    .isEqualTo("/api/orders/" + orderNumber);

            Response detail = buyer.get("/api/orders/" + orderNumber);

            assertThat(detail.body())
                    .as("가맹점은 카드번호를 보관도 전달도 못 한다(여신전문금융업법 제19조)")
                    .contains("\"card_last4\":\"4242\"")
                    .doesNotContain("4242424242424242")
                    .doesNotContain(GOOD_CARD);
        }

        @Test
        @DisplayName("거절도 201 로 내려오고 주문이 닫힌다")
        void declinedPaymentComesBackAsResult() {
            SellableProduct product = givenSellableProduct(8000, 2);
            Session buyer = loggedIn("declined");
            addToCart(buyer, product.skuId(), 1);
            placeOrder(buyer, cartItemIdOf("declined"));

            Response paid = pay(buyer, orderNumberOf("declined"), DECLINED_CARD);

            assertThat(paid.status().value())
                    .as("4xx 로 던지면 주문 상태와 재고 복구가 같이 롤백된다(`D11`). 본문: %s", paid.body())
                    .isEqualTo(201);
            assertThat(paid.body()).contains("\"status\":\"FAILED\"")
                    .contains("\"decline_reason\":\"limit_exceeded\"");

            assertThat(orderStatusOf("declined"))
                    .as("거절된 주문이 결제 대기로 남으면 재고를 물고 있다")
                    .isEqualTo("payment_failed");
        }

        @Test
        @DisplayName("모르는 묶음 번호는 없는 것과 같다")
        void unknownShipmentLooksMissing() {
            Session buyer = loggedIn("missing");

            assertThat(buyer.post("/api/shipments/S-20260101-ZZZZZZ/cancel", null).is(404))
                    .as("403 이면 번호를 훑어 실재하는 묶음의 지도가 그려진다(`D5`)")
                    .isTrue();
        }
    }

    private Response addToCart(Session session, long skuId, int quantity) {
        return session.post("/api/cart/items", """
                {"sku_id": %d, "quantity": %d}
                """.formatted(skuId, quantity));
    }

    private Response placeOrder(Session session, long cartItemId) {
        return session.postWithIdempotencyKey("/api/orders", """
                {
                  "cart_item_ids": [%d],
                  "shipping": {
                    "receiver_name": "홍길동", "receiver_phone": "010-0000-0000",
                    "postal_code": "06134", "address1": "서울시 강남구", "address2": "101호"
                  }
                }
                """.formatted(cartItemId), "http-test-" + cartItemId);
    }

    /**
     * 실제 결제 경로로 낸다. <b>청크 12-2 가 SQL 로 상태를 밀던 것을 걷어냈다</b> —
     * 그 방식은 전이표도 결제 기록도 안 거쳐서, 셀러 쪽 뷰가 열리는 것 말고는 아무것도 안 봤다.
     */
    private Response pay(Session session, String orderNumber, String cardNumber) {
        return session.postWithIdempotencyKey("/api/payments", """
                {"order_number": "%s", "method": "card", "card_number": "%s"}
                """.formatted(orderNumber, cardNumber), "http-test-pay-" + orderNumber);
    }

    private long cartItemIdOf(String name) {
        return jdbc.sql("""
                        select ci.cart_item_id from cart_item ci
                          join cart c on c.cart_id = ci.cart_id
                         where c.user_id = :userId
                         order by ci.cart_item_id desc limit 1
                        """)
                .param("userId", userIdOf(name))
                .query(Long.class)
                .single();
    }

    private String orderStatusOf(String name) {
        return jdbc.sql("""
                        select status from shop_order where user_id = :userId
                         order by order_id desc limit 1
                        """)
                .param("userId", userIdOf(name))
                .query(String.class)
                .single();
    }

    private String orderNumberOf(String name) {
        return jdbc.sql("""
                        select order_number from shop_order where user_id = :userId
                         order by order_id desc limit 1
                        """)
                .param("userId", userIdOf(name))
                .query(String.class)
                .single();
    }

    private String sellerOrderNumberOf(long sellerId) {
        return jdbc.sql("""
                        select seller_order_number from seller_order where seller_id = :sellerId
                         order by seller_order_id desc limit 1
                        """)
                .param("sellerId", sellerId)
                .query(String.class)
                .single();
    }

    /** 토큰을 받고 가입해서 로그인까지 마친 세션. 계정 관리 테스트가 매번 밟는 준비 단계다. */
    private Session loggedIn(String name) {
        Session session = newSession();
        session.get("/api/health");
        signUp(session, name);
        logIn(session, name);
        return session;
    }

    private Response signUp(Session session, String name) {
        return session.post("/api/auth/signup", """
                {
                  "email": "%s",
                  "password": "%s",
                  "display_name": "http",
                  "consents": {"terms_of_service": true, "privacy_collect": true}
                }
                """.formatted(email(name), PASSWORD));
    }

    private Response logIn(Session session, String name) {
        return session.post("/api/auth/login", """
                {"email": "%s", "password": "%s"}
                """.formatted(email(name), PASSWORD));
    }

    private static String email(String name) {
        return EMAIL_PREFIX + name + "@test.local";
    }

    private void grantAuditor(String name) {
        jdbc.sql("""
                insert into user_role (user_id, role_id)
                select u.user_id, r.role_id from app_user u, role r
                 where u.email = :email and r.code = 'auditor'
                """).param("email", email(name)).update();
    }

    private long userIdOf(String name) {
        return jdbc.sql("select user_id from app_user where email = :email")
                .param("email", email(name))
                .query(Long.class)
                .single();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
