# 스택과 버전

무엇을 어느 버전으로 쓰는지 적고, **기억으로 쓰면 틀리는 자리**를 표시한다.

## 이 문서는 API 레퍼런스가 아니다

메서드 이름이나 설정 키를 여기 옮겨 적지 않는다. 두 가지가 걸린다.

- 옮겨 적는 사람이 그 버전을 정확히 모르면 **틀린 것이 문서로 굳는다.** 다음 사람은 그걸 근거로 믿는다
- 라이브러리가 올라가면 낡는데, 낡은 줄 모르고 본다

API 가 필요하면 아래 공식 문서를 연다. **여기 적는 것은 "어디를 봐야 하나" 와 "무엇을 조심하나" 뿐이다.**

## 버전

| 대상 | 버전 | 어디에 박혀 있나 |
|---|---|---|
| Spring Boot | 4.1.0 | `backend/build.gradle.kts` |
| Java | 25 | 같은 파일의 toolchain |
| Gradle | 9.5.1 | `gradle/wrapper/gradle-wrapper.properties` |
| PostgreSQL | 17-alpine | `docker-compose.yml` |
| Redis | 7-alpine | `docker-compose.yml`. 테스트 컨테이너도 같은 이미지다 |
| Testcontainers | 1.21.4 | `build.gradle.kts` 의 BOM |
| Caffeine | 3.2.4 | 안 적는다. **Boot BOM 이 관리한다** |
| Jackson | 3.1.4 | 안 적는다. `starter-webmvc` 가 딸려 온다 |
| Spring Security | 7.1.0 | 아직 의존성에 없다. 청크 5 에서 들어온다 |

**버전을 물으면 이 표가 아니라 위 파일들을 본다.** 표가 낡을 수 있다.

## 공식 문서

| 대상 | 링크 |
|---|---|
| Spring Boot | https://docs.spring.io/spring-boot/index.html |
| Spring Framework | https://docs.spring.io/spring-framework/reference/index.html |
| Spring Security | https://docs.spring.io/spring-security/reference/index.html |
| PostgreSQL 17 | https://www.postgresql.org/docs/17/index.html |
| Testcontainers | https://java.testcontainers.org/ |

설계 근거로 삼은 자료(Zalando·OWASP·Stripe 등)는 `external-references.md` 에 따로 있다.
이 표는 구현할 때 여는 것이다.

## 기억으로 쓰면 틀리는 자리

### Boot 4 는 스타터 이름이 3.x 와 다르다

`build.gradle.kts` 에 실제로 들어 있는 이름이다.

| Boot 4 | 3.x 에서 쓰던 이름 |
|---|---|
| `spring-boot-starter-webmvc` | `spring-boot-starter-web` |
| `spring-boot-starter-flyway` | `flyway-core` 를 직접 넣었다 |
| `spring-boot-starter-webmvc-test` | `spring-boot-starter-test` 하나였다 |

**테스트 스타터가 모듈별로 쪼개졌다.** `-actuator-test`, `-data-jpa-test`, `-flyway-test`,
`-validation-test`, `-webmvc-test` 가 따로 있다.

의존성을 추가할 때 **기억으로 쓰지 말고 `build.gradle.kts` 에 이미 있는 이름의 형태를 따른다.**
여기 없는 스타터가 필요하면 공식 문서에서 확인한다.

여기 적은 셋 말고 무엇이 더 바뀌었는지는 확인하지 않았다.

### Jackson 3 이라 패키지가 `tools.jackson` 이다

Boot 4 는 Jackson 3 을 쓴다. **네임스페이스가 통째로 바뀌었다.**

| Boot 4 (Jackson 3) | 3.x (Jackson 2) |
|---|---|
| `tools.jackson.databind.ObjectMapper` | `com.fasterxml.jackson.databind.ObjectMapper` |
| `tools.jackson.core.JacksonException` | `com.fasterxml.jackson.core.JsonProcessingException` |

`com.fasterxml.jackson.core:jackson-annotations` 는 아직 2.x 로 남아 있어서
**의존성 트리에 두 이름이 같이 보인다.** 애너테이션만 옛 이름이다.

`JacksonException` 은 `RuntimeException` 이라 `throws` 선언이 필요 없다.
Jackson 2 습관으로 checked 예외를 잡으려 하면 컴파일이 안 된다.

### Boot 4 는 자동설정 클래스도 모듈별로 옮겼다

스타터 이름만 갈린 게 아니다. **클래스가 앉은 패키지가 같이 움직였다.**
IDE 자동 완성이 옛 이름을 안 찾아 주고, 오류는 `package ... does not exist` 로만 나온다.

