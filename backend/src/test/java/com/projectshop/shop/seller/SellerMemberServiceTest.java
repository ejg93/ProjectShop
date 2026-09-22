package com.projectshop.shop.seller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

/**
 * 셀러 초대와 멤버십(`5a`).
 *
 * <p>고정하는 것이 셋이다 — <b>소속과 역할이 같이 들어간다</b>는 것,
 * <b>같은 토큰이 두 번 안 먹는다</b>는 것, 그리고 <b>초대로 전역 역할을 못 준다</b>는 것.
 * 마지막 둘은 앱이 아니라 DB 가 든다(부분 유니크 인덱스와 트리거).
 */
@DisplayName("셀러 초대와 멤버십")
class SellerMemberServiceTest extends PostgresTestBase {

    @Autowired
    private SellerMemberService service;

    @Autowired
    private JdbcClient jdbc;

    private AuthFixture fixture;
    private long seller;
    private long owner;
    private long invitee;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);

        seller = fixture.insertSeller("id-a", "A셀러");
        owner = fixture.insertUser("owner@example.com", "대표");
        fixture.joinSeller(seller, owner);
        fixture.grantOrg(owner, "seller_owner", seller);

        invitee = fixture.insertUser("staff@example.com", "담당자");
    }

    @Nested
    @DisplayName("수락")
    class Accept {

        @Test
        @DisplayName("소속과 역할이 같이 생긴다")
        void 소속과_역할이_같이_생긴다() {
            var invitation = service.invite(seller, "staff@example.com", "seller_staff", owner);

            service.accept(invitation.token(), invitee);

            assertThat(memberCount(seller, invitee)).isEqualTo(1);
            assertThat(orgRoleCount(invitee, "seller_staff", seller)).isEqualTo(1);
        }

        @Test
        @DisplayName("같은 토큰은 두 번 안 먹는다")
        void 같은_토큰은_두_번_안_먹는다() {
            var invitation = service.invite(seller, "staff@example.com", "seller_staff", owner);
            service.accept(invitation.token(), invitee);

            assertThatThrownBy(() -> service.accept(invitation.token(), invitee))
                    .isInstanceOf(ShopException.class)
                    .extracting(error -> ((ShopException) error).code())
                    .isEqualTo(ErrorCode.SELLER_INVITATION_INVALID);
        }

        @Test
        @DisplayName("주소가 다른 계정은 못 받는다")
        void 주소가_다른_계정은_못_받는다() {
            long stranger = fixture.insertUser("stranger@example.com", "남");
            var invitation = service.invite(seller, "staff@example.com", "seller_staff", owner);

            assertThatThrownBy(() -> service.accept(invitation.token(), stranger))
                    .isInstanceOf(ShopException.class);

            assertThat(memberCount(seller, stranger)).isZero();
        }

        @Test
        @DisplayName("거둔 초대는 못 받는다")
        void 거둔_초대는_못_받는다() {
            var invitation = service.invite(seller, "staff@example.com", "seller_staff", owner);
            service.revoke(invitation.invitationId(), owner);

            assertThatThrownBy(() -> service.accept(invitation.token(), invitee))
                    .isInstanceOf(ShopException.class);
        }
    }

    @Nested
    @DisplayName("DB 가 드는 것")
    class Enforced {

        /**
         * 앱에서만 세면 두 번 눌러 두 건이 되고, 그러면 하나를 거둬도 나머지로 들어온다.
         */
        @Test
        @DisplayName("살아 있는 초대는 셀러·주소 당 하나다")
        void 살아_있는_초대는_셀러_주소_당_하나다() {
            service.invite(seller, "staff@example.com", "seller_staff", owner);

            assertThatThrownBy(
                    () -> service.invite(seller, "STAFF@example.com", "seller_staff", owner))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("거둔 뒤에는 다시 부를 수 있다")
        void 거둔_뒤에는_다시_부를_수_있다() {
            var first = service.invite(seller, "staff@example.com", "seller_staff", owner);
            service.revoke(first.invitationId(), owner);

            var second = service.invite(seller, "staff@example.com", "seller_staff", owner);

            assertThat(second.invitationId()).isNotEqualTo(first.invitationId());
        }

        /**
         * 셀러 하나를 가리키는 초대가 전역 권한을 주면 조직 경계가 통째로 없어진다.
         * {@code check} 로 못 내린다 — 다른 표를 봐야 해서 트리거가 막는다.
         */
        @Test
        @DisplayName("초대로 전역 역할을 못 준다")
        void 초대로_전역_역할을_못_준다() {
            // 트리거의 `raise exception` 은 P0001 이라 무결성 위반으로 분류가 안 된다.
            // 그 상위인 DataAccessException 으로 잡는다(`ConsentSchemaTest` 와 같은 자리).
            assertThatThrownBy(() -> service.invite(seller, "staff@example.com", "admin", owner))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("조직 역할만");
        }
    }

    @Nested
    @DisplayName("담당자와 대표를 가르는 것")
    class StaffVersusOwner {

        @Test
        @DisplayName("담당자는 멤버를 못 바꾼다")
        void 담당자는_멤버를_못_바꾼다() {
            assertThat(hasPermission("seller_staff", "seller_member", "manage")).isFalse();
            assertThat(hasPermission("seller_owner", "seller_member", "manage")).isTrue();
        }

        /**
         * `V3` 주석이 이 청크를 지목해 뒀다 — "내가 등록한 상품만" 이 담당자 축이다.
         */
        @Test
        @DisplayName("담당자의 상품 수정은 자기가 등록한 것만이다")
        void 담당자의_상품_수정은_자기가_등록한_것만이다() {
            assertThat(scopeOf("seller_staff", "product", "update")).isEqualTo("own");
            assertThat(scopeOf("seller_owner", "product", "update")).isEqualTo("seller");
        }
    }

    private int memberCount(long sellerId, long userId) {
        return jdbc.sql("""
                        select count(*) from seller_member
                         where seller_id = :sellerId and user_id = :userId
                        """)
                .param("sellerId", sellerId)
                .param("userId", userId)
                .query(Integer.class)
                .single();
    }

    private int orgRoleCount(long userId, String roleCode, long sellerId) {
        return jdbc.sql("""
                        select count(*) from user_role ur
                          join role r on r.role_id = ur.role_id
                         where ur.user_id = :userId and r.code = :code and ur.seller_id = :sellerId
                        """)
                .param("userId", userId)
                .param("code", roleCode)
                .param("sellerId", sellerId)
                .query(Integer.class)
                .single();
    }

    private boolean hasPermission(String roleCode, String resource, String action) {
        return jdbc.sql("""
                        select exists(
                            select 1 from role_permission rp
                              join role r on r.role_id = rp.role_id
                              join permission p on p.permission_id = rp.permission_id
                             where r.code = :code and p.resource = :resource and p.action = :action
                               and rp.effect = 'allow'
                        )
                        """)
                .param("code", roleCode)
                .param("resource", resource)
                .param("action", action)
                .query(Boolean.class)
                .single();
    }

    private String scopeOf(String roleCode, String resource, String action) {
        return jdbc.sql("""
                        select rp.scope from role_permission rp
                          join role r on r.role_id = rp.role_id
                          join permission p on p.permission_id = rp.permission_id
                         where r.code = :code and p.resource = :resource and p.action = :action
                        """)
                .param("code", roleCode)
                .param("resource", resource)
                .param("action", action)
                .query(String.class)
                .single();
    }
}
