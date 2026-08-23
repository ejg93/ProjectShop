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

### 테스트가 전부 실패하면 Docker 부터 본다

Docker Desktop 이 꺼져 있으면 **테스트가 하나도 안 통과한다.** `PostgresTestBase` 가
Testcontainers 로 Postgres 를 직접 띄우기 때문이다(`D15`).

읽히는 오류가 코드 문제처럼 생겼다는 것이 함정이다 — 맨 앞에 나오는 것은
`IllegalStateException at DefaultCacheAwareContextLoaderDelegate` 고, Spring 컨텍스트가
안 뜬 이야기라 **방금 고친 코드를 의심하게 된다.** 진짜 원인은 스택 맨 아래
`DockerClientProviderStrategy` 한 줄이다.

```
docker info --format "{{.ServerVersion}}"
```

이것이 실패하면 코드를 보지 말고 Docker Desktop 을 띄운다. 기동에 시간이 걸려서
바로 다시 돌리면 같은 오류가 난다.

### `bootRun` 은 죽여도 8080 을 안 놓는다

`bootRun` 을 배경으로 띄우고 Gradle 쪽 프로세스를 죽여도 **앱의 JVM 은 남는다.**
그 상태에서 `docker compose down -v` 를 하면 살아 있던 앱이 DB 를 잃고,
`/api/health` 가 이렇게 답한다.

```
relation "flyway_schema_history" does not exist
```

**새 마이그레이션이 깨진 것처럼 보이는 것이 함정이다.** 실제로는 방금 띄우려던 앱이
아직 뜨지도 않았고, 답한 것은 지난번 앱이다. 다시 띄우기 전에 포트를 비운다.

```powershell
Get-NetTCPConnection -LocalPort 8080 -State Listen | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
```

### 뷰는 표에 컬럼이 늘어도 안 따라온다

Postgres 는 `create view` 시점의 컬럼 목록을 굳힌다. `select so.*` 로 썼어도 마찬가지다 —
뷰를 만들 때 `*` 가 그 순간의 컬럼으로 펼쳐져 저장된다.

`seller_order_visible` 이 그 자리다(`V16`). 표에 컬럼을 더하는 마이그레이션은
**뷰를 `drop` 하고 다시 만들어야** 한다. `V26` 이 그것을 빠뜨려서 셀러 조회가
`bad SQL grammar` 로 깨졌다 — 셀러 조회가 이 뷰만 읽기 때문이다(`11c-2b`).

**조용히 틀리지 않고 바로 깨지는 쪽이라** 별도 방벽을 안 뒀다.
`create or replace view` 는 컬럼을 <b>뒤에 더할 때만</b> 되고 순서를 바꾸거나
중간에 끼우면 거부한다 — 그래서 `drop` 후 재생성이 정해진 방법이다.

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

증상이 엉뚱한 데서 난다. `AuthLoginTest` 를 추가했더니 손대지 않은 CSRF 토큰 테스트 여섯이
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

**`@Transactional` 도 같다.** 자기 호출이면 전파 설정이 통째로 무시되고
`REQUIRES_NEW` 가 `REQUIRED` 처럼 돈다 — **고쳤다고 믿는 채로 원래 결함이 남는 모양이라
증상으로는 안 갈린다.** 청크 4b-2 가 삽입을 `AuditLogWriter` 로 뺀 이유다.

### `@BeforeTransaction`·`@AfterTransaction` 은 `@Nested` 클래스에서 안 돈다

Spring 은 그 표시를 **테스트 클래스의 상속 계층**에서 찾는데, 중첩 클래스는 바탕 클래스를
상속하지 않는다. 바탕에 달아 두면 중첩 안 쓴 클래스에서만 돌아서 **일부만 정리된다.**

`@BeforeEach`·`@AfterEach` 는 JUnit 이 바깥 클래스까지 훑으므로 중첩에서도 돈다.
그래서 트랜잭션 밖에서 해야 하는 정리는 **`@BeforeEach` 안에서 `REQUIRES_NEW` 로 연다**
(`PostgresTestBase.purgeCommittedAuditLogs`).

