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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import com.projectshop.shop.StorageTestBase;

/**
 * 버킷이 서고 <b>익명으로는 안 열리는지</b> 잰다({@code 26}, {@code media-rules.md} 「여는 법」).
 *
 * <h2>이것이 그 청크의 강제 지점이다</h2>
 *
 * <p>「공개 버킷을 안 만든다」는 문서에 적으면 아무것도 안 막는다({@code D23} 축 2의 5위).
 * 여기서 <b>실제로 저장소를 띄우고 인증 없이 열어 본다</b> — 열리면 빨갛다.
 *
 * <p><b>정책을 거는 코드가 없다는 것도 같이 잰다.</b> S3 호환 저장소의 버킷은 만들 때부터
 * 비공개고, 공개로 바꾸려면 정책을 붙여야 한다. 그 코드를 안 쓰는 것이 막는 방법이라,
 * 누가 나중에 정책을 붙이면 이 테스트가 그 자리에서 빨개진다.
 */
@DisplayName("파일 저장소 버킷")
class StorageBootstrapTest extends StorageTestBase {

    @Autowired
    private S3Client s3;

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
