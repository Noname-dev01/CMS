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

    /** 공지 게시판의 {@link #boardKey}. 공통 공지 게시판(id=1)은 V2가 시드한다. */
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
     * 시스템 게시판 키. null이면 일반 게시판이다. 공지 게시판은 {@link #NOTICE_KEY}이며 공개 {@code /notices}가 이 게시판에 의존하므로
     * 삭제·비공개 전환을 서비스가 막는다(PLAN-notice-to-board.md 쟁점 1·2).
     */
    @Column(name = "board_key", length = 30)
    private String boardKey;

    /**
     * 소속 사이트. null = 모든 공개 사이트 공통. 공개 사이트(PUBLIC)만 가리키며 서비스(ContentSiteScope)가 검증한다.
     * 소프트 삭제하면 비워진다 — 삭제된 게시판은 어느 사이트에도 속하지 않아 사이트 삭제의 FK RESTRICT에 걸리지 않는다.
     */
    @Column(name = "site_id")
    private Long siteId;

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

    /** 시스템 게시판(키가 있는 게시판)인지. 비공개 전환과 표시 대상 변경이 금지된다(공통 공지는 삭제도 금지 — {@link #isCommonNotice}). */
    public boolean isSystem() {
        return boardKey != null;
    }

    /** 공통 공지 게시판({@link #NOTICE_KEY})인지. 사이트 전용 공지 게시판({@link #noticeKeyFor})과 달리 삭제할 수 없다. */
    public boolean isCommonNotice() {
        return NOTICE_KEY.equals(boardKey);
    }

    /** 사이트 전용 공지 게시판의 키 — 서버만 만든다(사용자 입력 아님). 기존 UNIQUE(board_key)가 사이트당 하나를 보장한다. */
    public static String noticeKeyFor(Long siteId) {
        return NOTICE_KEY + ":" + siteId;
    }

    /** 표시 대상 변경. 호출자가 사이트 존재·타입을 검증하고 시스템 게시판은 막는다. {@code null} = 공통. */
    public void changeSite(Long siteId, LocalDateTime now) {
        this.siteId = siteId;
        this.updateDate = now;
    }

    /**
     * 소프트 삭제. deleted=true 처리 후 수정 시각을 갱신한다. 소속 사이트와(사이트 전용 공지 게시판의) 키를 비워, 삭제된 행이 사이트 삭제의
     * FK와 board_key 유일성을 붙잡지 않게 한다.
     */
    public void softDelete(LocalDateTime now) {
        this.deleted = true;
        this.siteId = null;
        this.boardKey = null;
        this.updateDate = now;
    }
}
