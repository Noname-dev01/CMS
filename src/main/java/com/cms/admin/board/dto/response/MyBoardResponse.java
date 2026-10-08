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
    private final List<PermissionAction> actions;
}
