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
 * 게시판 정의(PLAN-board.md 쟁점 6). ADMIN만 만들고 고친다. {@code publicYn}은 공개 사이트 노출 여부,
 * {@code attachmentYn}은 새 첨부 업로드 허용 여부(끄더라도 기존 첨부는 그대로 노출·관리된다 — PR B).
 * {@code deleted}는 소프트 삭제이며 살아 있는 게시글이 없을 때만 삭제할 수 있다.
 */
@Entity
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Board {

    /** 공지 게시판의 {@link #boardKey}. V32가 만든다. */
    public static final String NOTICE_KEY = "NOTICE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "public_yn", nullable = false)
    private Boolean publicYn;

    @Column(name = "attachment_yn", nullable = false)
    private Boolean attachmentYn;

    @Column(nullable = false)
    private Boolean deleted;

    /**
     * 시스템 게시판 키(V31). null이면 일반 게시판이다. 공지 게시판은 {@link #NOTICE_KEY}이며 공개 {@code /notices}가 이 게시판에 의존하므로
     * 삭제·비공개 전환을 서비스가 막는다(PLAN-notice-to-board.md 쟁점 1·2).
     */
    @Column(name = "board_key", length = 30)
    private String boardKey;

    private LocalDateTime createDate;

    private LocalDateTime updateDate;

    /**
     * 부분 수정. 각 파라미터가 null이 아닐 때만 반영한다(null=기존값 유지). 공백 거부·전체 null 거부는 서비스 책임이다.
     *
     * @param now 앱 Clock 기준 현재 시각 — updateDate에 기록
     */
    public void update(String name, Boolean publicYn, Boolean attachmentYn, LocalDateTime now) {
        if (name != null) {
            this.name = name;
        }
        if (publicYn != null) {
            this.publicYn = publicYn;
        }
        if (attachmentYn != null) {
            this.attachmentYn = attachmentYn;
        }
        this.updateDate = now;
    }

    /** 시스템 게시판(키가 있는 게시판)인지. 삭제·비공개 전환이 금지된다. */
    public boolean isSystem() {
        return boardKey != null;
    }

    /** 소프트 삭제. deleted=true 처리 후 수정 시각을 갱신한다. */
    public void softDelete(LocalDateTime now) {
        this.deleted = true;
        this.updateDate = now;
    }
}
