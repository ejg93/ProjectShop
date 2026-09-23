plugins {
	java
	jacoco
	// 버그 패턴 검출. 바이트코드를 읽어서 컴파일러에 안 붙는다 — ErrorProne 은 javac
	// 플러그인이라 JDK 를 올릴 때마다 같이 막힌다(`stack.md`).
	id("com.github.spotbugs") version "6.5.11"
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
}

// 저장소와 같은 선언이다(`2l`). 배포는 안 하지만 갈리면 안 된다 — 루트 `LICENSE` 가 원문이다.
description = "ProjectShop backend (Apache-2.0)"
group = "com.projectshop"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	mavenCentral()
}

// Tomcat 을 BOM 이 주는 값보다 올린다(`2e-6`).
//
// **Boot 를 올려서는 못 닫는다.** BOM 이 `11.0.24` 를 주는데 그 판에 critical 셋이 열려 있고
// (`CVE-2026-68525`·`CVE-2026-65905`·`CVE-2026-65182`), 패치는 `11.0.25` 다.
// Boot 는 `4.1.1` 이 최신이라(`4.1.2`·`4.2.0` 이 둘 다 404) 올릴 자리가 없다.
//
// **BOM 이 검증한 조합에서 벗어나는 것이다.** Boot 가 `11.0.25` 이상을 주는 판을 내면
// 이 줄을 지우고 BOM 값으로 돌린다 — 지워도 되는지는 `TomcatVersionTest` 가 판정한다.
//
// 한 줄이 `tomcat-embed-core`·`-el`·`-websocket` 셋을 다 덮는다.
extra["tomcat.version"] = "11.0.25"

// Boot 의 BOM 이 Testcontainers 버전을 관리하지 않아서 직접 넣는다.
//
// **2.x 는 모듈 좌표에 `testcontainers-` 접두어가 붙는다**(`org.testcontainers:postgresql` →
// `testcontainers-postgresql`). BOM 만 올리면 `Could not find org.testcontainers:postgresql:` 로
// `compileTestJava` 가 죽는다 — 버전이 안 붙는 것이라 오류가 「없는 모듈」처럼 보인다.
//
// 1.x 를 쓸 때 1.21.4 미만이 Docker Engine 29 에서 안 뜨던 문제는 2.x 에는 없다.
// docker-java 가 API 버전을 1.32 로 잡던 것이 원인이었다(testcontainers-java#11212, #11235).
dependencyManagement {
	imports {
		mavenBom("org.testcontainers:testcontainers-bom:2.0.5")
	}
}