**뒤가 아니라 앞에서 지운다.** 뒤에서 지우면 아직 커밋 안 된 그 테스트의 행을
다른 트랜잭션이 지우려 드는 모양이 돼서 잠금에 걸린다.

### Testcontainers 는 Boot BOM 이 관리하지 않는다

버전을 직접 지정한다. 이유와 Docker 29 함정은 `build.gradle.kts` 주석에 있다.
요약하면 **1.21.4 미만은 Docker 29 에서 안 뜨고, 오류 메시지에 원인이 안 드러난다.**

### `bootRun` 을 멈춰도 java 프로세스가 남는다

Gradle 태스크를 죽여도 **`bootRun` 이 띄운 자식 java 는 8080 을 계속 쥔다.**
다음 기동은 `Port 8080 was already in use` 로 죽는데, **그때 `curl` 은 200 을 준다** —
낡은 인스턴스가 답하기 때문이다.

**옛 코드로 검증하게 되는 자리다.** 마이그레이션이나 응답 형식을 고친 뒤라면 결과가 거짓이 된다.
포트를 잡은 프로세스를 직접 죽이고 다시 띄운다.

```powershell
Get-NetTCPConnection -LocalPort 8080 -State Listen | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
```

**`npm run dev` 도 같다.** 3000 을 쥔 node 가 남고, 다음 `dev` 는 조용히 실패하는데
**낡은 서버가 계속 답한다.** 그쪽은 파일 변경을 따라가므로(HMR) 새 코드가 도는 것처럼 보이지만
**시작 시점에 만들어진 것은 안 바뀐다** — 그 상태에서 `notFound()` 가 500 을 준 적이 있다.
포트 둘 다 위 명령으로 확인하고 시작한다. **화면 축은 두 포트를 다 본다.**

### `JAVA_HOME` 이 JDK 11 을 가리킨다

이 환경의 문제다. `CLAUDE.md` 의 검증 절에 명령이 있다.

### Windows 에서 만든 실행 파일은 실행 비트가 없다

**로컬에서는 영원히 안 드러난다.** Windows 에 그 개념이 없어서 `./gradlew` 가 잘 돌고,
git 은 `100644`(실행 불가)로 들고 있다. **리눅스 러너에서만 `Permission denied`(exit 126)** 가 난다.

`2026-08-23` 에 CI 가 처음 돌면서 44초 만에 죽은 자리다. 그전까지 `./gradlew build` 를
수십 번 돌렸는데 한 번도 안 나왔다.

```
git ls-files -s backend/gradlew        # 100644 이면 실행 비트가 없다
git update-index --chmod=+x backend/gradlew
```

**새 실행 파일을 저장소에 넣을 때 같이 본다.** 지금 걸릴 만한 것은 `gradlew` 하나뿐이지만,
셸 스크립트를 더하는 청크는 그 자리에서 모드를 확인한다 — **CI 가 잡아 주지만 한 번 빨개진 뒤다.**

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

### `LocalTime.MAX` 를 `timestamptz` 에 넣으면 다음날이 된다

`23:59:59.999999999` 는 나노초까지고 Postgres 는 **마이크로초까지만 담고 나머지를 올린다.**
저장된 값은 다음날 `00:00:00` 이다.

「말일 24시」로 쓸 때는 맞는 값이지만, **그것을 다시 날짜로 되돌리면 말일이 아니라 말일+1** 이다.

```java
lastDay.atTime(LocalTime.MAX)                    // 저장되면 lastDay + 1일 00:00
read().atZoneSameInstant(ZONE).toLocalDate()     // lastDay 가 아니다
```

시각으로 비교하는 코드는 멀쩡하고 **날짜로 되돌리는 코드만 틀린다.** 그래서 늦게 드러난다 —
`OrderStatusServiceTest` 가 말일이 금요일인 날에만 깨졌다(청크 `11-4` 가 고쳤다).

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

### 지연 트리거 안에서 `NEW` 는 커밋 시점의 값이 아니다

**`NEW` 는 그 트리거를 걸어 준 문장 시점의 행이다.** 커밋 때 도는 것이지 커밋 때의 값을 보는 게 아니다.

같은 트랜잭션에서 넣고 고치는 흐름이면 이 차이가 결과를 뒤집는다.

