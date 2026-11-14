package com.projectshop.shop.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.projectshop.shop.PostgresTestBase;

/**
 * 중단·재개 요구를 받을 수령인이 로그인 없이 읽히는지 잰다(`Q463`, 저작권법 제103조④·시행령 제44조 `D2` R42).
 *
 * <p><b>값은 환경변수가 든다</b> — 저장소에 지어 넣지 않는다. 비면 화면이 「지정되지 않았다」고 쓴다.
 */
@DisplayName("저작권 수령인 공지")
@TestPropertySource(properties = {
    "shop.copyright.recipient.name=김수령",
    "shop.copyright.recipient.department=운영팀",
    "shop.copyright.recipient.email=copyright@test.local",
    "shop.copyright.recipient.phone=02-000-0000",
    "shop.copyright.recipient.address=서울시 어딘가 1"})
class CopyrightRecipientApiTest extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("로그인 없이 다섯 칸을 읽는다")
    void readsWithoutLogin() throws Exception {
        mvc.perform(get("/api/copyright-reports/recipient"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("김수령"))
                .andExpect(jsonPath("$.department").value("운영팀"))
                .andExpect(jsonPath("$.email").value("copyright@test.local"))
                .andExpect(jsonPath("$.phone").value("02-000-0000"))
                .andExpect(jsonPath("$.address").value("서울시 어딘가 1"));
    }

    @Test
    @DisplayName("빈 값은 null 이고 통보 문구는 값 있는 칸만 잇는다")
    void blanksAreNull() {
        CopyrightRecipient recipient = new CopyrightRecipient("김수령", "", "copyright@test.local", " ", "");

        assertThat(recipient.response().department()).isNull();
        assertThat(recipient.response().phone()).isNull();
        assertThat(recipient.configured()).isTrue();
        assertThat(recipient.displayText()).isEqualTo("김수령 · copyright@test.local");
        assertThat(new CopyrightRecipient("", "", "", "", "").configured()).isFalse();
    }
}