dependencies {
	// **Dependabot 경보 #6 을 닫는다**(2026-09-17, medium). `at.yawk.lz4:lz4-java` 1.10.1 이
	// `kafka-clients` 를 타고 들어오는데(spring-boot-starter-kafka → spring-kafka → kafka-clients),
	// 네이티브 XXHash 가 **잘못된 배열 범위를 받으면 JVM 을 죽인다**(<= 1.11.0).
	//
	// **우리가 그 길을 밟나**: 지금은 안 밟는다 — `compression.type` 을 안 정해서 카프카 기본값
	// `none` 이고, LZ4 코덱이 안 불린다. **그래도 고정한다** — 「안 걸리는 설정을 켜 두면
	// 「막고 있다」고 읽힌다」와 같은 자리다. 압축을 켜는 날 이 경보를 다시 만나는 것보다
	// 지금 한 줄이 싸고, **버전이 올라가면 이 제약이 저절로 무의미해진다**(아래가 더 낮으면 진다).
	constraints {
		implementation("at.yawk.lz4:lz4-java:1.11.3")
	}

	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-cache")
	implementation("com.github.ben-manes.caffeine:caffeine")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-data-redis")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	// 세션을 Redis 에 둔다(`Q52`). 그전에는 서블릿 메모리라 **재배포마다 전원 로그아웃**이었고
	// 인스턴스가 둘이면 로그인이 튀었다.
	//
	// **`org.springframework.session:spring-session-data-redis` 가 아니다.** Boot 4 는 자동설정이
	// 모듈로 쪼개져 있어서 그쪽만 넣으면 클래스는 오는데 `SessionRepository` 빈이 안 뜬다 —
	// 증상이 `NoSuchBeanDefinitionException: FindByIndexNameSessionRepository` 다.
	// 추적 의존성에서 이미 밟은 자리와 같은 함정이고, 이 좌표가 세션 구현을 끌고 온다.
	implementation("org.springframework.boot:spring-boot-session-data-redis")
	implementation("org.flywaydb:flyway-database-postgresql")

	// 추적 ID 를 발급하고 MDC 까지 나르는 것(D16).
	//
	// Brave 를 고른 것은 OpenTelemetry 를 청크 62·63 으로 미뤄 뒀기 때문이다 —
	// opentelemetry 쪽 모듈은 그 API 를 지금 들인다.
	//
	// Boot 4 는 자동설정이 모듈로 쪼개져 있어서 **둘 다** 넣어야 한다.
	// 자동설정 모듈만 넣으면 Brave 를 optional 로 잡아서 조건이 안 맞고,
	// 브리지만 넣으면 자동설정이 없다. 어느 쪽이 빠져도 증상은 같다 —
	// 빈은 뜨는데 그게 `Tracer.NOOP` 이라 추적 ID 가 조용히 안 찍힌다.
	implementation("org.springframework.boot:spring-boot-micrometer-tracing-brave")
	implementation("io.micrometer:micrometer-tracing-bridge-brave")
	// 지표를 Prometheus 노출 형식으로 내준다(`Q53`). **수집 도구는 아직 없다** —
	// 여기까지가 「잴 수 있다」고, 목표 수치는 값이 쌓인 뒤에 정한다(`D21`).
	implementation("io.micrometer:micrometer-registry-prometheus")
	// 아웃박스 표를 브로커로 내보낸다(`33`, `event-catalog.md` 「전송 — Kafka」).
	//
	// **스타터를 들인다.** `spring-kafka` 만 넣으면 자동 설정 모듈(`spring-boot-kafka`)이 안 따라와서
	// `spring.kafka.*` 설정도 `KafkaTemplate` 빈도 안 생긴다 — Boot 4 는 자동 설정이 모듈별로 쪼개져 있다.
	// **버전은 안 적는다 — BOM 이 관리한다**(4.1.1 의 `spring-kafka.version` 이 4.1.1).
	// 위 springdoc 과 반대 자리라 적어 둔다.
	//
	// **로컬에서만 쓴다.** `shop.events.sink` 가 `none` 이면 **발행기와 토픽 빈이 안 선다**
	// (`OutboxPublisher`·`EventTopicConfig` 의 `@ConditionalOnProperty`) — 브로커로 나가는 연결이
	// 안 열려서 배포와 빠른 레인이 브로커를 안 찾는다.
	//
	// **자동 설정까지 끄는 것은 아니다.** 스타터가 클래스패스에 있으면 `KafkaTemplate` 빈은 늘 서고,
	// 그것을 끄려면 `spring.autoconfigure.exclude` 에 적어야 하는데 그 값이 `EVENTS_SINK` 와 같이
	// 안 움직인다(`event-catalog.md` 「전송」 아래). 프로듀서는 첫 발송 때 붙으므로 연결은 안 열린다.
	implementation("org.springframework.boot:spring-boot-starter-kafka")
	runtimeOnly("org.postgresql:postgresql")
	// API 스펙을 코드에서 뽑는다(`2a`). **UI 스타터를 안 들인다** — 행이 연 것은 스펙 하나고,
	// Swagger UI 는 정적 자원과 경로를 더 열어서 노출면만 넓힌다.
	//
	// **3.x 가 Boot 4 판이다.** 2.x 는 Boot 3 모듈 배치(`spring-boot-starter-*`)를 부르고
	// 3.x 가 쪼개진 배치(`spring-boot-webmvc`·`spring-boot-tomcat`)를 부른다 — 2.x 를 얹으면
	// 없는 좌표를 찾다가 죽는다. Boot BOM 이 관리 안 해서 버전을 직접 적는다.
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.1.1")
	// 파일 저장소를 S3 API 로 말한다(`26`, `media-rules.md`). 로컬은 MinIO 컨테이너고
	// **배포는 Cloudflare R2 다**(사용자 결정 2026-09-18) — 둘 다 S3 호환이라 클라이언트가 하나다.
	// 갈리는 것은 엔드포인트와 키 셋뿐이고 그것은 설정으로 들어온다.
	//
	// **`apache-client` 를 같이 넣는다.** SDK 는 HTTP 구현을 런타임에 고르는데, 후보가 하나도
	// 없으면 빈이 뜨는 대신 **첫 호출에서** `Unable to load an HTTP implementation` 으로 죽는다 —
	// 기동은 초록이고 업로드만 터져서 원인이 멀어 보인다.
	//
	// **BOM 으로 버전을 묶는다.** `s3` 와 전송 계층이 판이 갈리면 서명 방식에서 어긋난다.
	implementation(platform("software.amazon.awssdk:bom:2.55.0"))
	implementation("software.amazon.awssdk:s3")
	implementation("software.amazon.awssdk:apache-client")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	// 파일 저장소를 띄워서 버킷 정책을 실제로 잰다(`26`). 2.x 좌표 규칙대로 접두어가 붙는다.
	testImplementation("org.testcontainers:testcontainers-minio")
	// 2.x 좌표 규칙대로 `testcontainers-` 접두어가 붙는다(`stack.md`). 쓰는 것은 `33b` 다.
	testImplementation("org.testcontainers:testcontainers-kafka")
	// 계층 규칙을 문서에서 테스트로 내린다(`2n`). JUnit 6 아티팩트다 — 이 저장소가 6.0.3 이다.
	testImplementation("com.tngtech.archunit:archunit-junit6:1.5.0")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")

	// **SpotBugs 기본 검출기가 보안을 거의 안 본다**(`2e-1`). CodeQL Java 76규칙이
	// 경보 0이라 SQL 조립·암호·역직렬화를 지금 아무도 안 보고 있다.
	// 게이트를 새로 안 만들고 위 SpotBugs 의 **눈만 넓힌다.**
	spotbugsPlugins("com.h3xstream.findsecbugs:findsecbugs-plugin:1.14.0")
}

