package com.projectshop.shop.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;

/**
 * 표시·광고의 근거를 등록 시점에 받는가(청크 13f-1, `D2` R32).
 *
 * <p>표시광고법 제5조가 「사실과 관련한 사항은 실증할 수 있어야 한다」고 하고
 * 공정위가 요청하면 <b>15일 안에</b> 내야 한다. 등록 시점에 안 받으면
 * <b>그 문구를 쓴 사람이 떠난 뒤에 근거를 찾게 된다.</b>
 *
 * <p><b>없어도 막지 않는다.</b> 사실 주장이 없는 상품도 있어서 비어 있는 것 자체는 잘못이 아니다 —
 * 있어야 하는데 없는 것을 가르려면 문구에서 주장을 뽑아내야 하고, 그것은 사람의 판단이다.
 */
@DisplayName("표시·광고 실증자료")
class ProductSubstantiationTest extends PostgresTestBase {

    @Autowired
    private ProductService products;

    @Autowired
    private JdbcClient jdbc;

    private long sellerId;
    private long userId;

    @BeforeEach
    void setUp() {
        AuthFixture fixture = new AuthFixture(jdbc);
        sellerId = fixture.insertSeller("proof-seller", "근거셀러");
        fixture.verifySeller(sellerId);

        userId = fixture.insertUser("proof-owner@test.local", "근거주인");
        fixture.joinSeller(sellerId, userId);
        fixture.grantOrg(userId, "seller_owner", sellerId);
    }

    @Test
    @DisplayName("주장마다 근거가 한 줄씩 남는다")
    void keepsOneRowPerClaim() {
        long productId = products.create(userId, command(List.of(
                new ProductService.SubstantiationCommand("국내 1위", "2026 시장조사 보고서", "https://x.test/1"),
                new ProductService.SubstantiationCommand("3년 보증", "보증약관 제4조", null)))).productId();

        assertThat(claimsOf(productId))
                .describedAs("한 칸에 섞으면 제출 요구가 왔을 때 어느 부분이 답인지 사람이 다시 가른다")
                .containsExactlyInAnyOrder("국내 1위", "3년 보증");
    }

    @Test
    @DisplayName("근거가 없어도 등록된다")
    void allowsProductWithoutClaims() {
        long productId = products.create(userId, command(List.of())).productId();

        // 제5조는 「요청이 오면 낼 수 있어야」지 「모든 상품에 있어야」가 아니다.
        assertThat(claimsOf(productId)).isEmpty();
    }

    @Test
    @DisplayName("같은 주장을 두 번 못 적는다")
    void rejectsDuplicateClaim() {
        assertThatThrownBy(() -> products.create(userId, command(List.of(
                new ProductService.SubstantiationCommand("정품", "수입 계약서", null),
                new ProductService.SubstantiationCommand("정품", "다른 근거", null)))))
                .describedAs("어느 것이 최신인지 안 갈린다")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private ProductService.Command command(List<ProductService.SubstantiationCommand> proofs) {
        return new ProductService.Command(sellerId, "근거 시험 상품", "설명", null, false, null, 3,
                List.of(), List.of(new ProductService.SkuCommand(List.of(), 10_000L, 5)), proofs);
    }

    private List<String> claimsOf(long productId) {
        return jdbc.sql("select claim from product_substantiation where product_id = :id")
                .param("id", productId)
                .query(String.class)
                .list();
    }
}
