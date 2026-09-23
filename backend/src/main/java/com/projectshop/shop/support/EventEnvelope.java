package com.projectshop.shop.support;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * CloudEvents 1.0 봉투를 만든다({@code event-catalog.md} 「봉투」). <b>한 곳이다</b> — Kafka 발행기와 웹훅 발송기(`30`)가 같이 쓴다.
 * 둘이 각자 만들면 봉투가 두 벌이 되고 한쪽만 고쳐진다.
 *
 * <p><b>표기가 둘이다</b>(`30`, 2026-09-23 사용자 결정). 안쪽(Kafka)은 저장값 그대로 — 소비자({@code NotificationConsumer})가
 * 소문자 저장값으로 가른다. 바깥(웹훅)은 <b>API 와 같게 열거값을 대문자로</b> 낸다(`D5` 「값의 형식」) — 셀러가 API 로 다시 읽을 때
 * 같은 값이 두 표기로 보이지 않게. 트리거는 안 건드린다({@code event-catalog.md} 「표기」).
 */
public final class EventEnvelope {

    /** 봉투의 {@code time} 은 UTC `Z` 다({@code D10}, {@code event-catalog.md} 「봉투」) */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ISO_INSTANT;
    private static final String SPEC_VERSION = "1.0";
    private static final String DATA_CONTENT_TYPE = "application/json";

    /** {@code data} 에서 열거값인 칸. 트리거가 만드는 칸 목록에서 셌다({@code V76}) */
    static final Set<String> ENUM_KEYS = Set.of("from_status", "to_status", "actor_type", "status", "reason_code");

    private EventEnvelope() {
    }

    /**
     * @param apiCase 참이면 {@link #ENUM_KEYS} 의 값을 대문자로 바꾼다(웹훅). 거짓이면 저장값 그대로다(Kafka)
     */
    public static String of(ObjectMapper mapper, long id, String type, String source, String subject,
            OffsetDateTime occurredAt, String data, boolean apiCase) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("specversion", SPEC_VERSION);
        envelope.put("id", String.valueOf(id));
        envelope.put("source", source);
        envelope.put("type", type);
        envelope.put("subject", subject);
        envelope.put("time", TIME.format(occurredAt.toInstant()));
        envelope.put("datacontenttype", DATA_CONTENT_TYPE);

        JsonNode body = mapper.readTree(data);
        if (apiCase && body instanceof ObjectNode object) {
            for (String key : ENUM_KEYS) {
                JsonNode value = object.get(key);
                if (value != null && value.isString()) {
                    object.put(key, value.asString().toUpperCase(Locale.ROOT));
                }
            }
        }
        envelope.set("data", body);
        return envelope.toString();
    }
}
