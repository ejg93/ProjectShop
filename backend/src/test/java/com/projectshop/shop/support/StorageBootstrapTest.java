package com.projectshop.shop.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * 버킷이 서고 <b>익명으로는 안 열리는지</b> 잰다({@code 26}, {@code media-rules.md} 「여는 법」).
 *
 * <h2>이것이 이 청크의 강제 지점이다</h2>
 *
 * <p>「공개 버킷을 안 만든다」는 문서에 적으면 아무것도 안 막는다({@code D23} 축 2의 5위).
 * 여기서 <b>실제로 저장소를 띄우고 인증 없이 열어 본다</b> — 열리면 빨갛다.
 *
 * <p><b>정책을 거는 코드가 없다는 것도 같이 잰다.</b> S3 호환 저장소의 버킷은 만들 때부터
 * 비공개고, 공개로 바꾸려면 정책을 붙여야 한다. 그 코드를 안 쓰는 것이 막는 방법이라,
 * 누가 나중에 정책을 붙이면 이 테스트가 그 자리에서 빨개진다.
 */
@Tag("db")
@SpringBootTest(properties = "shop.storage.bootstrap=true")
@DisplayName("파일 저장소 버킷")
class StorageBootstrapTest extends com.projectshop.shop.PostgresTestBase {

    /**
     * 컴포즈와 같은 이미지다. 갈리면 테스트가 통과해도 로컬에서 깨진다({@code stack.md} 버전 표).
     *
     * <p><b>{@code quay.io} 다.</b> Docker Hub 의 {@code minio/minio} 는 2025 에 없어졌다.
     * Testcontainers 의 기본값이 그 좌표라, 다른 레지스트리를 주면
     * <b>「compatible substitute」가 아니라고 거부한다</b> — 이미지를 못 받는 것이 아니라
     * 이름이 다르다고 막는 것이라, {@code asCompatibleSubstituteFor} 로 같은 것이라고 말해 준다.
     */
    private static final MinIOContainer MINIO =
            new MinIOContainer(DockerImageName
                    .parse("quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z")
                    .asCompatibleSubstituteFor("minio/minio"))
                    .withReuse(true);

    static {
        MINIO.start();
    }

    /**
     * <b>이 실행만의 이름이다.</b> 컨테이너를 재사용하므로({@code withReuse}) 고정 이름을 쓰면
     * <b>지난 실행이 만든 버킷을 보고 초록이 된다</b> — 실측으로 밟았다. 만드는 코드를 지우고
     * 돌렸는데 통과했고, 이 테스트가 재던 것은 자기가 만든 것이 아니라 남은 흔적이었다.
     *
     * <p>fork 마다 pid 가 달라서 같은 저장소를 나눠 쓰는 다른 fork 와도 안 겹친다
     * ({@code KafkaTestBase} 의 토픽 이름과 같은 수).
     */
    private static final String PUBLIC_BUCKET = "shop-public-" + ProcessHandle.current().pid();

    private static final String PRIVATE_BUCKET = "shop-private-" + ProcessHandle.current().pid();

    @Autowired
    private S3Client s3;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("shop.storage.endpoint", MINIO::getS3URL);
        registry.add("shop.storage.public-bucket", () -> PUBLIC_BUCKET);
        registry.add("shop.storage.private-bucket", () -> PRIVATE_BUCKET);
        registry.add("shop.storage.access-key", MINIO::getUserName);
        registry.add("shop.storage.secret-key", MINIO::getPassword);
    }

    @Test
    @DisplayName("갈래마다 버킷이 하나씩 선다")
    void 갈래마다_버킷이_하나씩_선다() {
        List<String> buckets = s3.listBuckets().buckets().stream()
                .map(software.amazon.awssdk.services.s3.model.Bucket::name)
                .toList();

        assertThat(buckets).contains(PUBLIC_BUCKET, PRIVATE_BUCKET);
    }

    /**
     * <b>이름이 「공개」라도 익명에게 안 열린다.</b> 여는 것은 앱이 내주는 서명 URL 이고,
     * 버킷 자체는 갈래와 무관하게 비공개다({@code media-rules.md} 「공개 쪽도 판정을 지난다」).
     */
    @Test
    @DisplayName("공개 버킷도 익명으로는 못 읽는다")
    void 공개_버킷도_익명으로는_못_읽는다() throws IOException, InterruptedException {
        s3.putObject(
                PutObjectRequest.builder().bucket(PUBLIC_BUCKET).key("probe.txt").build(),
                RequestBody.fromString("probe"));

        HttpResponse<Void> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(MINIO.getS3URL() + "/" + PUBLIC_BUCKET + "/probe.txt"))
                        .GET().build(),
                HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isIn(401, 403);
    }

    @Test
    @DisplayName("없는 열쇠는 없다고 한다")
    void 없는_열쇠는_없다고_한다() {
        assertThatThrownBy(() -> s3.getObject(builder ->
                builder.bucket(PRIVATE_BUCKET).key("없다.txt")))
                .isInstanceOf(NoSuchKeyException.class);
    }
}
