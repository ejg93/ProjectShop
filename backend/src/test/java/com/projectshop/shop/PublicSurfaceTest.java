package com.projectshop.shop;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import org.springframework.stereotype.Component;

/**
 * 「{@code public} 에는 근거가 있어야 한다」를 저장소 전체에 건다(`43a-27`).
 *
 * <p><b>같은 물음에 반대 답이 나오면서 규칙이 됐다.</b> {@code 43a-20} 은 {@code Scope} 를 열었고
 * {@code 43a-25} 는 {@code NotificationTemplates.Version} 을 좁혔다. 가른 것은
 * <b>패키지 밖에서 실제로 쓰나</b> 하나인데, <b>저장소 전체가 그런지는 아무도 안 봤다.</b>
 *
 * <p><b>열어 두면 조용히 굳는다.</b> 아무도 안 쓰는 {@code public} 은 다음 사람에게
 * 「여기로 들어와도 된다」는 신호라, 계층을 지나 부르는 코드가 그 자리로 들어온다.
 * 좁히면 그런 코드가 컴파일이 안 된다 — <b>강제 지점 1위</b>다.
 *
 * <h2>{@link ArchitectureTest} 와 파일을 가른 이유</h2>
 *
 * <p><b>읽는 범위가 다르다.</b> 저쪽은 {@code DoNotIncludeTests} 로 {@code main} 만 보는데,
 * 이 규칙은 <b>테스트도 소비자로 센다</b> — {@code FieldGroupTest} 가 {@code auth} 에 살면서
 * {@code account.UserFields}·{@code order.OrderFields} 를 표와 대조한다. 그건 권한 관심사라
 * 그 자리가 맞고, 테스트를 안 세면 <b>좁힐 수 없는 것을 좁히라고 시킨다.</b>
 *
 * <p><b>같은 파일에서 범위만 바꿀 수는 없다.</b> {@code @AnalyzeClasses} 가 클래스 단위고,
 * 저쪽의 순환 규칙이 테스트 패키지를 같이 보면 <b>가짜 순환</b>이 뜬다.
 *
 * <p>이름을 적어 빼는 예외 목록을 안 만든다. 「테스트가 쓴다」를 예외로 적기 시작하면
 * 그 목록이 곧 낡고, <b>왜 뺐는지가 아니라 언제 넣었는지만 남는다.</b>
 */
@AnalyzeClasses(packages = "com.projectshop.shop")
class PublicSurfaceTest {

    /**
     * 스프링이 부르는 것은 뺀다.
     *
     * <p>컨트롤러·서비스·설정은 우리 코드가 아니라 컨테이너가 부르므로 「패키지 밖 참조」가
     * 0인 것이 정상이다. {@code @RestController}·{@code @Service}·{@code @Configuration}·
     * {@code @SpringBootApplication} 이 전부 {@code @Component} 를 메타 표시로 달고 있어서
     * <b>한 줄로 걸린다</b> — 목록을 적으면 새 표시가 생길 때 샌다.
     */
    @ArchTest
    static final ArchRule public_에는_근거가_있다 =
            classes()
                    .that().arePublic()
                    .and().areTopLevelClasses()
                    .and().areNotMetaAnnotatedWith(Component.class)
                    .should(usedFromAnotherPackage())
                    .because("아무도 안 쓰는 public 은 「여기로 들어와도 된다」는 신호가 된다"
                            + " (coding-rules.md, 43a-27). 좁히면 잘못 쓰는 코드가 컴파일이 안 된다");

    private static ArchCondition<JavaClass> usedFromAnotherPackage() {
        return new ArchCondition<>("패키지 밖에서 쓰인다") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                boolean used = item.getDirectDependenciesToSelf().stream()
                        .anyMatch(dependency -> !dependency.getOriginClass().getPackageName()
                                .equals(item.getPackageName()));
                if (!used) {
                    events.add(SimpleConditionEvent.violated(item,
                            "%s 이 public 인데 패키지 밖에서 아무도 안 쓴다. 좁히거나, 못 좁히는 이유를 적는다"
                                    .formatted(item.getName())));
                }
            }
        };
    }

    private PublicSurfaceTest() {
    }
}