```
insert (컬럼 = null)   ← 트리거 예약. NEW 에 null 이 박힌다
update (컬럼 = 값)     ← 트리거 또 예약. 이건 통과한다
commit                 ← 앞의 예약분이 null 로 터진다
```

**`after insert` 를 같이 걸어 두면 그 트리거는 절대 통과할 수 없다.**

```sql
-- 안 된다: insert 로 걸린 예약분이 언제나 null 을 본다
if new.response_body is null then raise exception ...

-- 한다: 행을 다시 읽는다. 지워졌으면 검사할 것이 없다
select response_body into v_body from idempotency_key
 where idempotency_key_id = new.idempotency_key_id;
if not found then return null; end if;
```

`V16` 의 `assert_order_amounts` 가 처음부터 이 모양이었고, `V17` 은 `NEW` 를 믿었다가
**`POST /api/orders` 가 실서버에서 언제나 500** 이었다. 위의 「한 번도 안 돈다」와 겹쳐서
테스트 30개가 전부 초록인 채로 지나갔다 — 청크 `35c` 가 HTTP 층에서 첫 커밋을 일으켜 잡았다.

### 동의 항목·정책 문서는 「지금 판」을 골라야 한다

개정판을 **미리 넣어 두고 시행 시각에 갈아 끼우는** 설계다(`V27` 의 불변 트리거).
그래서 표에는 **아직 시행 안 된 판이 같이 들어 있다.**

```sql
-- 틀린다. 시행 전인 판을 집는다
where code = :code order by version desc limit 1

-- 맞다
where code = :code and effective_at <= now()
order by effective_at desc, version desc limit 1
```

**같은 실수가 하루에 셋이었다**(`D2-7`·`6b-1`). `V36`(약관 제3판, 시행 이레 뒤)이 들어오는
순간 한꺼번에 드러났다.

| 어디 | 어떻게 됐나 |
|---|---|
| `MeConsentTest` 픽스처 | **시행 전 판에 동의**한 것으로 기록돼서 「내 사본」 테스트가 깨졌다 |
| `V900` 데모 시드 | `join consent_item ci on ci.is_required` — 판을 안 가려서 **두 판에 다 동의**했다 |
| 운영 경로 둘 | **안 틀렸다.** `SignupService.currentConsentItems`·`ConsentService.findItem` 이 둘 다 본다 |

**운영만 맞고 나머지가 틀린 것이 이 함정의 모양이다.** 시행 전 판이 없는 동안에는
`order by version desc` 도 같은 답을 줘서, **개정판을 처음 넣는 날까지 아무도 모른다.**

`version` 이 크다고 지금 판이 아니다 — 고르는 기준은 언제나 `effective_at` 이다.

### 시드를 한 번 넣은 DB 에는 새 마이그레이션이 안 들어간다

데모 시드가 `V900` 이라 **적용 이력의 최고 버전이 900** 이 된다.
그 뒤에 `V19` 같은 낮은 번호를 추가하면 Flyway 가 순서를 어긴 것으로 보고 기동을 막는다.

```
Detected resolved migration not applied to database: 19.
```

`local` 프로필로 한 번 띄운 로컬 DB 에서만 난다. **`docker compose down -v` 로 다시 만든다** —
`out-of-order=true` 를 켜는 쪽은 안 골랐다. 그걸 켜면 진짜 순서 사고도 같이 통과한다.

**`6b-1` 에서 실제로 막혔고, 그때 이 결정을 어겼다가 되돌렸다.** `V29` 까지 적용된 로컬 DB 에
`V30`~`V36` 이 한꺼번에 왔고, `out-of-order` 를 켜 봤더니 **그것만으로도 안 뜬다** —
옮기기 전에 도는 검증이 먼저 막아서 `ignore-migration-patterns` 까지 켜야 했다.
**끄는 스위치가 둘이면 그만큼 안 보이게 된다**는 것이 이 문서의 판단을 되레 뒷받침했다.

**다시 만드는 값이 `6b-1` 로 싸졌다.** 그전에는 상품을 손으로 넣어 둔 것이 같이 날아가서
`down -v` 가 아까웠는데, 이제 시드가 상품·옵션·조합·재고까지 넣는다 —
**날아갈 것이 없으면 다시 만드는 것이 가장 싼 길이다.**

