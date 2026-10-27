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
import java.util.Set;

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

    /** 공개 목록 한 페이지 글 수로 고를 수 있는 값(PLAN-board-definition.md §4 D1 — 임의 값이면 COUNT·OFFSET 비용 계산이 흐려진다). */
    public static final Set<Integer> ALLOWED_LIST_PAGE_SIZES = Set.of(10, 20, 30, 50);

    /** 새 글 배지 기간 상한(일). 0 = 끔. */
    public static final int MAX_NEW_BADGE_DAYS = 30;

    public static final int MAX_DESCRIPTION_LENGTH = 500;

    public static final int MAX_AUTHOR_LABEL_LENGTH = 50;

    /** 쓰기 중지 게시판의 쓰기 거부 문구 — 게시글·첨부·본문 이미지 서비스가 같은 문구를 쓴다(400). */
    public static final String WRITE_STOPPED_MESSAGE = "쓰기가 중지된 게시판입니다.";

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

    /** 공개 목록 머리의 설명(평문 — 출력은 {@code th:text}). null = 없음. */
    @Column(length = MAX_DESCRIPTION_LENGTH)
    private String description;

    /** 공개 목록 한 페이지 글 수({@link #ALLOWED_LIST_PAGE_SIZES}). */
    @Builder.Default
    @Column(name = "list_page_size", nullable = false)
    private Integer listPageSize = 10;

    /** 공개 화면의 작성자 표시명 — 로그인 ID를 공개하지 않으려는 게시판 단위 고정값. null = 표시 안 함. */
    @Column(name = "author_label", length = MAX_AUTHOR_LABEL_LENGTH)
    private String authorLabel;

    @Builder.Default
    @Column(name = "show_view_count", nullable = false)
    private Boolean showViewCount = false;

    /** 새 글 배지 기간(일). 0 = 끔. */
    @Builder.Default
    @Column(name = "new_badge_days", nullable = false)
    private Integer newBadgeDays = 0;

    /**
     * 쓰기 허용. false = 쓰기 중지(보관) — 새 글·수정·첨부 업로드·본문 이미지 업로드가 400이고 조회·첨부 삭제·게시글 삭제는 된다.
     * 공개 노출은 {@link #publicYn}이 따로 정한다(PLAN-board-definition.md §4 D5).
     */
    @Builder.Default
    @Column(name = "writable_yn", nullable = false)
    private Boolean writableYn = true;

    /** 낡은 화면 검출용 변경 버전 — 수정·삭제마다 +1, 요청 값과 다르면 서비스가 409(정적 페이지·배너·팝업과 같은 방식). */
    @Builder.Default
    @Column(nullable = false)
    private Integer version = 1;

    /**
     * 수정. 이름·공개·첨부는 null이 아닐 때만 반영하고(null=기존값 유지), 표시 설정은 서비스가 계산한 최종값으로 전부 대입한다 — "지움"(null 저장)과
     * "유지"를 엔티티에서 섞지 않기 위해서다. 공백 거부·전체 null 거부·범위 검증은 서비스 책임이다. 요청마다 {@link #version}을 한 번 올린다.
     *
     * @param now 앱 Clock 기준 현재 시각 — updateDate에 기록
     */
    public void update(String name, Boolean publicYn, Boolean attachmentYn, BoardDisplay display, LocalDateTime now) {
        if (name != null) {
            this.name = name;
        }
        if (publicYn != null) {
            this.publicYn = publicYn;
        }
        if (attachmentYn != null) {
            this.attachmentYn = attachmentYn;
        }
        this.description = display.description();
        this.listPageSize = display.listPageSize();
        this.authorLabel = display.authorLabel();
        this.showViewCount = display.showViewCount();
        this.newBadgeDays = display.newBadgeDays();
        this.writableYn = display.writableYn();
        this.version = Math.incrementExact(this.version);
        this.updateDate = now;
    }

    /** 현재 표시 설정 — 서비스가 PATCH의 "누락 = 유지"를 계산하는 출발점. */
    public BoardDisplay display() {
        return new BoardDisplay(description, listPageSize, authorLabel, showViewCount, newBadgeDays, writableYn);
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

    /**
     * 표시 대상 변경. 호출자가 사이트 존재·타입을 검증하고 시스템 게시판은 막는다. {@code null} = 공통. 같은 요청의 {@link #update} 뒤에만 불리므로
     * 버전은 올리지 않는다(한 요청이 +2 되지 않게).
     */
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
        this.version = Math.incrementExact(this.version);
        this.updateDate = now;
    }
}
