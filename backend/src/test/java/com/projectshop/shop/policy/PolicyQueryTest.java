package com.projectshop.shop.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

/**
 * 정책 문서 조회(`13a-1`).
 *
 * <p>다른 조회와 실패 방향이 반대다. 대개는 <b>너무 많이 나가는 것</b>이 사고인데
 * 여기서는 <b>안 나가는 것</b>이 사고다 — 개인정보법 제30조제2항이 공개를 요구하고
 * 전자상거래법 제13조제2항이 청약 이전 고지를 요구한다.
 *
 * <p>그래서 <b>본문에 무엇이 들어 있는지까지 고정한다.</b> 조회가 도는 것만 보면
 * 문안에서 법이 요구한 절이 빠져도 초록이다.
 */
@AutoConfigureMockMvc
@DisplayName("정책 문서 조회")
class PolicyQueryTest extends PostgresTestBase {

    @Autowired
    private PolicyQuery policyQuery;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Nested
    @DisplayName("누가 볼 수 있나")
    class Access {

        @Test
        @DisplayName("비로그인이 받는다 — 동의하기 전에 읽는 것이다")
        void opensToAnonymous() throws Exception {
            mvc.perform(get("/api/policies/{code}", "privacy_policy"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.title").value("개인정보처리방침"))
                    .andExpect(jsonPath("$.version").value(1));
        }

        @Test
        @DisplayName("없는 코드는 404 다")
        void unknownCodeIsNotFound() {
            assertThatThrownBy(() -> policyQuery.readCurrent("refund_rules"))
                    .isInstanceOfSatisfying(ShopException.class, error ->
                            assertThat(error.code()).isEqualTo(ErrorCode.POLICY_NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("개정판")
    class Revision {

        @Test
        @DisplayName("시행 전인 판은 안 나간다 — 사전 고지 기간이 사라진다")
        void hidesFuturePolicy() {
            jdbc.sql("""
                            insert into policy_document (code, title, version, body, effective_at)
                            values ('privacy_policy', '개인정보처리방침', 2,
                                    '## 아직 시행 전인 판', now() + interval '7 days')
                            """)
                    .update();

            assertThat(policyQuery.readCurrent("privacy_policy").version())
                    .as("""
                            개정판을 미리 넣어 두고 시점에 갈아 끼우는 설계다.
                            시행 시각을 안 보면 넣는 순간 바뀌어서 7일 사전 고지가 성립하지 않는다.
                            """)
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("시행이 지나면 새 판이 나간다")
        void servesEffectiveRevision() {
            jdbc.sql("""
                            insert into policy_document (code, title, version, body, effective_at)
                            values ('privacy_policy', '개인정보처리방침', 2, '## 시행된 새 판', now())
                            """)
                    .update();

            assertThat(policyQuery.readCurrent("privacy_policy").body()).contains("시행된 새 판");
        }

        /**
         * 시행 시각이 지금 판보다 <b>앞선</b> 판을 넣어도 안 걸린다.
         *
         * <p>{@code effective_at <= now()} 만 보면 새로 넣은 것이 이기리라 착각하기 쉽다.
         * 정렬이 <b>버전이 아니라 시행 시각</b>이라 그렇다 — 그래야 옛 판을 다시 넣는 실수가
         * 지금 걸린 방침을 갈아 끼우지 않는다.
         */
        @Test
        @DisplayName("시행 시각이 더 이른 판은 안 걸린다 — 번호가 커도 그렇다")
        void ignoresBackdatedRevision() {
            jdbc.sql("""
                            insert into policy_document (code, title, version, body, effective_at)
                            values ('privacy_policy', '개인정보처리방침', 2, '## 뒤늦게 넣은 옛 판',
                                    now() - interval '1 day')
                            """)
                    .update();

            assertThat(policyQuery.readCurrent("privacy_policy").version()).isEqualTo(1);
        }

        @Test
        @DisplayName("옛 판을 안 지운다 — 어느 판이 적용됐는지가 남아야 한다")
        void keepsOldRevisions() {
            jdbc.sql("""
                            insert into policy_document (code, title, version, body, effective_at)
                            values ('privacy_policy', '개인정보처리방침', 2, '## 새 판',
                                    now() - interval '1 day')
                            """)
                    .update();

            Integer kept = jdbc.sql(
                            "select count(*) from policy_document where code = 'privacy_policy'")
                    .query(Integer.class)
                    .single();

            assertThat(kept).isEqualTo(2);
        }
    }

    /**
     * 법이 요구한 절이 문안에 실제로 있나.
     *
     * <p><b>「없는 것이 위반」이라 코드에서 올라가면 안 보인다</b>(`D23`).
     * 요건에서 문안으로 내려가며 훑는다 — 점검 A 가 쓴 방법과 같다.
     */
    @Nested
    @DisplayName("법이 요구한 절")
    class RequiredSections {

        @Test
        @DisplayName("처리방침이 개인정보법 제30조제1항의 항목을 담는다")
        void privacyPolicyCoversArticle30() {
            String body = policyQuery.readCurrent("privacy_policy").body();

            assertThat(body)
                    .as("하나라도 빠지면 고지 의무를 못 지킨 방침이 공개된 것이 된다")
                    .contains("수집하는 개인정보 항목")
                    .contains("처리 목적")
                    .contains("보유 및 이용 기간")
                    .contains("제3자 제공")
                    .contains("위탁")
                    .contains("파기 절차")
                    .contains("정보주체의 권리")
                    .contains("자동으로 수집되는 정보")
                    .contains("보호책임자");
        }

        @Test
        @DisplayName("처리방침이 쿠키의 거부 방법과 그 결과를 적는다")
        void privacyPolicyTellsHowToRefuseCookies() {
            String body = policyQuery.readCurrent("privacy_policy").body();

            assertThat(body)
                    .as("""
                            비로그인 장바구니가 CART-TOKEN 으로 사람을 구분한다(청크 9).
                            설치·운영 목적만 적고 거부 방법을 빠뜨리면 고지가 반쪽이다.
                            """)
                    .contains("쿠키를 거부")
                    .contains("장바구니 이용이 되지 않습니다");
        }

        @Test
        @DisplayName("처리방침의 수집 항목이 스키마와 같다")
        void privacyPolicyMatchesSchema() {
            String body = policyQuery.readCurrent("privacy_policy").body();

            assertThat(body)
                    .as("""
                            user_consent.acted_ip 가 실제로 받는 값이다.
                            스키마에 있는데 고지에 없으면 근거 없이 받는 것이 된다(`D2` R11).
                            """)
                    .contains("접속 IP");
        }

        @Test
        @DisplayName("청약철회 안내가 기간과 기산점을 적는다")
        void withdrawalGuideTellsPeriod() {
            String body = policyQuery.readCurrent("withdrawal_guide").body();

            assertThat(body)
                    .as("전자상거래법 제17조제1항·제3항")
                    .contains("7일")
                    .contains("3개월")
                    .contains("30일");
        }

        @Test
        @DisplayName("청약철회 안내가 반품 비용 부담을 양쪽 다 적는다")
        void withdrawalGuideSplitsReturnCost() {
            String body = policyQuery.readCurrent("withdrawal_guide").body();

            assertThat(body)
                    .as("""
                            단순 변심은 소비자가(제18조제9항), 표시·광고와 다르면 판매자가 부담한다
                            (같은 조 제10항). 한쪽만 적으면 소비자에게 불리한 쪽만 알리는 것이 된다.
                            """)
                    .contains("이용자 부담")
                    .contains("판매자 부담")
                    .contains("위약금이나 손해배상을 청구할 수 없습니다");
        }

        @Test
        @DisplayName("청약철회 안내가 환급 기한을 적는다")
        void withdrawalGuideTellsRefundDeadline() {
            assertThat(policyQuery.readCurrent("withdrawal_guide").body())
                    .as("전자상거래법 제18조제2항")
                    .contains("3영업일");
        }

        @Test
        @DisplayName("청약철회 안내가 중개자 지위를 다시 밝힌다")
        void withdrawalGuideRepeatsBrokerage() {
            assertThat(policyQuery.readCurrent("withdrawal_guide").body())
                    .as("반품 주소가 판매자마다 다른 이유가 그것이다. 안 적으면 어디로 보낼지 모른다")
                    .contains("통신판매중개자")
                    .contains("반품 주소는 판매자마다 다릅니다");
        }
    }
}
