package com.projectshop.shop.brokerage;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;
import com.projectshop.shop.support.ListQuery.Paging;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 판매·결제 중개 명세의 관리자 입구 — 분기 목록 · 파일 내려받기 · 제출 기록(`Q456`, 부가가치세법 제75조 `D2` R46).
 *
 * <p>관리자는 전부, 감사자는 읽기만, 셀러는 없다(`V139`). 판정은 {@link BrokerageStatementQuery}·{@link BrokerageStatementService} 가 한다.
 */
@RestController
@RequestMapping("/api/admin/brokerage-statements")
public class BrokerageStatementController {

    private final BrokerageStatementQuery query;
    private final BrokerageStatementService statements;

    BrokerageStatementController(BrokerageStatementQuery query, BrokerageStatementService statements) {
        this.query = query;
        this.statements = statements;
    }

    /** 분기 목록. 최근 분기가 먼저다 */
    @GetMapping
    public BrokerageStatementQuery.StatementPage list(@AuthenticationPrincipal ShopUser user,
            @ParameterObject Paging paging) {
        return query.findAll(user.id(), paging);
    }

    /** 분기 하나와 지금 파일의 해시. 제출의 {@code Location} 이 가리키는 자리다 */
    @GetMapping("/{brokerageStatementId}")
    public BrokerageStatementQuery.StatementDetail one(@AuthenticationPrincipal ShopUser user,
            @PathVariable long brokerageStatementId) {
        return query.find(user.id(), brokerageStatementId);
    }

    /** 서식 순서 CSV(`D5` 「파일 응답」). 받을 때마다 감사 기록이 남는다 */
    @GetMapping(value = "/{brokerageStatementId}/file", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> file(@AuthenticationPrincipal ShopUser user,
            @PathVariable long brokerageStatementId) {
        byte[] file = query.file(user.id(), brokerageStatementId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename("brokerage-statement-" + query.fileName(brokerageStatementId) + ".csv", StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(file);
    }

    /** 제출 기록. 화면이 본 파일의 해시를 같이 보낸다 */
    @PostMapping("/{brokerageStatementId}/submission")
    public ResponseEntity<Void> submit(@AuthenticationPrincipal ShopUser user,
            @PathVariable long brokerageStatementId, @Valid @RequestBody SubmissionRequest request) {
        statements.submit(user.id(), brokerageStatementId, request.fileSha256());
        return ResponseEntity.created(URI.create("/api/admin/brokerage-statements/" + brokerageStatementId)).build();
    }

    /** 제출 요청 — 낸 파일의 sha256(소문자 16진 64자) */
    public record SubmissionRequest(@NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String fileSha256) {
    }
}