### 422 와 `asText()` 는 이름이 바뀌었다

빌드 경고 16개의 정체다. 둘 다 <b>기능이 없어진 것이 아니라 이름만 바뀐 것</b>이라
경고를 켜기 전에는 무엇이 문제인지 안 드러났다.

| 옛 이름 | 지금 | 왜 |
|---|---|---|
| `HttpStatus.UNPROCESSABLE_ENTITY` | `UNPROCESSABLE_CONTENT` | RFC 9110 이 422 를 "Unprocessable Content" 로 고쳤다 |
| `status().isUnprocessableEntity()` | `isUnprocessableContent()` | 같은 이유 |
| `JsonNode.asText()` | `asString()` | Jackson 3 |

`-Xlint:deprecation` 을 켜 두는 이유가 이것이다(`build.gradle.kts`).
켜기 전에는 "deprecated API 를 쓴다" 까지만 나오고 **어느 줄인지 안 나온다.**

### `set_config(..., true)` 로 켠 값은 서브트랜잭션이 abort 되면 되돌아간다

`V41` 의 재고 차단이 이 사실 위에 서 있다. `move_stock()` 이 `shop.stock_move` 를 켜고
`UPDATE` 뒤에 끄는데, **그 사이에서 예외가 나면 켠 채로 남는 것처럼 읽힌다.**
남으면 같은 트랜잭션에서 `sku_stock.on_hand` 직접 `UPDATE` 가 열려서 차단이 뚫린다.

**안 뚫린다.** `is_local := true` 로 켠 값은 GUC 스택에 서브트랜잭션 단위로 쌓여서,
그 구간이 abort 되면 켜기 전 값으로 돌아간다. 그리고 **예외를 삼키려면 abort 가 반드시 먼저 온다** —
Postgres 는 오류 뒤에 세이브포인트로 되돌리지 않으면 그 트랜잭션에서 아무것도 못 한다.

두 갈래로 확인했다.

| 삼키는 방식 | 삼킨 뒤 플래그 | 직접 `UPDATE` |
|---|---|---|
| `rollback to savepoint` — Spring 중첩 트랜잭션이 이것이다 | 빈 값 | 거부 |
| plpgsql `exception when others` | 빈 값 | 거부 |

**그래서 `move_stock()` 에 `exception` 블록을 안 넣었다**(청크 `53a`). 넣으면 막는 것 없이
암묵 세이브포인트가 재고 이동마다 하나씩 생긴다 — plpgsql 의 `exception` 블록이 그것이다.
`SkuStockMovementTest` 의 「막는 것」이 두 갈래를 다 밟아서, 이 사실이 바뀌면 거기서 빨개진다.

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

### 한글이 든 본문을 `curl -d` 로 보내면 400 이 난다

이 환경의 Git Bash 가 명령줄 인자를 UTF-8 로 안 넘겨서 서버가 못 읽는다.
증상이 **「요청 형식이 맞지 않는다」 하나뿐**이라 필드가 틀린 것처럼 보인다 —
같은 요청을 영문 이름으로 보내면 201 이 나와서 원인이 인코딩인 줄 모른다.

```bash
# 안 된다
curl -d '{"name":"기본 반팔 티셔츠", ...}'

# 된다 - 파일로 두고 바이트 그대로 보낸다
curl -H "Content-Type: application/json; charset=utf-8" --data-binary @body.json
```

파일은 Write 도구로 만든다. 셸 heredoc 도 같은 자리에서 깨진다.

### `next/image` 는 목록에 없는 호스트를 통째로 거부한다

`next.config.ts` 의 `images.remotePatterns` 에 없으면 그 자리에서 막힌다.
막아 두는 것이 기본값인 이유는, 아무 주소나 받으면 **우리 서버가 남의 이미지를
대신 내려받아 주는 통로**가 되기 때문이다.

상품 사진 자리표시(`picsum.photos`)를 청크 `14` 가 처음 등록했다.
진짜 업로드(청크 26)로 바뀔 때 이 항목도 같이 바뀐다.

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

