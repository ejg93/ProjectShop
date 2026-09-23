package com.projectshop.shop.product;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.support.ListQuery.Paging;

/**
 * 상품 목록이 싣는 허용 동작과 검수 요청(`Q182`).
 *
 * <p><b>화면은 이 이름으로만 버튼을 그린다.</b> 그래서 이름이 서버의 전이표·판정과 같은지를 여기서 잰다 —
 * 화면이 상태를 보고 버튼을 고르면 표가 두 벌이 된다.
 */
@DisplayName("상품 검수 동작")
class ProductReviewActionsTest extends PostgresTestBase {

    private static final Paging FIRST = new Paging(0, 20);

    @Autowired
    private ProductQuery query;

    @Autowired
    private ProductReviewService reviews;

    @Autowired
    private JdbcClient jdbc;

    private long seller;
    private long owner;
    private long staff;
    private long admin;

    @BeforeEach
    void setUp() {
        AuthFixture fixture = new AuthFixture(jdbc);
        seller = fixture.insertSeller("actions-seller", "동작셀러");
        fixture.verifySeller(seller);

        owner = fixture.insertUser("act-owner@test.local", "대표");
        fixture.joinSeller(seller, owner);
        fixture.grantOrg(owner, "seller_owner", seller);

        staff = fixture.insertUser("act-staff@test.local", "담당자");
        fixture.joinSeller(seller, staff);
        fixture.grantOrg(staff, "seller_staff", seller);

        admin = fixture.insertUser("act-admin@test.local", "관리자");
        fixture.grantGlobal(admin, "admin");
    }

    @Test
    @DisplayName("초안에는 셀러에게 검수 요청이, 검수 대기에는 관리자에게 승인·반려가 실린다")
    void listCarriesActionsFromTheTransitionTable() {
        long draft = insertProduct(owner, "초안");
        long pending = insertProduct(owner, "검수 대기");
        jdbc.sql("update product set status = 'pending_review' where product_id = :id")
                .param("id", pending).update();

        assertThat(actionsOf(owner, draft)).containsExactly("SUBMIT_REVIEW");
        assertThat(actionsOf(owner, pending))
                .as("셀러는 자기 상품을 승인하지 못한다 — 검수는 관리자다")
                .isEmpty();
        assertThat(actionsOf(admin, pending)).containsExactlyInAnyOrder("APPROVE", "REJECT");
    }

    @Test
    @DisplayName("관리자는 검수 대기만 걸러 본다")
    void adminFiltersPendingReview() {
        insertProduct(owner, "초안");
        long pending = insertProduct(owner, "검수 대기");
        jdbc.sql("update product set status = 'pending_review' where product_id = :id")
                .param("id", pending).update();

        assertThat(query.findForSeller(admin, null, "PENDING_REVIEW", null, FIRST).items())
                .extracting(ProductQuery.SellerItem::productId)
                .containsExactly(pending);
    }

    /**
     * 검수 서비스가 판정 대상에 등록자를 안 실어서 담당자의 {@code own} 이 아무것도 안 덮었다 —
     * <b>자기가 등록한 상품도 검수 요청을 못 했다</b>(`Q161` 이 사진에서 고친 것과 같은 구멍).
     */
    @Test
    @DisplayName("담당자는 자기가 등록한 상품의 검수를 요청한다")
    void staffSubmitsOwnProduct() {
        long mine = insertProduct(staff, "담당자가 올린 것");

        assertThat(actionsOf(staff, mine)).containsExactly("SUBMIT_REVIEW");
        reviews.submit(staff, mine);

        assertThat(jdbc.sql("select status from product where product_id = :id")
                .param("id", mine).query(String.class).single()).isEqualTo("pending_review");
    }

    private java.util.List<String> actionsOf(long viewer, long productId) {
        return query.findForSeller(viewer, null, null, null, FIRST).items().stream()
                .filter(item -> item.productId() == productId)
                .findFirst()
                .orElseThrow()
                .allowedActions();
    }

    private long insertProduct(long createdBy, String name) {
        return jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name)
                        values (:sellerId, :userId, :name)
                        returning product_id
                        """)
                .param("sellerId", seller)
                .param("userId", createdBy)
                .param("name", name)
                .query(Long.class)
                .single();
    }
}
