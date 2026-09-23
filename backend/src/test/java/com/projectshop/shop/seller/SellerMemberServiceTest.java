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
            service.revoke(seller, invitation.invitationId(), owner);

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
            service.revoke(seller, first.invitationId(), owner);

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

    /**
     * 조직 경계(`16a`, `Q162` 흡수).
     *
     * <p><b>화면이 셀러 번호를 넘기는 것을 막지 않는다</b> — 막을 수가 없고(주소에 실려 온다)
     * 막을 필요도 없다. 남의 번호를 넣으면 <b>판정이 거부한다.</b>
     *
     * <p>둘째가 판정만으로는 안 막히는 자리다. 판정은 「이 셀러를 다룰 수 있나」를 답했지
     * <b>「이 초대 번호가 그 셀러 것인가」</b>를 안 봤다 — 그것은 조회 조건이 든다.
     */
    @Nested
    @DisplayName("조직 경계")
    class OrgBoundary {

        @Test
        @DisplayName("남의 셀러에는 초대를 못 낸다")
        void 남의_셀러에는_초대를_못_낸다() {
            long other = fixture.insertSeller("id-b", "B셀러");

            assertThatThrownBy(() -> service.invite(other, "x@example.com", "seller_staff", owner))
                    .isInstanceOf(ShopException.class)
                    .extracting(error -> ((ShopException) error).code())
                    .isEqualTo(ErrorCode.SELLER_MEMBER_FORBIDDEN);
        }

        @Test
        @DisplayName("권한 없는 사람은 초대를 못 낸다")
        void 권한_없는_사람은_초대를_못_낸다() {
            long stranger = fixture.insertUser("nobody@example.com", "남");

            assertThatThrownBy(() -> service.invite(seller, "x@example.com", "seller_staff", stranger))
                    .isInstanceOf(ShopException.class)
                    .extracting(error -> ((ShopException) error).code())
                    .isEqualTo(ErrorCode.SELLER_MEMBER_FORBIDDEN);
        }

        /** 번호만 보고 지우면 판정을 지난 사람이 남의 셀러 초대를 거둔다 */
        @Test
        @DisplayName("남의 셀러 초대는 번호를 알아도 못 거둔다")
        void 남의_셀러_초대는_번호를_알아도_못_거둔다() {
            long other = fixture.insertSeller("id-c", "C셀러");
            long otherOwner = fixture.insertUser("owner-c@example.com", "다른대표");
            fixture.joinSeller(other, otherOwner);
            fixture.grantOrg(otherOwner, "seller_owner", other);

            var theirs = service.invite(other, "x@example.com", "seller_staff", otherOwner);

            // 자기 셀러 번호로 부르므로 판정은 지난다. 막는 것은 조회 조건이다.
            assertThatThrownBy(() -> service.revoke(seller, theirs.invitationId(), owner))
                    .isInstanceOf(ShopException.class)
                    .extracting(error -> ((ShopException) error).code())
                    .isEqualTo(ErrorCode.SELLER_INVITATION_INVALID);
        }

        /** 담당자는 멤버를 못 바꾼다 — 그 권한을 안 받는다(`5a`) */
        @Test
        @DisplayName("담당자는 초대를 못 낸다")
        void 담당자는_초대를_못_낸다() {
            long staff = fixture.insertUser("staff-member@example.com", "담당자");
            fixture.joinSeller(seller, staff);
            fixture.grantOrg(staff, "seller_staff", seller);

            assertThatThrownBy(() -> service.invite(seller, "x@example.com", "seller_staff", staff))
                    .isInstanceOf(ShopException.class)
                    .extracting(error -> ((ShopException) error).code())
                    .isEqualTo(ErrorCode.SELLER_MEMBER_FORBIDDEN);
        }

        /** 주소가 실려서다 — 아직 회원이 아닐 수 있는 사람의 개인정보다(`D13`) */
        @Test
        @DisplayName("담당자에게는 초대 목록이 안 보인다")
        void 담당자에게는_초대_목록이_안_보인다() {
            service.invite(seller, "invitee@example.com", "seller_staff", owner);

            long staff = fixture.insertUser("staff-view@example.com", "담당자");
            fixture.joinSeller(seller, staff);
            fixture.grantOrg(staff, "seller_staff", seller);

            assertThat(service.find(staff, seller).invitations()).isEmpty();
            assertThat(service.find(owner, seller).invitations()).hasSize(1);
        }
    }

    /**
     * 이미 들어온 사람의 역할과 소속(`Q165`).
     *
     * <p><b>`16a` 가 선언한 「역할을 주고 회수한다」의 절반이 안 닫혀 있었다</b> —
     * 만든 것은 초대·수락·초대 거두기뿐이었고, 그 자리의 주석이
     * <b>「그 입구는 `16a` 가 만든다」로 남아 끝난 청크가 자기를 가리켰다.</b>
     */
    @Nested
    @DisplayName("이미 들어온 사람")
    class Existing {

        private long staff;

        @BeforeEach
        void joinStaff() {
            staff = fixture.insertUser("staff-existing@example.com", "담당자");
            fixture.joinSeller(seller, staff);
            fixture.grantOrg(staff, "seller_staff", seller);
        }

        @Test
        @DisplayName("역할을 바꾸면 소속은 그대로다")
        void 역할을_바꾸면_소속은_그대로다() {
            service.changeRole(seller, staff, "seller_owner", owner);

            assertThat(orgRoleCount(staff, "seller_owner", seller)).isEqualTo(1);
            assertThat(orgRoleCount(staff, "seller_staff", seller)).isZero();
            assertThat(memberCount(seller, staff)).isEqualTo(1);
        }

        /** 역할만 지우면 아무것도 못 하는 소속이 남고, 소속만 지우면 트리거가 걸린다 */
        @Test
        @DisplayName("내보내면 소속과 역할이 같이 사라진다")
        void 내보내면_소속과_역할이_같이_사라진다() {
            service.remove(seller, staff, owner);

            assertThat(memberCount(seller, staff)).isZero();
            assertThat(orgRoleCount(staff, "seller_staff", seller)).isZero();
        }

        /** 대표가 0이 되면 그 셀러는 멤버를 부를 수도 뺄 수도 없는 상태로 잠긴다 */
        @Test
        @DisplayName("마지막 대표는 못 나간다")
        void 마지막_대표는_못_나간다() {
            assertThatThrownBy(() -> service.remove(seller, owner, owner))
                    .isInstanceOf(ShopException.class)
                    .extracting(error -> ((ShopException) error).code())
                    .isEqualTo(ErrorCode.SELLER_LAST_OWNER);
        }

        @Test
        @DisplayName("마지막 대표는 역할도 못 내린다")
        void 마지막_대표는_역할도_못_내린다() {
            assertThatThrownBy(() -> service.changeRole(seller, owner, "seller_staff", owner))
                    .isInstanceOf(ShopException.class)
                    .extracting(error -> ((ShopException) error).code())
                    .isEqualTo(ErrorCode.SELLER_LAST_OWNER);
        }

        @Test
        @DisplayName("대표가 둘이면 하나는 내려올 수 있다")
        void 대표가_둘이면_하나는_내려올_수_있다() {
            service.changeRole(seller, staff, "seller_owner", owner);

            service.changeRole(seller, owner, "seller_staff", owner);

            assertThat(orgRoleCount(owner, "seller_staff", seller)).isEqualTo(1);
        }

        /**
         * 앱을 안 거치는 길(`psql`, 앞으로 생길 입구)도 막힌다(`Q169`). 트리거가 지연이라 롤백되는
         * 시험에서는 안 터지므로 그 자리에서 켠다.
         */
        @Test
        @DisplayName("앱을 거치지 않고 마지막 대표 역할을 지워도 DB 가 막는다")
        void 앱을_거치지_않고_마지막_대표_역할을_지워도_DB_가_막는다() {
            jdbc.sql("delete from user_role where user_id = :id and seller_id = :seller")
                    .param("id", owner).param("seller", seller).update();

            assertThatThrownBy(() -> jdbc.sql(
                            "set constraints user_role_keeps_seller_owner immediate").update())
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("마지막 대표");
        }

        @Test
        @DisplayName("앱을 거치지 않고 마지막 대표가 탈퇴해도 DB 가 막는다")
        void 앱을_거치지_않고_마지막_대표가_탈퇴해도_DB_가_막는다() {
            jdbc.sql("update app_user set deleted_at = now() where user_id = :id")
                    .param("id", owner).update();

            assertThatThrownBy(() -> jdbc.sql(
                            "set constraints app_user_withdrawal_keeps_seller_owner immediate").update())
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("마지막 대표");
        }

        /**
         * 넘기는 것은 「새 대표를 넣고 옛 대표를 뺀다」 두 걸음이다. 트리거가 즉시면 순서에 따라
         * 중간에 0이 되어 넘길 수가 없다 — 지연인 이유다.
         */
        @Test
        @DisplayName("한 트랜잭션 안에서 대표를 넘기는 것은 된다")
        void 한_트랜잭션_안에서_대표를_넘기는_것은_된다() {
            jdbc.sql("delete from user_role where user_id = :id and seller_id = :seller")
                    .param("id", owner).param("seller", seller).update();
            jdbc.sql("delete from user_role where user_id = :id and seller_id = :seller")
                    .param("id", staff).param("seller", seller).update();
            fixture.grantOrg(staff, "seller_owner", seller);

            jdbc.sql("set constraints user_role_keeps_seller_owner immediate").update();

            assertThat(orgRoleCount(staff, "seller_owner", seller)).isEqualTo(1);
        }

        @Test
        @DisplayName("전역 역할은 이 입구로 못 준다")
        void 전역_역할은_이_입구로_못_준다() {
            assertThatThrownBy(() -> service.changeRole(seller, staff, "admin", owner))
                    .isInstanceOf(ShopException.class);
        }

        @Test
        @DisplayName("속하지 않은 사람은 못 바꾼다")
        void 속하지_않은_사람은_못_바꾼다() {
            long outsider = fixture.insertUser("outsider@example.com", "바깥");

            assertThatThrownBy(() -> service.remove(seller, outsider, owner))
                    .isInstanceOf(ShopException.class)
                    .extracting(error -> ((ShopException) error).code())
                    .isEqualTo(ErrorCode.SELLER_MEMBER_NOT_FOUND);
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
