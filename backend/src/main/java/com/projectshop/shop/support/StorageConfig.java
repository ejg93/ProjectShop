package com.projectshop.shop.support;

import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * 파일 저장소에 붙는 자리를 만든다({@code 26}, {@code media-rules.md}).
 *
 * <h2>왜 S3 클라이언트 하나인가</h2>
 *
 * <p>로컬은 MinIO 컨테이너고 <b>배포는 Cloudflare R2</b> 다(사용자 결정 2026-09-18).
 * 둘 다 S3 API 를 말해서 코드가 하나다 — 갈리는 것은 엔드포인트와 키 셋뿐이고 설정으로 들어온다.
 *
 * <h2>버킷을 컴포즈가 아니라 앱이 만든다</h2>
 *
 * <p>컴포즈에 {@code mc} 컨테이너를 두면 <b>로컬에서만 참인 절차</b>가 생기고, 배포에서는
 * 사람이 콘솔로 같은 일을 다시 한다. <b>절차가 두 벌이면 한쪽이 낡는다.</b>
 *
 * <p>그리고 앱이 만들면 <b>「공개 버킷을 안 만든다」를 테스트가 잴 수 있다</b>
 * ({@code StorageBootstrapTest}) — 컴포즈에 두면 그 사실이 사람 눈에만 보인다.
 *
 * <h2>기본이 꺼짐이다</h2>
 *
 * <p>{@code shop.storage.bootstrap} 이 {@code true} 일 때만 버킷을 만든다.
 * {@code shop.events.sink} 가 {@code none} 인 것과 같은 자리다({@code 33b}) — 켜는 것은
 * 저장소를 띄운 로컬과 그것을 띄우는 테스트뿐이고, 안 켜면 <b>기동이 저장소를 안 찾는다.</b>
 *
 * <p>클라이언트 빈은 그와 무관하게 늘 선다. <b>만드는 것과 붙는 것이 다르다</b> —
 * S3 클라이언트는 첫 호출에서 붙으므로 빈이 서는 것만으로는 연결이 안 열린다.
 */
@Configuration
class StorageConfig {

    private static final Logger log = LoggerFactory.getLogger(StorageConfig.class);

    @Bean
    S3Client s3Client(
            @Value("${shop.storage.endpoint}") String endpoint,
            @Value("${shop.storage.access-key}") String accessKey,
            @Value("${shop.storage.secret-key}") String secretKey,
            @Value("${shop.storage.region}") String region,
            @Value("${shop.storage.path-style}") boolean pathStyle) {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(pathStyle)
                        .build())
                .build();
    }

    /**
     * 서명 URL 을 만드는 쪽이다({@code 28}). 클라이언트와 <b>같은 자격·같은 엔드포인트</b>를 써야
     * 서명이 맞는다 — 갈리면 저장소가 {@code SignatureDoesNotMatch} 로 거절하고,
     * 그 메시지는 「키가 틀렸다」처럼 읽혀서 원인이 멀어진다.
     */
    @Bean
    S3Presigner s3Presigner(
            @Value("${shop.storage.endpoint}") String endpoint,
            @Value("${shop.storage.access-key}") String accessKey,
            @Value("${shop.storage.secret-key}") String secretKey,
            @Value("${shop.storage.region}") String region,
            @Value("${shop.storage.path-style}") boolean pathStyle) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                // **클라이언트와 같은 방식이어야 한다.** 여기만 빠뜨리면 서명 URL 이
                // `버킷.호스트` 로 나와서 `UnknownHostException` 이다 — 27 이 실측으로 밟았다.
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(pathStyle)
                        .build())
                .build();
    }

    /**
     * 갈래마다 버킷 하나를 만든다({@code media-rules.md} 「여는 법」).
     *
     * <p><b>정책을 안 건다.</b> S3 호환 저장소에서 버킷은 <b>만들 때부터 비공개</b>고,
     * 공개로 바꾸려면 정책을 따로 붙여야 한다 — 우리가 그 코드를 아예 안 쓰는 것이
     * 「공개 버킷을 안 만든다」의 실제 모습이다. 정책을 걸어서 막는 것이 아니라
     * <b>여는 코드가 없다.</b>
     *
     * <p><b>이미 있으면 넘어간다.</b> 재기동마다 도는 자리라 {@code 409} 가 정상이다.
     *
     * <p>기동을 다 마친 뒤에 돈다. 빈을 만드는 중에 바깥을 부르면 저장소가 늦게 뜬 날
     * <b>기동 실패의 원인이 저장소라는 것이 로그에서 멀어진다.</b>
     */
    @Bean
    @ConditionalOnProperty(name = "shop.storage.bootstrap", havingValue = "true")
    StorageBootstrap storageBootstrap(
            S3Client s3,
            @Value("${shop.storage.public-bucket}") String publicBucket,
            @Value("${shop.storage.private-bucket}") String privateBucket) {
        return new StorageBootstrap(s3, List.of(publicBucket, privateBucket));
    }

    static class StorageBootstrap {

        private final S3Client s3;
        private final List<String> buckets;

        StorageBootstrap(S3Client s3, List<String> buckets) {
            this.s3 = s3;
            this.buckets = buckets;
        }

        @EventListener(ApplicationReadyEvent.class)
        void createBuckets() {
            for (String bucket : buckets) {
                try {
                    s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                    log.info("버킷을 만들었다 bucket={}", bucket);
                } catch (BucketAlreadyOwnedByYouException e) {
                    log.debug("버킷이 이미 있다 bucket={}", bucket);
                }
            }
        }
    }
}
