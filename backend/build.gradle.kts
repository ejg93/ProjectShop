plugins {
	java
	jacoco
	// 버그 패턴 검출. 바이트코드를 읽어서 컴파일러에 안 붙는다 — ErrorProne 은 javac
	// 플러그인이라 JDK 를 올릴 때마다 같이 막힌다(`stack.md`).
	id("com.github.spotbugs") version "6.5.11"
	id("org.springframework.boot") version "4.1.0"
	id("io.spring.dependency-management") version "1.1.7"
}

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

// Boot 의 BOM 이 Testcontainers 버전을 관리하지 않아서 직접 넣는다.
//
// 1.21.4 미만은 Docker Engine 29 에서 안 뜬다. docker-java 가 API 버전을 1.32 로 잡는데
// Docker 29 의 최소 지원이 1.44 라서 /info 가 빈 응답과 400 을 준다.
// 오류 메시지는 "Could not find a valid Docker environment" 라 원인이 안 드러난다.
// testcontainers-java#11212, #11235
dependencyManagement {
	imports {
		mavenBom("org.testcontainers:testcontainers-bom:2.0.5")
	}
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-cache")
	implementation("com.github.ben-manes.caffeine:caffeine")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-data-redis")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
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
	runtimeOnly("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:postgresql")
	testImplementation("org.testcontainers:junit-jupiter")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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

tasks.withType<Test> {
	useJUnitPlatform()

	// 대조하는 문서를 입력으로 신고한다. 안 하면 문서만 고친 청크에서 Gradle 이 `test` 를
	// `UP-TO-DATE` 로 건너뛰고, 그 대조가 한 번도 안 돈다.
	//
	// **`stack.md` 는 청크 `2f` 에서 뒤늦게 붙었다.** `StackVersionConsistencyTest` 를
	// 세우고 표를 일부러 틀리게 고쳐 봤는데 빌드가 초록이었다 —
	// **걸려 있는 것과 도는 것은 다르다.**
	inputs.files(file("../PLAN.md"), file("../PROGRESS.md"), file("../doc/reference/stack.md"))
		.withPropertyName("comparedDocs")
		.withPathSensitivity(PathSensitivity.RELATIVE)

	// 스냅샷은 명시적으로 갱신한다. 자동으로 덮으면 diff 를 안 보고 넘어간다.
	systemProperty("snapshot.update", System.getProperty("snapshot.update") ?: "false")
	finalizedBy(tasks.jacocoTestReport)
}

// 커버리지는 측정만 하고 목표를 두지 않는다. 수치를 채우려는 테스트가 생기기 때문이다.
tasks.jacocoTestReport {
	dependsOn(tasks.test)
	reports {
		xml.required = true
		html.required = true
	}
}
