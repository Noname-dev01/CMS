package com.cms.admin.board.dto.response;

import com.cms.admin.board.domain.Board;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

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
                .version(board.getVersion())
                .createDate(board.getCreateDate())
                .updateDate(board.getUpdateDate())
                .build();
    }
}
