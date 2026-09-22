package com.projectshop.shop.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.PermissionEvaluator.Target;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

/**
 * 관리자가 전역 역할을 주고 회수한다(`16`).
 *
 * <p><b>제일 값이 큰 것이 「회수가 곧바로 먹나」다.</b> 판정 규칙이 60초 캐시되므로
 * 캐시를 안 버리면 <b>화면은 뺐다고 하고 서버는 아직 허용한다</b> — 권한을 준 것보다
 * 못 뺀 것이 사고다. {@code UserRoleWriteTest} 가 새 경로가 생기는 것을 원천에서 막고,
 * 여기서는 <b>실제로 판정이 바뀌는지</b>를 본다(`D15` — 층이 다르다).
 */
@DisplayName("전역 역할 편집")
class UserRoleServiceTest extends PostgresTestBase {

    @Autowired
    private UserRoleService roles;

    @Autowired
    private PermissionEvaluator evaluator;

    @Autowired
    private JdbcClient jdbc;

    private AuthFixture fixture;
    private long admin;
    private long target;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);
        admin = fixture.insertUser("admin@example.com", "관리자");
        fixture.grantGlobal(admin, "admin");

        target = fixture.insertUser("target@example.com", "대상");
    }

    @Nested
    @DisplayName("캐시")
    class Cache {

        /**
         * 판정을 먼저 한 번 돌려 캐시를 채운 뒤 회수한다.
         * <b>캐시를 안 버리면 이 시험이 그 자리에서 빨갛다.</b>
         */
        @Test
        @DisplayName("회수가 곧바로 먹는다")
        void 회수가_곧바로_먹는다() {
            roles.grant(admin, target, "auditor");
            assertThat(canReadAudit(target)).isTrue();

            roles.revoke(admin, target, "auditor");

            assertThat(canReadAudit(target))
                    .as("캐시를 안 버리면 TTL 60초 동안 회수가 안 먹는다")
                    .isFalse();
        }

        @Test
        @DisplayName("부여도 곧바로 먹는다")
        void 부여도_곧바로_먹는다() {
            assertThat(canReadAudit(target)).isFalse();

            roles.grant(admin, target, "auditor");

            assertThat(canReadAudit(target)).isTrue();
        }
    }

    @Nested
    @DisplayName("같은 말을 두 번 해도 된다")
    class Idempotent {

        /** 두 번 눌러 두 행이 되면 회수가 한 번으로 안 끝난다 */
        @Test
        @DisplayName("두 번 줘도 한 행이다")
        void 두_번_줘도_한_행이다() {
            roles.grant(admin, target, "auditor");
            roles.grant(admin, target, "auditor");

            assertThat(globalRoleCount(target, "auditor")).isEqualTo(1);
        }

        /** 부르는 쪽이 원한 상태가 이미 사실이라 실패로 답할 이유가 없다 */
        @Test
        @DisplayName("없는 것을 빼도 오류가 아니다")
        void 없는_것을_빼도_오류가_아니다() {
            roles.revoke(admin, target, "auditor");

            assertThat(globalRoleCount(target, "auditor")).isZero();
        }
    }

    @Nested
    @DisplayName("막는 것")
    class Rejected {

        @Test
        @DisplayName("권한 없는 사람은 못 준다")
        void 권한_없는_사람은_못_준다() {
            long stranger = fixture.insertUser("stranger@example.com", "남");

            assertThatThrownBy(() -> roles.grant(stranger, target, "auditor"))
                    .isInstanceOf(ShopException.class)
                    .extracting(error -> ((ShopException) error).code())
                    .isEqualTo(ErrorCode.ROLE_FORBIDDEN);
        }

        /**
         * 조직 역할은 소속과 함께 들어가야 해서 {@code SellerMemberService} 가 든다(`5a`).
         * 여기서 주면 트리거가 막는데 그것은 500 이라, 고칠 수 있는 오류로 먼저 답한다.
         */
        @Test
        @DisplayName("조직 역할은 이 입구로 못 준다")
        void 조직_역할은_이_입구로_못_준다() {
            assertThatThrownBy(() -> roles.grant(admin, target, "seller_owner"))
                    .isInstanceOf(ShopException.class)
                    .extracting(error -> ((ShopException) error).code())
                    .isEqualTo(ErrorCode.ROLE_NOT_ASSIGNABLE);
        }

        @Test
        @DisplayName("없는 사람은 404 다")
        void 없는_사람은_404_다() {
            assertThatThrownBy(() -> roles.find(admin, 9_999_999L))
                    .isInstanceOf(ShopException.class)
                    .extracting(error -> ((ShopException) error).code())
                    .isEqualTo(ErrorCode.USER_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("조회")
    class Read {

        /**
         * 가입 응답의 {@code Location} 이 이 주소를 가리킨다(`Q166`).
         * 역할 권한을 요구하면 <b>갓 가입한 사람이 자기 것을 못 읽는다.</b>
         */
        @Test
        @DisplayName("본인은 역할 권한 없이 읽는다")
        void 본인은_역할_권한_없이_읽는다() {
            UserRoleService.Detail detail = roles.find(target, target);

            assertThat(detail.userId()).isEqualTo(target);
            assertThat(detail.canAssign())
                    .as("읽을 수는 있어도 바꿀 수는 없다")
                    .isFalse();
        }

        @Test
        @DisplayName("남의 것은 여전히 권한이 있어야 읽는다")
        void 남의_것은_여전히_권한이_있어야_읽는다() {
            long stranger = fixture.insertUser("nobody@example.com", "남");

            assertThatThrownBy(() -> roles.find(stranger, target))
                    .isInstanceOf(ShopException.class)
                    .extracting(error -> ((ShopException) error).code())
                    .isEqualTo(ErrorCode.ROLE_FORBIDDEN);
        }

        /** 감사에서 「누가 무엇을 가졌었나」를 물으면 그 계정이 이미 나갔을 수 있다 */
        @Test
        @DisplayName("탈퇴한 계정도 보이고 나간 것이 칸으로 드러난다")
        void 탈퇴한_계정도_보인다() {
            jdbc.sql("update app_user set deleted_at = now() where user_id = :id")
                    .param("id", target)
                    .update();

            UserRoleService.Detail detail = roles.find(admin, target);

            assertThat(detail.deleted()).isTrue();
        }

        @Test
        @DisplayName("조직 역할은 어느 셀러인지까지 실린다")
        void 조직_역할은_어느_셀러인지까지_실린다() {
            long seller = fixture.insertSeller("role-seller", "역할셀러");
            fixture.joinSeller(seller, target);
            fixture.grantOrg(target, "seller_owner", seller);

            UserRoleService.Detail detail = roles.find(admin, target);

            assertThat(detail.roles())
                    .filteredOn(grant -> "seller_owner".equals(grant.roleCode()))
                    .singleElement()
                    .satisfies(grant -> {
                        assertThat(grant.sellerId()).isEqualTo(seller);
                        assertThat(grant.sellerName()).isEqualTo("역할셀러");
                    });
        }
    }

    /** 감사 조회 권한은 `auditor` 만 가진다(`V5`). 판정이 바뀌었는지를 이것으로 본다 */
    private boolean canReadAudit(long userId) {
        return evaluator.decide(userId, "audit", "read", new Target(null, null, null)).allowed();
    }

    private int globalRoleCount(long userId, String roleCode) {
        return jdbc.sql("""
                        select count(*) from user_role ur
                          join role r on r.role_id = ur.role_id
                         where ur.user_id = :id and r.code = :code and ur.seller_id is null
                        """)
                .param("id", userId)
                .param("code", roleCode)
                .query(Integer.class)
                .single();
    }
}