| Boot 4 | 3.x |
|---|---|
| `org.springframework.boot.web.server.autoconfigure.ServerProperties` | `org.springframework.boot.autoconfigure.web.ServerProperties` |
| `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc` | `org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc` |

규칙이 있다. **기술 이름이 앞으로 나오고 `autoconfigure` 가 뒤로 간다.**
못 찾겠으면 클래스 이름으로 jar 안을 뒤지는 편이 빠르다.

```
unzip -l <jar> | grep ServerProperties.class
```

### SPA 에 CSRF 쿠키를 내주려면 두 군데를 더 손봐야 한다

`CookieCsrfTokenRepository.withHttpOnlyFalse()` 만 걸면 **쿠키가 안 나간다.**
설정은 맞아 보이는데 응답에 아무것도 안 실리고, 오류도 안 난다.

| 걸리는 것 | 증상 | 손볼 곳 |
|---|---|---|
| 토큰이 지연 생성된다 | 아무도 안 읽으면 쿠키가 안 나간다 | 토큰을 한 번 읽는 필터를 `CsrfFilter` 뒤에 넣는다 |
| `XorCsrfTokenRequestAttributeHandler` | 쿠키에서 읽은 값을 헤더에 그대로 실으면 거부된다 | 헤더로 온 값만 평문 비교한다 |

두 번째가 특히 안 보인다. 저장소는 쿠키에 **평문**을 넣는데 XOR 핸들러는 돌아온 값을
인코딩된 것으로 보고 디코딩을 시도한다. 클라이언트는 받은 값을 그대로 보냈는데 403 이 난다.

`SecurityConfig.csrfTokenRequestHandler()` 가 내보낼 때만 XOR 를 쓰고,
헤더로 돌아온 값은 평문으로 비교한다. 폼 파라미터 경로는 그대로 XOR 로 푼다.

### MockMvc 로 실제 로그인을 하면 다음 테스트 클래스가 인증된 채로 시작한다

컨트롤러가 `SecurityContextHolder.setContext()` 를 부르면 그 값이 **스레드에 남는다.**
MockMvc 는 테스트들이 스레드를 나눠 쓰기 때문에 다음 클래스가 그 인증을 물려받는다.

증상이 엉뚱한 데서 난다. `AuthLoginTest` 를 추가했더니 손대지 않은 `CsrfTokenTest` 6개가
토큰 쿠키를 못 받아 깨졌다. **기동한 서버에서는 안 난다** — 요청마다 스레드가 갈린다.

`PostgresTestBase` 가 걷어낸다. 테스트마다 손으로 붙이지 않는 이유는 빠뜨렸을 때
깨지는 것이 **빠뜨린 그 클래스가 아니라 남의 클래스**라서다. 원인을 찾을 실마리가 없다.

**비울 곳이 둘이다.** `SecurityContextHolder` 는 로그인 컨트롤러가 심은 것을,
`TestSecurityContextHolder` 는 `with(user(...))` 가 심은 것을 들고 있다. 둘 다 비운다.

### `with(csrf())` 를 한 번 쓰면 그 컨텍스트에서 CSRF 쿠키가 영영 안 나온다

**MockMvc 로는 쿠키 기반 CSRF 발급을 검증할 수 없다.** 프레임워크가 저장소를 갈아치운다.

`SecurityMockMvcRequestPostProcessors.csrf()` 는 설정된 `CsrfTokenRepository` 를
`TestCsrfTokenRepository` 로 감싸는데, **그 안은 언제나 `HttpSessionCsrfTokenRepository` 다.**
`CookieCsrfTokenRepository` 로 설정해 뒀어도 그렇다. 그리고 그 교체가 **공유 Spring 컨텍스트에 남아서**,
같은 컨텍스트를 쓰는 뒤 테스트들은 토큰을 세션에 저장하고 `Set-Cookie` 를 안 보낸다.

순서를 고정해 확인한 결과다.

```
before-any-csrf  cookies=1
used with(csrf())
after-with-csrf  cookies=0     ← 여기서 죽는다
```

**로그인과는 무관하다.** 인증 상태를 비워도 안 고쳐지고, `@DirtiesContext(BEFORE_CLASS)` 로는
고쳐진다 — 컨텍스트에 남은 상태이기 때문이다.

Spring Security 는 이 리포트를 `status: invalid` 로 닫았다. 테스트 인프라의 의도된 동작이다.

