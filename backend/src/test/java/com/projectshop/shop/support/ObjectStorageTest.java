package com.projectshop.shop.support;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.projectshop.shop.StorageTestBase;
import com.projectshop.shop.support.ObjectStorage.Visibility;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * 객체를 버킷 사이로 옮기면 원 버킷에서 사라지고 새 버킷에 같은 키로 남는지 잰다(`Q464`). 저작권 게시 중단이 이 길로 숨긴다.
 */
@DisplayName("저장소 버킷 이동")
class ObjectStorageTest extends StorageTestBase {

    @Autowired
    private ObjectStorage storage;

    @Autowired
    private S3Client s3;

    @Test
    @DisplayName("옮기면 원 버킷은 없고 새 버킷에 같은 키로 있다")
    void movesBetweenBuckets() {
        storage.put(Visibility.PUBLIC, "move/a.jpg", new byte[] {1, 2, 3}, "image/jpeg");

        storage.move(Visibility.PUBLIC, Visibility.PRIVATE, "move/a.jpg");

        assertThatThrownBy(() -> s3.headObject(b -> b.bucket(PUBLIC_BUCKET).key("move/a.jpg")))
                .isInstanceOf(NoSuchKeyException.class);
        s3.headObject(b -> b.bucket(PRIVATE_BUCKET).key("move/a.jpg"));

        storage.move(Visibility.PRIVATE, Visibility.PUBLIC, "move/a.jpg");

        s3.headObject(b -> b.bucket(PUBLIC_BUCKET).key("move/a.jpg"));
    }
}
