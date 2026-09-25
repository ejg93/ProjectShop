package com.projectshop.shop.webhook;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
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
 * <p><b>검사한 주소가 결과에 실린다</b>({@link Target}, `Q210`). 발송기는 그 주소에만 연결한다 — 이름을 다시 풀지 않아서
 * 검사와 연결 사이에 DNS 가 바뀌는 리바인딩이 안 먹는다. 검사 없이 보낼 길도 없다(보내는 입구가 {@code Target} 만 받는다).
 *
 * <p><b>루프백 허용은 한 칸이다</b>({@code shop.webhook.allow-loopback}). 기본은 거짓이고 시험 기반 클래스만 켠다 — 시험은
 * 로컬 HTTP 서버로 받는다. 켜도 풀리는 것은 루프백뿐이고 사설·링크로컬·메타데이터 대역은 그대로 막는다. {@code https} 강제도
 * 같이 풀린다(로컬 서버는 {@code http} 다).
 */
@Component
class WebhookUrlPolicy {

    /**
     * 검사를 지난 주소. {@code addresses} 가 그때 이름이 가리킨 주소 전부이고, 발송기는 이것에만 연결한다.
     */
    record Target(URI uri, List<InetAddress> addresses) {

        Target {
            addresses = List.copyOf(addresses);
        }
    }

    /** 이름을 주소로 푼다. 시험이 「검사 뒤에 이름이 바뀐다」를 만들려고 바꿔 끼운다 */
    @FunctionalInterface
    interface NameResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final boolean allowLoopback;
    private final NameResolver names;

    @Autowired
    WebhookUrlPolicy(@Value("${shop.webhook.allow-loopback:false}") boolean allowLoopback) {
        this(allowLoopback, InetAddress::getAllByName);
    }

    WebhookUrlPolicy(boolean allowLoopback, NameResolver names) {
        this.allowLoopback = allowLoopback;
        this.names = names;
    }

    /** 받을 수 있는 주소면 검사한 주소와 함께 돌려준다. 아니면 {@code WEBHOOK_URL_NOT_ALLOWED}(422) 다 */
    Target require(String url) {
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
            addresses = names.resolve(host);
        } catch (UnknownHostException e) {
            throw notAllowed(url, "이름이 안 풀린다");
        }
        for (InetAddress address : addresses) {
            if (inward(address) && !(allowLoopback && address.isLoopbackAddress())) {
                throw notAllowed(url, "안쪽 주소를 가리킨다: " + address.getHostAddress());
            }
        }
        return new Target(uri, List.of(addresses));
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
