package com.projectshop.shop.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.catalina.util.ServerInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 클래스패스에 실제로 올라온 Tomcat 이 보안 패치 판 이상인지 본다. 낮으면 실패한다.
 *
 * <p><b>버전을 덮는 줄은 아무것도 안 막는다.</b> {@code build.gradle.kts} 의
 * {@code extra["tomcat.version"]} 은 Boot 를 올리는 다음 청크가 「BOM 이 관리하니 지워도
 * 되겠지」로 지워도 <b>빌드가 초록이고 테스트도 전부 통과한다</b> — 그러면 CVSS 9.8 셋이
 * 조용히 다시 열린다. 그 자리를 강제 지점 4위(테스트)로 내린 것이 이 클래스다.
 *
 * <p><b>빌드 파일이 아니라 런타임을 읽는다.</b> 문자열을 대조하면 「그 줄이 있나」만 보는데,
 * 물어야 하는 것은 <b>해석된 버전이 얼마인가</b>다. BOM 이 나중에 더 높은 값을 주면 그 줄은
 * 없어도 되고, 반대로 그 줄이 있어도 다른 의존성이 아래로 끌어내리면 소용이 없다.
 * {@link ServerInfo#getServerNumber()} 가 {@code tomcat-embed-core} 안의
 * {@code ServerInfo.properties} 를 읽어서 <b>실제로 올라온 판</b>을 준다.
 *
 * <p>이 검사가 빨개지면 고칠 자리는 둘 중 하나다 — {@code tomcat.version} 을 올리거나,
 * 그 값보다 높은 것을 주는 Boot 로 올리고 그 줄을 지운다.
 */
class TomcatVersionTest {

    /**
     * 이 값 미만은 열린 것으로 본다.
     *
     * <p>{@code CVE-2026-68525}·{@code CVE-2026-65905}·{@code CVE-2026-65182} 셋이
     * {@code 11.0.25} 에서 닫혔다. 셋 다 <b>컨테이너가 직접 하는 인증·인가</b>를 치는데
     * (FORM·DIGEST 인증기, {@code web.xml} 의 {@code <security-constraint>} 순서)
     * 이 저장소는 {@code web.xml} 이 없고 인가가 전부 Spring Security 필터라 지금은 안 탄다.
     * <b>「안 탄다」가 지금 코드 기준이라 막는다</b> — 그 경로를 켜는 날 아무도 이 판단을
     * 다시 안 한다.
     */
    private static final String MINIMUM = "11.0.25";

    /** {@code 11.0.25.0} 처럼 뒤에 칸이 더 붙기도 하고, {@code -M1} 같은 꼬리가 붙기도 한다. */
    private static final Pattern SEGMENT = Pattern.compile("\\d+");

    @Test
    @DisplayName("올라온 Tomcat 이 보안 패치 판 이상이다")
    void resolvedTomcatIsAtLeastPatched() {
        String resolved = ServerInfo.getServerNumber();

        assertThat(compare(resolved, MINIMUM))
                .describedAs("Tomcat %s 가 올라왔다. %s 미만은 critical 셋이 열려 있다 — "
                        + "build.gradle.kts 의 tomcat.version 을 올리거나, "
                        + "그보다 높은 값을 주는 Boot 로 올리고 그 줄을 지운다", resolved, MINIMUM)
                .isNotNegative();
    }

    /** 점으로 갈린 숫자 칸을 앞에서부터 견준다. 칸 수가 다르면 짧은 쪽의 없는 칸을 0으로 본다. */
    private static int compare(String left, String right) {
        int[] a = segments(left);
        int[] b = segments(right);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int compared = Integer.compare(i < a.length ? a[i] : 0, i < b.length ? b[i] : 0);
            if (compared != 0) {
                return compared;
            }
        }
        return 0;
    }

    private static int[] segments(String version) {
        Matcher digits = SEGMENT.matcher(version);
        return digits.results().mapToInt(result -> Integer.parseInt(result.group())).toArray();
    }
}
