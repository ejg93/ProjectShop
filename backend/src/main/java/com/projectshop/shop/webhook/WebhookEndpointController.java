package com.projectshop.shop.webhook;

import java.net.URI;
import java.util.Set;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;

/**
 * 셀러 웹훅 엔드포인트의 입구(`29`). 대표만 자기 셀러에 건다({@code webhook:manage}, `V110`).
 */
@RestController
@RequestMapping("/api/seller/webhooks")
public class WebhookEndpointController {

    private final WebhookEndpointService endpoints;

    WebhookEndpointController(WebhookEndpointService endpoints) {
        this.endpoints = endpoints;
    }

    /**
     * 등록 요청.
     *
     * @param url        받을 주소. {@code https} 만이고 안쪽 대역을 가리키면 422 다(`WebhookUrlPolicy`)
     * @param eventTypes 구독할 사건. 넷 중에서 고른다({@link WebhookEventType})
     */
    public record RegisterRequest(
            @NotNull Long sellerId,
            @NotBlank @Size(max = 2000) String url,
            @NotEmpty @Size(max = 4) Set<WebhookEventType> eventTypes) {
    }

    @GetMapping
    public WebhookEndpointService.Endpoints list(@AuthenticationPrincipal ShopUser user,
            @RequestParam long sellerId) {
        return endpoints.list(user.id(), sellerId);
    }

    /** 걸고 시크릿을 받는다. <b>시크릿은 이 응답에만 있다</b> */
    @PostMapping
    public ResponseEntity<WebhookEndpointService.Created> register(@AuthenticationPrincipal ShopUser user,
            @Valid @RequestBody RegisterRequest request) {

        WebhookEndpointService.Created created = endpoints.register(user.id(), request.sellerId(),
                request.url(), request.eventTypes());
        return ResponseEntity.created(URI.create("/api/seller/webhooks/" + created.webhookEndpointId()))
                .body(created);
    }

    @GetMapping("/{webhookEndpointId}")
    public WebhookEndpointService.Endpoint find(@AuthenticationPrincipal ShopUser user,
            @PathVariable long webhookEndpointId) {
        return endpoints.find(user.id(), webhookEndpointId);
    }

    @DeleteMapping("/{webhookEndpointId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal ShopUser user, @PathVariable long webhookEndpointId) {
        endpoints.delete(user.id(), webhookEndpointId);
        return ResponseEntity.noContent().build();
    }
}
