package com.projectshop.shop.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;

/**
 * 감사 대상 종류의 값 목록이 세 자리에서 닫혀 있는지 본다(`Q381`) — 권한 표 · {@code check} · 필터 입구.
 *
 * <p>{@code EnumConstraintTest} 는 열거형과 {@code check} 의 글자가 같은지만 본다. 여기는 그 바깥이다 —
 * 권한 표에 자원이 늘면 거부 기록이 {@code check} 에서 깨지는데 그 자원은 마이그레이션의 데이터라
 * 글자 대조로는 안 보인다.
 */
@DisplayName("감사 대상 종류")
class AuditTargetTypeTest extends PostgresTestBase {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("권한 표의 자원이 전부 감사 대상 종류에 있다 — 거부 기록이 그 이름을 그대로 쓴다")
    void everyPermissionResourceIsATargetType() {
        List<String> resources = jdbc.sql("select distinct resource from permission order by resource")
                .query(String.class)
                .list();

        assertThat(resources).isNotEmpty();
        assertThat(resources).allSatisfy(resource ->
                assertThat(AuditTargetType.ofResource(resource).code()).isEqualTo(resource));
    }

    @Test
    @DisplayName("목록 밖 대상 종류는 check 가 막는다 — not valid 여도 새 행은 검사한다")
    void checkRejectsUnknownTargetType() {
        // 시험 트랜잭션 안에서 넣는다. 밖에서 고치면 fork DB 를 건드린다(2026-09-26 실측).
        assertThatThrownBy(() -> jdbc.sql("""
                        insert into audit_log (event_type, target_type)
                        values ('test.q381', 'sku')
                        """).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("audit_log_target_type_check");
    }

    @Test
    @DisplayName("감사 로그 필터에 모르는 대상 종류를 주면 400 validation-failed 이고 errors 가 target_type 을 짚는다")
    void unknownTargetTypeFilterIsValidationFailed() throws Exception {
        long userId = new AuthFixture(jdbc).insertUser("q381@test.local", "감사");
        ShopUser viewer = new ShopUser(userId, "q381@test.local", null, true);

        mvc.perform(get("/api/audit-logs").with(user(viewer)).param("target_type", "sku"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("tag:projectshop.example,2026:error:validation-failed"))
                .andExpect(jsonPath("$.errors[0].field").value("target_type"));
    }
}