### 화면을 진짜 브라우저로 밟는 방법이 있다

`curl` 은 HTML 만 받는다. **버튼을 눌러 무슨 일이 나는지는 못 본다** — 로그인이 200 인데
화면이 안 넘어가는 결함(`13-2` 수정)이 그 사이에 숨어 있었다.

Puppeteer 가 이 기계에 깔려 있다. 저장소 의존성이 아니라 **사용자 홈**에 있다.

```
C:\Users\EJG\node_modules\puppeteer   (25.3.0)
~/.cache/puppeteer                    (Chrome 바이너리)
```

**저장소에 안 넣는다.** 화면 테스트를 정식으로 세우는 것은 나중 청크고(`D15`),
지금은 손으로 확인할 때만 쓴다. 스크래치패드에 스크립트를 두고 부른다.

```js
// .mjs 여야 한다. 이 판은 ESM 전용이라 require 가 안 된다.
const { default: puppeteer } = await import(
  "file:///C:/Users/EJG/node_modules/puppeteer/lib/puppeteer/puppeteer.js"
);
```

**경로를 `file://` URL 로 준다.** Git Bash 의 POSIX 경로(`/c/Users/...`)를 그대로 넘기면
Node 가 `C:\c\Users\...` 로 읽어서 못 찾는다.

### `notFound()` 는 404 가 아니라 200 으로 나간다

화면 안에서 `notFound()` 를 부르면 **없는 쪽 UI 는 그려지는데 HTTP 상태는 200** 이다.
라우팅 단계에서 안 잡힌 주소(`/아무데나`)만 진짜 404 다.

```
/아무데나없는주소                 404   라우팅이 잡는다
/products/999999                  200   notFound() 가 던진다
/seller/orders/S-99999999-XXXXXX  200   같다
```

**우리가 잘못 쓴 것이 아니다.** 번들된 문서(`node_modules/next/dist/docs`)의
`04-functions/not-found.md` 가 그렇게 적어 뒀다 — 검사가 `<Suspense>` 안에서 도는데
**응답은 이미 200 으로 흘러나가기 시작했고, 스트리밍이 시작된 뒤에는 상태를 못 바꾼다.**
대신 Next 가 `<meta name="robots" content="noindex">` 를 넣어 색인에서 뺀다.

진짜 404 를 내려면 **스트리밍 전에** 검사해야 하고, 그 자리는 `proxy` 다.

**틀린 주석이 하나 있었다**(`13g` 에서 고쳤다). `orders/[orderNumber]/page.tsx` 가
「화면만 바꿔 그리고 200 으로 답하지 않는다」고 적어 뒀는데 **실제로는 200 이었다** —
확인 없이 쓴 문장이 코드 옆에서 사실처럼 읽히던 자리다.

### `bootRun` 을 죽여도 8080 은 안 풀린다

`bootRun` 은 앱을 **자식 JVM** 으로 띄운다. Gradle 쪽 프로세스를 죽이면 그 자식은 남고,
포트를 쥔 채라 다음 기동이 이렇게 끝난다.

```
APPLICATION FAILED TO START
Web server failed to start. Port 8080 was already in use.
```

**Gradle 이 안 죽였다는 신호가 없다.** 죽인 명령은 성공으로 끝나고, 실패는 다음 기동에서
전혀 다른 얼굴로 나온다 — 고친 코드가 원인처럼 보인다.

포트를 쥔 것을 직접 찾아 내린다.

```powershell
Get-NetTCPConnection -LocalPort 8080 -State Listen |
    Select-Object -ExpandProperty OwningProcess -Unique |
    ForEach-Object { Stop-Process -Id $_ -Force }
```

## 데이터 접근은 `JdbcClient` 다

**JPA 를 안 쓴다**(`Q15` 에서 확정했다). `spring-boot-starter-jdbc` 만 들이고
엔티티는 하나도 안 만든다.

권한 판정이 재귀적인 조인이라 SQL 을 직접 쓰는 편이 읽기 쉬웠고, 그 뒤로 상품·주문·결제·환불이
전부 같은 길로 갔다. **`spring-boot-starter-data-jpa` 가 청크 2 부터 남아 있었는데**
그 절은 「청크 6 에서 정한다」에 멈춰 있었다 — 청크 6 은 오래전에 끝났다.

