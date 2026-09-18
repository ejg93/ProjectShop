package com.projectshop.shop;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.utility.DockerImageName;

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
 * <h2>이미지가 {@code quay.io} 다</h2>
 *
 * <p>Docker Hub 의 {@code minio/minio} 는 2025 에 없어졌다. Testcontainers 의 기본값이
 * 그 좌표라, 다른 레지스트리를 주면 <b>「compatible substitute」가 아니라고 거부한다</b> —
 * 이미지를 못 받는 것이 아니라 이름이 다르다고 막는 것이라 메시지가 원인에서 멀다({@code stack.md}).
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
    protected static final MinIOContainer MINIO = new MinIOContainer(DockerImageName
            .parse("quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z")
            .asCompatibleSubstituteFor("minio/minio"))
            .withReuse(true);

    static {
        MINIO.start();
    }

    protected static final String PUBLIC_BUCKET = "shop-public-" + ProcessHandle.current().pid();

    protected static final String PRIVATE_BUCKET = "shop-private-" + ProcessHandle.current().pid();

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("shop.storage.endpoint", MINIO::getS3URL);
        registry.add("shop.storage.access-key", MINIO::getUserName);
        registry.add("shop.storage.secret-key", MINIO::getPassword);
        registry.add("shop.storage.public-bucket", () -> PUBLIC_BUCKET);
        registry.add("shop.storage.private-bucket", () -> PRIVATE_BUCKET);
    }
}
