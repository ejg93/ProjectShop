package com.projectshop.shop.notification;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 광고성 정보를 보내는 유일한 입구다(청크 55, 정보통신망법 제50조).
 *
 * <p><b>거래 통지와 입구를 갈랐다.</b> 한 메서드에서 종류로 분기하면 그것은 앱 검증이고
 * (`D23` 축 2의 3순위) 새 입구가 생길 때 빠뜨린다. 입구가 둘이면 <b>거래 통지 코드에서
 * 동의를 볼 방법 자체가 없다</b> — {@link NotificationService} 는 {@link AdvertisingGate} 를 안 든다.
 *
 * <p><b>보내는 청크는 아직 없다.</b> 이 클래스가 만드는 것은 광고가 지나야 하는 관문이고,
 * 실제로 보내는 자리는 광고를 보내기로 정할 때 선다(`D18` 「지금 안 하는 것」).
 */
@Service
public class AdvertisingNotifications {

    /** 광고는 사건이 하나다. 문안은 캠페인마다 갈리지만 법이 보는 눈은 같다 */
    private static final String EVENT = "advertisement";

    /**
     * 광고 본문이 반드시 들고 있어야 하는 자리.
     *
     * <p>제50조제4항이 전송자의 <b>명칭과 연락처</b>, 그리고 <b>수신거부·철회 의사표시를
     * 쉽게 할 수 있는 조치</b>를 명시하라고 한다. 판에 그 자리가 없으면 본문에 안 들어가고,
     * 안 들어간 채로 나가면 그 발송이 위반이다.
     *
     * <p><b>값이 아니라 판을 본다.</b> 값만 검사하면 판이 그 자리를 안 쓸 때 조용히 빠진다 —
     * 안 쓰이는 값은 아무 데도 안 나타나고 아무것도 안 깨진다.
     */
    private static final List<String> REQUIRED_PLACEHOLDERS =
            List.of("sender_name", "sender_contact", "unsubscribe");

    private static final Logger log = LoggerFactory.getLogger(AdvertisingNotifications.class);

    private final AdvertisingGate gate;
    private final NotificationTemplates templates;
    private final NotificationService notifications;

    AdvertisingNotifications(AdvertisingGate gate, NotificationTemplates templates,
            NotificationService notifications) {
        this.gate = gate;
        this.templates = templates;
        this.notifications = notifications;
    }

    /**
     * 광고를 보낸다.
     *
     * <p><b>막히면 이력을 안 남긴다.</b> 발송 이력은 「보냈다」를 증명하는 자리라
     * 안 보낸 것을 넣으면 그 표의 뜻이 흐려진다(`D18` 「발송 이력」).
     * 막힌 이유는 로그로 남고, 식별자만 적는다(`D16`).
     *
     * <p><b>야간에 막힌 것을 미루지 않는다</b>(사용자 선택). 미루려면 예약 시각 칸과
     * 예약분을 보내는 배치가 필요한데, 광고를 실제로 보내는 청크가 아직 없어서
     * 쓸 데가 없는 자리를 지금 만드는 것이 된다. 시간을 고르는 것은 보내는 쪽 몫이다.
     *
     * @param templateCode 캠페인의 문안 코드
     * @param userId       받을 사람
     * @param values       판이 부르는 자리표시자와 넣을 값
     * @return 남긴 발송 id. 관문에 막혔으면 빈 값
     * @throws MissingRequiredPlaceholderException 판에 제50조제4항의 자리가 빠졌으면
     */
    public Optional<Long> send(String templateCode, long userId, Map<String, String> values) {
        AdvertisingGate.Verdict verdict = gate.check(userId, OffsetDateTime.now());
        if (!verdict.allowed()) {
            log.debug("광고를 안 보낸다 받는이={} 이유={}", userId, verdict);
            return Optional.empty();
        }

        requireLegalPlaceholders(templateCode);
        return notifications.send(EVENT, templateCode, NotificationService.Target.none(),
                userId, values);
    }

    /**
     * 판이 제50조제4항의 자리를 들고 있는지 본다.
     *
     * <p>판을 여기서 한 번 더 읽는다. {@link NotificationService} 안에서 검사하면
     * <b>거래 통지도 같은 검사를 지나게 되고</b>, 그러면 이 규칙이 광고에만 걸린다는 사실이
     * 코드에서 안 드러난다 — 검사가 광고 경로에만 있는 것 자체가 강제 지점이다.
     */
    private void requireLegalPlaceholders(String templateCode) {
        NotificationTemplates.Version version = templates.current(templateCode, OffsetDateTime.now())
                .orElseThrow(() -> new IllegalStateException(
                        "시행 중인 광고 템플릿이 없다: " + templateCode));

        for (String placeholder : REQUIRED_PLACEHOLDERS) {
            if (!version.body().contains("{{" + placeholder + "}}")) {
                throw new MissingRequiredPlaceholderException(templateCode, placeholder);
            }
        }
    }

    /** 광고 판에 법이 요구하는 자리가 빠졌다 */
    public static class MissingRequiredPlaceholderException extends RuntimeException {

        public MissingRequiredPlaceholderException(String templateCode, String placeholder) {
            super("광고 판에 제50조제4항이 요구하는 자리가 없다: " + templateCode + " / " + placeholder);
        }
    }
}
