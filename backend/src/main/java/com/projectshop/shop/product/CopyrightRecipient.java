package com.projectshop.shop.product;

import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 저작권 중단·재개 요구를 받을 수령인을 {@code shop.copyright.recipient.*} 에서 읽어 공지한다(`Q463`, 저작권법 제103조④ ·
 * 시행령 제44조 `D2` R42).
 *
 * <p><b>값은 사람이 넣는다</b>({@code COPYRIGHT_RECIPIENT_*}). 저장소에 지어 넣으면 사실 아닌 고지다 — 사업자 표시를 비워 둔
 * {@code site-footer} 와 같은 원칙이다. 비면 화면이 「지정되지 않았다」고 쓰고 기동 로그가 경고한다.
 */
@Component
public final class CopyrightRecipient {

    private static final Logger log = LoggerFactory.getLogger(CopyrightRecipient.class);

    private final String name;
    private final String department;
    private final String email;
    private final String phone;
    private final String address;

    CopyrightRecipient(@Value("${shop.copyright.recipient.name:}") String name,
            @Value("${shop.copyright.recipient.department:}") String department,
            @Value("${shop.copyright.recipient.email:}") String email,
            @Value("${shop.copyright.recipient.phone:}") String phone,
            @Value("${shop.copyright.recipient.address:}") String address) {
        this.name = blankToNull(name);
        this.department = blankToNull(department);
        this.email = blankToNull(email);
        this.phone = blankToNull(phone);
        this.address = blankToNull(address);
        if (!configured()) {
            log.warn("저작권 수령인이 비어 있다 — 신고 화면이 「수령인이 아직 지정되지 않았습니다」를 낸다"
                    + "(COPYRIGHT_RECIPIENT_NAME·_EMAIL, 저작권법 제103조제4항)");
        }
    }

    /** 성명과 전자우편이 있나 — 시행령 제44조의 표시 중 요구를 받을 수 있는 최소다 */
    boolean configured() {
        return name != null && email != null;
    }

    /** 통보 본문에 싣는 한 줄 — 값 있는 칸만 잇는다 */
    String displayText() {
        String person = department == null || name == null ? name : name + "(" + department + ")";
        return Stream.of(person, email, phone, address)
                .filter(value -> value != null)
                .collect(Collectors.joining(" · "));
    }

    RecipientResponse response() {
        return new RecipientResponse(name, department, email, phone, address);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** 공지할 다섯 칸. 넣지 않은 칸은 {@code null} 이다 */
    public record RecipientResponse(String name, String department, String email, String phone, String address) {
    }
}
