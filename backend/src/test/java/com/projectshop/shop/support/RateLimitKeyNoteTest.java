package com.projectshop.shop.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 요청 제한의 첫 거부 한 줄이 무엇을 찍나(`Q251`).
 *
 * <p>운영에서 손님 전원이 한 열쇠로 세였는데 그 열쇠가 무엇인지 남은 것이 없었다. 이 한 줄이 원인 후보 셋을 가른다 —
 * 헤더가 없다(앞단·rewrite 가 안 실었다) · 헤더를 안 믿었다(보낸 상대가 `TRUSTED_PROXIES` 밖) · 믿었는데 값이 안쪽 주소다.
 * <b>주소 자체는 안 찍는다</b>(`D16`) — 대역 이름만 낸다.
 */
@DisplayName("요청 제한의 첫 거부 기록")
class RateLimitKeyNoteTest {

    @Test
    @DisplayName("손님 헤더가 없거나, 주소가 그 값이거나, 다르다 — 셋을 가른다")
    void tellsWhetherTheClientHeaderWasTrusted() {
        assertThat(RateLimitFilter.headerState(null, "10.0.0.5")).isEqualTo("absent");
        assertThat(RateLimitFilter.headerState(" ", "10.0.0.5")).isEqualTo("absent");
        assertThat(RateLimitFilter.headerState("203.0.113.7", "203.0.113.7")).isEqualTo("trusted");
        assertThat(RateLimitFilter.headerState("2001:DB8:0:0:0:0:0:1", "2001:db8:0:0:0:0:0:1")).isEqualTo("trusted");
        assertThat(RateLimitFilter.headerState("203.0.113.7", "10.0.0.5"))
                .as("헤더가 있는데 주소가 그 값이 아니면 보낸 상대를 안 믿은 것이다")
                .isEqualTo("ignored");
    }

    @Test
    @DisplayName("열쇠 주소의 대역만 낸다 — 사설망 IPv6·IPv4·공유·공인을 가른다")
    void namesTheAddressRange() {
        assertThat(RateLimitFilter.addressClass("0:0:0:0:0:0:0:1")).isEqualTo("loopback");
        assertThat(RateLimitFilter.addressClass("127.0.0.1")).isEqualTo("loopback");
        assertThat(RateLimitFilter.addressClass("fd12:3456:789a:1:0:0:0:2")).isEqualTo("ipv6-ula");
        assertThat(RateLimitFilter.addressClass("fe80:0:0:0:0:0:0:1")).isEqualTo("ipv6-link-local");
        assertThat(RateLimitFilter.addressClass("2001:db8:0:0:0:0:0:1")).isEqualTo("ipv6-public");
        assertThat(RateLimitFilter.addressClass("10.250.3.4")).isEqualTo("ipv4-private");
        assertThat(RateLimitFilter.addressClass("172.20.0.9")).isEqualTo("ipv4-private");
        assertThat(RateLimitFilter.addressClass("172.32.0.9")).isEqualTo("ipv4-public");
        assertThat(RateLimitFilter.addressClass("192.168.0.2")).isEqualTo("ipv4-private");
        assertThat(RateLimitFilter.addressClass("100.64.0.1")).isEqualTo("ipv4-shared");
        assertThat(RateLimitFilter.addressClass("169.254.1.1")).isEqualTo("ipv4-link-local");
        assertThat(RateLimitFilter.addressClass("203.0.113.7")).isEqualTo("ipv4-public");
        assertThat(RateLimitFilter.addressClass(null)).isEqualTo("unknown");
        assertThat(RateLimitFilter.addressClass("not-an-address")).isEqualTo("unknown");
    }

    @Test
    @DisplayName("낸 값에 주소가 안 섞인다")
    void neverEchoesTheAddress() {
        String address = "203.0.113.7";

        assertThat(RateLimitFilter.addressClass(address)).doesNotContain(address).doesNotContain("203");
        assertThat(RateLimitFilter.headerState(address, "10.0.0.5")).doesNotContain(address);
    }
}
