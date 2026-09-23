package com.projectshop.shop.health;

import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import com.projectshop.shop.PostgresTestBase;

/**
 * 건강 확인이 배포 검사에 필요한 셋을 내나(`Q205`). {@code scripts/deploy-check.sh} 가 {@code commit} 으로 「그 커밋이
 * 떴나」를, {@code applied_migrations} 로 「마이그레이션이 다 올라갔나」를 잰다.
 */
@DisplayName("건강 확인")
class HealthControllerTest extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("커밋을 모르는 판은 unknown 이라 말한다 — 배포 검사가 그것을 빨강으로 읽는다")
    void reportsCommitAndMigrations() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commit").value("unknown"))
                .andExpect(jsonPath("$.applied_migrations").value(greaterThan(0)));
    }
}
