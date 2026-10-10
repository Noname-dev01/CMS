package com.cms.publicweb.home;

import com.cms.admin.board.domain.Board;
import com.cms.admin.board.repository.BoardRepository;
import com.cms.admin.board.repository.PostRepository;
import com.cms.admin.board.repository.PublishedPostRow;
import com.cms.publicweb.banner.PublicBannerService;
import com.cms.publicweb.home.dto.PublicHomePost;
import com.cms.publicweb.home.dto.PublicHomeView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 공개 메인(/) 조회. 공개 조건은 {@code PostRepository.findLatestPublished}와 {@link PublicBannerService}의 쿼리에 고정돼 있고, 이
 * 클래스는 섹션을 조립만 한다(PLAN-public-home-banner.md 쟁점 12). 공지 게시판이 없거나 비공개/삭제면 공지 섹션은 비어 있다(메인의 부속
 * 섹션이라 404/500으로 번역하지 않는다 — {@code /notices}와 달리).
 */
@Service
@RequiredArgsConstructor
public class PublicHomeService {

    /** 공지·새 글 섹션이 각각 보여 주는 최대 건수. */
    static final int SECTION_SIZE = 5;

    private final PublicBannerService publicBannerService;
    private final BoardRepository boardRepository;
    private final PostRepository postRepository;

    @Transactional(readOnly = true)
    public PublicHomeView getHome() {
        Optional<Long> noticeBoardId = boardRepository.findIdByBoardKey(Board.NOTICE_KEY);
        List<PublicHomePost> notices = noticeBoardId
                .map(id -> toPosts(postRepository.findLatestPublished(id, null, SECTION_SIZE)))
                .orElse(List.of());
        // 새 글에서 공지 게시판을 뺀다 — 같은 글이 두 섹션에 나오지 않게. 공지 게시판이 없으면 뺄 게시판도 없다.
        List<PublicHomePost> latest = toPosts(postRepository.findLatestPublished(null, noticeBoardId.orElse(null), SECTION_SIZE));
        return new PublicHomeView(publicBannerService.findDisplayableBanners(), notices, latest);
    }

    private static List<PublicHomePost> toPosts(List<PublishedPostRow> rows) {
        return rows.stream().map(PublicHomePost::from).toList();
    }
}
