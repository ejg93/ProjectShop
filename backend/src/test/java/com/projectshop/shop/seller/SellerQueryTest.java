package com.projectshop.shop.seller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

/**
 * 셀러 신원 공개 조회(`14a`). <b>법이 표시를 요구하는 값이라 안 나가는 것이 곧 위반이다</b>(`D2` R1).
 *
 * <p>다른 조회와 실패 방향이 반대다. 대개는 <b>너무 많이 나가는 것</b>이 사고인데
 * 여기서는 <b>안 나가는 것</b>도 사고다 — 전자상거래법 제20조제2항이 청약 이전에 제공하라고 했다.
 * 그래서 양쪽을 다 고정한다: 신원 일곱 칸이 나가는 것과, 우리와 셀러 사이의 조건이 안 나가는 것.
 */
@AutoConfigureMockMvc
@DisplayName("셀러 신원 공개 조회")
class SellerQueryTest extends PostgresTestBase {

    @Autowired
    private SellerQuery sellerQuery;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    private AuthFixture fixture;
    private long selling;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);

        selling = fixture.insertSeller("id-a", "A셀러");
        fixture.verifySeller(selling);
    }

    @Nested
    @DisplayName("내려주는 것")
    class Fields {

        @Test
        @DisplayName("법이 요구하는 일곱 칸이 다 나간다")
        void givesEveryRequiredField() {
            SellerQuery.PublicIdentity identity = sellerQuery.findPublicIdentity(selling);

            assertThat(identity.businessName()).isNotBlank();
            assertThat(identity.representativeName()).isNotBlank();
            assertThat(identity.businessRegNo()).isNotBlank();
            assertThat(identity.address()).isNotBlank();
            assertThat(identity.phone()).isNotBlank();
            assertThat(identity.email())
                    .as("하나라도 비면 상품 상세가 법이 요구하는 표시를 못 그린다(`D2` R1)")
                    .isNotBlank();
        }

        @Test
        @DisplayName("신고번호가 없으면 면제 사유가 대신 나간다")
        void tellsExemptionWhenUnregistered() {
            SellerQuery.PublicIdentity identity = sellerQuery.findPublicIdentity(selling);

            assertThat(identity.mailOrderNo()).isNull();
            assertThat(identity.mailOrderExemptReason())
                    .as("빈 칸으로 두면 화면이 「아직 안 넣은 것」과 「면제라 없는 것」을 못 가른다")
                    .isEqualTo(MailOrderExemption.SIMPLIFIED_TAXPAYER);
        }

        @Test
        @DisplayName("신고번호가 있으면 면제 사유가 없다")
        void dropsExemptionWhenRegistered() {
            jdbc.sql("""
                            update seller
                               set mail_order_exempt_reason = null, mail_order_no = '2026-서울강남-0001'
                             where seller_id = :id
                            """)
                    .param("id", selling)
                    .update();

            SellerQuery.PublicIdentity identity = sellerQuery.findPublicIdentity(selling);

            assertThat(identity.mailOrderNo()).isEqualTo("2026-서울강남-0001");
            assertThat(identity.mailOrderExemptReason()).isNull();
        }

        /**
         * 마스킹을 안 붙인 이유를 고정한다. record 에 애초에 그 칸이 없어서 샐 자리가 없다 —
         * <b>필드 그룹을 걸면 새 컬럼을 더할 때마다 빠뜨릴 수 있다</b>(`4d` 와 반대 방향의 선택).
         */
        @Test
        @DisplayName("수수료율과 배송비는 응답에 없다")
        void hidesOurTerms() throws Exception {
            mvc.perform(get("/api/sellers/{id}", selling))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.commission_bp").doesNotExist())
                    .andExpect(jsonPath("$.default_shipping_fee").doesNotExist());
        }
    }

    @Nested
    @DisplayName("누가 볼 수 있나")
    class Access {

        @Test
        @DisplayName("비로그인이 받는다 — 살 사람은 사기 전에 본다")
        void opensToAnonymous() throws Exception {
            mvc.perform(get("/api/sellers/{id}", selling))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.business_name").value("주식회사 테스트"))
                    .andExpect(jsonPath("$.mail_order_exempt_reason").value("SIMPLIFIED_TAXPAYER"));
        }

        @Test
        @DisplayName("심사 중인 셀러는 404 다")
        void hidesPending() {
            long pending = fixture.insertSeller("id-pending", "심사중");

            assertThatThrownBy(() -> sellerQuery.findPublicIdentity(pending))
                    .as("신원 칸이 빈 채로 나가면 화면이 빈 표를 그린다 — `3c` 의 check 는 active 에만 걸린다")
                    .isInstanceOf(ShopException.class)
                    .extracting(error -> ((ShopException) error).code())
                    .isEqualTo(ErrorCode.SELLER_NOT_FOUND);
        }

        @Test
        @DisplayName("폐업한 셀러는 404 다")
        void hidesClosed() {
            jdbc.sql("update seller set deleted_at = now() where seller_id = :id")
                    .param("id", selling)
                    .update();

            assertThatThrownBy(() -> sellerQuery.findPublicIdentity(selling))
                    .isInstanceOf(ShopException.class);
        }

        @Test
        @DisplayName("없는 셀러도 같은 404 다")
        void hidesMissing() {
            assertThatThrownBy(() -> sellerQuery.findPublicIdentity(-1))
                    .as("가르면 번호를 두드려서 심사 중인 셀러의 존재를 알아낼 수 있다")
                    .isInstanceOf(ShopException.class);
        }
    }

    @Nested
    @DisplayName("면제 사유 목록")
    class Exemption {

        @Test
        @DisplayName("DB 제약과 같다")
        void matchesConstraint() {
            String definition = jdbc.sql("""
                            select pg_get_constraintdef(oid) from pg_constraint
                             where conname = 'seller_exempt_reason_check'
                            """)
                    .query(String.class)
                    .single();

            // 따옴표 안을 뽑는다. `ProductStatusTest` 와 같은 방식이지만 숫자를 같이 받는다 —
            // `under_50_transactions` 에 50 이 들어 있어서 소문자만 훑으면 그 값이 빠진다.
            List<String> inDatabase = Arrays.stream(definition.split("'"))
                    .filter(part -> part.matches("[a-z0-9_]+"))
                    .toList();

            assertThat(inDatabase)
                    .as("한쪽에만 있는 사유가 생기면 조회가 통째로 500 이 되거나 못 쓰는 값이 남는다")
                    .containsExactlyInAnyOrderElementsOf(
                            Arrays.stream(MailOrderExemption.values())
                                    .map(MailOrderExemption::code)
                                    .toList());
        }

        @Test
        @DisplayName("모르는 값은 조용히 통과하지 않는다")
        void unknownCodeThrows() {
            assertThatThrownBy(() -> MailOrderExemption.of("small_business"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
