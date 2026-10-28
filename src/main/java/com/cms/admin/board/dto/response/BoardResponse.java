package com.cms.admin.board.dto.response;

import com.cms.admin.board.domain.Board;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.TreeSet;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "게시판 응답")
public class BoardResponse {

    private Long id;
    private String name;
    private Boolean publicYn;
    private Boolean attachmentYn;
    @Schema(description = "소속 사이트 ID (null = 모든 공개 사이트 공통)")
    private Long siteId;
    @Schema(description = "공지 게시판(시스템 게시판) 여부 — 공통 공지 또는 사이트 전용 공지")
    private Boolean noticeBoard;
    @Schema(description = "공개 목록 머리의 설명(평문, null = 없음)")
    private String description;
    @Schema(description = "공개 목록 한 페이지 글 수")
    private Integer listPageSize;
    @Schema(description = "공개 화면의 작성자 표시명(null = 표시 안 함)")
    private String authorLabel;
    private Boolean showViewCount;
    @Schema(description = "새 글 배지 기간(일, 0 = 끔)")
    private Integer newBadgeDays;
    @Schema(description = "쓰기 허용(false = 쓰기 중지·보관)")
    private Boolean writableYn;
    @Schema(description = "관리 화면 표시 순서(작을수록 먼저)")
    private Integer sortOrder;
    @Schema(description = "상단 고정 최대 글 수(0 = 고정 끔)")
    private Integer pinMaxCount;
    @Schema(description = "게시글당 첨부 개수 상한")
    private Integer attachMaxCount;
    @Schema(description = "첨부 파일당 크기 상한(MB)")
    private Integer attachMaxSizeMb;
    @Schema(description = "허용 확장자 — 키가 없으면 전역 허용 목록 전체")
    private List<String> attachExtensions;
    @Schema(description = "본문 이미지 허용 여부")
    private Boolean bodyImageYn;
    @Schema(description = "예약 게시 허용 여부")
    private Boolean publishPeriodYn;
    @Schema(description = "현재 공개 주소 슬러그 — 공개 주소는 /{사이트 키}/boards/{slug}")
    private String slug;
    @Schema(description = "슬러그를 바꿀 수 있는가(기본 슬러그 board-{id}일 때만 한 번)")
    private Boolean slugChangeable;
    @Schema(description = "게시판 변경 버전 — 수정·삭제 요청에 그대로 보낸다")
    private Integer version;
    private LocalDateTime createDate;
    private LocalDateTime updateDate;

    public static BoardResponse from(Board board) {
        return BoardResponse.builder()
                .id(board.getId())
                .name(board.getName())
                .publicYn(board.getPublicYn())
                .attachmentYn(board.getAttachmentYn())
                .siteId(board.getSiteId())
                .noticeBoard(board.isSystem())
                .description(board.getDescription())
                .listPageSize(board.getListPageSize())
                .authorLabel(board.getAuthorLabel())
                .showViewCount(board.getShowViewCount())
                .newBadgeDays(board.getNewBadgeDays())
                .writableYn(board.getWritableYn())
                .sortOrder(board.getSortOrder())
                .pinMaxCount(board.getPinMaxCount())
                .attachMaxCount(board.getAttachMaxCount())
                .attachMaxSizeMb(board.getAttachMaxSizeMb())
                .attachExtensions(board.getAttachExtensions() == null ? null : List.copyOf(new TreeSet<>(board.attachExtensionSet())))
                .bodyImageYn(board.getBodyImageYn())
                .publishPeriodYn(board.getPublishPeriodYn())
                .slug(board.publicSlug())
                .slugChangeable(board.isSlugChangeable())
                .version(board.getVersion())
                .createDate(board.getCreateDate())
                .updateDate(board.getUpdateDate())
                .build();
    }
}
