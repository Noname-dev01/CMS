package com.cms.admin.contentimage.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 편집기 본문 이미지(V24). 파일은 {@code FileStorage} 루트(네임스페이스 없음)에 있고, 공개 여부는 이 행이 아니라
 * {@link ContentImageRef}(어떤 콘텐츠가 참조하는지)로 판정한다(PLAN-html-editor.md 쟁점 6).
 */
@Entity
@Table(name = "content_image")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContentImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "storage_key", nullable = false, unique = true)
    private String storageKey;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    /** 업로드한 관리자 userId 스냅샷(member FK 아님 — 공지 authorId와 같은 규약). */
    @Column(name = "uploader_id", nullable = false, length = 100)
    private String uploaderId;

    private LocalDateTime createDate;
}
