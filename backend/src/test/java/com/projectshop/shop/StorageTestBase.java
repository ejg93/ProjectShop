package com.projectshop.shop;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

/**
 * 파일 저장소가 필요한 테스트의 바탕({@code 26}~{@code 28}).
 *
 * <h2>왜 따로 있나</h2>
 *
 * <p><b>저장소를 쓰는 테스트에서만 띄운다.</b> {@link PostgresTestBase} 에 두면
 * 그것을 안 쓰는 느린 레인 전부가 같이 띄운다({@code KafkaTestBase} 와 같은 판단).
 *
 * <p><b>설정을 한 자리에 모으는 이유는 컨텍스트 캐시다.</b> {@code shop.storage.*} 가
 * 캐시 키라, 켜는 자리를 여기 하나로 묶으면 <b>켠 컨텍스트도 하나</b>다.
 *
 * <h2>이미지가 S3Mock 이다</h2>
 *
 * <p>MinIO 였다({@code 26}). <b>2026-09-25 에 MinIO 공개 이미지가 사라졌다</b> — Docker Hub 의
 * {@code minio/minio} 는 2025 에 없어졌고, 그 뒤 쓰던 {@code quay.io/minio/minio} 가 인증을
 * 요구하기 시작해 CI 가 이미지를 못 당겼다(로컬은 캐시라 돌았다). 우리가 부르는 S3 호출은
 * 다섯(버킷 만들기·올리기·읽기·지우기·서명 URL)이고 배포는 R2 라 로컬은 어차피 목이다 —
 * Docker Hub 에 공개로 있는 {@code adobe/s3mock} 으로 바꿨다({@code Q229}, {@code stack.md}).
 *
 * <p>자격 증명은 아무 값이나 받는다(S3Mock 은 서명을 안 본다). 그래도 값을 주는 이유는
 * SDK 가 서명할 때 빈 값을 거부해서다.
 *
 * <h2>버킷 이름에 pid 를 넣는다</h2>
 *
 * <p>컨테이너를 재사용하므로 고정 이름을 쓰면 <b>지난 실행이 만든 버킷을 보고 초록이 된다</b> —
 * {@code 26} 이 실측으로 밟았다. 만드는 코드를 지우고 돌렸는데 통과했고, 그때 재던 것은
 * 자기가 만든 것이 아니라 남은 흔적이었다. fork 끼리 안 겹치는 것은 덤이다.
 */
@SpringBootTest(properties = "shop.storage.bootstrap=true")
public abstract class StorageTestBase extends PostgresTestBase {

    /** 컴포즈와 같은 이미지다. 갈리면 테스트가 통과해도 로컬에서 깨진다({@code stack.md} 버전 표) */
    static final String S3MOCK_IMAGE = "adobe/s3mock:5.2.3";

    private static final int S3MOCK_HTTP_PORT = 9090;

    @SuppressWarnings("resource")
    protected static final GenericContainer<?> S3MOCK = new GenericContainer<>(S3MOCK_IMAGE)
            .withExposedPorts(S3MOCK_HTTP_PORT)
            .withReuse(true);

    static {
        S3MOCK.start();
    }

    protected static final String PUBLIC_BUCKET = "shop-public-" + ProcessHandle.current().pid();
    protected static final String PRIVATE_BUCKET = "shop-private-" + ProcessHandle.current().pid();

    static String s3Url() {
        return "http://" + S3MOCK.getHost() + ":" + S3MOCK.getMappedPort(S3MOCK_HTTP_PORT);
    }

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("shop.storage.endpoint", StorageTestBase::s3Url);
        registry.add("shop.storage.access-key", () -> "s3mock");
        registry.add("shop.storage.secret-key", () -> "s3mock");
        registry.add("shop.storage.public-bucket", () -> PUBLIC_BUCKET);
        registry.add("shop.storage.private-bucket", () -> PRIVATE_BUCKET);
    }
}