**대응은 층을 옮기는 것뿐이다.** 쿠키·세션이 실제로 오가는지 보려면 `HttpTestBase` 를 쓴다.
`with(csrf())` 자체는 POST 를 태우는 정상적인 방법이라 계속 쓴다 — 같은 컨텍스트에서
쿠키 발급까지 검증하려 들지만 않으면 된다.

기동한 서버는 언제나 정상이었다. 프로덕션 결함이 아니다.

### 베이스 클래스의 `@AfterEach` 는 `protected` 여야 한다

package-private 이면 **다른 패키지의 하위 클래스에 상속되지 않고, JUnit 이 조용히 안 부른다.**
오류도 경고도 없다. 정리 코드를 베이스로 올렸는데 아무것도 안 바뀌면 이걸 먼저 본다.

```java
@AfterEach
protected void clearSecurityContext() { ... }
```

### CSRF 거부가 MockMvc 와 기동한 서버에서 다르게 나온다

토큰 없는 POST 를 열린 경로에 보내면 **MockMvc 는 403, 기동한 서버는 401** 이다.
같은 필터 설정에서 갈린다. 이유는 안 밝혔다.

**실제 답은 401 이다.** `HttpFlowTest` 가 진짜 HTTP 로 확정했다.
MockMvc 쪽 테스트는 상태 코드를 못박지 말고 `is4xxClientError()` 로 둔다.

### `singleRow()` 는 `timestamptz` 를 `java.sql.Timestamp` 로 준다

`JdbcClient` 의 `query().singleRow()` 는 `Map<String, Object>` 를 돌려주는데, 그 안의 시각 값은
`OffsetDateTime` 이 아니라 `java.sql.Timestamp` 다. 캐스팅하면 `ClassCastException` 이고
**컴파일은 통과해서 실행할 때만 드러난다.**

시각 컬럼을 읽을 때는 RowMapper 로 받는다.

```java
.query((rs, rowNum) -> new Row(rs.getObject("created_at", OffsetDateTime.class)))
```

### `@Cacheable` 은 private 메서드와 자기 호출에 안 먹는다

프록시가 메서드 호출을 가로채는 방식이라 **같은 객체 안에서 부른 것은 프록시를 안 거친다.**
붙여 놓고 안 걸리는 것을 눈치 못 채는 게 이 함정의 성질이다.

청크 4a 에서 조회를 `PermissionRuleLoader` 로 뺀 이유가 이것이다.
캐시를 새로 붙일 때 **부르는 쪽과 캐시된 메서드가 다른 빈에 있는지** 먼저 본다.

### Testcontainers 는 Boot BOM 이 관리하지 않는다

버전을 직접 지정한다. 이유와 Docker 29 함정은 `build.gradle.kts` 주석에 있다.
요약하면 **1.21.4 미만은 Docker 29 에서 안 뜨고, 오류 메시지에 원인이 안 드러난다.**

### `JAVA_HOME` 이 JDK 11 을 가리킨다

이 환경의 문제다. `CLAUDE.md` 의 검증 절에 명령이 있다.

### `set_updated_at` 트리거 때문에 시각을 되돌릴 수 없다

파기·만료를 테스트하려면 "오래된 행" 을 만들어야 하는데, `update` 로 `updated_at` 을 과거로 넣으면
**그 트리거가 다시 `now()` 로 덮어쓴다.**

```java
// 안 된다. 트리거가 now() 로 되돌린다
insert ...; update cart set updated_at = :old ...

// 한다. 트리거가 before update 에만 걸려 있다
insert into cart (cart_token, updated_at) values (:t, :old)
```

`created_at` 은 트리거가 없어서 `update` 로도 된다. **`updated_at` 만 이 문제가 있다.**

### 텍스트 블록은 줄 끝 공백을 지운다

SQL 을 텍스트 블록으로 쓰다가 변수를 이으면 단어가 붙는다.

```java
// 안 된다. "order by" 뒤 공백이 사라져서 order byp.created_at 이 된다
"""
 order by """ + orderBy

// 한다. 공백을 문자열에 직접 넣는다
"""
 group by p.product_id
"""
+ " order by " + orderBy
```

Java 가 들여쓰기를 계산할 때 각 줄의 **후행 공백을 제거**한다(`\s` 이스케이프로 지킬 수는 있다).
증상이 `syntax error at or near` 라 원인이 안 드러난다 — 소스에는 공백이 보인다.

### Jackson 3 은 빠진 필드를 기본형에 못 넣는다

요청 본문에 없는 필드가 `boolean`·`long`·`int` 로 가면 **요청 전체가 깨진다.**

