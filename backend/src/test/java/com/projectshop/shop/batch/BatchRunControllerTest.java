package com.projectshop.shop.batch;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.auth.PermissionRuleLoader;
import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;

/**
 * 기준일 배치를 손으로 돌리는 입구(`Q241`).
 *
 * <p><b>배치는 {@code daily_sales} 로 잰다.</b> 먼 기준일이라 집계할 매출이 없어 초 단위로 끝나고,
 * 시험 트랜잭션이 되돌려 회차 줄이 안 남는다({@code SalesStatsApiTest} 가 같은 수를 쓴다).
 */
// 입구가 기본으로는 없다(마무리 55차) — 운영에 공개된 관리자 연습 계정이 회차를 돌리지 못하게 한다.
@TestPropertySource(properties = "shop.batch.manual-run=true")
@DisplayName("배치 손 실행")
class BatchRunControllerTest extends PostgresTestBase {

    /** 다른 시험·실제 회차와 안 겹치는 먼 날 */
    private static final String BASELINE = "2001-02-03";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PermissionRuleLoader ruleLoader;

    private long adminId;
    private long auditorId;
    private long customerId;

    @BeforeEach
    void setUp() {
        AuthFixture fixture = new AuthFixture(jdbc);
        adminId = fixture.insertUser("batch-admin@test.local", "관리자");
        fixture.grantGlobal(adminId, "admin");
        auditorId = fixture.insertUser("batch-auditor@test.local", "감사자");
        fixture.grantGlobal(auditorId, "auditor");
        customerId = fixture.insertUser("batch-customer@test.local", "고객");
        fixture.grantGlobal(customerId, "customer");
    }

    @Test
    @DisplayName("관리자가 돌리면 200 과 그 회차 줄이 나온다")
    void adminRunsTheBatch() throws Exception {
        run(adminId, "daily_sales")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batch_name").value("daily_sales"))
                .andExpect(jsonPath("$.baseline_date").value(BASELINE))
                .andExpect(jsonPath("$.status").value("succeeded"))
                .andExpect(jsonPath("$.target_count").value(0))
                .andExpect(jsonPath("$.processed_count").value(0));
    }

    /**
     * <b>이미 성공한 회차는 줄 없이 건너뛴다</b>({@code BatchRuns.record}). 입구가 그것을 「건너뜀」으로 읽는다 —
     * 앞 줄(성공)을 그대로 내면 방금 또 돌린 것처럼 보인다.
     */
    @Test
    @DisplayName("같은 기준일을 다시 부르면 skipped 다")
    void secondRunIsSkipped() throws Exception {
        run(adminId, "daily_sales").andExpect(status().isOk());

        run(adminId, "daily_sales")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("skipped"))
                .andExpect(jsonPath("$.target_count").doesNotExist());
    }

    @Test
    @DisplayName("감사자는 403 이다 — 읽기 전용 역할의 거부를 트리거가 달았다")
    void auditorIsRefused() throws Exception {
        run(auditorId, "daily_sales")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("tag:projectshop.example,2026:error:batch-forbidden"));
    }

    @Test
    @DisplayName("고객은 403 이다")
    void customerIsRefused() throws Exception {
        run(customerId, "daily_sales").andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("기준일 배치가 아닌 이름은 404 다 — 5분 주기 스위퍼도 안 연다")
    void unknownNameIsNotFound() throws Exception {
        run(adminId, "notification_sweep")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("tag:projectshop.example,2026:error:batch-not-found"));
    }

    /**
     * <b>오늘과 미래는 안 받는다</b>(마무리 55차 독립 리뷰). 미래 기준일은 보존 기한의 끝을 앞당겨 아직 지킬 기록을 지우고,
     * 오늘은 {@code daily_sales} 가 반쪽 집계를 성공으로 남겨 진짜 회차를 막는다.
     */
    @Test
    @DisplayName("오늘이나 미래 기준일은 400 이다")
    void rejectsTodayAndFuture() throws Exception {
        String today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString();
        runOn(adminId, "transaction_purge", today)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("tag:projectshop.example,2026:error:validation-failed"));
        runOn(adminId, "transaction_purge", "2999-01-01").andExpect(status().isBadRequest());
    }

    /**
     * <b>주인이 없는 동작이라 {@code own} 부여로는 안 열린다</b>(마무리 55차 독립 리뷰). 자기 자신을 대상으로 재던 때는
     * {@code own} 하나로 통과했다.
     */
    @Test
    @DisplayName("own 부여로는 못 돌린다 — all 만 연다")
    void ownGrantDoesNotOpen() throws Exception {
        jdbc.sql("""
                        insert into role_permission (role_id, permission_id, scope, effect)
                        select r.role_id, p.permission_id, 'own', 'allow'
                          from role r join permission p on p.resource = 'batch' and p.action = 'run'
                         where r.code = 'customer'
                        """)
                .update();
        ruleLoader.evict(customerId);
        try {
            run(customerId, "daily_sales").andExpect(status().isForbidden());
        } finally {
            ruleLoader.evict(customerId);
        }
    }

    private ResultActions run(long userId, String name) throws Exception {
        return runOn(userId, name, BASELINE);
    }

    private ResultActions runOn(long userId, String name, String baseline) throws Exception {
        return mvc.perform(post("/api/admin/batches/{name}/runs", name)
                .with(user(new ShopUser(userId, "batch@test.local", "{noop}x", true)))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"baseline_date\": \"" + baseline + "\"}"));
    }
}
