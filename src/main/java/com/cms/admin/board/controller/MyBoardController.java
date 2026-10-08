package com.cms.admin.board.controller;

import com.cms.admin.board.dto.response.MyBoardResponse;
import com.cms.admin.board.service.MyBoardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 내가 접근할 수 있는 게시판 목록. 경로가 {@code /admin/api/members/me/**}라 기존 상시 허용(MY_INFO) 게이트 안이다 — {@code SecurityConfig}
 * 변경 없음. 결과는 판정기로 거른다(권한 없는 게시판은 목록에 없다).
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Admin My Board", description = "내 게시판 접근 정보 API")
public class MyBoardController {

    private final MyBoardService myBoardService;

    @Operation(summary = "내가 조회할 수 있는 게시판 목록", description = "ADMIN은 삭제되지 않은 게시판 전부, MANAGER는 조회 권한이 있는 게시판만. 게시판별 허용 동작 포함")
    @ApiResponse(responseCode = "200", description = "조회 성공(없으면 빈 배열)")
    @GetMapping("/admin/api/members/me/boards")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<List<MyBoardResponse>> getMyBoards() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return ResponseEntity.ok(myBoardService.getMyBoards(authentication));
    }
}