// 버그 패턴 검출을 어떻게 돌리나.
//
// **`test` 소스는 안 본다.** 테스트는 픽스처를 만드느라 일부러 이상한 코드를 쓰고,
// 거기서 나온 검출은 고칠 대상이 아니라 노이즈다. 노이즈가 섞이면 목록 전체를 안 읽는다.
spotbugs {
	ignoreFailures = false
	// 낮은 신뢰도까지 켜면 오탐이 는다. 기본값이 신뢰도 중간 이상만 본다.
	effort = com.github.spotbugs.snom.Effort.MAX
	reportLevel = com.github.spotbugs.snom.Confidence.DEFAULT
	// 제외 목록을 파일로 둔다. 이 파일 안에 적으면 왜 뺐는지를 못 적는다.
	excludeFilter = file("config/spotbugs/exclude.xml")
}

tasks.spotbugsTest {
	enabled = false
}

tasks.withType<com.github.spotbugs.snom.SpotBugsTask> {
	reports.create("xml") { required = true }
	reports.create("html") { required = true }
}

// 무엇이 경고인지 이름을 대게 한다. 기본 설정은 "deprecated API 를 쓴다" 까지만 말하고
// 어느 줄인지 안 알려줘서, 경고가 떠 있어도 고칠 대상을 못 짚는다.
//
// 경고를 오류로 올리지는 않았다(-Werror). 라이브러리를 올릴 때 남의 코드에서 오는 경고로
// 빌드가 통째로 막히면, 급할 때 옵션을 통째로 끄게 된다.
tasks.withType<JavaCompile> {
	options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
}

