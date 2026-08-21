package com.projectshop.shop.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;

/**
 * 계약 시점의 문서 판이 남나(`D2` R22, 전자상거래법 제13조제2항 후단).
 *
 * <p>법이 <b>「계약이 체결되면 계약내용에 관한 서면을 재화등을 공급할 때까지 교부」</b>하라고 한다.
 * 화면 바닥 링크로는 안 된다 — 링크는 「지금의 문안」을 가리켜서, 안내를 개정하면
 * <b>지나간 주문의 계약 조건까지 바뀐 것처럼 보인다.</b>
 *
 * <p><b>불변 제약이 이 설계의 전제다.</b> 판을 가리키는 방식은 그 판이 안 바뀔 때만 성립한다 —
 * 전자문서법 제4조의2 2호가 「저장된 때의 형태로 보존」을 서면 요건으로 정해서,
 * 본문을 고칠 수 있으면 우리가 교부했다는 서면이 서면이 아니다.
 */
@DisplayName("계약 문서")
class OrderContractTest extends PostgresTestBase {

    private static final long PRICE = 10_000;

    /**
     * 테스트가 넣는 개정판의 번호.
     *
     * <p><b>시드 판 번호를 안 쓴다.</b> `V28` 이 제2판을 쌓으면서 2 를 박아 둔 테스트가 전부
     * 유니크 제약에 걸렸다 — 문안을 개정하는 청크마다 이 파일이 깨지는 자리였다.
     */
    private static final int DRAFT_VERSION = 99;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderQuery orders;

    @Autowired
    private JdbcClient jdbc;

