package com.projectshop.shop.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;

/**
 * 동의를 거두고 다시 켜는 경로.
 *
 * <p>`5-0` 이 동의를 append-only 로 만든 이유가 철회인데 부를 경로가 없었다.
 * <b>스키마가 표현할 수 있는 것을 앱이 못 하면 그 설계는 쓰인 적이 없는 것이다</b>(`D2` R7).
 */
@AutoConfigureMockMvc
class MeConsentTest extends PostgresTestBase {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ConsentService consentService;

    AuthFixture fixture;
    long userId;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);
        userId = fixture.insertUser("consent-me@test.local", "동의자");
        fixture.grantGlobal(userId, "customer");

        grantAtSignup("terms_of_service");
        grantAtSignup("privacy_collect");
    }

    @Nested
    @DisplayName("고지 조회")
    class NoticeLookup {

        @Test
        @DisplayName("로그인 없이 지금 판을 읽는다")
        void readsCurrentWithoutLogin() throws Exception {
            mvc.perform(get("/api/consent-items/terms_of_service"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.version").value(1))
                    .andExpect(jsonPath("$.body").isNotEmpty());
        }

        /**
         * 가입 화면이 부르는 목록. <b>로그인 전이다.</b>
         *
         * <p>이 경로가 없으면 화면이 코드를 박게 되고, `V11` 이 항목을 데이터로 둔 뜻이 사라진다.
         */
        @Test
        @DisplayName("로그인 없이 항목 전부를 받는다")
        void listsEveryItemWithoutLogin() throws Exception {
            mvc.perform(get("/api/consent-items"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(5))
                    .andExpect(jsonPath("$[0].code").value("terms_of_service"))
                    .andExpect(jsonPath("$[0].is_required").value(true));
        }

        @Test
        @DisplayName("목록에는 본문이 없다 — 약관 전문이 항목 수만큼 딸려 나온다")
        void listOmitsBody() throws Exception {
            mvc.perform(get("/api/consent-items"))
                    .andExpect(jsonPath("$[0].body").doesNotExist())
                    .andExpect(jsonPath("$[0].title").isNotEmpty());
        }

        @Test
        @DisplayName("목록에도 고지 넷이 온다 — 동의받는 그 자리에서 보여야 한다")
        void listCarriesNotices() throws Exception {
            mvc.perform(get("/api/consent-items"))
                    .andExpect(jsonPath("$[1].code").value("privacy_collect"))
                    .andExpect(jsonPath("$[1].purpose").isNotEmpty())
                    .andExpect(jsonPath("$[1].collected_items").isNotEmpty())
                    .andExpect(jsonPath("$[1].retention_period").isNotEmpty())
                    .andExpect(jsonPath("$[1].refusal_disadvantage").isNotEmpty());
        }

        @Test
        @DisplayName("개인정보 항목은 고지 넷이 다 온다")
        void returnsAllFourNotices() throws Exception {
            mvc.perform(get("/api/consent-items/privacy_collect"))
                    .andExpect(jsonPath("$.purpose").isNotEmpty())
                    .andExpect(jsonPath("$.collected_items").isNotEmpty())
                    .andExpect(jsonPath("$.retention_period").isNotEmpty())
                    .andExpect(jsonPath("$.refusal_disadvantage").isNotEmpty());
        }

        /**
         * <b>종속 항목이 부모보다 뒤에 온다.</b> 스키마로는 못 건다 — 다른 행을 봐야 하는 조건이라
         * {@code check} 에 안 들어간다. 여기가 유일한 방벽이다(`D23` 「불변식」).
         *
         * <p>어기면 화면에 <b>야간 수신이 이메일 수신보다 위에</b> 뜬다. 그 칸은 부모가 꺼져 있으면
         * 누를 수 없는 칸이라, 먼저 나오면 이유를 모른 채 막힌 것을 먼저 보게 된다.
         */
        @Test
        @DisplayName("종속 항목이 부모 바로 뒤에 온다")
        void placesDependentAfterParent() {
            List<String> codes = consentService.listCurrent().stream()
                    .map(ConsentService.Notice::code)
                    .toList();

            for (ConsentService.Notice item : consentService.listCurrent()) {
                if (item.dependsOn() == null) {
                    continue;
                }
                assertThat(codes.indexOf(item.code()))
                        .as("%s 가 부모 %s 보다 앞에 있다. sort_no 를 부모 뒤로 옮긴다",
                                item.code(), item.dependsOn())
                        .isGreaterThan(codes.indexOf(item.dependsOn()));
            }
        }

        @Test
        @DisplayName("필수가 먼저 오고 그 안에서 정한 순서를 따른다")
        void ordersRequiredFirst() {
            List<Boolean> required = consentService.listCurrent().stream()
                    .map(ConsentService.Notice::required)
                    .toList();

            assertThat(required)
                    .as("선택 항목이 필수보다 먼저 오면 가입하려는 사람이 안 눌러도 되는 것부터 읽는다")
                    .isSortedAccordingTo(Comparator.<Boolean>naturalOrder().reversed());
        }

        @Test
        @DisplayName("모르는 코드는 404 다")
        void unknownCodeIsNotFound() throws Exception {
            mvc.perform(get("/api/consent-items/no-such-item"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("개정돼도 내 사본은 내가 동의한 판이다")
        void myCopyStaysOnTheVersionIAgreedTo() throws Exception {
            // 2판을 내놓는다. 지금 효력 있는 판이 바뀐다.
            jdbc.sql("""
                            insert into consent_item (code, version, title, body)
                            values ('terms_of_service', 2, '이용약관 2판', '## 2판 본문')
                            """).update();

            // 공개 조회는 지금 판을 준다.
            mvc.perform(get("/api/consent-items/terms_of_service"))
                    .andExpect(jsonPath("$.version").value(2));

            mvc.perform(get("/api/me/consents/terms_of_service").with(user(principal())))
                    // 최신판을 내주면 그 사이 우리가 고친 것을 들이미는 꼴이 된다.
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.notice.version").value(1))
                    .andExpect(jsonPath("$.granted").value(true))
                    .andExpect(jsonPath("$.acted_at").isNotEmpty());
        }

        @Test
        @DisplayName("동의한 적 없는 항목은 사본이 없다")
        void noCopyForUntouchedItem() throws Exception {
            mvc.perform(get("/api/me/consents/marketing_sms").with(user(principal())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("내 사본은 로그인이 필요하다")
        void myCopyNeedsLogin() throws Exception {
            mvc.perform(get("/api/me/consents/terms_of_service"))
                    .andExpect(status().is4xxClientError());
        }
    }

    @Nested
    @DisplayName("목록")
    class Listing {

        @Test
        @DisplayName("건드린 적 없는 항목도 나온다")
        void includesUntouchedItems() throws Exception {
            // 동의한 것만 보여주면 무엇을 더 켤 수 있는지 알 방법이 없다.
            mvc.perform(get("/api/me/consents").with(user(principal())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(5))
                    .andExpect(jsonPath("$[?(@.code == 'marketing_sms')].granted").value(false))
                    // 필터는 배열을 준다. acted_at 이 null 이라는 것이 "건드린 적 없다" 는 뜻이다.
                    .andExpect(jsonPath("$[?(@.code == 'marketing_sms')].acted_at")
                            .value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue())));
        }

        @Test
        @DisplayName("필수 항목이 먼저 나오고 종속이 표시된다")
        void showsRequiredFirstAndDependency() throws Exception {
            mvc.perform(get("/api/me/consents").with(user(principal())))
                    .andExpect(jsonPath("$[0].is_required").value(true))
                    .andExpect(jsonPath("$[?(@.code == 'marketing_night')].depends_on")
                            .value("marketing_email"));
        }
    }

    @Nested
    @DisplayName("철회")
    class Revoking {

        @Test
        @DisplayName("거둔 사실이 행으로 남는다 — 동의했던 행은 안 지운다")
        void keepsBothRows() throws Exception {
            grant("marketing_email").andExpect(status().isNoContent());
            revoke("marketing_email").andExpect(status().isNoContent());

            List<Boolean> history = jdbc.sql("""
                            select uc.granted from user_consent uc
                              join consent_item ci on ci.consent_item_id = uc.consent_item_id
                             where uc.user_id = :id and ci.code = 'marketing_email'
                             order by uc.user_consent_id
                            """)
                    .param("id", userId)
                    .query(Boolean.class)
                    .list();

            assertThat(history)
                    .as("철회가 update 였으면 동의 시점을 잃는다(R7)")
                    .containsExactly(true, false);
        }

        @Test
        @DisplayName("필수 항목은 거둘 수 없다")
        void requiredCannotBeRevoked() throws Exception {
            // 허용하면 동의 없이 살아 있는 계정이 생긴다. 그건 탈퇴지 철회가 아니다.
            revoke("terms_of_service").andExpect(status().isUnprocessableContent());
        }

        @Test
        @DisplayName("채널을 거두면 야간 수신도 같이 거둬진다")
        void revokingChannelCascadesToNight() throws Exception {
            grant("marketing_email");
            grant("marketing_night").andExpect(status().isNoContent());

            revoke("marketing_email").andExpect(status().isNoContent());

            assertThat(granted("marketing_night"))
                    .as("채널 없는 야간 동의가 남으면 나중에 채널만 켜질 때 야간까지 열린다")
                    .isFalse();
        }

        @Test
        @DisplayName("이미 거둔 것을 또 거둬도 행이 안 늘어난다")
        void repeatedRevokeIsIdempotent() throws Exception {
            grant("marketing_sms");
            revoke("marketing_sms");
            long before = rowCount("marketing_sms");

            revoke("marketing_sms").andExpect(status().isNoContent());

            assertThat(rowCount("marketing_sms"))
                    .as("남길 것은 바뀐 순간이지 요청이 온 횟수가 아니다")
                    .isEqualTo(before);
        }

        @Test
        @DisplayName("모르는 항목은 404")
        void unknownItemIsNotFound() throws Exception {
            revoke("no-such-thing").andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("재동의")
    class Granting {

        @Test
        @DisplayName("껐던 것을 다시 켠다")
        void turnsItBackOn() throws Exception {
            grant("marketing_sms");
            revoke("marketing_sms");

            grant("marketing_sms").andExpect(status().isNoContent());

            assertThat(granted("marketing_sms")).isTrue();
        }

        @Test
        @DisplayName("채널 없이 야간만 켤 수 없다")
        void nightNeedsItsChannel() throws Exception {
            grant("marketing_night").andExpect(status().isUnprocessableContent());
        }

        @Test
        @DisplayName("채널을 먼저 켜면 야간도 켜진다")
        void nightFollowsItsChannel() throws Exception {
            grant("marketing_email").andExpect(status().isNoContent());

            grant("marketing_night").andExpect(status().isNoContent());

            assertThat(granted("marketing_night")).isTrue();
        }
    }

    @Nested
    @DisplayName("권한")
    class Authorization {

        @Test
        @DisplayName("로그인 없이는 못 본다")
        void requiresLogin() throws Exception {
            mvc.perform(get("/api/me/consents")).andExpect(status().isUnauthorized());
        }
    }

    private ResultActions grant(String code) throws Exception {
        return mvc.perform(post("/api/me/consents/{code}/grant", code)
                .with(user(principal())).with(csrf()));
    }

    private ResultActions revoke(String code) throws Exception {
        return mvc.perform(post("/api/me/consents/{code}/revoke", code)
                .with(user(principal())).with(csrf()));
    }

    private boolean granted(String code) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        select coalesce((select granted from current_consent
                                          where user_id = :id and item_code = :code), false)
                        """)
                .param("id", userId)
                .param("code", code)
                .query(Boolean.class)
                .single());
    }

    private long rowCount(String code) {
        return jdbc.sql("""
                        select count(*) from user_consent uc
                          join consent_item ci on ci.consent_item_id = uc.consent_item_id
                         where uc.user_id = :id and ci.code = :code
                        """)
                .param("id", userId)
                .param("code", code)
                .query(Long.class)
                .single();
    }

    private void grantAtSignup(String code) {
        jdbc.sql("""
                        insert into user_consent (user_id, consent_item_id, granted, source)
                        select :id, consent_item_id, true, 'signup' from consent_item
                         where code = :code order by version desc limit 1
                        """)
                .param("id", userId)
                .param("code", code)
                .update();
    }

    private ShopUser principal() {
        return new ShopUser(userId, "consent-me@test.local", "{noop}x", true);
    }
}