// 테스트를 두 레인으로 가른다.
//
// **되먹임 속도가 자율 실행의 상한을 정한다.** 검증 한 번에 2분 27초를 태우면 고치고
// 다시 돌리는 주기가 그 값에 묶인다. 컨테이너를 안 타는 테스트는 초 단위로 답할 수 있는데
// 한 태스크에 섞여 있어서 **전부 느린 쪽 속도로 돌고 있었다.**
//
// **소스셋이 아니라 태그로 가른다.** 소스셋은 빠른 쪽에 testcontainers 의존성을 안 줘서
// 컴파일로 막는 1위 강제 지점이지만, 지금 옮길 파일이 78개라 diff 를 아무도 못 읽는다.
// 태그는 그보다 약한 대신 **표식을 빠뜨릴 자리가 없다** — 컨테이너가 `PostgresTestBase`·
// `HttpTestBase` 에만 있어서 DB 를 쓰려면 상속해야 하고, 상속하면 태그가 따라온다.
// 상속하지 않고 DB 를 쓰면 빠른 레인에서 곧바로 빨개진다.
// **신고 목록의 단일 진실**(`Q25`). 아래 `inputs.files` 와 `BuildInputTest` 가 같은 값을 든다.
//
// **테스트가 이 파일을 글자로 읽지 않는다.** 읽게 두면 신고를 쓰는 방식이 하나 늘 때마다
// (파일 하나씩 적는 것과 폴더를 통째로 거는 것이 이미 섞여 있다) 읽는 쪽이 같이 깨진다.
// 대신 아래 `tasks.withType<Test>` 가 이 목록을 시스템 속성으로 내려보낸다.
val comparedInSlowLane = listOf(
	"../doc/reference/data-lifecycle.md",
	"../doc/reference/commerce-compliance.md",
	// **폴더로 건다**(`Q83`). `IdentifierReferenceTest` 가 `doc/reference` 를 **목록으로 읽어서**
	// 문서가 부르는 컬럼·테스트 이름을 대조한다 — 파일 하나씩 걸면 새 문서가 생긴 날 입력이 안 바뀐다.
	"../doc/reference",
	// **javadoc 만 고치면 클래스 파일이 같다.** 같은 테스트가 주석도 읽으므로 소스를 입력으로 건다 —
	// 안 걸면 이름이 틀린 주석을 넣어도 `integrationTest` 가 UP-TO-DATE 로 건너뛴다(실측, `Q83`).
	"src/main/java",
	// **그림이 대조 대상이다**(`66`). `SchemaErdTest` 가 DB 에서 뽑은 것을 이 폴더의 글과 견주는데,
	// 안 걸면 **그림을 손으로 고쳐도 UP-TO-DATE 로 넘어간다** — 스냅샷을 쓰는 자리의 기본 함정이다.
	"../doc/erd",
	// **되돌리는 파일을 실제로 돌린다**(`Q173`). `MigrationUndoRunTest` 가 이 폴더를 읽어 번호 역순으로 돌린다 —
	// 안 걸면 되돌리는 파일만 고친 청크에서 그 시험이 `UP-TO-DATE` 로 건너뛴다.
	"src/main/resources/db/undo")