```
MismatchedInputException: Cannot map `null` into type `boolean`
(set `DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES` to 'false')
```

Jackson 2 는 이 기능이 꺼져 있어서 `false`·`0` 이 들어갔다. **Jackson 3 은 켜져 있다.**
Boot 3 예제를 그대로 옮기면 여기서 걸린다.

증상이 나쁘다. "이 필드가 없다" 가 **"요청 형식이 맞지 않는다" 로 뭉개져서** 어느 칸인지 안 드러나고,
Spring 은 이 예외를 `DEBUG` 로 찍어서 로그에도 안 남는다.

**요청 record 에는 래퍼 타입을 쓴다.** 필수면 `@NotNull` 을 걸어 Bean Validation 이 필드를 짚게 한다.
전역으로 그 기능을 끄면 `null` 이 조용히 `0`·`false` 가 돼서 진짜 실수가 안 드러난다.

```java
// 안 한다
@PositiveOrZero long price

// 한다
@NotNull @PositiveOrZero Long price
```

### `@RestControllerAdvice` 만으로는 프레임워크 예외를 못 잡는다

`spring.mvc.problemdetails.enabled=true` 를 켜면 Spring 이 **자기 핸들러를 먼저 등록한다.**
검증 실패(`MethodArgumentNotValidException`), 깨진 JSON, 지원 안 하는 메서드·미디어 타입이
전부 그쪽으로 간다.

그래서 `@ExceptionHandler(MethodArgumentNotValidException.class)` 를 적어 둬도 **안 불린다.**
증상이 조용하다 — 응답은 나가는데 우리가 넣은 `type` 과 `trace_id` 만 없다.

**`ResponseEntityExceptionHandler` 를 상속하고 메서드를 재정의해야** 우리 형식이 걸린다.

```java
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(...) { }
}
```

`WebRequest` 에서 `HttpServletRequest` 를 꺼내려면 `((ServletWebRequest) request).getRequest()` 다.

### 보안 필터의 401 은 예외 처리기가 못 잡는다

인증 실패는 `AuthenticationEntryPoint` 가 MVC 에 닿기 전에 응답을 끝낸다.
`@RestControllerAdvice` 는 컨트롤러까지 온 요청에만 걸려서 **401 만 본문 없이 나간다.**

본문을 그 자리에서 직접 써야 한다. 우리는 `ProblemEntryPoint` 가 하고,
본문을 만드는 것은 `ProblemFactory` 하나로 모았다 — 두 자리가 각자 만들면 형태가 갈린다.

### `MockMvc` 의 `Content-Type` 은 charset 이 붙는다

`application/problem+json` 을 기대하면 `application/problem+json;charset=UTF-8` 이 와서 어긋난다.
`content().contentTypeCompatibleWith(...)` 로 타입만 본다.

### `ResultActions` 에 `.as()` 가 없다

AssertJ 문법이다. MockMvc 체인에 붙이면 컴파일이 깨진다. 이유는 주석으로 적는다.

### 지연 트리거는 `@Transactional` 테스트에서 한 번도 안 돈다

`deferrable initially deferred` 로 건 제약은 **커밋 시점에** 검사한다.
테스트는 `@Transactional` 이라 롤백하므로 그 시점이 오지 않고, **검사가 한 번도 실행되지 않은 채 전부 초록이 된다.**
트리거를 아무리 틀리게 짜도 테스트가 안 잡는다.

```java
jdbc.sql("set constraints all immediate").update();
```

이 문장이 밀려 있던 검사를 그 자리에서 돌린다. `OrderSchemaTest.flush()` 가 그것이다.
지연 제약을 새로 걸면 **테스트에 이 호출이 있는지부터 본다** — 없으면 검사한 적이 없는 것이다.

트리거가 `raise exception` 으로 떨어뜨리면 SQLSTATE 가 `P0001` 이라
Spring 이 `UncategorizedSQLException` 으로 준다. `DataIntegrityViolationException` 이 아니다 —
둘을 같이 받으려면 `DataAccessException` 으로 잡는다.

### Postgres 의 데드락은 `DeadlockLoserDataAccessException` 이 아니다

`40P01` 을 그 이름의 예외로 받을 것 같지만 **`PessimisticLockingFailureException`** 으로 온다.
Spring 이 오류 코드표(`sql-error-codes.xml`)로 번역할 때만 세분화하고,
Postgres 는 SQLSTATE 앞 두 자리(`40` = 트랜잭션 롤백)로 번역돼서 상위 타입에 멈춘다.

