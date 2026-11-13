package com.projectshop.shop.brokerage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;

/**
 * 중개 명세 관리자 입구가 권한대로 열리고 서식 순서 CSV 를 내며 제출을 해시로 기록하는지 잰다(`Q456`, `D2` R46).
 *
 * <p><b>셀러 신원은 내려받을 때 붙는다</b>(2026-11-13 사용자 결정) — 줄은 수치만 박제하고 파일이 {@code seller} 에서 상호·대표자·
 * 사업자번호를 읽는다. 그래서 제출 요청은 화면이 본 해시를 같이 보내고, 그 사이 신원이 바뀌었으면 409 다.
 */
@DisplayName("중개 명세 관리자 입구")
class BrokerageStatementApiTest extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    private long adminId;
    private long auditorId;
    private long ownerId;
    private long customerId;
    private long sellerId;
    private long statementId;

    @BeforeEach
    void setUp() {
        AuthFixture fixture = new AuthFixture(jdbc);
        adminId = account(fixture, "brk-admin@test.local", "admin");
        auditorId = account(fixture, "brk-auditor@test.local", "auditor");
        customerId = account(fixture, "brk-customer@test.local", null);
        sellerId = fixture.insertSeller("s-brk-api", "명세셀러");
        fixture.verifySeller(sellerId);
        ownerId = account(fixture, "brk-owner@test.local", null);
        fixture.joinSeller(sellerId, ownerId);
        fixture.grantOrg(ownerId, "seller_owner", sellerId);

        statementId = jdbc.sql("""
                        insert into brokerage_statement (period_year, period_quarter, period_start, period_end, due_date)
                        values (2020, 1, date '2020-01-01', date '2020-03-31', date '2020-04-15')
                        returning brokerage_statement_id
                        """)
                .query(Long.class)
                .single();
        jdbc.sql("""
                        insert into brokerage_statement_line
                               (brokerage_statement_id, seller_id, sales_month, method, sale_count, sale_amount)
                        values (:statement, :seller, date '2020-02-01', 'card', 3, 45000)
                        """)
                .param("statement", statementId)
                .param("seller", sellerId)
                .update();
    }

    @Test
    @DisplayName("관리자는 목록을 보고 제출할 수 있다")
    void adminLists() throws Exception {
        list(adminId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.can_submit").value(true))
                .andExpect(jsonPath("$.items[?(@.brokerage_statement_id == " + statementId + ")].status").value("OVERDUE"));
    }

    @Test
    @DisplayName("감사자는 목록만 보고 제출은 403 이다")
    void auditorReadsOnly() throws Exception {
        list(auditorId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.can_submit").value(false));
        submit(auditorId, sha(file(adminId))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("셀러와 고객은 목록도 파일도 403 이다")
    void othersForbidden() throws Exception {
        for (long userId : new long[] {ownerId, customerId}) {
            list(userId).andExpect(status().isForbidden());
            mvc.perform(get("/api/admin/brokerage-statements/{id}/file", statementId).with(user(principal(userId))))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("파일은 서식 순서 CSV 이고 신원과 서식 코드를 붙인다")
    void rendersFile() throws Exception {
        String text = new String(file(adminId), StandardCharsets.UTF_8);
        String[] rows = text.split("\r\n");

        assertThat(rows[0]).startsWith("\"일련번호\",\"판매대행연월\",\"결제구분\",\"건수\",\"금액\",\"사업자등록번호\"");
        assertThat(rows[1]).isEqualTo(
                "\"1\",\"202002\",\"C\",\"3\",\"45000\",\"1234567891\",\"주식회사 테스트\",\"홍길동\",\""
                        + sellerId + "\",\"\",\"\",\"02-0000-0000\",\"\"");
    }

    @Test
    @DisplayName("수식으로 읽힐 값은 작은따옴표를 앞에 붙인다")
    void neutralizesFormula() throws Exception {
        jdbc.sql("update seller set business_name = '=HYPERLINK(1)' where seller_id = :id").param("id", sellerId).update();

        String text = new String(file(adminId), StandardCharsets.UTF_8);

        assertThat(text)
                .as("스프레드시트가 상호를 수식으로 실행하면 안 된다")
                .contains("\"'=HYPERLINK(1)\"");
    }

    @Test
    @DisplayName("탭이나 CR 로 시작하는 값도 작은따옴표를 붙인다")
    void neutralizesControlLead() throws Exception {
        jdbc.sql("update seller set business_name = E'\\t=1', representative_name = E'\\r=2' where seller_id = :id")
                .param("id", sellerId)
                .update();

        String text = new String(file(adminId), StandardCharsets.UTF_8);

        assertThat(text)
                .as("OWASP CSV Injection 목록은 탭·CR 로 시작하는 값도 든다")
                .contains("\"'\t=1\"")
                .contains("\"'\r=2\"");
    }

    @Test
    @DisplayName("제출은 201 이고 다시 내면 409 다")
    void submitsOnce() throws Exception {
        String hash = sha(file(adminId));

        submit(adminId, hash)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/admin/brokerage-statements/" + statementId));
        list(adminId)
                .andExpect(jsonPath("$.items[?(@.brokerage_statement_id == " + statementId + ")].status").value("SUBMITTED"));
        submit(adminId, hash)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("brokerage-statement-already-submitted")));
    }

    @Test
    @DisplayName("본 파일과 지금 파일의 해시가 다르면 409 다")
    void rejectsChangedFile() throws Exception {
        String hash = sha(file(adminId));
        jdbc.sql("update seller set representative_name = '김갑동' where seller_id = :id").param("id", sellerId).update();

        submit(adminId, hash)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("brokerage-statement-file-changed")));
    }

    @Test
    @DisplayName("내려받기와 제출이 감사 기록을 남긴다")
    void auditsDownloadAndSubmit() throws Exception {
        submit(adminId, sha(file(adminId))).andExpect(status().isCreated());

        assertThat(jdbc.sql("""
                        select count(*) from audit_log
                         where target_type = 'brokerage_statement' and target_id = :id
                           and event_type in ('brokerage_statement.downloaded', 'brokerage_statement.submitted')
                        """)
                .param("id", statementId)
                .query(Integer.class)
                .single())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("없는 명세는 404 다")
    void missingIs404() throws Exception {
        mvc.perform(get("/api/admin/brokerage-statements/{id}/file", Long.MAX_VALUE).with(user(principal(adminId))))
                .andExpect(status().isNotFound());
    }

    private ResultActions list(long userId) throws Exception {
        return mvc.perform(get("/api/admin/brokerage-statements").with(user(principal(userId))));
    }

    private byte[] file(long userId) throws Exception {
        return mvc.perform(get("/api/admin/brokerage-statements/{id}/file", statementId).with(user(principal(userId))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
    }

    private ResultActions submit(long userId, String hash) throws Exception {
        return mvc.perform(post("/api/admin/brokerage-statements/{id}/submission", statementId)
                .with(user(principal(userId)))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"file_sha256\": \"" + hash + "\"}"));
    }

    private static String sha(byte[] file) {
        return BrokerageStatementFile.sha256Hex(file);
    }

    private static ShopUser principal(long userId) {
        return new ShopUser(userId, "brk@test.local", null, true).verified();
    }

    private static long account(AuthFixture fixture, String email, String role) {
        long id = fixture.insertUser(email, "명세");
        fixture.grantGlobal(id, "customer");
        if (role != null) {
            fixture.grantGlobal(id, role);
        }
        return id;
    }
}