val comparedInFastLane = listOf(
	"../PLAN.md",
	"../PROGRESS.md",
	// `BuildInputTest` 가 `lane compare` 줄을 읽어 신고 목록과 맞춘다(`Q111`). 그 줄만 고쳐도 다시 돌아야 한다.
	"../scripts/verify-fingerprint.sh",
	"../doc/reference/stack.md",
	"../docker-compose.yml",
	// **폴더로 건다**(`Q48`). `DocumentMapConsistencyTest` 의 둘째가 `doc/reference/` 를
	// **목록으로 읽어서** 지도에 없는 문서를 찾는다 — 파일 하나씩 걸면 **새 문서가 생긴 날
	// 입력이 안 바뀌어서** 정작 그 문서가 안 잡힌다. 잡아야 할 사건이 곧 입력의 변화다.
	"../doc/reference",
	// 셋째가 여기서 `create table` 을 읽는다. 표를 더한 청크에서 이 테스트가 돌아야 한다.
	"src/main/resources/db/migration",
	// `PlanProgressConsistencyTest` 의 예약 번호 검사가 **시드도 센다**(`Q69`) — 거기 있는 번호는
	// 예약이 아니라 인용이라 걷어내야 한다. 시드가 하나 늘면 그 판정이 바뀌므로 입력이다.
	"src/main/resources/db/seed",
	// 배포에만 붓는 시드(`Q143`). 같은 이유로 입력이고, `SeedOutboxTest` 도 이쪽을 읽는다.
	"src/main/resources/db/seed-demo")
val comparedScreenRoot = "../frontend/src"
val declaredComparedInputs = comparedInSlowLane + comparedInFastLane + comparedScreenRoot

