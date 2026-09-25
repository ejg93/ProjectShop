package com.projectshop.shop.product;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.List;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;
import com.projectshop.shop.support.ImagePipeline;
import com.projectshop.shop.support.ListQuery.Paging;

/**
 * 셀러가 자기 상품을 보는 입구.
 *
 * <p>{@code /api/products} 와 <b>경로를 가른 이유</b>는 조건의 성격이 달라서다.
 * 그쪽은 사용자를 안 받고 파는 중인 것만 주지만, 여기는 <b>보는 사람에 따라 답이 달라진다.</b>
 *
 * <p>둘을 한 경로에 두면 비로그인 분기까지 한 쿼리에 섞이고, 조건 하나가 틀리면
 * 공개 목록으로 {@code draft} 가 샌다. 그 실수는 조용해서 못 잡는다.
 */
@RestController
@RequestMapping("/api/seller/products")
public class SellerProductController {

    private final ProductQuery productQuery;
    private final ProductImageService productImageService;

    SellerProductController(ProductQuery productQuery, ProductImageService productImageService) {
        this.productQuery = productQuery;
        this.productImageService = productImageService;
    }

    /**
     * 팔기 전 상태와 재고가 같이 나온다.
     *
     * @param sellerId 여러 셀러에 속한 사람이 하나로 좁힐 때 쓴다. 안 주면 볼 수 있는 전부
     * @param status   그 상태만(대문자, `Q182`). 관리자의 검수 대기 목록이 {@code PENDING_REVIEW} 로 부른다 —
     *                 관리자는 {@code all} 이라 이 목록으로 전체가 보여서 따로 입구를 안 팠다
     */
    @GetMapping
    public ProductQuery.SellerPage list(
            @AuthenticationPrincipal ShopUser user,
            @RequestParam(name = "seller_id", required = false) Long sellerId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort,
            @ParameterObject Paging paging) {

        return productQuery.findForSeller(user.id(), sellerId, status, sort, paging);
    }

    /**
     * 자기 상품의 사진을 훑는다(`Q139`).
     *
     * <p><b>공개 상세로는 이 화면을 못 만든다.</b> 그쪽은 서명 URL 목록만 주는데
     * 지우는 입구가 {@code productImageId} 를 받아서, 그 번호를 내주는 자리가 없으면
     * <b>올리기만 하고 지울 수 없는 화면</b>이 된다.
     *
     * <p>경로가 상품 아래다 — 올리는 입구와 같은 모양이고, 어느 상품의 것인지를 경로가 든다.
     */
    @GetMapping("/{productId}/images")
    public List<ProductImageService.Image> images(
            @AuthenticationPrincipal ShopUser user,
            @PathVariable long productId) {

        return productImageService.find(user.id(), productId);
    }

    /**
     * 자기 상품에 사진을 올린다({@code 27}).
     *
     * <p><b>경로가 상품 아래다.</b> 사진은 상품 없이 존재하지 않는다 — 어느 상품의 것인지를
     * 본문이 아니라 경로가 들면, 그 값을 빠뜨린 요청이 <b>성립하지 않는다</b>.
     *
     * <p>판정은 {@link ProductImageService} 가 한다. 남의 상품이면 403 이다.
     */
    @PostMapping("/{productId}/images")
    public ResponseEntity<ProductImageService.Uploaded> upload(
            @AuthenticationPrincipal ShopUser user,
            @PathVariable long productId,
            @RequestPart("file") MultipartFile file) {

        // `Location` 은 그 사진을 드는 GET 이다 — 위 `images`(`Q139`). 공개 상세는 파는 중인 상품만 열어서
        // 초안에 올린 사진을 가리키면 404 다(`Q227`, 마무리 51차 독립 리뷰, `D5` 「상태 코드」).
        return ResponseEntity.created(URI.create("/api/seller/products/" + productId + "/images"))
                .body(productImageService.upload(user.id(), productId, incoming(file)));
    }

    /**
     * 자기 상품의 사진을 지운다({@code Q95}). <b>저장소의 객체까지 사라진다.</b>
     *
     * <p>경로가 상품 아래가 아니라 사진 번호 하나다 — 사진은 이미 어느 상품의 것인지를
     * 자기 행에 들고 있어서, 상품 번호를 또 받으면 <b>둘이 어긋난 요청</b>이 성립한다.
     */
    @DeleteMapping("/images/{productImageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteImage(
            @AuthenticationPrincipal ShopUser user,
            @PathVariable long productImageId) {

        productImageService.delete(user.id(), productImageId);
    }

    /**
     * <b>웹 타입이 여기서 끝난다</b>({@code D23} 「계층」). 서비스는 이름과 바이트만 받는다 —
     * 그래야 같은 규칙을 HTTP 가 아닌 자리(배치·이관)에서도 쓴다.
     */
    private static ImagePipeline.Incoming incoming(MultipartFile file) {
        try {
            return new ImagePipeline.Incoming(file.getOriginalFilename(), file.getBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