    private AuthFixture fixture;
    private long buyerId;
    private long skuId;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);

        buyerId = fixture.insertUser("contract-buyer@test.local", "산사람");
        fixture.grantGlobal(buyerId, "customer");

        long sellerId = fixture.insertSeller("s-contract", "계약셀러");
        fixture.verifySeller(sellerId);

        skuId = insertSku(sellerId);
    }

    @Nested
    @DisplayName("주문을 만들면")
    class OnOrder {

        /**
         * 제13조제2항이 요구하는 호 넷이 붙는다.
         *
         * <p><b>호로 센다.</b> 문서 코드로 세면 문서를 쪼개거나 합칠 때 이 테스트가 같이 흔들리는데,
         * 법이 정한 것은 호지 우리 문서 구성이 아니다.
         */
        @Test
        @DisplayName("계약 문서 넷이 붙는다")
        void attachesEveryRequiredClause() {
            OrderQuery.Detail detail = orders.findByNumber(buyerId, placeOrder());

            assertThat(detail.contractDocuments())
                    .extracting(OrderQuery.ContractDocument::clause)
                    .as("5호 청약철회 · 6호 교환·반품·환불 · 8호 분쟁 처리 · 9호 약관")
                    .containsExactly("WITHDRAWAL", "EXCHANGE", "DISPUTE", "TERMS");
        }

        /**
         * 약관은 {@code user_consent} 가 이미 판을 가리킨다. <b>그건 가입 시점이다.</b>
         *
         * <p>가입 후 약관이 개정되면 주문 시점 약관이 무엇이었는지 아무 데도 없다 —
         * 그래서 주문에도 다시 박제한다.
         */
        @Test
        @DisplayName("약관은 consent_item 에서 온다")
        void takesTermsFromConsentItem() {
            OrderQuery.Detail detail = orders.findByNumber(buyerId, placeOrder());

            assertThat(clause(detail, "TERMS").code()).isEqualTo("terms_of_service");
        }

        /** 시행 전인 판은 아직 아무에게도 고지되지 않았다 */
        @Test
        @DisplayName("시행 전인 판은 안 잡는다")
        void ignoresDocumentsNotYetEffective() {
            insertFuturePolicy("withdrawal_guide", DRAFT_VERSION);

            OrderQuery.Detail detail = orders.findByNumber(buyerId, placeOrder());

            assertThat(clause(detail, "WITHDRAWAL").version())
                    .as("미래 판을 미리 넣어 두는 것이 이 표의 설계다(`V21`)")
                    .isEqualTo(effectiveVersionOf("withdrawal_guide"));
        }

        /**
         * <b>이 줄이 이 청크의 이유다.</b>
         *
         * <p>주문 뒤에 안내를 개정해도 그 주문이 가리키는 판은 안 바뀐다.
         * 링크만 있으면 개정하는 순간 지나간 계약의 조건이 통째로 바뀐 것처럼 보인다.
         */
        @Test
        @DisplayName("나중에 개정해도 지나간 주문은 옛 판을 가리킨다")
        void keepsPointingAtTheOldVersion() {
            String orderNumber = placeOrder();
            int before = clause(orders.findByNumber(buyerId, orderNumber), "WITHDRAWAL").version();
            insertEffectivePolicy("withdrawal_guide", DRAFT_VERSION);

            assertThat(clause(orders.findByNumber(buyerId, orderNumber), "WITHDRAWAL").version())
                    .as("계약 조건은 그 계약 시점의 것이다")
                    .isEqualTo(before);
        }

        @Test
        @DisplayName("새 주문은 개정판을 가리킨다")
        void newOrdersTakeTheNewVersion() {
            insertEffectivePolicy("withdrawal_guide", DRAFT_VERSION);

            assertThat(clause(orders.findByNumber(buyerId, placeOrder()), "WITHDRAWAL").version())
                    .isEqualTo(DRAFT_VERSION);
        }
    }

    /**
     * <b>서면 요건이 이 제약에 걸려 있다</b>(전자문서법 제4조의2 2호).
     *
     * <p>「저장된 때의 형태로 보존」이 안 되면 그 전자문서는 서면이 아니다.
     * `V21`·`V11` 이 「옛 판은 안 지운다」고 <b>주석으로</b> 적어 뒀는데 그건 아무것도 안 막았다
     * (`D23` 축 2 의 5위). `V27` 이 2위로 내렸다.
     */
    @Nested
    @DisplayName("시행된 문서는")
    class Immutability {

        @Test
        @DisplayName("본문을 못 고친다")
        void cannotBeEdited() {
            assertThatThrownBy(() -> jdbc.sql("""
                            update policy_document set body = '슬쩍 바꾼 문안'
                             where code = 'withdrawal_guide'
                            """)
                    .update())
                    .as("고칠 수 있으면 우리가 교부했다는 서면이 서면이 아니다")
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("지울 수 없다")
        void cannotBeDeleted() {
            assertThatThrownBy(() -> jdbc.sql(
                            "delete from policy_document where code = 'withdrawal_guide'")
                    .update())
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("동의 항목도 같이 막힌다")
        void appliesToConsentItemsToo() {
            assertThatThrownBy(() -> jdbc.sql("""
                            update consent_item set title = '슬쩍 바꾼 제목'
                             where code = 'terms_of_service'
                            """)
                    .update())
                    .as("약관은 consent_item 에 있어서 그 표에도 같은 제약이 필요하다")
                    .isInstanceOf(DataAccessException.class);
        }

        /**
         * 시행일을 미래로 밀면서 본문을 같이 고치는 것을 막는다.
         *
         * <p>트리거가 {@code NEW} 를 보면 통과한다 — 판단은 <b>고치기 전 값</b>으로 해야 한다.
         */
        @Test
        @DisplayName("시행일을 미래로 밀어도 못 고친다")
        void cannotBeUnshippedByMovingTheDate() {
            assertThatThrownBy(() -> jdbc.sql("""
                            update policy_document
                               set effective_at = now() + interval '30 days', body = '바뀐 문안'
                             where code = 'withdrawal_guide'
                            """)
                    .update())
                    .isInstanceOf(DataAccessException.class);
        }

        /** 시행 전인 판은 아직 아무도 그것에 계약하지 않았고 아무도 고지받지 않았다 */
        @Test
        @DisplayName("시행 전인 판은 고칠 수 있다")
        void allowsEditingDraftVersions() {
            insertFuturePolicy("withdrawal_guide", DRAFT_VERSION);

            int updated = jdbc.sql("""
                            update policy_document set body = '오타를 고친 문안'
                             where code = 'withdrawal_guide' and version = %d
                            """.formatted(DRAFT_VERSION))
                    .update();

            assertThat(updated)
                    .as("안 열면 오타 하나에 판이 하나씩 쌓인다")
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("제약이")
    class Constraints {

        @Test
        @DisplayName("두 표를 동시에 가리키는 행을 막는다")
        void rejectsRowsPointingAtBothTables() {
            String orderNumber = placeOrder();

            assertThatThrownBy(() -> jdbc.sql("""
                            insert into order_contract_document
                                   (order_id, policy_document_id, consent_item_id, clause)
                            select o.order_id,
                                   (select min(policy_document_id) from policy_document),
                                   (select min(consent_item_id) from consent_item),
                                   'dispute'
                              from shop_order o where o.order_number = :number
                            """)
                    .param("number", orderNumber)
                    .update())
                    .as("정확히 하나를 가리켜야 어느 문서인지가 갈린다")
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("법이 정한 호 밖의 값을 막는다")
        void rejectsUnknownClauses() {
            String orderNumber = placeOrder();

            assertThatThrownBy(() -> jdbc.sql("""
                            insert into order_contract_document (order_id, policy_document_id, clause)
                            select o.order_id,
                                   (select min(policy_document_id) from policy_document),
                                   '아무거나'
                              from shop_order o where o.order_number = :number
                            """)
                    .param("number", orderNumber)
                    .update())
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("가리켜진 문서는 지워지지 않는다")
        void keepsReferencedDocumentsAlive() {
            placeOrder();

            assertThatThrownBy(() -> jdbc.sql("""
                            delete from policy_document
                             where code = 'dispute_resolution' and version = 1
                            """)
                    .update())
                    .as("계약 조건을 잃은 주문이 남지 않는다")
                    .isInstanceOf(DataAccessException.class);
        }
    }

    private static OrderQuery.ContractDocument clause(OrderQuery.Detail detail, String clause) {
        return detail.contractDocuments().stream()
                .filter(document -> document.clause().equals(clause))
                .findFirst()
                .orElseThrow(() -> new AssertionError("그 호의 계약 문서가 없다: " + clause));
    }

    /** 지금 효력 있는 판. <b>번호를 안 박는다</b> — 개정하면 늘어나는 값이다 */
    private int effectiveVersionOf(String code) {
        return jdbc.sql("""
                        select version from policy_document
                         where code = :code and effective_at <= now()
                         order by effective_at desc, version desc limit 1
                        """)
                .param("code", code)
                .query(Integer.class)
                .single();
    }

    /** 아직 시행 안 된 개정판. 미리 넣어 두는 것이 `V21` 의 설계다 */
    private void insertFuturePolicy(String code, int version) {
        insertPolicy(code, version, "now() + interval '30 days'");
    }

    /**
     * 서면이 빠진 주문이 서지 않나(`Q3`, `V31`).
     *
     * <p><b>넣는 코드가 조용히 0행을 낼 수 있다.</b> {@code insert ... select ... join lateral} 이라
     * 정책 문서가 없거나 아직 시행 전이면 아무것도 안 물고, 반환값도 안 본다.
     * 유니크 제약은 같은 조항이 두 번 들어가는 것만 막는다 —
     * <b>넷이 다 있는지는 아무도 안 봤다.</b>
     */
    @Nested
    @DisplayName("서면이 빠지면")
    class Completeness {

        @Test
        @DisplayName("조항 하나만 없어도 주문이 안 선다")
        void rejectsOrderMissingAnyClause() {
            for (String clause : allowedClauses()) {
                String orderNumber = placeOrder();
                jdbc.sql("savepoint before_delete").update();

                jdbc.sql("""
                                delete from order_contract_document d
                                 using shop_order o
                                 where d.order_id = o.order_id
                                   and o.order_number = :number and d.clause = :clause
                                """)
                        .param("number", orderNumber)
                        .param("clause", clause)
                        .update();

                // 지연 제약이라 지우는 순간이 아니라 커밋할 때 걸린다. 테스트는 커밋을 안 하므로
                // 밀린 검사를 당겨 돌린다(`OrderSchemaTest` 와 같은 수법).
                assertThatThrownBy(() -> jdbc.sql("set constraints all immediate").update())
                        .as("%s 가 빠진 주문이 서면 그 조항은 교부된 적이 없다", clause)
                        .hasMessageContaining(clause);

                // 걸린 트랜잭션을 세이브포인트까지만 되감는다. 통째로 rollback 하면
                // `` 이 깔아 둔 픽스처까지 사라져서 다음 회차가 엉뚱한 데서 깨진다.
                jdbc.sql("rollback to savepoint before_delete").update();
            }
        }

        /**
         * 조항 이름이 두 곳에 있다 — {@code order_contract_document_clause_check} 와
         * {@code assert_contract_documents_complete()} 다.
         *
         * <p>이 테스트가 <b>제약 쪽 목록에서 이름을 읽어</b> 위 검사를 돌리므로,
         * 제약에 조항을 하나 늘리고 트리거를 안 늘리면 그 회차에서 깨진다.
         */
        private List<String> allowedClauses() {
            String definition = jdbc.sql("""
                            select pg_get_constraintdef(oid) from pg_constraint
                             where conname = 'order_contract_document_clause_check'
                            """)
                    .query(String.class)
                    .single();

            return Pattern.compile("'([a-z_]+)'::text").matcher(definition).results()
                    .map(match -> match.group(1))
                    .toList();
        }
    }

    private void insertEffectivePolicy(String code, int version) {
        insertPolicy(code, version, "now() - interval '1 second'");
    }

    private void insertPolicy(String code, int version, String effectiveAt) {
        jdbc.sql("""
                        insert into policy_document (code, version, title, body, effective_at)
                        values (:code, :version, '개정판', '개정된 문안', %s)
                        """.formatted(effectiveAt))
                .param("code", code)
                .param("version", version)
                .update();
    }

    private String placeOrder() {
        long cartId = jdbc.sql("select cart_id from cart where user_id = :userId")
                .param("userId", buyerId)
                .query(Long.class)
                .optional()
                .orElseGet(() -> jdbc.sql(
                                "insert into cart (user_id) values (:userId) returning cart_id")
                        .param("userId", buyerId)
                        .query(Long.class)
                        .single());

        long cartItemId = jdbc.sql("""
                        insert into cart_item (cart_id, sku_id, quantity)
                        values (:cartId, :skuId, 1)
                        returning cart_item_id
                        """)
                .param("cartId", cartId)
                .param("skuId", skuId)
                .query(Long.class)
                .single();

        return orderService.create(buyerId, new OrderService.Command(List.of(cartItemId),
                        new OrderService.Shipping("홍길동", "010-0000-0000", "06134",
                                "서울시 강남구", "101호", null)))
                .orderNumber();
    }

    private long insertSku(long sellerId) {
        long ownerId = fixture.insertUser(
                "contract-owner-" + UUID.randomUUID() + "@test.local", "대표");
        fixture.joinSeller(sellerId, ownerId);

        long productId = jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name, status)
                        values (:sellerId, :userId, '계약 문서 상품', 'on_sale')
                        returning product_id
                        """)
                .param("sellerId", sellerId)
                .param("userId", ownerId)
                .query(Long.class)
                .single();

        return jdbc.sql("""
                        with new_sku as (
                            insert into sku (product_id, price_incl_vat)
                            values (:productId, :price)
                            returning sku_id
                        )
                        insert into sku_stock (sku_id, on_hand)
                        select sku_id, 10 from new_sku
                        returning sku_id
                        """)
                .param("productId", productId)
                .param("price", PRICE)
                .query(Long.class)
                .single();
    }
}
