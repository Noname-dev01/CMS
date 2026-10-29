package com.cms.admin.contentimage.repository;

import com.cms.admin.contentimage.domain.ContentImageThumb;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface ContentImageThumbRepository extends JpaRepository<ContentImageThumb, Long> {

    /**
     * 썸네일 행을 넣는다. PK가 {@code image_id}라 같은 이미지를 동시에 대표로 지정한 두 트랜잭션 중 뒤쪽은 앞쪽 행 잠금을 기다렸다가 0행을 받는다
     * (앞이 롤백하면 자기 행이 들어간다). 일반 INSERT/UPDATE로 바꾸면 이미지 공유 잠금끼리 서로 승격을 기다리는 교착이 생긴다(PLAN-board-definition.md PR 5 D4).
     *
     * @return 1 = 이 트랜잭션이 만들었다, 0 = 이미 있었다(호출자는 자기 파일을 지워야 한다)
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT IGNORE INTO content_image_thumb (image_id, storage_key, content_type, file_size, create_date)"
            + " VALUES (:imageId, :storageKey, :contentType, :fileSize, :createDate)", nativeQuery = true)
    int insertIgnore(@Param("imageId") Long imageId, @Param("storageKey") String storageKey,
                     @Param("contentType") String contentType, @Param("fileSize") long fileSize,
                     @Param("createDate") LocalDateTime createDate);
}
