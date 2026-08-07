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

상태 코드를 못박는 테스트는 둘 중 한쪽에서만 통과한다.
`is4xxClientError()` 로 두고, 통과하는 쪽(`with(csrf())`)을 짝으로 둬서 신호를 만든다.

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
