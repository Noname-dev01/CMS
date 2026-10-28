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
import java.util.HashSet;
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

    public static final String BODY_IMAGE_DISABLED_MESSAGE = "본문 이미지를 허용하지 않는 게시판입니다.";

    public static final String PUBLISH_PERIOD_DISABLED_MESSAGE = "예약 게시를 허용하지 않는 게시판입니다.";

    public static final String PIN_DISABLED_MESSAGE = "상단 고정을 허용하지 않는 게시판입니다.";

    /** 표시 순서 상한(0..이 값). */
    public static final int MAX_SORT_ORDER = 9999;

    /** 게시판당 상단 고정 글 수 상한의 최댓값. 0 = 고정 끔. */
    public static final int MAX_PIN_COUNT = 5;

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

    /** 게시글당 첨부 개수 상한 — 전역 상한({@code AttachmentFilePolicy.MAX_COUNT_PER_OWNER}) 이하만 쓴다. */
    @Builder.Default
    @Column(name = "attach_max_count", nullable = false)
    private Integer attachMaxCount = 5;

    /** 첨부 파일당 크기 상한(MB) — 전역 상한 이하만 쓴다. */
    @Builder.Default
    @Column(name = "attach_max_size_mb", nullable = false)
    private Integer attachMaxSizeMb = 10;

    /** 허용 확장자 정규형(소문자·사전순·쉼표 구분). null = 전역 화이트리스트 전체. */
    @Column(name = "attach_extensions", length = 200)
    private String attachExtensions;

    /** 본문 이미지 허용. false면 새 이미지만 거부하고 이미 참조 중인 이미지는 유지한다. */
    @Builder.Default
    @Column(name = "body_image_yn", nullable = false)
    private Boolean bodyImageYn = true;

    /** 예약 게시 허용. false면 새 기간 설정만 거부하고 기존 기간은 계속 유효하다(비우기는 허용). */
    @Builder.Default
    @Column(name = "publish_period_yn", nullable = false)
    private Boolean publishPeriodYn = true;

    /** 관리 화면의 표시 순서 — 작은 값이 먼저, 같으면 id 순. 연속성·유일성은 요구하지 않는다. */
    @Builder.Default
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    /** 공개 목록 1페이지 상단에 고정할 수 있는 글 수 상한. 0 = 고정 끔(낮추거나 꺼도 이미 고정된 글은 그대로 두고 공개 영역만 상한만큼 보인다). */
    @Builder.Default
    @Column(name = "pin_max_count", nullable = false)
    private Integer pinMaxCount = 0;

    /**
     * 관리자가 정한 공개 주소 슬러그. null = 기본 슬러그 {@code board-{id}}(저장하지 않는다 — {@link BoardSlug}). 기본값에서 한 번만 바꿀 수 있고
     * 소프트 삭제도 비우지 않는다(삭제된 게시판이 UNIQUE를 쥐어 한 번 쓴 슬러그가 영구 예약된다 — PLAN-board-definition.md PR 4a D1).
     */
    @Column(length = BoardSlug.MAX_LENGTH)
    private String slug;

    /** 낡은 화면 검출용 변경 버전 — 수정·삭제마다 +1, 요청 값과 다르면 서비스가 409(정적 페이지·배너·팝업과 같은 방식). */
    @Builder.Default
    @Column(nullable = false)
    private Integer version = 1;

    /**
     * 수정. 이름·공개·첨부·표시 순서는 null이 아닐 때만 반영하고(null=기존값 유지), 표시 설정은 서비스가 계산한 최종값으로 전부 대입한다 — "지움"(null 저장)과
     * "유지"를 엔티티에서 섞지 않기 위해서다. 공백 거부·전체 null 거부·범위 검증은 서비스 책임이다. 요청마다 {@link #version}을 한 번 올린다.
     *
     * @param now 앱 Clock 기준 현재 시각 — updateDate에 기록
     */
    public void update(String name, Boolean publicYn, Boolean attachmentYn, Integer sortOrder, BoardDisplay display,
                       BoardWritePolicy writePolicy, LocalDateTime now) {
        if (name != null) {
            this.name = name;
        }
        if (sortOrder != null) {
            this.sortOrder = sortOrder;
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
        this.pinMaxCount = display.pinMaxCount();
        this.attachMaxCount = writePolicy.attachMaxCount();
        this.attachMaxSizeMb = writePolicy.attachMaxSizeMb();
        this.attachExtensions = writePolicy.attachExtensions();
        this.bodyImageYn = writePolicy.bodyImageYn();
        this.publishPeriodYn = writePolicy.publishPeriodYn();
        this.version = Math.incrementExact(this.version);
        this.updateDate = now;
    }

    /** 현재 표시 설정 — 서비스가 PATCH의 "누락 = 유지"를 계산하는 출발점. */
    public BoardDisplay display() {
        return new BoardDisplay(description, listPageSize, authorLabel, showViewCount, newBadgeDays, writableYn, pinMaxCount);
    }

    /** 현재 작성 정책 — 서비스가 PATCH의 "누락 = 유지"를 계산하는 출발점. */
    public BoardWritePolicy writePolicy() {
        return new BoardWritePolicy(attachMaxCount, attachMaxSizeMb, attachExtensions, bodyImageYn, publishPeriodYn);
    }

    /**
     * 게시판이 허용한 확장자 집합. null = 게시판 제한 없음(전역 전체). 저장값은 서비스가 정규화해 넣지만 DB를 직접 고쳐도 천장은
     * {@code AttachmentFilePolicy}의 전역 검사가 먼저 지키므로 여기서는 자르기만 한다.
     */
    public Set<String> attachExtensionSet() {
        if (attachExtensions == null) {
            return null;
        }
        Set<String> result = new HashSet<>();
        for (String token : attachExtensions.split(",")) {
            String extension = token.strip();
            if (!extension.isEmpty()) {
                result.add(extension);
            }
        }
        return result;
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

    /** 현재 공개 슬러그 — 공개 링크(목록·상세·메인)와 관리 응답이 쓴다. */
    public String publicSlug() {
        return BoardSlug.effective(id, slug);
    }

    /** 기본 슬러그에서 사용자 슬러그로 한 번 바꿀 수 있는가. */
    public boolean isSlugChangeable() {
        return slug == null;
    }

    /**
     * 사용자 슬러그 지정(1회). 호출자가 형식·중복·"이미 바꿨는가"를 검증한다. 같은 요청의 {@link #update} 뒤에만 불리므로 버전은 올리지 않는다
     * ({@link #changeSite}와 같은 이유).
     */
    public void assignSlug(String slug, LocalDateTime now) {
        if (this.slug != null) {
            throw new IllegalStateException("슬러그는 한 번만 바꿀 수 있습니다.");
        }
        this.slug = slug;
        this.updateDate = now;
    }

    /**
     * 소프트 삭제. deleted=true 처리 후 수정 시각을 갱신한다. 소속 사이트와(사이트 전용 공지 게시판의) 키를 비워, 삭제된 행이 사이트 삭제의
     * FK와 board_key 유일성을 붙잡지 않게 한다. 슬러그는 비우지 않는다(영구 예약 — {@link #slug}).
     */
    public void softDelete(LocalDateTime now) {
        this.deleted = true;
        this.siteId = null;
        this.boardKey = null;
        this.version = Math.incrementExact(this.version);
        this.updateDate = now;
    }
}
