package com.cms.admin.dashboard.dto.response;

import java.util.List;

/**
 * 대시보드 콘텐츠 현황. 조회 불가(장애)는 이 객체 전체가 null이다 — 방문자 지표와 폴백을 분리한 것이다.
 * 제목·게시판 이름·파일명은 사용자 입력이라 화면이 {@code th:text}로만 출력한다.
 */
public record ContentStatsResponse(long totalViews, long totalDownloads, List<TopPost> topPosts, List<TopAttachment> topAttachments) {

    public record TopPost(Long id, Long boardId, String boardName, String title, long viewCount) {
    }

    public record TopAttachment(Long attachmentId, Long postId, Long boardId, String boardName, String postTitle,
                                String filename, long downloadCount) {
    }
}
