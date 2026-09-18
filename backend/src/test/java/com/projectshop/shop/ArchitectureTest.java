package com.projectshop.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.projectshop.shop.auth.PermissionEvaluator;
import com.projectshop.shop.notification.MockNotificationSender;
import com.projectshop.shop.payment.MockPaymentGateway;
import com.projectshop.shop.support.ListQuery;
import com.projectshop.shop.support.ListQuery.Paging;

import jakarta.validation.Valid;

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
 * <p><b>못 보는 것</b> — 한 트랜잭션이 한 유스케이스인가 같은 것은 여전히 문서와 사람이 든다.
 * 트랜잭션 경계는 {@code Q32} 가 셋을 내렸다 — 조회에 없나, 안에서 바깥을 안 부르나.
 * <b>「판정을 지나나」는 {@code Q56} 이 내렸다</b> — 그 전까지 이 자리에 「사람이 든다」로 적혀 있었다.
 * 각 규칙이 못 보는 것은 그 javadoc 에 있다.
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
     * <p><b>허용이 둘이다. 둘 다 「업무가 두 자원에 걸쳐 있다」는 같은 모양이고,
     * 가르면 가짜 경계가 생긴다.</b> 모듈을 쪼개게 되면 여기부터 막히므로
     * 그때는 걸친 것을 공용 인터페이스로 빼는 것이 답이다 — 예외를 늘리는 것이 아니다.
     *
     * <p><b>{@code order ↔ payment} 가 그 첫 실례다</b>(`Q58`). 결제가 주문 상태를 옮기는 것은
     * 남았는데 부르는 대상이 {@code payment.PaymentOutcome} 인터페이스로 바뀌었고, 구현
     * ({@code order.PaymentOutcomeHandler}) 이 주문 쪽에 있어서 방향이 하나로 남았다.
     * {@code IdempotencyService} 는 자원을 모르는 도구라 {@code support} 로 옮겼다.
     * 남은 {@code OrderQuery} → 결제 열거형 넷은 한 방향이라 순환이 아니다.
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
     * </table>
     *
     * <p><b>셋째가 생기면 예외로 넣기 전에 멈춘다.</b> 둘은 도메인이 그렇게 생겨서 난 것이고,
     * 셋째는 대개 「부를 자리가 없어서 아무 데나 부른 것」이다.
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
                    .because("자원끼리 순환하면 모듈을 쪼갤 때 통째로 막힌다."
                            + " 둘만 도메인이 그렇게 생겨서 뺀다 (coding-rules.md 「의존」, 2n · Q58)");

    /**
     * 「자원이 아닌데 패키지를 파는 경우」 — 공용 도구는 자원을 모른다.
     *
     * <p>{@code support} 는 자원이 아니라 여러 자원이 같이 쓰는 것들이다. 거기서 자원 패키지를
     * 부르기 시작하면 <b>공용이 아니라 그 자원의 일부</b>가 되고, 다음 사람이 어느 쪽에
     * 코드를 둘지 매번 판단하게 된다.
     *
     * <p><b>허용 목록은 라이브러리를 적는다.</b> {@code io.swagger..} 는 `Q41` 에서 들어왔다 —
     * {@code OpenApiConfig} 가 스펙을 그리는 표기를 물리는데, 그것은 자원이 아니라
     * Jackson 과 같은 급의 도구다. <b>여기 더할 수 있는 것은 라이브러리뿐</b>이고
     * {@code com.projectshop.shop.<자원>} 은 못 더한다 — 더하는 순간 규칙이 뜻을 잃는다.
     */
    @ArchTest
    static final ArchRule 공용_도구는_자원을_모른다 =
            noClasses()
                    .that().resideInAPackage("..support..")
                    .should().dependOnClassesThat()
                    .resideOutsideOfPackages("java..", "javax..", "jakarta..",
                            "org.springframework..", "org.slf4j..", "com.fasterxml..", "tools.jackson..",
                            "io.swagger..", "io.micrometer..", "org.apache.kafka..",
                            "software.amazon.awssdk..",
                            "com.projectshop.shop.support..", "com.projectshop.shop.error..")
                    .because("support 가 자원을 부르면 공용이 아니라 그 자원의 일부가 된다"
                            + " (coding-rules.md 「자원이 아닌데 패키지를 파는 경우」)."
                            + " 자원을 열거하지 않고 허용을 적는다 — 열거하면 새 자원 패키지가 생길 때마다 샌다."
                            + " accessClassesThat 은 호출만 보고 필드 선언을 안 봐서 dependOnClassesThat 이다."
                            + " io.micrometer 는 Spring·Jackson 과 같은 자리다 — 지표 라이브러리고 자원이 아니다 (Q53)."
                            + " org.apache.kafka 도 같다 — 발행기가 ProducerRecord 로 헤더를 싣는다 (33b)."
                            + " software.amazon.awssdk 도 같다 — 저장소를 S3 API 로 부르는 클라이언트고"
                            + " 자원이 아니다. 로컬 MinIO 와 배포 R2 가 같은 API 라 클라이언트가 하나다 (26).");

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
     *
     * <p><b>못 보는 것 — 흐름이 아니라 호출 유무를 잰다.</b> {@code orderBy} 를 부르고도
     * 그 결과를 버린 뒤 {@code " order by " + sort} 로 요청 문자열을 잇는 코드는 이 규칙을 통과한다.
     * 그 자리까지 보려면 데이터 흐름 분석이 필요하고 ArchUnit 이 그것을 안 준다 —
     * <b>거기는 CodeQL 이 보는 자리다</b>({@code 2e-5} 가 {@code JdbcClient.sql} 을 싱크로 등록했다).
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


    /**
     * 「입력과 출력」 — 요청 본문은 Bean Validation 을 거친다(`Q32`, `D14`).
     *
     * <p>{@code @RequestBody} 에 {@code @Valid} 가 없으면 <b>검증이 조용히 빠진다.</b> 컴파일도
     * 통과하고 record 의 {@code @Size}·{@code @NotBlank} 는 그대로 있어서 <b>붙어 있는 것처럼 보인다.</b>
     * 그 요청은 400 대신 DB 제약에서 500 으로 튄다({@code D23} 「길이 상한은 앱과 DB 양쪽에 둔다」).
     * 지금 29곳이 전부 붙어 있어서 기준선 없이 건다.
     */
    @ArchTest
    static final ArchRule 요청_본문은_검증을_거친다 =
            methods()
                    .that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
                    .and(본문을_받는다())
                    .should(본문에_Valid_가_붙는다())
                    .because("@Valid 가 빠지면 record 의 검증 애너테이션이 그대로 있어도 안 돈다"
                            + " (security-baseline.md 「입력과 출력」) — 400 이 나갈 요청이 DB 제약에서 500 이 된다");

    /**
     * 「트랜잭션 경계」 — 읽기 전용 조회에 트랜잭션을 안 건다(`Q32`, {@code concurrency-rules.md}).
     *
     * <p>{@code *Query} 는 읽기 쪽 서비스다. 거기에 {@code @Transactional} 이 붙으면 커넥션을 응답이
     * 끝날 때까지 쥔다 — 목록 하나가 풀에서 커넥션 하나를 요청 내내 가져간다. 메서드와 클래스 둘 다 본다.
     */
    @ArchTest
    static final ArchRule 조회는_트랜잭션을_안_건다 =
            noMethods()
                    .that().areDeclaredInClassesThat().haveSimpleNameEndingWith("Query")
                    .should().beAnnotatedWith(Transactional.class)
                    .because("읽기 전용 조회에 트랜잭션을 걸면 커넥션을 오래 쥔다 (concurrency-rules.md 「트랜잭션 경계」)");

    @ArchTest
    static final ArchRule 조회_클래스는_트랜잭션을_안_건다 =
            noClasses()
                    .that().haveSimpleNameEndingWith("Query")
                    .should().beAnnotatedWith(Transactional.class)
                    .because("클래스에 걸면 모든 메서드가 트랜잭션이 된다 (concurrency-rules.md 「트랜잭션 경계」)");

    /**
     * 「트랜잭션 경계」 — 트랜잭션 안에서 바깥 시스템을 안 부른다(`Q32`, {@code concurrency-rules.md}).
     *
     * <p><b>호출 사슬 전체를 걷는다</b>(사용자 결정, 2026-09-13). 직접 호출만 보면 빈 규칙이다 —
     * 지금 코드가 게이트웨이를 전부 {@code Retries.on(() -> gateway.approve(...))} 람다와
     * private 헬퍼 뒤에서 부른다. ArchUnit 1.x 는 람다 안의 호출을 감싼 메서드의 호출로 귀속시키므로
     * 람다는 걷힌다.
     *
     * <p><b>전파는 예외가 못 된다.</b> {@code REQUIRES_NEW}·{@code NOT_SUPPORTED} 는 바깥 트랜잭션을
     * <b>중단</b>할 뿐 닫지 않는다 — 커넥션과 잠금이 그대로다. 문서가 막는 것이 「응답을 기다리는 동안
     * 잠금이 유지된다」라 안쪽에 경계가 새로 생겨도 위반이다. 대신 <b>지나온 경계를 메시지에 적는다</b> —
     * 읽는 사람이 「REQUIRES_NEW 라 괜찮다」고 오해하는 자리를 미리 막는다.
     *
     * <p><b>못 보는 것 둘.</b> (1) {@code TransactionTemplate} 블록은 애너테이션이 아니라 여기 안 잡힌다 —
     * {@code NotificationService} 하나뿐이고 그 배치(발송은 블록 밖)는 {@code NotificationSendTest} 가 잰다.
     * (2) 인터페이스 뒤 구현체는 정적으로 못 따라간다 — 지금 게이트웨이·발송기가 둘 다 구체 클래스라 없다.
     *
     * <p>바깥 시스템 목록은 여기 셋이다. 넷째가 생기면 {@link #바깥} 에 더한다.
     */
    @ArchTest
    static final ArchRule 트랜잭션_안에서_바깥을_안_부른다 =
            methods()
                    .that(트랜잭션_경계다())
                    .should(사슬_어디서도_안_부른다())
                    .because("PG·메일 응답을 기다리는 동안 잠금이 유지된다 (concurrency-rules.md 「트랜잭션 경계」)."
                            + " 느린 PG 하나가 같은 멱등키의 뒤 요청을 전부 409 로 만든다");

    /**
     * 「저장은 UTC」 — 시각은 {@code OffsetDateTime} 이다(`Q32`, {@code time-rules.md}).
     *
     * <p>{@code LocalDateTime} 은 시간대가 없어서 같은 값이 서버 설정에 따라 다른 순간을 가리킨다.
     * <b>드는 것</b>을 막는다 — 필드·반환 타입·파라미터. 지금 셋 다 0이고 시각은 217곳이 전부 {@code OffsetDateTime} 이다.
     *
     * <p><b>스쳐 가는 것은 안 막는다.</b> {@code BusinessCalendar.endOfDay} 가
     * {@code date.atTime(...).atZone(SEOUL)} 로 KST 하루 경계를 만드는 중간에 {@code LocalDateTime} 이 한 번 지나간다 —
     * 어디에도 안 담기고 바로 {@code OffsetDateTime} 이 된다. 의존(`dependOnClassesThat`)으로 재면 그 자리가 걸려서
     * 시그니처로 잰다. {@code ZonedDateTime} 도 같은 이유로 안 막는다 — 「판단은 KST」의 도구다.
     */
    @ArchTest
    static final ArchRule 시각은_OffsetDateTime_이다 =
            noFields()
                    .should().haveRawType(LocalDateTime.class)
                    .because("LocalDateTime 은 시간대가 없어 서버 설정에 따라 다른 순간이 된다 (time-rules.md 「저장은 UTC」)");

    @ArchTest
    static final ArchRule 시각을_LocalDateTime_으로_안_주고받는다 =
            noMethods()
                    .should().haveRawReturnType(LocalDateTime.class)
                    .orShould().haveRawParameterTypes(하나라도(LocalDateTime.class))
                    .because("경계를 LocalDateTime 으로 넘기면 받는 쪽이 시간대를 짐작한다 (time-rules.md 「저장은 UTC」)");

    /** 트랜잭션 안에서 부르면 안 되는 바깥 시스템. */
    private static final Set<String> 바깥 = Set.of(
            MockPaymentGateway.class.getName(), MockNotificationSender.class.getName(),
            // 브로커도 바깥이다(`33b`). 발행기가 ack 를 기다리는 동안 트랜잭션이 열려 있으면
            // 그 잠금이 손님 요청을 막는다 — 그래서 집기·보내기·도장을 셋으로 갈랐다.
            org.springframework.kafka.core.KafkaTemplate.class.getName());

    private static DescribedPredicate<JavaMethod> 본문을_받는다() {
        return DescribedPredicate.describe("@RequestBody 를 받는",
                method -> method.getParameters().stream()
                        .anyMatch(parameter -> parameter.isAnnotatedWith(RequestBody.class)));
    }

    private static ArchCondition<JavaMethod> 본문에_Valid_가_붙는다() {
        return new ArchCondition<>("의 @RequestBody 에 @Valid 가 붙는다") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                boolean ok = method.getParameters().stream()
                        .filter(parameter -> parameter.isAnnotatedWith(RequestBody.class))
                        .allMatch(parameter -> parameter.isAnnotatedWith(Valid.class));

                events.add(new SimpleConditionEvent(method, ok,
                        method.getFullName() + (ok ? " 은 검증을 거친다" : " 의 @RequestBody 에 @Valid 가 없다")));
            }
        };
    }

    /** 메서드나 그 클래스에 {@code @Transactional} 이 붙었나. */
    private static DescribedPredicate<JavaMethod> 트랜잭션_경계다() {
        return DescribedPredicate.describe("@Transactional 인",
                method -> method.isAnnotatedWith(Transactional.class)
                        || method.getOwner().isAnnotatedWith(Transactional.class));
    }

    /**
     * 호출 사슬 어디서도 {@link #바깥} 을 안 부르나. 깊이 우선으로 걷고 저장소 밖 클래스에서는 멈춘다.
     * 방문 집합이 순환을 끊는다. 위반 메시지는 사슬 전체와 지나온 트랜잭션 경계를 든다.
     */
    private static ArchCondition<JavaMethod> 사슬_어디서도_안_부른다() {
        return new ArchCondition<>("가 호출 사슬 어디서도 바깥 시스템을 안 부른다") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                List<String> hits = new ArrayList<>();
                Deque<String> path = new ArrayDeque<>(List.of(짧은_이름(method)));
                걷는다(method, path, new HashSet<>(Set.of(method)), hits);

                events.add(new SimpleConditionEvent(method, hits.isEmpty(),
                        hits.isEmpty()
                                ? 짧은_이름(method) + " 은 바깥을 안 부른다"
                                : 짧은_이름(method) + " 이 트랜잭션 안에서 바깥을 부른다: " + String.join(" / ", hits)));
            }
        };
    }

    private static void 걷는다(JavaMethod from, Deque<String> path, Set<JavaMethod> visited, List<String> hits) {
        for (JavaMethodCall call : from.getMethodCallsFromSelf()) {
            JavaClass owner = call.getTargetOwner();
            if (바깥.contains(owner.getName())) {
                hits.add(String.join(" → ", path) + " → " + owner.getSimpleName() + "." + call.getName());
                continue;
            }
            if (!owner.getPackageName().startsWith("com.projectshop.shop")) {
                continue;
            }
            Optional<JavaMethod> target = call.getTarget().resolveMember();
            if (target.isEmpty() || !visited.add(target.get())) {
                continue;
            }
            path.addLast(짧은_이름(target.get()) + 경계(target.get()));
            걷는다(target.get(), path, visited, hits);
            path.removeLast();
        }
    }

    /** 그 메서드가 새 전파로 경계를 만들면 표시한다 — 예외가 아니라 <b>읽는 사람을 위한 표식</b>이다. */
    private static String 경계(JavaMethod method) {
        Transactional tx = method.isAnnotatedWith(Transactional.class)
                ? method.getAnnotationOfType(Transactional.class)
                : method.getOwner().isAnnotatedWith(Transactional.class)
                        ? method.getOwner().getAnnotationOfType(Transactional.class)
                        : null;
        if (tx == null || tx.propagation() == Propagation.REQUIRED) {
            return "";
        }
        return " [" + tx.propagation() + " 경계 — 바깥 잠금은 유지된다]";
    }

    private static String 짧은_이름(JavaMethod method) {
        return method.getOwner().getSimpleName() + "." + method.getName();
    }

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


    /**
     * 「판정을 안 지나는 입구」 — 쓰기 입구는 판정 엔진을 지나거나 소유 목록에 있다(`Q56`, `D6`).
     *
     * <p><b>그전에는 규칙이 없었다.</b> 이 클래스가 스스로 「판정을 서비스에서 부르나는 사람이 든다」를
     * 못 보는 것에 적어 뒀고, 새 쓰기 입구가 생길 때마다 어느 쪽을 쓸지 매번 판단했다.
     *
     * <p><b>두 스타일이 다 맞다</b>({@code permission-rules.md} 「판정을 안 지나는 입구」).
     * 역할이 답을 바꾸는 동작은 판정 엔진이 막고, <b>소유자 말고 아무도 못 하고 소유자는 언제나 되는</b>
     * 동작은 SQL 소유 조건이 막는다 — 어느 역할도 남의 주문을 대신 결제하지 않는다.
     * 가르는 물음은 「역할이 답을 바꾸나」 하나다.
     *
     * <p><b>목록이 메서드 단위다</b>(사용자 선택 ②). 컨트롤러 단위로 들면 이미 예외로 찍힌 클래스 안에서
     * <b>기본이 통과가 된다</b> — {@link #소유가_곧_권한인_입구} 의 {@code MeController} 가 그 자리다:
     * 쓰기 입구 일곱 중 넷은 판정을 제대로 지나는데, 클래스로 열면 그 사실이 안 잡히고
     * 새 입구가 판정을 빠뜨려도 조용히 통과한다. 이 규칙이 막으려던 바로 그 일이다.
     *
     * <p>호출 사슬을 걷는 것은 {@code 트랜잭션_안에서_바깥을_안_부른다} 와 같은 {@link #걷는다} 다 —
     * 판정을 컨트롤러가 직접 부르는 자리가 거의 없고 서비스 안쪽 private 헬퍼에 있다.
     */
    @ArchTest
    static final ArchRule 쓰기_입구는_판정을_지나거나_소유_목록에_있다 =
            methods()
                    .that(쓰기_입구다())
                    .should(판정을_지나거나_목록에_있다());

    /**
     * 판정 엔진을 안 지나도 되는 쓰기 입구. <b>여는 것이 아니라 적어 두는 것이다.</b>
     *
     * <p>{@code permission-rules.md} 「판정을 안 지나는 입구」의 표와 같아야 한다.
     * 항목이 실재하는 메서드인지는 {@link #목록의_입구가_실재한다} 가 잰다 —
     * 이름이 바뀐 줄은 <b>아무도 안 막는 죽은 줄</b>이고, 그것이 다음 예외를 몰래 들여보내는 자리가 된다.
     */
    private static final Set<String> 소유가_곧_권한인_입구 = Set.of(
            // 소유 조건이 막는 셋. SQL 의 where 가 곧 권한이다.
            "PaymentController.pay",
            "OrderController.create",
            "CartController.add",
            "CartController.changeQuantity",
            "CartController.remove",
            // 본인 계정. 판정할 역할이 없다 — 남이 대신 탈퇴하거나 이메일을 바꾸는 경로가 없다.
            // 탈퇴는 비밀번호를 다시 받고, 이메일 변경은 확인 토큰이 막는다.
            "MeController.withdraw",
            "MeController.changeEmail",
            "MeController.confirmEmail",
            "MeController.changePassword",
            // 인증 이전. 판정할 사람이 아직 없다.
            "AuthController.signUp",
            "AuthController.logIn",
            "AuthController.logOut",
            "AuthController.requestPasswordReset",
            "AuthController.confirmPasswordReset");

    /** 목록의 항목이 실재하는 쓰기 입구인가. 죽은 줄을 남기지 않는다(`Q56`) */
    @ArchTest
    static final ArchRule 목록의_입구가_실재한다 =
            methods()
                    .that(쓰기_입구다())
                    .should(목록을_다_쓴다());



    /**
     * 문서의 표와 위 목록이 같나(`Q65`).
     *
     * <p><b>대조가 사람 손이었다.</b> {@code permission-rules.md} 가 「이 표와 그 목록이 같아야 한다」고
     * 적어 두고 대조를 사람에게 맡겼는데, {@code Q56} 이 그 자리에서 이미 <b>표 셋 대 실물 다섯</b>로
     * 갈려 있던 것을 찾았고 <b>세운 날 숫자가 또 한 칸 틀렸다</b>.
     *
     * <p>그래서 문서 쪽 표기를 {@code Class.method} 로 바꿨다 — 산문으로 적혀 있으면 기계가 못 센다.
     * 그 이름이 실재하는 메서드인지는 {@link #목록의_입구가_실재한다} 가, 실재하는 클래스인지는
     * {@code IdentifierReferenceTest} 가 각각 잰다.
     */
    @Test
    @DisplayName("문서의 「판정을 안 지나는 입구」 표가 목록과 같다")
    void 문서의_표와_목록이_같다() throws IOException {
        Path doc = Path.of("..", "doc", "reference", "permission-rules.md");
        List<String> lines = Files.readAllLines(doc, StandardCharsets.UTF_8);

        Pattern entry = Pattern.compile("`([A-Z][A-Za-z0-9]*Controller\\.[a-zA-Z0-9]+)`");
        Set<String> inDoc = new TreeSet<>();
        boolean inSection = false;
        for (String line : lines) {
            if (line.startsWith("## ")) {
                inSection = line.contains("판정을 안 지나는 입구");
                continue;
            }
            if (!inSection) {
                continue;
            }
            Matcher matcher = entry.matcher(line);
            while (matcher.find()) {
                inDoc.add(matcher.group(1));
            }
        }

        assertThat(inDoc)
                .as("문서의 표와 소유가_곧_권한인_입구 목록이 갈렸다. "
                        + "한쪽만 고치면 다음 예외가 몰래 들어온다 (permission-rules.md)")
                .isEqualTo(new TreeSet<>(소유가_곧_권한인_입구));
    }

    /**
     * 순수 계산이 서비스 안에 남는 것을 막는다(`Q68`).
     *
     * <p><b>{@code RefundMath} 를 뺀 강제 지점은 「옮긴 계산이 되돌아오는 것」만 막았다.</b>
     * 새 계산을 {@code *Service} 안에 {@code private static} 으로 쓰면 아무도 안 잡고,
     * 그 규칙은 {@code coding-rules.md}·{@code testing-strategy.md} 에만 있어 <b>문서 5위</b>였다.
     *
     * <p><b>시그니처로 판정한다.</b> 「필드를 안 쓴다」는 ArchUnit 이 못 보므로
     * <b>바이트코드가 무엇을 부르나</b>로 잰다 — 바깥(DB·시계·지표)을 하나도 안 부르는 {@code static}
     * 메서드는 <b>계산</b>이고, 계산은 자기 클래스로 나가야 시험할 수 있다(`D15`).
     *
     * <p><b>{@code private} 도 센다.</b> 접근 제어자를 좁히는 것으로 이 규칙을 피할 수 있으면
     * 규칙이 아니라 권고다.
     */
    @ArchTest
    static final ArchRule 순수_계산은_서비스에_안_남는다 =
            methods()
                    .that(계산을_담는_클래스의_static_메서드다())
                    .should(바깥을_부르거나_계산이_아니다());

    /** 쓰기 입구 — {@code *Controller} 의 public 메서드 중 비-GET 매핑이 붙은 것 */
    private static DescribedPredicate<JavaMethod> 쓰기_입구다() {
        return DescribedPredicate.describe("컨트롤러의 쓰기 입구인",
                method -> method.getOwner().getSimpleName().endsWith("Controller")
                        && method.getModifiers().contains(JavaModifier.PUBLIC)
                        && 쓰기_매핑.stream().anyMatch(method::isAnnotatedWith));
    }

    private static final List<Class<? extends java.lang.annotation.Annotation>> 쓰기_매핑 = List.of(
            PostMapping.class, PutMapping.class, PatchMapping.class, DeleteMapping.class);

    /**
     * 호출 사슬 어딘가에서 판정 엔진을 부르거나, 소유 목록에 있나.
     *
     * <p>위반 메시지가 <b>사슬 전체</b>를 든다. 「판정을 안 부른다」만 찍으면 어디까지 갔다가 없었는지를
     * 다시 손으로 따라가야 한다 — {@code 트랜잭션_안에서_바깥을_안_부른다} 와 같은 판단이다.
     */
    private static ArchCondition<JavaMethod> 판정을_지나거나_목록에_있다() {
        return new ArchCondition<>("가 판정을 지나거나 소유 목록에 있다") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                String name = 짧은_이름(method);
                if (소유가_곧_권한인_입구.contains(name)) {
                    events.add(new SimpleConditionEvent(method, true, name + " 은 소유 조건이 막는다"));
                    return;
                }

                List<String> hits = new ArrayList<>();
                Deque<String> path = new ArrayDeque<>(List.of(name));
                판정까지_걷는다(method, path, new HashSet<>(Set.of(method)), hits);

                events.add(new SimpleConditionEvent(method, !hits.isEmpty(),
                        hits.isEmpty()
                                ? name + " 이 판정을 안 지나고 소유 목록에도 없다. "
                                        + "역할이 답을 바꾸면 PermissionEvaluator.decide 를 부르고, "
                                        + "소유가 곧 권한이면 permission-rules.md 표와 이 목록에 같이 적는다"
                                : name + " 은 판정을 지난다: " + hits.get(0)));
            }
        };
    }

    /** 목록에 적혔는데 실재하지 않는 입구를 찾는다. 죽은 줄은 다음 예외를 몰래 들여보낸다 */
    private static ArchCondition<JavaMethod> 목록을_다_쓴다() {
        return new ArchCondition<>("의 소유 목록에 죽은 줄이 없다") {
            private final Set<String> 남은_것 = new HashSet<>();

            /**
             * <b>평가마다 비운다</b>(마무리 17차 독립 리뷰). 생성자에서만 채우면 같은 JVM 에서
             * 이 규칙이 두 번 돌 때 <b>두 번째부터 집합이 비어 무조건 통과한다</b> —
             * 「죽은 줄을 막는다」가 이 규칙의 존재 이유인데 그 자체가 죽은 줄이 된다.
             * 지금은 {@code test} 태스크 한 번뿐이라 안 드러난다.
             */
            @Override
            public void init(Collection<JavaMethod> allMethods) {
                남은_것.clear();
                남은_것.addAll(소유가_곧_권한인_입구);
            }

            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                남은_것.remove(짧은_이름(method));
            }

            @Override
            public void finish(ConditionEvents events) {
                events.add(new SimpleConditionEvent(소유가_곧_권한인_입구, 남은_것.isEmpty(),
                        남은_것.isEmpty()
                                ? "소유 목록이 전부 실재한다"
                                : "소유 목록에 없는 입구가 적혀 있다: " + 남은_것
                                        + ". 이름이 바뀌었으면 같이 고치고, 사라졌으면 줄을 지운다"));
            }
        };
    }

    /** {@link #걷는다} 와 같은 모양이다. 찾는 것이 바깥 시스템이 아니라 판정 엔진 호출이다 */
    private static void 판정까지_걷는다(JavaMethod from, Deque<String> path, Set<JavaMethod> visited,
            List<String> hits) {

        for (JavaMethodCall call : from.getMethodCallsFromSelf()) {
            JavaClass owner = call.getTargetOwner();
            if (owner.isEquivalentTo(PermissionEvaluator.class)
                    && 판정_메서드.contains(call.getName())) {
                hits.add(String.join(" → ", path) + " → decide");
                return;
            }
            if (!owner.getPackageName().startsWith("com.projectshop.shop")) {
                continue;
            }
            Optional<JavaMethod> target = call.getTarget().resolveMember();
            if (target.isEmpty() || !visited.add(target.get())) {
                continue;
            }
            path.addLast(짧은_이름(target.get()));
            판정까지_걷는다(target.get(), path, visited, hits);
            path.removeLast();
            if (!hits.isEmpty()) {
                return;
            }
        }
    }

    private static final Set<String> 판정_메서드 = Set.of("decide", "allowedActions");

    private ArchitectureTest() {
    }


    /** 계산이 숨기 쉬운 자리. 이 셋은 「무엇을 언제 하나」를 드는 클래스라 <b>계산의 집이 아니다</b>(`D23` 「계층」). */
    private static DescribedPredicate<JavaMethod> 계산을_담는_클래스의_static_메서드다() {
        return DescribedPredicate.describe("서비스·스위퍼·배치의 static 메서드인",
                method -> {
                    String owner = method.getOwner().getSimpleName();
                    return (owner.endsWith("Service") || owner.endsWith("Sweeper")
                            || owner.endsWith("Batch"))
                            && method.getModifiers().contains(JavaModifier.STATIC)
                            && !계산이_아닌_static.containsKey(owner + "." + method.getName());
                });
    }

    /**
     * {@code static} 인데 계산이 아닌 자리와 그 근거.
     *
     * <p><b>근거 없이 이름만 넣지 않는다.</b> 근거 칸이 없으면 이 목록이
     * <b>계산을 서비스에 두고 싶을 때 도망칠 자리</b>가 된다.
     */
    private static final Map<String, String> 계산이_아닌_static = Map.of(
            "TransactionPurgeService.purgeableOrderIds",
            "SQL 을 짜는 자리라 계산이 아니다. 조건이 곧 파기 순서고 그것은 DB 에 붙어 있다",
            "LoginAttemptService.key",
            "저장소 열쇠 규칙이다. 그 서비스가 쓰는 Redis 에 붙어 있어 도메인이 아니다",
            "PaymentService.fingerprint",
            "그 서비스의 private record 를 짜 넣는다. 밖으로 빼면 그 record 도 같이 나가고 쓰는 곳은 여전히 하나다",
            "RefundSweeper.reasonOf",
            "상태를 사유 코드로 옮기는 표다. 그 스위퍼가 쓰는 전이에 붙어 있다",
            "RefundService.upper",
            "문자열 손질이다. 도메인 규칙이 아니라 입력 정규화고 쓰는 곳이 하나다",
            "RefundService.blankToNull",
            "〃");

    /**
     * 바깥을 하나라도 부르면 계산이 아니다.
     *
     * <p><b>부르는 것으로 잰다.</b> 「필드를 안 쓴다」는 ArchUnit 이 못 보고, 「인자가 도메인 타입뿐」은
     * {@code long}·{@code String} 이 섞이면 못 가른다 — <b>DB·시계·지표를 하나도 안 부르는 {@code static}</b>
     * 이면 그 메서드는 입력만으로 답이 정해진다. 그것이 계산이고, 계산은 자기 클래스로 나가야 시험된다(`D15`).
     *
     * <p><b>직접 호출만 본다.</b> 사슬을 걷지 않으므로 <b>private 헬퍼 뒤에 숨기면 안 걸린다</b> —
     * 그물이 성기다는 것을 여기 적어 둔다. 규칙 자체는 여전히 {@code coding-rules.md} 「계층」이다.
     */
    private static ArchCondition<JavaMethod> 바깥을_부르거나_계산이_아니다() {
        return new ArchCondition<>("바깥(DB·시계·지표)을 부르거나 계산이 아니어야") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                boolean touchesOutside = method.getMethodCallsFromSelf().stream()
                        .map(JavaMethodCall::getTargetOwner)
                        .map(JavaClass::getName)
                        .anyMatch(계산_바깥::contains);
                if (touchesOutside || !계산의_모양이다(method)) {
                    return;
                }
                events.add(SimpleConditionEvent.violated(method,
                        method.getOwner().getSimpleName() + "." + method.getName()
                                + " 은 바깥을 하나도 안 부르는 static 이다 — 계산이면 자기 클래스로 뺀다"
                                + "(`RefundMath` 가 그 꼴이다). 계산이 아니면 "
                                + "ArchitectureTest.계산이_아닌_static 에 근거와 함께 적는다"));
            }
        };
    }

    /**
     * 계산의 모양인가.
     *
     * <p><b>바깥을 안 부르는 static 이 전부 계산은 아니다.</b> 처음 쟀을 때 열일곱이 걸렸는데
     * 대부분이 <b>가드</b>({@code notFound}·{@code require*})와 <b>행 매퍼</b>였다 —
     * 예외를 던지는 것은 값을 내는 것이 아니고, {@code ResultSet} 을 받는 것은 DB 모양에 묶여 있다.
     *
     * <p>그래서 셋을 뺀다: <b>예외를 던지는 것</b> · <b>{@code ResultSet} 을 받는 것</b> ·
     * <b>값을 안 돌려주는 것</b>. 남는 것이 「입력을 넣으면 값이 나오는」 자리고 그것이 계산이다.
     */
    private static boolean 계산의_모양이다(JavaMethod method) {
        if (method.getRawReturnType().getName().equals("void")) {
            return false;
        }
        if (method.getRawParameterTypes().stream()
                .anyMatch(type -> type.getName().equals("java.sql.ResultSet"))) {
            return false;
        }
        return method.getConstructorCallsFromSelf().stream()
                .map(call -> call.getTargetOwner())
                .noneMatch(owner -> owner.isAssignableTo(Throwable.class));
    }

    /** 바깥이라고 부르는 것들. 이 중 하나라도 부르면 입력만으로 답이 안 정해진다. */
    private static final Set<String> 계산_바깥 = Set.of(
            "org.springframework.jdbc.core.simple.JdbcClient",
            "io.micrometer.core.instrument.MeterRegistry",
            "io.micrometer.core.instrument.Counter",
            "java.time.Clock",
            "java.time.OffsetDateTime",
            "java.time.LocalDate",
            "java.time.Instant",
            "org.slf4j.Logger");
}
