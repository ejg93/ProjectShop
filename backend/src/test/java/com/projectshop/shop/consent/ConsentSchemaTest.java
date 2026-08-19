package com.projectshop.shop.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;

/**
 * 동의 이력 스키마를 고정한다.
 *
 * <p>여기서 지키려는 것은 <b>동의했던 사실이 없어지지 않는다</b> 하나다.
 * 철회를 update 로 적거나 뷰가 옛 행을 고르면 그 순간 입증(R7)이 무너지는데,
 * 화면에는 아무 증상이 없어서 분쟁이 나기 전까지 모른다.
 */
class ConsentSchemaTest extends PostgresTestBase {

    @Autowired
    JdbcClient jdbc;

    AuthFixture fixture;
    long user;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);
        user = fixture.insertUser("consent@test.local", "동의자");
    }

    @Nested
    @DisplayName("항목 시드")
    class Items {

        @Test
        @DisplayName("필수는 계약과 개인정보 수집 둘뿐이다")
        void requiredItemsAreTwo() {
            List<String> required = jdbc.sql(
                            "select distinct code from consent_item where is_required order by code")
                    .query(String.class)
                    .list();

            assertThat(required).containsExactly("privacy_collect", "terms_of_service");
        }

        @Test
        @DisplayName("마케팅 항목은 채널별로 갈라져 있다")
        void marketingItemsAreSplitPerChannel() {
            List<String> optional = jdbc.sql(
                            "select code from consent_item where not is_required order by code")
                    .query(String.class)
                    .list();

            assertThat(optional).containsExactly("marketing_email", "marketing_night", "marketing_sms");
        }

        @Test
        @DisplayName("야간 수신은 이메일 수신에 걸려 있다")
        void nightConsentDependsOnEmail() {
            String parent = jdbc.sql("""
                            select p.code
                              from consent_item c
                              join consent_item p on p.consent_item_id = c.depends_on_id
                             where c.code = 'marketing_night'
                            """)
                    .query(String.class)
                    .single();

            assertThat(parent).isEqualTo("marketing_email");
        }

        @Test
        @DisplayName("같은 코드의 같은 판을 두 번 넣지 못한다")
        void codeAndVersionAreUnique() {
            assertThatThrownBy(() -> insertItem("terms_of_service", 1, "이용약관 사본"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Nested
    @DisplayName("고지 내용")
    class Notice {

        @Test
        @DisplayName("개인정보 항목은 넷을 다 갖는다")
        void privacyItemHasAllFour() {
            Map<String, Object> row = jdbc.sql("""
                            select purpose, collected_items, retention_period, refusal_disadvantage
                              from consent_item where code = 'privacy_collect'
                            """)
                    .query()
                    .singleRow();

            assertThat(row.values())
                    .as("하나라도 빠지면 알리지 않고 받은 동의가 된다(개인정보법 제15조제2항)")
                    .doesNotContainNull();
        }

        @Test
        @DisplayName("수집 항목에 접속 IP 가 적혀 있다")
        void discloseActedIp() {
            String items = jdbc.sql(
                            "select collected_items from consent_item where code = 'privacy_collect'")
                    .query(String.class)
                    .single();

            assertThat(items)
                    .as("user_consent.acted_ip 를 실제로 받고 있다. 스키마에 있는데 고지에 없으면 근거 없이 받는 것이다")
                    .contains("IP");
        }

        @Test
        @DisplayName("이용약관은 본문을 갖는다")
        void termsHaveBody() {
            String body = jdbc.sql(
                            """
                            select body from consent_item
                             where code = 'terms_of_service'
                             order by version desc limit 1
                            """)
                    .query(String.class)
                    .single();

            assertThat(body)
                    .as("고객이 요구하면 사본을 내줘야 한다. 보여줄 원문이 없으면 못 준다(약관규제법 제3조제2항)")
                    .isNotBlank();
        }

        @Test
        @DisplayName("고지할 것이 하나도 없는 항목은 못 만든다")
        void rejectsItemWithoutContent() {
            assertThatThrownBy(() -> jdbc.sql("""
                            insert into consent_item (code, title) values ('empty_notice', '빈 항목')
                            """).update())
                    .as("무엇에 대한 동의인지 모르는 행이 생기면 그 동의는 입증에 못 쓴다")
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("정형 넷 중 일부만 채우면 못 만든다")
        void rejectsPartialNotice() {
            assertThatThrownBy(() -> jdbc.sql("""
                            insert into consent_item (code, title, purpose, collected_items)
                            values ('partial_notice', '반쪽 항목', '목적', '항목')
                            """).update())
                    .as("셋만 채우면 법이 요구한 하나가 빠진 채로 동의를 받게 된다")
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Nested
    @DisplayName("사건이 쌓인다")
    class History {

        @Test
        @DisplayName("철회해도 동의했던 행이 남는다")
        void revokeKeepsTheGrantRow() {
            grant("marketing_email", true, "signup");
            grant("marketing_email", false, "mypage");

            List<Map<String, Object>> rows = jdbc.sql("""
                            select uc.granted, uc.source
                              from user_consent uc
                              join consent_item ci on ci.consent_item_id = uc.consent_item_id
                             where uc.user_id = :user and ci.code = 'marketing_email'
                             order by uc.user_consent_id
                            """)
                    .param("user", user)
                    .query()
                    .listOfRows();

            assertThat(rows).hasSize(2);
            assertThat(rows.get(0)).containsEntry("granted", true).containsEntry("source", "signup");
            assertThat(rows.get(1)).containsEntry("granted", false).containsEntry("source", "mypage");
        }

        @Test
        @DisplayName("안 건드린 항목은 행이 없다 — 거부와 갈린다")
        void untouchedItemHasNoRow() {
            grant("marketing_sms", false, "signup");

            assertThat(currentGranted("marketing_sms")).contains(false);
            assertThat(currentGranted("marketing_email")).isEmpty();
        }
    }

    @Nested
    @DisplayName("현재 상태 뷰")
    class CurrentView {

        @Test
        @DisplayName("마지막 사건을 고른다")
        void picksTheLastEvent() {
            grant("marketing_email", true, "signup");
            grant("marketing_email", false, "mypage");
            grant("marketing_email", true, "mypage");

            assertThat(currentGranted("marketing_email")).contains(true);
        }

        @Test
        @DisplayName("같은 트랜잭션에서 연달아 넣어도 순서가 정해진다")
        void breaksTieWithinOneTransaction() {
            // 한 트랜잭션 안에서는 now() 가 같은 값이라 acted_at 만으로는 순서가 안 난다.
            // 뷰가 id 를 tiebreak 로 안 쓰면 여기서 갈린다.
            grant("marketing_sms", true, "signup");
            grant("marketing_sms", false, "signup");

            List<Object> at = jdbc.sql("""
                            select distinct uc.acted_at
                              from user_consent uc
                              join consent_item ci on ci.consent_item_id = uc.consent_item_id
                             where uc.user_id = :user and ci.code = 'marketing_sms'
                            """)
                    .param("user", user)
                    .query(Object.class)
                    .list();

            assertThat(at).as("전제가 깨지면 이 테스트가 아무것도 안 잡는다").hasSize(1);
            assertThat(currentGranted("marketing_sms")).contains(false);
        }

        @Test
        @DisplayName("개정 전 판에 한 동의도 코드로 잡힌다")
        void oldVersionStillCountsUnderTheSameCode() {
            // 동의한 판을 붙잡아 둔다. 번호를 박으면 개정하는 청크마다 이 줄이 깨진다 —
            // `V28` 이 약관 제2판을 쌓았을 때 실제로 깨졌다.
            grant("terms_of_service", true, "signup");
            int agreedVersion = latestVersionOf("terms_of_service");

            insertItem("terms_of_service", 99, "이용약관 개정 예정판");

            Map<String, Object> row = jdbc.sql("""
                            select granted, item_version
                              from current_consent
                             where user_id = :user and item_code = 'terms_of_service'
                            """)
                    .param("user", user)
                    .query()
                    .singleRow();

            assertThat(row).containsEntry("granted", true);
            assertThat(row)
                    .as("동의한 판이 남아야 재동의가 필요한지 판단할 수 있다")
                    .containsEntry("item_version", agreedVersion);
        }

        /** {@code grant} 가 고르는 것과 같은 판. 그쪽이 {@code order by version desc limit 1} 이다 */
        private int latestVersionOf(String code) {
            return jdbc.sql("""
                            select version from consent_item
                             where code = :code order by version desc limit 1
                            """)
                    .param("code", code)
                    .query(Integer.class)
                    .single();
        }
    }

    @Nested
    @DisplayName("파기와 삭제")
    class Deletion {

        @Test
        @DisplayName("계정을 지우면 동의 이력도 같이 지워진다")
        void deletingUserDropsConsentHistory() {
            grant("terms_of_service", true, "signup");

            jdbc.sql("delete from app_user where user_id = :user").param("user", user).update();

            Long left = jdbc.sql("select count(*) from user_consent where user_id = :user")
                    .param("user", user)
                    .query(Long.class)
                    .single();

            assertThat(left).isZero();
        }

        /**
         * <b>이제 방벽이 둘이고 트리거가 먼저 걸린다</b>(`V27`).
         *
         * <p>시행된 문서는 이력이 있든 없든 못 지운다 — 전자문서법 제4조의2 2호가
         * 「저장된 때의 형태로 보존」을 서면 요건으로 정해서다(`D2` R22).
         * 그래서 예외 타입이 외래키의 것이 아니라 트리거의 것이 된다.
         *
         * <p>외래키가 사라진 것이 아니라 <b>가려진 것</b>이라, 그쪽은 아래 시행 전 판이 증명한다.
         */
        @Test
        @DisplayName("동의 이력이 달린 항목은 못 지운다")
        void itemWithHistoryIsProtected() {
            grant("terms_of_service", true, "signup");

            assertThatThrownBy(() -> jdbc.sql(
                            "delete from consent_item where code = 'terms_of_service' and version = 1")
                    .update())
                    .isInstanceOf(DataAccessException.class);
        }

        /**
         * 외래키가 여전히 지키는 것을 <b>트리거가 안 가리는 자리</b>에서 본다.
         *
         * <p>시행 전인 판은 불변 제약이 열려 있으므로(`V27`) 여기서 막는 것은 외래키뿐이다.
         * 이 테스트가 없으면 `V27` 이 외래키를 통째로 지워도 아무것도 안 깨진다.
         */
        @Test
        @DisplayName("시행 전인 판도 이력이 달리면 외래키가 막는다")
        void draftItemWithHistoryIsProtectedByTheForeignKey() {
            long draftId = jdbc.sql("""
                            insert into consent_item (code, version, title, body, effective_at)
                            values ('terms_of_service', 99, '개정 예정판', '개정될 문안',
                                    now() + interval '30 days')
                            returning consent_item_id
                            """)
                    .query(Long.class)
                    .single();

            jdbc.sql("""
                            insert into user_consent (user_id, consent_item_id, granted, source)
                            values (:user, :item, true, 'signup')
                            """)
                    .param("user", user)
                    .param("item", draftId)
                    .update();

            assertThatThrownBy(() -> jdbc.sql(
                            "delete from consent_item where consent_item_id = :id")
                    .param("id", draftId)
                    .update())
                    .as("불변 트리거가 안 걸리는 판이라 외래키가 유일한 방벽이다")
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    /**
     * 본문을 같이 넣는다. 고지할 내용이 없는 항목은 스키마가 막는다 —
     * 무엇에 대한 동의인지 모르는 행이 생기면 그 동의는 입증에 못 쓴다(`D2` R7·R16).
     */
    private long insertItem(String code, int version, String title) {
        return jdbc.sql("""
                        insert into consent_item (code, version, title, body)
                        values (:code, :version, :title, '테스트용 본문')
                        returning consent_item_id
                        """)
                .param("code", code)
                .param("version", version)
                .param("title", title)
                .query(Long.class)
                .single();
    }

    private void grant(String code, boolean granted, String source) {
        jdbc.sql("""
                        insert into user_consent (user_id, consent_item_id, granted, source)
                        select :user, consent_item_id, :granted, :source
                          from consent_item
                         where code = :code
                         order by version desc
                         limit 1
                        """)
                .param("user", user)
                .param("code", code)
                .param("granted", granted)
                .param("source", source)
                .update();
    }

    private Optional<Boolean> currentGranted(String code) {
        return jdbc.sql("""
                        select granted from current_consent
                         where user_id = :user and item_code = :code
                        """)
                .param("user", user)
                .param("code", code)
                .query(Boolean.class)
                .optional();
    }
}
