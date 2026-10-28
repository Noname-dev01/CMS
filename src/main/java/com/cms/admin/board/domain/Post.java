package com.cms.admin.board.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 게시글(PLAN-board.md 쟁점 7). 공지({@code Notice})와 같은 구조 — {@code useYn}(노출)과 {@code deleted}(소프트 삭제)는 별도 상태이고,
 * 본문은 정리된 HTML이다. {@code boardId}는 연관관계 매핑 없이 plain {@code Long}으로 두고 DB FK만 건다(공지 첨부와 같은 이유).
 */
@Entity
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Post {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "board_id", nullable = false)
    private Long boardId;

    @Column(nullable = false, length = 200)
    private String title;

    @Lob
    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    private String content;

    @Column(name = "use_yn", nullable = false)
    private Boolean useYn;

    @Column(nullable = false)
    private Boolean deleted;

    @Column(name = "author_id", nullable = false, length = 100)
    private String authorId;

    private LocalDateTime createDate;

    private LocalDateTime updateDate;

    /**
     * 최종 수정자 userId — {@link #updateDate}를 만든 관리자다(생성 시 작성자). 둘은 항상 같은 쓰기에서 함께 바뀐다.
     * null은 V5 이전에 만들어져 이후 손대지 않은 글(수정자 모름)이다. 원본은 감사 로그다(PLAN-board-definition.md PR 0).
     */
    @Column(name = "updater_id", length = 100)
    private String updaterId;

    /** 공개 노출 시작 시각(포함). null이면 시작 제한 없음. 구간은 [publishStart, publishEnd) — PLAN-post-publish-period.md. */
    @Column(name = "publish_start")
    private LocalDateTime publishStart;

    /** 공개 노출 종료 시각(미포함). null이면 종료 제한 없음. */
    @Column(name = "publish_end")
    private LocalDateTime publishEnd;

    /**
     * 공개 조회 누적 횟수(PLAN-view-download-count.md). 증가는 {@code ContentCounterWriter}의 원자적 UPDATE만 한다.
     * {@code updatable=false}라 관리자 수정이 로드된 낡은 값으로 이 열을 덮어쓰지 않는다 — 제거하지 않는다.
     */
    @Builder.Default
    @Column(name = "view_count", nullable = false, updatable = false)
    private long viewCount = 0L;

    /**
     * 공개 목록 1페이지 상단 고정 여부. 개수 상한(게시판 {@code pinMaxCount})은 서비스가 게시판 {@code FOR UPDATE} 아래서 판정한다.
     * 상한을 낮추거나 꺼도 이 값은 그대로 두고 해제만 서비스가 언제나 허용한다(PLAN-board-definition.md PR 3 D3-3).
     */
    @Builder.Default
    @Column(nullable = false)
    private Boolean pinned = false;

    /**
     * 부분 수정. 각 파라미터가 null이 아닐 때만 반영한다(null=기존값 유지). 공백 거부·전체 null 거부는 서비스 책임이다.
     *
     * @param updaterId 수정한 관리자 userId — updaterId에 기록
     * @param now       앱 Clock 기준 현재 시각 — updateDate에 기록
     */
    public void update(String title, String content, Boolean useYn, Boolean pinned, String updaterId, LocalDateTime now) {
        if (title != null) {
            this.title = title;
        }
        if (content != null) {
            this.content = content;
        }
        if (useYn != null) {
            this.useYn = useYn;
        }
        if (pinned != null) {
            this.pinned = pinned;
        }
        touch(updaterId, now);
    }

    /** 본문 필드 없이 수정 시각·수정자만 바꾼다 — 첨부 업로드·삭제가 쓴다(게시글 행 잠금 안에서 호출). */
    public void touch(String updaterId, LocalDateTime now) {
        this.updaterId = updaterId;
        this.updateDate = now;
    }

    /**
     * 노출 기간을 통째로 교체한다(null은 그쪽 열림). 값은 호출자(서비스)가 범위 검사·분 절단·순서 검사를 마친 것이어야 한다.
     * {@code updateDate}는 {@link #update}가 갱신하므로 같은 트랜잭션에서 함께 호출한다.
     */
    public void changePublishPeriod(LocalDateTime publishStart, LocalDateTime publishEnd) {
        this.publishStart = publishStart;
        this.publishEnd = publishEnd;
    }

    /** 소프트 삭제. deleted=true 처리 후 수정 시각·수정자를 갱신한다. */
    public void softDelete(String updaterId, LocalDateTime now) {
        this.deleted = true;
        touch(updaterId, now);
    }
}
