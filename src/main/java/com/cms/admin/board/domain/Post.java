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
     * 부분 수정. 각 파라미터가 null이 아닐 때만 반영한다(null=기존값 유지). 공백 거부·전체 null 거부는 서비스 책임이다.
     *
     * @param now 앱 Clock 기준 현재 시각 — updateDate에 기록
     */
    public void update(String title, String content, Boolean useYn, LocalDateTime now) {
        if (title != null) {
            this.title = title;
        }
        if (content != null) {
            this.content = content;
        }
        if (useYn != null) {
            this.useYn = useYn;
        }
        this.updateDate = now;
    }

    /** 소프트 삭제. deleted=true 처리 후 수정 시각을 갱신한다. */
    public void softDelete(LocalDateTime now) {
        this.deleted = true;
        this.updateDate = now;
    }
}
