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
 * 편집기 본문 이미지. 파일은 {@code FileStorage} 루트(네임스페이스 없음)에 있고, 공개 여부는 이 행이 아니라
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

    /**
     * 업로드 출처(PLAN-board.md 쟁점 9) — {@code NOTICE}(공지 편집기) 또는 {@code BOARD}(게시판 편집기, {@link #scopeId} = 게시판 ID).
     * 참조 저장은 콘텐츠 출처와 같은 출처의 이미지만 허용하고, 공개 판정·미리보기도 출처로 판정한다.
     */
    @Column(name = "scope_type", nullable = false, length = 30)
    private String scopeType;

    @Column(name = "scope_id")
    private Long scopeId;

    private LocalDateTime createDate;

    /**
     * 파일이 있는 {@code FileStorage} 네임스페이스. 빈 문자열 = 루트(본문 이미지·라이브러리 업로드), {@code banner}·{@code popup} = 이관되거나
     * 배너·팝업에서 직접 올린 이미지. NULL이 아니라 빈 문자열인 이유는 UNIQUE가 NULL 중복을 허용하기 때문이다(PLAN-media-library.md R4-1).
     */
    @Builder.Default
    @Column(name = "namespace", nullable = false, length = 30)
    private String namespace = "";

    /** 검색·표시용 원본 파일명(서버가 정리한 값). 이관된 행은 배너·팝업 제목, 본문 이미지는 NULL일 수 있다. */
    @Column(name = "original_name", length = 200)
    private String originalName;

    /** 루트 영역 파일인지(네임스페이스 API가 아니라 루트 API로 읽고 지워야 하는지). */
    public boolean isRootNamespace() {
        return namespace == null || namespace.isEmpty();
    }
}
