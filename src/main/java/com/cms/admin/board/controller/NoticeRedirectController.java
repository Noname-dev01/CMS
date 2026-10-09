package com.cms.admin.board.controller;

import com.cms.admin.board.service.BoardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.regex.Pattern;

/**
 * 옛 공지 관리 화면 주소 {@code /admin/notice/manage}를 공지 게시판의 게시글 관리 화면으로 보낸다(PLAN-notice-to-board.md 쟁점 9) —
 * 북마크·옛 통합 검색 링크 호환. 페이지가 아니라 {@code ResponseEntity} 리다이렉트라 {@code @AdminPage}(사이드바 모델 주입)를 붙이지 않는다.
 *
 * <p>접근 통제는 URL 게이트 한 곳이다 — {@code AdminFeature.BOARD}의 게이트 패턴이 이 정확 경로를 기능 단위 READ("어느 게시판이든 조회 권한")로
 * 막는다. 이 핸들러는 GET만 두며(게이트가 메서드를 구분하지 않으므로) 읽기 전용이라 {@code AdminEndpointAuthorizationConventionTest}의
 * 읽기 전용 GET 면제 대상이다. 게시글 화면 접근 권한은 이동한 뒤 화면·API가 게시판 단위로 다시 판정한다.
 */
@RestController
@RequiredArgsConstructor
public class NoticeRedirectController {

    /** 통합 검색·화면이 쓰는 id 형식과 같다({@code ^[1-9]\d{0,15}$}) — 그 밖의 값은 버리고 목록으로 보낸다. */
    private static final Pattern SAFE_ID = Pattern.compile("^[1-9][0-9]{0,15}$");

    private final BoardService boardService;

    @GetMapping("/admin/notice/manage")
    public ResponseEntity<Void> redirectToNoticeBoard(@RequestParam(required = false) String id) {
        String location = "/admin/board/posts?boardId=" + boardService.getNoticeBoardId();
        if (id != null && SAFE_ID.matcher(id).matches()) {
            location += "&id=" + id;
        }
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location).build();
    }
}
