package com.projectshop.shop;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import java.util.List;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import com.projectshop.shop.support.ListQuery;
import com.projectshop.shop.support.ListQuery.Paging;

/**
 * {@code coding-rules.md}(D23)가 글로만 적어 둔 계층 규칙을 기계가 지킨다(청크 {@code 2n}).
 *
 * <p>그 문서의 「계층」·「예외」·「의존」·「자원이 아닌데 패키지를 파는 경우」는
 * <b>강제 지점 5위</b>였다 — 사람이 읽을 때만 걸린다. 여기로 내리면 4위가 된다.
 *
 * <p><b>컨테이너를 안 탄다.</b> 바이트코드만 읽으므로 빠른 레인({@code test})에서 돈다.
 *
 * <p><b>{@code FreezingArchRule} 을 안 쓴다.</b> 기준선 파일로 눌러 두면 예외마다
 * <b>왜 뺐는지를 못 적는다.</b> 지금 예외가 넷이고(컨트롤러 하나·허용 순환 셋) 넷 다 문서에 이유가 있어서,
 * 코드에 이름을 붙여 적는 쪽이 낫다. {@code 2e} 가 SpotBugs 기준선을 안 만든 것과 같은 판단이다.
 *
 * <p><b>아무 클래스도 안 보는 규칙은 조용히 통과한다.</b> 이름 규칙이 바뀌면 그렇게 된다 —
 * {@code archunit.properties} 의 {@code archRule.failOnEmptyShould=true} 가 그때 실패시킨다.
 * <b>코드에서 {@code System.setProperty} 로 켜면 안 된다</b> — 규칙이 {@code static final} 이라
 * 필드 초기화가 {@code static} 블록보다 먼저 돌 수 있다.
 *
 * <p><b>못 보는 것</b> — 이건 의존 방향만 본다. 트랜잭션 경계가 서비스에 있나,
 * 판정을 서비스에서 부르나 같은 것은 여전히 문서와 사람이 든다.
 */