**의존성 목록이 설계를 안 속이게 한다.** 남겨 두면 다음 사람이 Hibernate 가 도는 줄 알고,
`ddl-auto` 가 스키마를 지킨다고 오해한다. 스키마를 지키는 것은 Flyway 와 마이그레이션이다.

## 프론트에서 안 쓰는 것

**관례로 깔리는 넷을 안 쓴다.** `D23` 「안 넣은 것도 근거를 남긴다」가 요구하는 근거를
여기 적는다 — 없으면 다음 사람이 빠뜨린 줄 알고 채워 넣는다(`Q14`).

| 안 쓰는 것 | 관례상 기본 | 왜 안 쓰나 |
|---|---|---|
| 서버 상태 관리 | `@tanstack/react-query`·`swr` | **서버 컴포넌트가 기본이라 캐시 계층이 겹친다**(`D24`). 목록·상세는 서버가 그리고, 조작 뒤 갱신은 `router.refresh()` 가 서버에게 다시 물어본다 — 클라이언트가 들고 있을 상태가 없다 |
| 폼 | `react-hook-form` | 칸이 적고 검증이 **서버가 유일한 출처**다(`5-2`). 화면 검사는 편의고 판정이 아니라, 비제어 `FormData` 로 충분하다 |
| 날짜 | `date-fns`·`dayjs` | 로케일이 하나(`ko-KR`)고 시간대가 하나(`Asia/Seoul`)다. `toLocaleDateString` 이 그 둘을 다 받는다(`lib/format.ts`) |
| HTTP | `axios` | 입구가 셋뿐이고(`api.ts`) 인터셉터로 할 일을 그 셋이 이미 한다 — 표기 변환·CSRF·401 처리 |

**넷 다 값이 오르면 다시 본다.** 화면이 늘어 같은 데이터를 여러 곳에서 부르기 시작하거나,
칸이 많은 폼이 생기거나, 로케일이 둘이 되면 그때가 그 시점이다.

## 2026-08-20 관례 대조 — 처분 완료

축 1 에서 업계 관례는 4순위고 **근거만 대면 버린다.** 그래서 이 축의 판정은 하나였다 —
**따랐든 버렸든 근거가 적혀 있나.**

**셋이 나왔고 같은 날 다 쳤다.**

| # | 무엇이 틀렸었나 | 무엇으로 닫았나 |
|---|---|---|
| C1 | 「넣었지만 안 쓰는 것」이 **「청크 6 에서 정한다」에 멈춰 있었다** | `Q15` — `starter-jdbc` 로 바꾸고 위 「데이터 접근」으로 결론을 적었다 |
| C2 | 프론트가 관례 넷을 안 쓰는데 **근거가 한 줄도 없었다** | `Q14` — 위 「프론트에서 안 쓰는 것」 |
| C3 | `RefundSweeper` 가 배치 카탈로그에 없었다 | `Q14` — `batch-catalog.md` 에 행을 더했다 |

**`C1` 과 `C2` 는 성격이 다르다.** `C1` 은 판단이 끝났는데 문서가 안 따라온 것이고,
`C2` 는 판단이 코드에만 있고 문장으로 나온 적이 없는 것이다. 뒤엣것이 더 조용하다 —
**아무도 틀렸다고 말할 수 없어서** 다음 사람이 반대로 해도 근거를 댈 수 없다.

**`C1` 과 같은 모양이 `D15` 에도 있었다**(`P2`, 화면 테스트). 「그때 정한다」가 지나간 자리가
둘이었고, 둘 다 지나쳤다는 사실이 어디에도 안 남아 있었다.

### 대조해서 맞았던 것

`window.confirm`·직접 만든 토스트·번호 페이징·`picsum` 자리표시·`next/image`·
`react-markdown` 의 원시 HTML 차단 — 전부 근거를 달고 있다.
`eslint.config.mjs` 는 `eslint-config-next` 기본값을 **부분집합이라고 밝히고**
접근성 규칙을 얹은 이유를 주석에 적어 뒀다 — 관례를 항목마다 재는 모양이 그것이다.

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
