package com.projectshop.shop.auth;

import static com.projectshop.shop.auth.PermissionEvaluator.evaluate;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.projectshop.shop.auth.PermissionEvaluator.Decision;
import com.projectshop.shop.auth.PermissionEvaluator.Rule;
import com.projectshop.shop.auth.PermissionEvaluator.Target;

/**
 * 판정 계산만 검증한다. Spring 도 DB 도 띄우지 않는다.
 *
 * <p>규칙을 코드로 만들어 넘기므로 계정·셀러·소속을 DB 에 넣는 준비가 없다.
 * 같은 것을 {@code PermissionEvaluatorTest} 로 확인하려면 테스트마다 INSERT 가 대여섯 번 나간다.
 *
 * <p>DB 에서 규칙을 제대로 읽어 오는지는 여기서 못 본다. 그건 통합 테스트의 몫이다.
 */
class PermissionRuleEvaluationTest {

    static final long ME = 100L;
    static final long OTHER = 200L;
    static final long ALPHA = 1L;
    static final long BETA = 2L;

    static Rule allow(String role, String scope) {
        return new Rule(role, null, scope, "allow", Set.of());
    }

    static Rule deny(String role, String scope) {
        return new Rule(role, null, scope, "deny", Set.of());
    }

    static Rule orgAllow(String role, long sellerId, String scope) {
        return new Rule(role, sellerId, scope, "allow", Set.of());
    }

    static Decision decide(List<Rule> rules, Target target) {
        return evaluate(rules, Set.of(ALPHA), ME, target);
    }

    @Nested
    @DisplayName("스코프가 대상을 덮나")
    class Coverage {

        @Test
        @DisplayName("own 은 내가 주인일 때만 덮는다")
        void ownCoversMineOnly() {
            List<Rule> rules = List.of(allow("customer", "own"));

            assertThat(decide(rules, Target.ownedBy(ME)).allowed()).isTrue();
            assertThat(decide(rules, Target.ownedBy(OTHER)).allowed()).isFalse();
        }

        @Test
        @DisplayName("all 은 주인도 셀러도 안 본다")
        void allCoversEverything() {
            List<Rule> rules = List.of(allow("admin", "all"));

            assertThat(decide(rules, Target.ownedBy(OTHER)).allowed()).isTrue();
            assertThat(decide(rules, Target.ofSeller(BETA)).allowed()).isTrue();
            assertThat(decide(rules, new Target(null, null)).allowed()).isTrue();
        }

        @Test
        @DisplayName("전역 부여의 seller 는 내가 속한 모든 셀러를 덮는다")
        void globalSellerScopeUsesMembership() {
            List<Rule> rules = List.of(allow("seller_owner", "seller"));

            assertThat(decide(rules, Target.ofSeller(ALPHA)).allowed()).isTrue();
            assertThat(decide(rules, Target.ofSeller(BETA)).allowed()).isFalse();
        }

        @Test
        @DisplayName("조직 부여의 seller 는 받은 그 셀러만 덮는다")
        void orgSellerScopeUsesGrant() {
            List<Rule> rules = List.of(orgAllow("seller_owner", BETA, "seller"));

            assertThat(decide(rules, Target.ofSeller(BETA)).allowed())
                    .as("소속이 아닌 셀러라도 그 셀러 역할을 받았으면 덮는다")
                    .isTrue();
            assertThat(decide(rules, Target.ofSeller(ALPHA)).allowed())
                    .as("소속이어도 그 셀러 역할이 아니면 못 덮는다")
                    .isFalse();
        }

        @Test
        @DisplayName("셀러가 없는 대상은 seller 스코프가 못 덮는다")
        void sellerScopeNeedsSeller() {
            assertThat(decide(List.of(allow("seller_owner", "seller")), Target.ownedBy(ME)).allowed()).isFalse();
        }
    }

    @Nested
    @DisplayName("효과 우선순위")
    class Effect {

        @Test
        @DisplayName("좁은 deny 가 넓은 allow 를 이긴다")
        void denyBeatsWiderAllow() {
            List<Rule> rules = List.of(allow("seller_owner", "seller"), deny("seller_owner", "own"));

            Decision mine = decide(rules, Target.of(ME, ALPHA));
            assertThat(mine.allowed()).isFalse();
            assertThat(mine.reason()).contains("deny/own");

            assertThat(decide(rules, Target.of(OTHER, ALPHA)).allowed())
                    .as("남의 주문은 deny/own 이 안 덮으므로 그대로 허용")
                    .isTrue();
        }

