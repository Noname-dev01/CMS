package com.cms.admin.board.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 게시글 첨부파일(PLAN-board.md 쟁점 7). 공지 첨부({@code NoticeAttachment})와 같은 모양으로 {@code deleted} 컬럼이 없다 — 하드 삭제만
 * 존재하고, 게시글 소프트 삭제는 첨부가 남아 있으면 409로 막혀 "삭제된 게시글의 첨부"라는 상태가 없다.
 */
@Entity
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PostAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @Column(name = "storage_key", nullable = false, length = 255)
    private String storageKey;

    private LocalDateTime createDate;
}
