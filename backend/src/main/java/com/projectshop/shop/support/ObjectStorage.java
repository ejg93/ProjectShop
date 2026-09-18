package com.projectshop.shop.support;

import java.io.InputStream;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * 파일을 넣고 꺼내는 유일한 입구({@code 26}~{@code 28}, {@code media-rules.md}).
 *
 * <h2>왜 자원 패키지가 S3 를 직접 안 부르나</h2>
 *
 * <p><b>버킷을 고르는 판단이 한 곳에 있어야 한다.</b> 갈래({@link Visibility})를 인자로 받아서
 * 부르는 쪽이 「공개냐 비공개냐」를 <b>말하게</b> 하고, 그 말이 어느 버킷인지는 여기서만 안다 —
 * 부르는 쪽이 버킷 이름을 알면 새 자원이 생길 때마다 그 판단이 한 벌씩 는다.
 *
 * <h2>갈래마다 여는 법이 다르다</h2>
 *
 * <p>공개는 <b>만료 5분 서명 URL</b> 이고 비공개는 <b>앱을 지나는 스트림</b>이다
 * ({@code media-rules.md} 「여는 법」, 사용자 결정 2026-09-18). 그래서 공개 쪽만
 * {@link #presignedUrl} 이 있고 비공개 쪽만 {@link #open} 이 있다 —
 * <b>없는 메서드가 규칙을 든다.</b> 비공개 파일에 서명 URL 을 내주려면 이 클래스를 고쳐야 하고,
 * 그 고침은 리뷰 diff 에 뜬다.
 */
@Component
public class ObjectStorage {

    /**
     * 갈래. <b>「로그인 안 한 사람에게도 보이나」가 가른다</b>({@code media-rules.md}).
     *
     * <p><b>애매하면 {@link #PRIVATE} 다.</b> 틀렸을 때 한쪽은 느려지고 다른 쪽은 샌다.
     */
    public enum Visibility {
        /** 상품 사진처럼 누구나 보는 것. 버킷 자체는 비공개고 앱이 서명 URL 을 내준다 */
        PUBLIC,
        /** 반품 검수 사진처럼 요청마다 판정이 필요한 것 */
        PRIVATE
    }

    /** 목록 한 화면을 보는 동안만 유효하면 된다. 넘어가도 5분 뒤에는 아무것도 아니다 */
    private static final Duration PRESIGN_TTL = Duration.ofMinutes(5);

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String publicBucket;
    private final String privateBucket;

    ObjectStorage(
            S3Client s3,
            S3Presigner presigner,
            @Value("${shop.storage.public-bucket}") String publicBucket,
            @Value("${shop.storage.private-bucket}") String privateBucket) {
        this.s3 = s3;
        this.presigner = presigner;
        this.publicBucket = publicBucket;
        this.privateBucket = privateBucket;
    }

    public void put(Visibility visibility, String key, byte[] body, String contentType) {
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket(visibility))
                        .key(key)
                        .contentType(contentType)
                        .build(),
                RequestBody.fromBytes(body));
    }

    /**
     * 공개 갈래만 받는다. 서명 URL 은 <b>발급 시점에 한 번</b> 판정하므로,
     * 요청마다 판정해야 하는 비공개에 쓰면 그 규칙이 깨진다.
     */
    public String presignedUrl(String key) {
        return presigner.presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(PRESIGN_TTL)
                        .getObjectRequest(GetObjectRequest.builder()
                                .bucket(publicBucket)
                                .key(key)
                                .build())
                        .build())
                .url()
                .toString();
    }

    /** 비공개 갈래만 받는다. 앱이 바이트를 나르는 대신 요청마다 판정한다 */
    public InputStream open(String key) {
        return s3.getObject(GetObjectRequest.builder()
                .bucket(privateBucket)
                .key(key)
                .build());
    }

    /**
     * <b>저장소에서 실제로 지운다.</b> 표의 행만 지우고 객체를 두면
     * 서명 URL 을 아는 사람에게 계속 열린다({@code media-rules.md} 「남의 것이 올라오면」).
     */
    public void delete(Visibility visibility, String key) {
        s3.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket(visibility))
                .key(key)
                .build());
    }

    private String bucket(Visibility visibility) {
        return visibility == Visibility.PUBLIC ? publicBucket : privateBucket;
    }
}
