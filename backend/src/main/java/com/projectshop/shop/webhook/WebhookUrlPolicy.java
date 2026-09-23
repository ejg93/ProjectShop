package com.projectshop.shop.webhook;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

/**
 * 웹훅 주소가 안으로 향하지 않나를 본다 — SSRF(`D14`, `29`·`30`).
 *
 * <p><b>등록 때와 발송 때 둘 다 부른다.</b> 등록 때 거르는 것은 셀러에게 바로 알려 주는 것이고, 발송 때 다시 거르는 것이
 * 진짜 방어다 — 등록 뒤에 그 이름이 가리키는 주소가 바뀔 수 있다. <b>이름이 가리키는 주소를 전부</b> 본다. 하나라도 안이면 막는다.
 *
 * <p><b>못 막는 것</b>: 검사와 연결 사이에 DNS 가 바뀌는 리바인딩. 연결을 푼 주소에 못박으려면 HTTP 클라이언트를 갈아야 해서
 * 여기서 안 한다 — `security-baseline.md` 「웹훅 — SSRF」에 적었다.
 *
 * <p><b>루프백 허용은 한 칸이다</b>({@code shop.webhook.allow-loopback}). 기본은 거짓이고 시험 기반 클래스만 켠다 — 시험은
 * 로컬 HTTP 서버로 받는다. 켜도 풀리는 것은 루프백뿐이고 사설·링크로컬·메타데이터 대역은 그대로 막는다. {@code https} 강제도
 * 같이 풀린다(로컬 서버는 {@code http} 다).
 */
@Component
class WebhookUrlPolicy {

    private final boolean allowLoopback;

    WebhookUrlPolicy(@Value("${shop.webhook.allow-loopback:false}") boolean allowLoopback) {
        this.allowLoopback = allowLoopback;
    }

    /** 받을 수 있는 주소면 그대로 돌려준다. 아니면 {@code WEBHOOK_URL_NOT_ALLOWED}(422) 다 */
    URI require(String url) {
        URI uri = parse(url);
        String host = uri.getHost();
        if (host == null || uri.getUserInfo() != null) {
            throw notAllowed(url, "호스트가 없거나 사용자 정보가 붙었다");
        }
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        if (!https && !(allowLoopback && "http".equalsIgnoreCase(uri.getScheme()))) {
            throw notAllowed(url, "https 만 받는다");
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw notAllowed(url, "이름이 안 풀린다");
        }
        for (InetAddress address : addresses) {
            if (inward(address) && !(allowLoopback && address.isLoopbackAddress())) {
                throw notAllowed(url, "안쪽 주소를 가리킨다: " + address.getHostAddress());
            }
        }
        return uri;
    }

    /** 사설·루프백·링크로컬(클라우드 메타데이터 169.254.169.254 포함)·공유 주소(100.64/10)·고유 로컬(fc00::/7) */
    static boolean inward(InetAddress address) {
        if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                || address.isAnyLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] raw = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = raw[0] & 0xff;
            int second = raw[1] & 0xff;
            return first == 0 || (first == 100 && second >= 64 && second <= 127);
        }
        if (address instanceof Inet6Address) {
            return (raw[0] & 0xfe) == 0xfc;
        }
        return false;
    }

    private static URI parse(String url) {
        try {
            return new URI(url);
        } catch (URISyntaxException e) {
            throw notAllowed(url, "주소 꼴이 아니다");
        }
    }

    private static ShopException notAllowed(String url, String why) {
        return new ShopException(ErrorCode.WEBHOOK_URL_NOT_ALLOWED, why + ": " + url);
    }
}
