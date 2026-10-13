package com.cms.admin.dashboard.service;

import com.cms.admin.board.repository.ContentStatsQueryRepository;
import com.cms.admin.dashboard.dto.response.ContentStatsResponse;
import com.cms.admin.member.domain.Role;
import com.cms.admin.permission.AdminPermissionEvaluator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 대시보드 콘텐츠 현황(조회수·다운로드 수) 집계 — PLAN-view-download-count.md 쟁점 6.
 *
 * <p>ADMIN은 미삭제 게시판 전부, MANAGER는 <b>READ가 유효한 게시판</b>의 항목만 본다(통합 검색과 같은 규칙) — 대시보드는 MANAGER도 보는 화면이라
 * 권한 없는 게시판의 제목이 상위 목록으로 새면 안 된다. 읽을 수 있는 게시판이 없으면 쿼리 없이 0·빈 목록이다.
 * {@link DashboardService}와 같이 트랜잭션을 두지 않고(쿼리별 개별 실행) 실패하면 null을 돌려준다 — 방문자 카드는 영향받지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentStatsService {

    private static final int TOP_LIMIT = 5;

    private final ContentStatsQueryRepository statsRepository;
    private final AdminPermissionEvaluator permissionEvaluator;

    public ContentStatsResponse getContentStats(Authentication authentication) {
        try {
            Set<Long> boardIds = null;
            if (!isAdmin(authentication)) {
                boardIds = permissionEvaluator.readableBoardIds(permissionEvaluator::snapshot, authentication);
                if (boardIds.isEmpty()) {
                    return new ContentStatsResponse(0L, 0L, List.of(), List.of());
                }
            }
            List<ContentStatsResponse.TopPost> posts = statsRepository.findTopViewedPosts(boardIds, TOP_LIMIT).stream()
                    .map(r -> new ContentStatsResponse.TopPost(r.id(), r.boardId(), r.boardName(), r.title(), r.viewCount()))
                    .toList();
            List<ContentStatsResponse.TopAttachment> attachments = statsRepository.findTopDownloadedAttachments(boardIds, TOP_LIMIT).stream()
                    .map(r -> new ContentStatsResponse.TopAttachment(r.attachmentId(), r.postId(), r.boardId(), r.boardName(),
                            r.postTitle(), r.filename(), r.downloadCount()))
                    .toList();
            return new ContentStatsResponse(statsRepository.sumViewCount(boardIds), statsRepository.sumDownloadCount(boardIds),
                    posts, attachments);
        } catch (Exception e) {
            log.error("대시보드 콘텐츠 현황 조회 실패 — null 폴백", e);
            return null;
        }
    }

    private static boolean isAdmin(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream().anyMatch(a -> Role.ROLE_ADMIN.name().equals(a.getAuthority()));
    }
}
