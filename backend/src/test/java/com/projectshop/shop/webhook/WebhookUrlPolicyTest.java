package com.projectshop.shop.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

/**
 * 웹훅 주소 검사 — SSRF(`29`, `D14`).
 *
 * <p><b>안쪽을 가리키는 주소가 하나라도 지나가면 우리 서버가 남의 요청을 대신 보내는 창구가 된다</b> — 클라우드 메타데이터
 * (169.254.169.254)가 그 대표다. 이름 풀이 없이 판정되는 주소 글자만 쓴다(시험이 바깥 DNS 에 기대지 않게).
 */
@DisplayName("웹훅 주소 검사")
class WebhookUrlPolicyTest {

    private final WebhookUrlPolicy strict = new WebhookUrlPolicy(false);
    private final WebhookUrlPolicy loopbackAllowed = new WebhookUrlPolicy(true);

    @ParameterizedTest
    @ValueSource(strings = {
        "https://127.0.0.1/hook", "https://localhost/hook", "https://10.0.0.1/hook", "https://172.16.0.1/hook",
        "https://192.168.1.1/hook", "https://169.254.169.254/latest/meta-data", "https://100.64.0.1/hook",
        "https://0.0.0.0/hook", "https://[::1]/hook", "https://[fc00::1]/hook", "https://[fe80::1]/hook",
        "http://93.184.216.34/hook", "https://user:pw@93.184.216.34/hook", "not a url", "ftp://93.184.216.34/x"})
    @DisplayName("안쪽 대역·http·사용자 정보·꼴 아님은 막는다")
    void rejects(String url) {
        assertThatThrownBy(() -> strict.require(url))
                .isInstanceOfSatisfying(ShopException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.WEBHOOK_URL_NOT_ALLOWED));
    }

    @Test
    @DisplayName("바깥 https 주소는 받는다")
    void acceptsPublicHttps() {
        assertThat(strict.require("https://93.184.216.34/hooks/shop").getHost()).isEqualTo("93.184.216.34");
    }

    /** 시험 기반 클래스만 켜는 칸이다. 켜도 풀리는 것은 루프백뿐이다 */
    @Test
    @DisplayName("루프백 허용은 루프백만 푼다")
    void loopbackAllowanceIsNarrow() {
        assertThat(loopbackAllowed.require("http://127.0.0.1:8089/hook")).isNotNull();
        assertThatThrownBy(() -> loopbackAllowed.require("https://10.0.0.1/hook"))
                .isInstanceOf(ShopException.class);
        assertThatThrownBy(() -> loopbackAllowed.require("https://169.254.169.254/latest/meta-data"))
                .isInstanceOf(ShopException.class);
    }
}
