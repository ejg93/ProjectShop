package com.projectshop.shop.webhook;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectshop.shop.audit.AuditLog;
import com.projectshop.shop.auth.PermissionEvaluator;
import com.projectshop.shop.auth.PermissionEvaluator.Target;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.support.EnumValue;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 셀러 웹훅 엔드포인트를 등록·조회·삭제한다(`29`).
 *
 * <p><b>시크릿은 서버가 만들고 등록 응답에 한 번만 싣는다</b>(Standard Webhooks 의 {@code whsec_} + base64). 표에는
 * 암호문만 있어서 다시 달라고 해도 못 준다 — 잃으면 지우고 다시 건다.
 *
 * <p><b>셀러당 {@value #MAX_ENDPOINTS_PER_SELLER} 개</b>다. 한 사건이 엔드포인트 수만큼 나가서, 상한이 없으면 한 셀러가
 * 발송 표와 바깥 요청을 무한히 늘린다. 세는 동안 그 셀러 행을 잠근다 — 동시에 두 번 걸어 여섯이 되는 틈을 막는다.
 */
@Service
public class WebhookEndpointService {

    static final int MAX_ENDPOINTS_PER_SELLER = 5;

    private static final String SECRET_PREFIX = "whsec_";
    private static final int SECRET_BYTES = 32;

    /** 엔드포인트 한 줄. 시크릿은 없다 — 등록 응답에만 한 번 나간다 */
    @Schema(name = "WebhookEndpoint")
    public record Endpoint(long webhookEndpointId, long sellerId, String url, List<String> eventTypes,
            OffsetDateTime createdAt) {
    }

    @Schema(name = "WebhookEndpointList")
    public record Endpoints(List<Endpoint> items) {
    }

    /** @param secret {@code whsec_} 로 시작한다. <b>이 응답에만 있다</b> */
    @Schema(name = "WebhookEndpointCreated")
    public record Created(long webhookEndpointId, String secret) {
    }

    private final JdbcClient jdbc;
    private final PermissionEvaluator evaluator;
    private final WebhookSecretCipher cipher;
    private final WebhookUrlPolicy urls;
    private final AuditLog auditLog;
    private final SecureRandom random = new SecureRandom();

    WebhookEndpointService(JdbcClient jdbc, PermissionEvaluator evaluator, WebhookSecretCipher cipher,
            WebhookUrlPolicy urls, AuditLog auditLog) {
        this.jdbc = jdbc;
        this.evaluator = evaluator;
        this.cipher = cipher;
        this.urls = urls;
        this.auditLog = auditLog;
    }

    @Transactional
    public Created register(long userId, long sellerId, String url, Set<WebhookEventType> eventTypes) {
        requireManage(userId, sellerId);
        if (!cipher.available()) {
            throw new ShopException(ErrorCode.WEBHOOK_KEY_MISSING);
        }
        urls.require(url);

        jdbc.sql("select seller_id from seller where seller_id = :id for update")
                .param("id", sellerId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new ShopException(ErrorCode.WEBHOOK_FORBIDDEN));
        long count = jdbc.sql("select count(*) from webhook_endpoint where seller_id = :id")
                .param("id", sellerId)
                .query(Long.class)
                .single();
        if (count >= MAX_ENDPOINTS_PER_SELLER) {
            throw new ShopException(ErrorCode.WEBHOOK_ENDPOINT_LIMIT,
                    "셀러 %d 의 엔드포인트가 이미 %d 개다".formatted(sellerId, count));
        }

        byte[] secret = new byte[SECRET_BYTES];
        random.nextBytes(secret);
        long id;
        try {
            id = jdbc.sql("""
                            insert into webhook_endpoint (seller_id, url, event_types,
                                                          secret_ciphertext, secret_key_version)
                            values (:sellerId, :url, cast(:types as text[]), :ciphertext, :keyVersion)
                            returning webhook_endpoint_id
                            """)
                    .param("sellerId", sellerId)
                    .param("url", url)
                    .param("types", eventTypes.stream().map(WebhookEventType::code).sorted().toArray(String[]::new))
                    .param("ciphertext", cipher.encrypt(secret))
                    .param("keyVersion", cipher.keyVersion())
                    .query(Long.class)
                    .single();
        } catch (DuplicateKeyException e) {
            throw new ShopException(ErrorCode.WEBHOOK_ENDPOINT_DUPLICATE, "이미 건 주소다: " + url);
        }

        auditLog.record(AuditLog.Kind.OUTCOME, "webhook.endpoint_registered", userId,
                AuditLog.Target.of("webhook_endpoint", id), Map.of("seller_id", sellerId));
        return new Created(id, SECRET_PREFIX + Base64.getEncoder().encodeToString(secret));
    }

    /** 그 셀러의 엔드포인트. 권한이 없으면 403 이다 — 셀러는 공개된 자원이라 존재를 숨길 것이 없다(`D5`) */
    public Endpoints list(long userId, long sellerId) {
        requireManage(userId, sellerId);
        return new Endpoints(jdbc.sql(SELECT + " where seller_id = :sellerId order by webhook_endpoint_id")
                .param("sellerId", sellerId)
                .query((rs, rowNum) -> endpointOf(rs))
                .list());
    }

    /** 엔드포인트 하나. 못 보는 것은 없는 것과 같은 404 다 */
    public Endpoint find(long userId, long endpointId) {
        Endpoint endpoint = jdbc.sql(SELECT + " where webhook_endpoint_id = :id")
                .param("id", endpointId)
                .query((rs, rowNum) -> endpointOf(rs))
                .optional()
                .orElseThrow(() -> notFound(endpointId));
        if (!allowed(userId, endpoint.sellerId())) {
            throw notFound(endpointId);
        }
        return endpoint;
    }

    @Transactional
    public void delete(long userId, long endpointId) {
        Endpoint endpoint = find(userId, endpointId);
        jdbc.sql("delete from webhook_endpoint where webhook_endpoint_id = :id")
                .param("id", endpointId)
                .update();
        auditLog.record(AuditLog.Kind.OUTCOME, "webhook.endpoint_deleted", userId,
                AuditLog.Target.of("webhook_endpoint", endpointId), Map.of("seller_id", endpoint.sellerId()));
    }

    private static final String SELECT = """
            select webhook_endpoint_id, seller_id, url, event_types, created_at
              from webhook_endpoint
            """;

    private static Endpoint endpointOf(java.sql.ResultSet rs) throws java.sql.SQLException {
        String[] stored = (String[]) rs.getArray("event_types").getArray();
        return new Endpoint(
                rs.getLong("webhook_endpoint_id"),
                rs.getLong("seller_id"),
                rs.getString("url"),
                Arrays.stream(stored).map(code -> EnumValue.of(code, WebhookEventType::of)).toList(),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private void requireManage(long userId, long sellerId) {
        if (!allowed(userId, sellerId)) {
            throw new ShopException(ErrorCode.WEBHOOK_FORBIDDEN);
        }
    }

    private boolean allowed(long userId, long sellerId) {
        return evaluator.decide(userId, "webhook", "manage", Target.ofSeller(sellerId)).allowed();
    }

    private static ShopException notFound(long endpointId) {
        return new ShopException(ErrorCode.WEBHOOK_ENDPOINT_NOT_FOUND, "그런 웹훅 엔드포인트가 없다: " + endpointId);
    }
}