val integrationTest = tasks.register<Test>("integrationTest") {
	description = "컨테이너를 띄우는 테스트만 돌린다."
	group = "verification"
	testClassesDirs = sourceSets.test.get().output.classesDirs
	classpath = sourceSets.test.get().runtimeClasspath
	useJUnitPlatform { includeTags("db") }
	shouldRunAfter(tasks.test)

	// fork 를 둘로 늘린다(`2i-2`). 그전에는 하나였다 — `maxParallelForks = 2` 로 세 번 돌려
	// 두 번이 빨갰고, 원인은 **fork 둘이 컨테이너 하나의 DB 하나를 나눠 쓴 것**이었다(`D15`).
	// `PostgresTestBase.forkDatabase`·`forkRedis` 가 fork 마다 DB 를 갈라서 그 이유가 사라졌다.
	//
	// **컨테이너는 여전히 하나다.** fork 마다 띄우면 재사용이 죽어서 `2i-1` 이 줄인 14초를 도로 낸다.
	//
	// **넷이 아니라 둘인 것은 재서 정했다**(중앙값 1↦93초 · 2↦81초 · 4↦84초, `D15`).
	// fork 마다 Spring 컨텍스트를 새로 띄우는 값이 붙어서 **늘린 만큼 빨라지지 않고 넷은 되레 는다.**
	// 기계마다 갈리는 값이라 `-PintegrationForks=N` 으로 다시 재고 이 기본값을 고친다.
	maxParallelForks = (findProperty("integrationForks") as String?)?.toInt() ?: 2

	// **이 레인에도 문서를 읽는 대조가 있다**(`점검 M`). `DataLifecycleCoverageTest` 가
	// `data-lifecycle.md` 를 읽어 DB 의 표 목록과 맞춰 보는데, **신고가 여기 하나도 없어서
	// 그 문서만 고친 청크에서 통째로 `UP-TO-DATE` 로 건너뛴다.** 실측으로 확인했다.
	//
	// **같은 함정을 저장소가 네 번 밟았다** — `2f`(`stack.md`)·`Q16`(화면 소스)·여기·`docker-compose.yml`.
	// 네 번이면 기록이 아니라 강제 지점이 필요하다(`Q25`).
	// `RequirementEnforcementTest`(`Q33`) 가 요건표의 강제 지점 이름을 실물과 대조한다 — 표만 고친 청크도 돌아야 한다.
	inputs.files(comparedInSlowLane.map { file(it) })
		.withPropertyName("comparedDocsInSlowLane")
		.withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.withType<Test> {
	// 스냅샷은 명시적으로 갱신한다. 자동으로 덮으면 diff 를 안 보고 넘어간다.
	systemProperty("snapshot.update", System.getProperty("snapshot.update") ?: "false")

	// **신고 목록을 테스트에 그대로 넘긴다**(`Q25`). `BuildInputTest` 가 이것과
	// 테스트 소스가 실제로 읽는 경로를 맞춰 본다. 값이 바뀌면 이 속성이 바뀌어
	// 그 테스트가 다시 돈다 — 신고를 고치고 대조가 안 도는 일이 없다.
	systemProperty("declaredComparedInputs", declaredComparedInputs.joinToString(";"))

	// **컨텍스트 캐시를 세려면 테스트 JVM 에 걸어야 한다**(`2i-3`). `-D` 는 Gradle JVM 에만 가서,
	// 그대로 주면 로그가 한 줄도 안 나오고 **세는 데 실패한 것이 성공처럼 보인다.**
	System.getProperty("contextCacheLog")?.let {
		systemProperty("logging.level.org.springframework.test.context.cache", "DEBUG")
		// Gradle 이 테스트 JVM 의 표준 출력을 삼킨다. 열지 않으면 DEBUG 를 켜도 아무것도 안 보인다.
		testLogging { showStandardStreams = true }
	}
}

tasks.test {
	description = "컨테이너를 안 타는 테스트만 돌린다."
	useJUnitPlatform { excludeTags("db") }

	// 대조하는 문서를 입력으로 신고한다. 안 하면 문서만 고친 청크에서 Gradle 이 `test` 를
	// `UP-TO-DATE` 로 건너뛰고, 그 대조가 한 번도 안 돈다.
	//
	// **`stack.md` 는 청크 `2f` 에서 뒤늦게 붙었다.** `StackVersionConsistencyTest` 를
	// 세우고 표를 일부러 틀리게 고쳐 봤는데 빌드가 초록이었다 —
	// **걸려 있는 것과 도는 것은 다르다.**
	//
	// 대조 테스트 셋은 컨테이너를 안 타서 이 레인에 있다. 그래서 신고도 여기에만 건다.
	//
	// **`docker-compose.yml` 이 셋째 구멍이었다**(`점검 M`). `StackVersionConsistencyTest` 는
	// `stack.md` 만 읽는 것이 아니라 **표의 세 번째 칸이 가리키는 파일까지** 읽는데,
	// Postgres·Redis 이미지 태그가 거기 있다. 실측으로 확인했다 — 그 파일을 고치고
	// `test` 를 돌리니 `UP-TO-DATE` 였다.
	inputs.files(comparedInFastLane.map { file(it) })
		.withPropertyName("comparedDocs")
		.withPathSensitivity(PathSensitivity.RELATIVE)

	// **화면 소스도 입력이다**(`Q16`). 대조 다섯이 프론트 파일을 읽는다 —
	// `ErrorSlugScreenTest`(오류 슬러그)·`OrderRecordTextTest`(상태 문구)·
	// `WithdrawalNoticeScreenTest`(제한 사유)·`PasswordHintScreenTest`(비밀번호 길이)·`ScreenLengthTest`(입력칸 maxLength, `Q27`). 가운데 둘은 `Q20-2` 다.
	// **수를 적는 자리는 여기 하나가 아니다** — `testing-strategy.md` 의 대조 표가 실물 목록이다.
	//
	// **안 걸면 화면만 고친 청크에서 `test` 가 `UP-TO-DATE` 로 건너뛴다.** 문서에서 두 번
	// 겪은 것과 같은 함정인데, `OrderRecordTextTest` 는 그동안 이 상태로 있었다 —
	// **대조가 걸려 있는 것과 도는 것은 다르다.**
	inputs.files(fileTree(comparedScreenRoot) { include("**/*.ts", "**/*.tsx") })
		.withPropertyName("comparedScreens")
		.withPathSensitivity(PathSensitivity.RELATIVE)
}

// `build` 는 두 레인을 다 돈다. **가른 것은 도는 자리지 무엇을 검증하나가 아니다** —
// 「검증」 표의 통과 기준(`BUILD SUCCESSFUL`)이 뜻하는 범위가 좁아지면 안 된다.
tasks.check {
	dependsOn(integrationTest, tasks.jacocoTestReport)
}

// 커버리지는 측정만 하고 목표를 두지 않는다. 수치를 채우려는 테스트가 생기기 때문이다.
//
// **`finalizedBy` 를 안 쓴다.** 그러면 빠른 레인만 돌려도 리포트가 딸려 오고,
// 리포트가 느린 레인에 매달려 있어서 **`test` 하나가 결국 컨테이너를 띄운다.**
tasks.jacocoTestReport {
	dependsOn(tasks.test, integrationTest)
	// 두 레인이 각자 exec 를 남긴다. 하나만 읽으면 커버리지가 레인 하나 몫으로 줄어든다.
	executionData.setFrom(layout.buildDirectory.dir("jacoco").map { dir ->
		fileTree(dir) { include("*.exec") }
	})
	reports {
		xml.required = true
		html.required = true
	}
}

// 변이 시험(`69`). **손으로 돌린다** — `verify.sh` 에도 `check` 에도 안 건다.
//
// 「게이트는 부순 증거가 있어야 닫힌다」(`2m`)를 사람이 손으로 부숴야 걸리던 자리를 **기계가 부순다.**
// 산출물은 커버리지 수치가 아니라 **살아남은 변이 목록**이고, 문턱을 안 둔다 — 수치를 두면 그것을 채우려는
// 시험이 생긴다(위 jacoco 와 같은 판단).
//
// **플러그인이 아니라 명령줄을 부른다.** Gradle 9 와 맞는 PIT 플러그인 판을 확인하지 못했고, 명령줄은
// 클래스패스와 인자만 받아서 빌드 도구 판에 안 묶인다. 대상은 컨테이너 없이 도는 순수 계산이다 —
// 한 변이마다 시험을 다시 돌려서 `db` 태그 시험을 넣으면 한 번에 몇 시간이 된다(적용범위는 `testing-strategy.md`).
val pitest = configurations.create("pitest")

dependencies {
	pitest("org.pitest:pitest-command-line:1.30.0")
	pitest("org.pitest:pitest-junit5-plugin:1.2.3")
}

val mutationTargets = listOf(
	"com.projectshop.shop.payment.RefundMath",
	"com.projectshop.shop.auth.PasswordPolicy",
	"com.projectshop.shop.support.TaxRetention",
	"com.projectshop.shop.support.BusinessCalendar")

tasks.register<JavaExec>("mutationTest") {
	description = "순수 계산 클래스에 변이를 넣고 빠른 레인 시험이 잡는지 본다(69). 손으로 돌린다."
	group = "verification"
	dependsOn(tasks.testClasses)
	mainClass = "org.pitest.mutationtest.commandline.MutationCoverageReport"
	classpath = pitest + sourceSets.test.get().runtimeClasspath
	// Windows 에서 클래스패스가 길면 줄여서 넘어가고, 그러면 PIT 가 `java.class.path` 에서 자기 에이전트를
	// 못 찾는다(「Unable to load class content for org.pitest.boot.HotSwapAgent」, 실측). 파일로 한 번 더 준다.
	val classPathFile = layout.buildDirectory.file("pitest-classpath.txt")
	doFirst {
		classPathFile.get().asFile.writeText(classpath.files.joinToString("\n") { it.path })
	}
	args(
		"--classPathFile", classPathFile.get().asFile.path,
		"--reportDir", layout.buildDirectory.dir("reports/pitest").get().asFile.path,
		"--targetClasses", mutationTargets.joinToString(","),
		"--targetTests", mutationTargets.joinToString(",") { "${it}Test*" },
		"--sourceDirs", file("src/main/java").path,
		"--excludedGroups", "db",
		"--outputFormats", "XML,HTML",
		"--timestampedReports", "false",
		"--threads", "4")
}
