package com.cms.admin.contentimage.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 이미지 단위 썸네일(PLAN-board-definition.md PR 5 D4). 이미지 하나에 행 하나이고 파일은 {@code FileStorage} 예약 네임스페이스
 * {@code thumb}에 있다. 행은 {@code INSERT IGNORE}로만 만들어지므로({@code ContentImageThumbRepository#insertIgnore}) 엔티티는 읽기 전용이다.
 * 원본 이미지 행이 지워지면 DB가 CASCADE로 함께 지운다(파일은 앱이 커밋 후 지운다).
 */
@Entity
@Table(name = "content_image_thumb")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContentImageThumb {

    /** 썸네일 파일이 있는 {@code FileStorage} 네임스페이스. */
    public static final String NAMESPACE = "thumb";

    @Id
    @Column(name = "image_id")
    private Long imageId;

    @Column(name = "storage_key", nullable = false, updatable = false)
    private String storageKey;

    @Column(name = "content_type", nullable = false, updatable = false, length = 100)
    private String contentType;

    @Column(name = "file_size", nullable = false, updatable = false)
    private Long fileSize;

    @Column(name = "create_date", updatable = false)
    private LocalDateTime createDate;
}