@AnalyzeClasses(
        packages = "com.projectshop.shop",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /**
     * 「계층」 — 컨트롤러는 서비스만 부른다.
     *
     * <p><b>{@code HealthController} 만 예외다.</b> DB 에 질의가 실제로 나가는지가
     * 그 엔드포인트의 목적이라, 서비스를 거치면 검사할 것이 사라진다(D23 「계층」).
     *
     * <p><b>{@code *Query} 를 부르는 것은 위반이 아니다.</b> 그 클래스들은 {@code @Service} 고
     * 읽기 쪽 서비스다. 규칙이 막는 것은 {@code JdbcClient} 를 컨트롤러가 직접 드는 것이다.
     */
    @ArchTest
    static final ArchRule 컨트롤러는_JdbcClient_를_직접_안_든다 =
            noClasses()
                    .that().haveSimpleNameEndingWith("Controller")
                    .and().doNotHaveSimpleName("HealthController")
                    .should().dependOnClassesThat().haveSimpleName("JdbcClient")
                    .because("DB 접근이 컨트롤러에 섞이면 같은 조회가 화면마다 복제된다"
                            + " (coding-rules.md 「계층」). HealthController 는 질의가 나가는지가 목적이라 예외다");

    /**
     * 「예외」 — 서비스는 도메인 예외만 던지고 HTTP 를 모른다.
     *
     * <p>서비스가 {@code ResponseStatusException} 을 던지면 웹을 알게 된다. 배치나 다른 입구에서
     * 같은 서비스를 재사용할 때 어색해지고, 상태 코드가 코드 전체에 흩어져 {@code D5} 의
     * 403/404 표와 대조할 수 없다. 번역은 {@code ApiExceptionHandler} 한 군데서 한다(청크 {@code 7b}).
     *
     * <p><b>이 규칙이 실제로 잡았다</b> — {@code 2n} 을 칠 때 서비스 셋과 {@code AuditLogQuery} 에
     * 쓰지도 않는 {@code HttpStatus} import 가 남아 있었다. 7b 가 옮기고 지우지 않은 자국이다.
     */
    @ArchTest
    static final ArchRule 서비스와_조회는_HTTP_를_모른다 =
            noClasses()
                    .that().haveSimpleNameEndingWith("Service")
                    .or().haveSimpleNameEndingWith("ServiceImpl")
                    .or().haveSimpleNameEndingWith("Query")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("org.springframework.web..", "org.springframework.http..")
                    .because("서비스가 HTTP 를 알면 배치·다른 입구에서 재사용이 어색해지고"
                            + " 상태 코드가 흩어진다. 번역은 ApiExceptionHandler 한 곳에서 한다"
                            + " (coding-rules.md 「예외」, 청크 7b)");

    /**
     * 「의존」 — 자원 패키지끼리 순환하지 않는다.
     *
     * <p><b>허용이 셋이다. 셋 다 「업무가 두 자원에 걸쳐 있다」는 같은 모양이고,
     * 가르면 가짜 경계가 생긴다.</b> 모듈을 쪼개게 되면 여기부터 막히므로
     * 그때는 걸친 것을 공용 인터페이스로 빼는 것이 답이다 — 예외를 늘리는 것이 아니다.
     *
     * <table>
     *   <caption>허용한 순환과 근거</caption>
     *   <tr><th>순환</th><th>왜 필연인가</th><th>세운 곳</th></tr>
     *   <tr><td>{@code auth ↔ audit}</td>
     *       <td>권한 거부를 판정 안에서 자동 기록하고, 그 로그 조회도 권한을 탄다.
     *           기록을 호출자에게 맡기면 빠뜨린다</td>
     *       <td>D23 「의존」 (4b · 4b-1)</td></tr>
     *   <tr><td>{@code auth ↔ cart}</td>
     *       <td><b>로그인 그 순간에 비회원 장바구니를 계정으로 옮긴다.</b>
     *           {@code AuthController} 가 {@code CartService.mergeIntoAccount} 와 쿠키 이름을 부르고,
     *           {@code CartController} 는 인증을 본다. 어느 쪽이 부르든 한 방향은 남는다</td>
     *       <td>{@code 2n} (사용자 결정, 2026-09-06)</td></tr>
     *   <tr><td>{@code order ↔ payment}</td>
     *       <td><b>결제가 주문 상태를 옮기고 주문 조회가 결제 상태를 보인다.</b>
     *           {@code PaymentService} 가 {@code OrderStatusService}·{@code IdempotencyService} 를,
     *           {@code OrderQuery} 가 결제·환불 열거형 넷을 쓴다. 둘을 가르면 가짜 경계가 생긴다</td>
     *       <td>{@code 2n} (사용자 결정, 2026-09-06)</td></tr>
     * </table>
     *
     * <p><b>넷째가 생기면 예외로 넣기 전에 멈춘다.</b> 셋은 도메인이 그렇게 생겨서 난 것이고,
     * 넷째는 대개 「부를 자리가 없어서 아무 데나 부른 것」이다.
     */
    @ArchTest
    static final ArchRule 자원_패키지는_순환하지_않는다 =
            slices().matching("com.projectshop.shop.(*)..")
                    .namingSlices("$1")
                    .should().beFreeOfCycles()
                    .ignoreDependency(resideInAPackage("..auth.."), resideInAPackage("..audit.."))
                    .ignoreDependency(resideInAPackage("..audit.."), resideInAPackage("..auth.."))
                    .ignoreDependency(resideInAPackage("..auth.."), resideInAPackage("..cart.."))
                    .ignoreDependency(resideInAPackage("..cart.."), resideInAPackage("..auth.."))
                    .ignoreDependency(resideInAPackage("..order.."), resideInAPackage("..payment.."))
                    .ignoreDependency(resideInAPackage("..payment.."), resideInAPackage("..order.."))
                    .because("자원끼리 순환하면 모듈을 쪼갤 때 통째로 막힌다."
                            + " 셋만 도메인이 그렇게 생겨서 뺀다 (coding-rules.md 「의존」, 2n)");

    /**
     * 「자원이 아닌데 패키지를 파는 경우」 — 공용 도구는 자원을 모른다.
     *
     * <p>{@code support} 는 자원이 아니라 여러 자원이 같이 쓰는 것들이다. 거기서 자원 패키지를
     * 부르기 시작하면 <b>공용이 아니라 그 자원의 일부</b>가 되고, 다음 사람이 어느 쪽에
     * 코드를 둘지 매번 판단하게 된다.
     */
    @ArchTest
    static final ArchRule 공용_도구는_자원을_모른다 =
            noClasses()
                    .that().resideInAPackage("..support..")
                    .should().dependOnClassesThat()
                    .resideOutsideOfPackages("java..", "javax..", "jakarta..",
                            "org.springframework..", "org.slf4j..", "com.fasterxml..", "tools.jackson..",
                            "com.projectshop.shop.support..", "com.projectshop.shop.error..")
                    .because("support 가 자원을 부르면 공용이 아니라 그 자원의 일부가 된다"
                            + " (coding-rules.md 「자원이 아닌데 패키지를 파는 경우」)."
                            + " 자원을 열거하지 않고 허용을 적는다 — 열거하면 새 자원 패키지가 생길 때마다 샌다."
                            + " accessClassesThat 은 호출만 보고 필드 선언을 안 봐서 dependOnClassesThat 이다");

    /**
     * 「목록 조회」 — 페이지를 내주는 조회는 {@link Paging} 을 받는다(`Q23`).
     *
     * <p><b>생성자가 못 막는 자리를 여기서 막는다.</b> {@code Paging} 은 만들어지는 순간
     * 보정되므로 <b>잘못된 값을 가진 {@code Paging}</b> 은 존재할 수 없다 — 거기까지가 1위다.
     * 하지만 <b>{@code Paging} 을 아예 안 받는 새 목록 메서드</b>는 타입이 못 막는다.
     * {@code int page, int size} 를 받아 그대로 {@code limit} 에 넣어도 컴파일도 테스트도 통과하고
     * <b>상한만 조용히 사라진다</b>(`D5` 「목록 하나로 전체를 긁어 갈 수 있다」).
     *
     * <p><b>반환 타입으로 「목록 조회」를 알아본다.</b> 파라미터 이름({@code page}·{@code size})으로
     * 재면 {@code -parameters} 컴파일 옵션에 매달리고, 이름을 바꾸면 규칙이 조용히 비켜난다.
     * 페이지를 내주는 것은 <b>{@code Page} 로 끝나는 record 를 돌려준다</b>는 규약이 이미 있고
     * ({@code Page}·{@code PublicPage}·{@code SellerPage}), 그건 응답 계약이라 함부로 안 바뀐다.
     */
    @ArchTest
    static final ArchRule 목록_조회는_페이지를_직접_안_받는다 =
            methods()
                    .that().arePublic()
                    .and().areDeclaredInClassesThat().haveSimpleNameEndingWith("Query")
                    .and().haveRawReturnType(simpleNameEndingWith("Page"))
                    .should(받는다(Paging.class))
                    .because("Paging 을 안 받으면 size 상한을 안 거치는 목록이 하나 생긴다"
                            + " (api-guidelines.md 「목록 조회」). 생성자가 값은 보증하지만"
                            + " 「그 타입을 안 쓰는 것」은 못 막는다");

    /**
     * 「목록 조회」 — 정렬을 받는 조회는 허용 목록을 거친다(`Q24`).
     *
     * <p><b>여기가 요청 문자열이 SQL 에 닿는 유일한 자리다</b>(`D14`). 컬럼명은 값이 아니라
     * 식별자라 바인딩이 안 되고({@code order by ?} 는 문법 오류다) <b>결합이 강제된다</b> —
     * 들어올 수 있는 값을 우리가 정하는 것 말고 막을 방법이 없다.
     *
     * <p><b>{@link OrderBy} 타입만으로는 못 막는다.</b> 조립이 문자열이라
     * {@code " order by " + sort} 라고 쓰면 그 타입을 아예 안 거치고 지나간다.
     * 타입은 「거쳤다」를 뜻하고 이 규칙이 「거치게」 만든다 — <b>둘이 서로의 구멍을 덮는다.</b>
     *
     * <p><b>{@code String} 파라미터로 알아본다.</b> ArchUnit 은 파라미터 <b>이름</b>을 안 줘서
     * {@code sort} 를 집어낼 수가 없다. 지금 페이지를 내주는 조회 중 {@code String} 을 받는 것은
     * 전부 정렬을 받는 것이고({@code RefundQuery} 의 {@code status} 는 정렬과 같은 메서드에 있다),
     * 아닌 것은 {@code String} 을 아예 안 받는다.
     *
     * <p><b>오탐이 나는 쪽으로 틀어 뒀다.</b> 정렬이 없는데 {@code String} 을 받는 목록이 생기면
     * 여기가 빨개진다 — 그때는 예외를 이유와 함께 적는다. 놓치는 쪽으로 틀면
     * <b>SQL 결합 자리가 조용히 하나 늘어난다.</b>
     */
    @ArchTest
    static final ArchRule 정렬을_받는_조회는_허용_목록을_거친다 =
            methods()
                    .that().arePublic()
                    .and().areDeclaredInClassesThat().haveSimpleNameEndingWith("Query")
                    .and().haveRawReturnType(simpleNameEndingWith("Page"))
                    .and().haveRawParameterTypes(하나라도(String.class))
                    .should(부른다(ListQuery.class, "orderBy"))
                    .because("정렬 문자열이 SQL 에 결합되는 유일한 자리다 (security-baseline.md `D14`)."
                            + " OrderBy 타입은 「거쳤다」를 뜻할 뿐 「거치게」 만들지는 못한다");

    /** 파라미터 목록에 그 타입이 하나라도 있나. */
    private static DescribedPredicate<List<JavaClass>> 하나라도(Class<?> type) {
        return DescribedPredicate.describe(type.getSimpleName() + " 을 받는",
                parameters -> parameters.stream()
                        .anyMatch(parameter -> parameter.isEquivalentTo(type)));
    }

    /** 그 메서드를 몸통에서 부르나. <b>여섯 중 호출을 보는 첫 규칙이다</b> — 나머지는 의존·시그니처다. */
    private static ArchCondition<JavaMethod> 부른다(Class<?> owner, String name) {
        return new ArchCondition<>("가 " + owner.getSimpleName() + "." + name + " 을 부른다") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                boolean found = method.getMethodCallsFromSelf().stream()
                        .anyMatch(call -> call.getTargetOwner().isEquivalentTo(owner)
                                && call.getName().equals(name));

                events.add(new SimpleConditionEvent(method, found,
                        method.getFullName() + (found ? " 이 부른다" : " 이 안 부른다")));
            }
        };
    }

    /** 파라미터 목록에 그 타입이 있나. ArchUnit 기본 조건에 「하나라도 있나」가 없어서 짠다. */
    private static ArchCondition<JavaMethod> 받는다(Class<?> type) {
        return new ArchCondition<>("가 " + type.getSimpleName() + " 을 받는다") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                boolean found = method.getRawParameterTypes().stream()
                        .anyMatch(parameter -> parameter.isEquivalentTo(type));

                events.add(new SimpleConditionEvent(method, found,
                        method.getFullName() + (found ? " 이 받는다" : " 이 안 받는다")));
            }
        };
    }

    private ArchitectureTest() {
    }

}