        @Test
        @DisplayName("규칙 순서가 바뀌어도 결과가 같다")
        void orderDoesNotMatter() {
            Target mine = Target.of(ME, ALPHA);

            Decision denyFirst = decide(List.of(deny("seller_owner", "own"), allow("seller_owner", "seller")), mine);
            Decision allowFirst = decide(List.of(allow("seller_owner", "seller"), deny("seller_owner", "own")), mine);

            assertThat(denyFirst.allowed()).isEqualTo(allowFirst.allowed()).isFalse();
        }

        @Test
        @DisplayName("대상을 안 덮는 deny 는 무시된다")
        void irrelevantDenyIsIgnored() {
            List<Rule> rules = List.of(allow("admin", "all"), deny("auditor", "own"));

            assertThat(decide(rules, Target.ownedBy(OTHER)).allowed()).isTrue();
        }
    }

    @Nested
    @DisplayName("스코프 우선순위")
    class Width {

        @Test
        @DisplayName("여러 allow 가 걸리면 넓은 쪽이 근거가 된다")
        void widestBecomesReason() {
            List<Rule> rules = List.of(allow("customer", "own"), allow("admin", "all"));

            assertThat(decide(rules, Target.ownedBy(ME)).reason()).contains("allow/all");
        }

        @Test
        @DisplayName("넓은 규칙이 대상을 못 덮으면 좁은 쪽이 근거가 된다")
        void narrowWinsWhenWideDoesNotCover() {
            List<Rule> rules = List.of(allow("customer", "own"), allow("seller_owner", "seller"));

            assertThat(decide(rules, Target.ownedBy(ME)).reason())
                    .as("셀러가 없는 대상이라 seller 스코프가 안 걸린다")
                    .contains("allow/own");
        }
    }

    @Nested
    @DisplayName("필드 그룹")
    class Fields {

        static Rule allowWith(String role, String scope, String... groups) {
            return new Rule(role, null, scope, "allow", Set.of(groups));
        }

        @Test
        @DisplayName("걸린 allow 의 그룹을 합친다")
        void unionOfGroups() {
            List<Rule> rules = List.of(
                    allowWith("customer", "own", "basic", "payment"),
                    allowWith("seller_owner", "seller", "basic", "shipping"));

            Decision decision = evaluate(rules, Set.of(ALPHA), ME, Target.of(ME, ALPHA));

            assertThat(decision.visibleFieldGroups()).containsExactlyInAnyOrder("basic", "payment", "shipping");
        }

        @Test
        @DisplayName("제한 없는 규칙이 하나라도 걸리면 제한이 풀린다")
        void unrestrictedWins() {
            List<Rule> rules = List.of(
                    allowWith("seller_owner", "seller", "basic"),
                    allow("admin", "all"));

            Decision decision = evaluate(rules, Set.of(ALPHA), ME, Target.of(ME, ALPHA));

            assertThat(decision.fieldRestricted()).isFalse();
            assertThat(decision.canSee("payment")).isTrue();
        }

        @Test
        @DisplayName("안 걸린 규칙의 그룹은 안 합쳐진다")
        void uncoveredRuleContributesNothing() {
            List<Rule> rules = List.of(
                    allowWith("customer", "own", "basic"),
                    allowWith("seller_owner", "seller", "payment"));

            Decision decision = evaluate(rules, Set.of(ALPHA), ME, Target.ownedBy(ME));

            assertThat(decision.visibleFieldGroups())
                    .as("셀러가 없는 대상이라 seller 규칙이 안 걸린다")
                    .containsExactly("basic");
        }

        @Test
        @DisplayName("거부된 판정은 아무 필드도 못 본다")
        void deniedSeesNothing() {
            List<Rule> rules = List.of(allowWith("customer", "own", "basic"), deny("auditor", "all"));

            Decision decision = evaluate(rules, Set.of(ALPHA), ME, Target.ownedBy(ME));

            assertThat(decision.canSee("basic")).isFalse();
        }
    }

    @Nested
    @DisplayName("허용이 없는 경우")
    class NoAllow {

        @Test
        @DisplayName("덮는 allow 가 없으면 거부다")
        void noCoveringAllow() {
            Decision decision = decide(List.of(allow("customer", "own")), Target.ownedBy(OTHER));

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.reason()).contains("범위 밖");
        }

        @Test
        @DisplayName("deny 만 있으면 거부다")
        void denyOnly() {
            Decision decision = decide(List.of(deny("auditor", "all")), Target.ownedBy(ME));

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.reason()).contains("deny/all");
        }
    }
}
