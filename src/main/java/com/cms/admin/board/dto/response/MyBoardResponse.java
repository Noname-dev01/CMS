package com.cms.admin.board.dto.response;

import com.cms.admin.permission.PermissionAction;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/** 내가 조회할 수 있는 게시판 한 건 + 그 게시판에서 허용된 동작(화면 버튼 표시용 — 서버 판정을 대신하지 않는다). */
@Getter
@AllArgsConstructor
@Schema(description = "내 게시판 접근 정보")
public class MyBoardResponse {

    private final Long boardId;
    private final String name;
    private final Boolean publicYn;
    private final Boolean attachmentYn;
    @Schema(description = "쓰기 허용 — false(쓰기 중지)면 화면이 새 글·수정·첨부 업로드 버튼을 숨긴다(서버 판정이 최종)")
    private final Boolean writableYn;
    @Schema(description = "게시글당 첨부 개수 상한 — 화면 안내용(서버 판정이 최종)")
    private final Integer attachMaxCount;
    @Schema(description = "첨부 파일당 크기 상한(MB)")
    private final Integer attachMaxSizeMb;
    @Schema(description = "실효 허용 확장자 — 게시판이 제한하지 않으면 전역 허용 목록 전체를 펼친 값(항상 있음)")
    private final List<String> attachExtensions;
    @Schema(description = "본문 이미지 허용 — false면 화면이 이미지 버튼을 숨긴다(서버 판정이 최종)")
    private final Boolean bodyImageYn;
    @Schema(description = "예약 게시 허용 — false면 화면이 노출 기간 입력을 숨긴다(서버 판정이 최종)")
    private final Boolean publishPeriodYn;
    private final List<PermissionAction> actions;
}