**재시도를 붙일 때 예외 이름으로 잡으면 데드락을 놓친다**(`D11` 「재시도」는 `40001`·`40P01` 둘 다 잡으라고 정했다).
SQLSTATE 를 직접 보는 쪽이 확실하다 — `OrderConcurrencyTest` 가 그렇게 확인한다.

### 롤백을 끈 테스트는 정리도 한 트랜잭션이어야 한다

`@Transactional(propagation = NOT_SUPPORTED)` 로 롤백을 끄면 정리 SQL 도 **문장마다 커밋된다.**
그러면 지연 트리거가 중간 상태를 본다 — `order_item` 만 지운 순간
`항목이 없는 셀러 주문` 으로 정리가 통째로 실패한다.

정리를 `TransactionTemplate` 하나로 묶어서 다 지운 뒤에 검사가 돌게 한다.

**시작할 때도 한 번 지운다.** 정리가 한 번 실패하면 그 데이터가 컨테이너에 남고,
같은 실행의 **뒤 테스트가 이메일 유니크에 걸려 시작도 못 한다** — 컨테이너와 스키마는
JVM 당 하나라 앞 테스트의 잔여물을 그대로 물려받는다.

### Redis 는 테스트 롤백이 안 되돌린다

`PostgresTestBase` 가 `@Transactional` 이라 DB 는 테스트마다 깨끗하게 시작하는데,
**Redis 에 쓴 것은 그대로 남는다.** 같은 키를 쓰는 테스트가 여럿이면 앞 테스트의 값이 뒤에 새어 간다.

`AuthLoginTest` 가 그 자리다 — 일부러 로그인을 실패시키는 테스트가 많고 전부 같은 (계정, IP) 라,
안 지우면 **앞 테스트가 쌓아 둔 실패 때문에 뒤 테스트가 차단된 채 시작한다.**

Redis 를 쓰는 테스트는 `@BeforeEach` 에서 자기 키를 지운다.

### Redis 의존성을 넣어도 캐시가 자동으로 안 넘어간다

`spring-boot-starter-data-redis` 를 넣으면 Spring Boot 가 `RedisCacheManager` 를 자동설정할 수 있다.
그러면 **판정 캐시(청크 4a)가 아무도 모르게 Redis 로 옮겨 간다** — 이관은 청크 39 의 일이다.

여기서는 안 넘어간다. `PermissionCacheConfig` 가 `CacheManager` 빈을 **명시적으로** 선언했고
자동설정이 `@ConditionalOnMissingBean` 이라 뜨지 않기 때문이다.

**이건 우연히 성립한 안전장치라 테스트로 고정해 뒀다**(`RedisConnectionTest`).
누가 그 빈을 지우면 캐시 구현이 조용히 바뀌는데, 그건 코드 어디에도 안 보인다.

## 넣었지만 안 쓰는 것

**`spring-boot-starter-data-jpa` 가 의존성에 있는데 코드는 `JdbcClient` 만 쓴다.**

```
JdbcClient   6곳
JPA 엔티티   0개
```

권한 판정이 재귀적인 조인이라 SQL 을 직접 쓰는 편이 읽기 쉬웠다.
스타터는 청크 2 에서 넣은 뒤로 그대로 남아 있다.

**엔티티가 있다고 가정하고 코드를 쓰지 않는다.** 없다.
JPA 로 갈지 `JdbcClient` 로 갈지는 청크 6(상품 스키마)에서 실제 매핑이 생길 때 정한다.

## 아직 안 정한 것

| 대상 | 언제 |
|---|---|
| Next.js 버전, 패키지 매니저 | 청크 13 |
| springdoc-openapi | 청크 2a |
| MinIO | 청크 26 |

정해지면 위 표에 줄을 더한다.

## 버전을 올릴 때

| 대상 | 올리면 볼 곳 |
|---|---|
| Spring Boot | 스타터 이름과 자동 설정. 마이너 버전에서도 바뀐 전례가 있다 |
| Testcontainers | Docker Engine 최소 지원 API 버전 |
| PostgreSQL | `transaction-iso` 문서. `D11` 이 Read Committed 동작에 기대고 있다 |
| Java | Gradle toolchain 과 CI 의 JDK |

**올린 뒤에는 `gradlew build` 와 `gradlew test` 를 실제로 돌린다.** 돌리지 않았으면 그렇게 적는다.

## 이 문서를 고칠 때

새 의존성이 들어오면 위 버전표에 줄을 더한다.
**기억과 실제가 어긋난 경험이 생기면 「기억으로 쓰면 틀리는 자리」에 적는다.** 그게 이 문서의 값이다.
