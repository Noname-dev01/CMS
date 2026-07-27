package com.cms.admin.notice.domain;

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
 * 공지사항 첨부파일. {@code noticeId}는 authorId(논리적 스냅샷)와 달리 진짜 부모-자식 구조적
 * 관계이므로 DB 레벨 FK를 건다(V10 마이그레이션 참조). 다만 JPA 연관관계 매핑({@code @ManyToOne})은
 * 쓰지 않고 plain {@code Long} 컬럼만 둔다 — 불필요한 프록시·N+1·양방향 연관관계 위험을 피하고
 * {@link Notice}를 전혀 건드리지 않기 위함이다(adversarial-review/plan/PLAN-notice-attachment.md
 * 쟁점 6 참조).
 *
 * <p>Notice와 달리 {@code deleted} 컬럼이 없다 — 첨부는 하드 삭제만 존재한다(개별 DELETE = 행+파일
 * 제거). notice 소프트 삭제는 첨부가 남아있으면 409로 차단되므로(쟁점 14), 소프트 삭제된 notice에
 * 딸린 첨부라는 상태 자체가 존재하지 않는다.
 */
@Entity
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NoticeAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "notice_id", nullable = false)
    private Long noticeId;

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
