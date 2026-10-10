package com.cms.admin.board.repository;

import com.cms.admin.board.domain.Post;
import com.cms.admin.board.dto.request.PostSearchRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;

public interface PostRepositoryCustom {

    /** 관리 목록 — 한 게시판의 삭제되지 않은 게시글을 제목·노출 여부로 검색한다. */
    Page<Post> searchPosts(Long boardId, PostSearchRequest request, Pageable pageable);

    /**
     * 공개 목록 — 한 게시판의 노출·미삭제 게시글. {@code keyword}가 null이면 전체, 아니면 제목 부분 일치.
     * SELECT와 COUNT가 같은 조건 객체를 쓴다(COUNT에서 공개 조건이 빠지면 비공개 일치 글의 존재가 총건수로 드러난다).
     * 게시판의 공개 여부는 호출자({@code PublicBoardService})가 먼저 확인한다.
     */
    Page<Post> searchPublished(Long boardId, String keyword, Pageable pageable);

    /**
     * 통합 검색 — 삭제되지 않은 게시판의 삭제되지 않은 게시글을 제목으로 검색한다(노출 여부 무관 — 관리 화면용). {@code boardIds}가 null이면 모든
     * 게시판(ADMIN), 아니면 그 게시판들로 한정한다(MANAGER의 READ 게시판 집합). 빈 컬렉션은 호출자가 거르고 부르지 않는다. 최신순 + id 보조 정렬.
     */
    Page<PostSearchRow> searchForAdminSearch(Collection<Long> boardIds, String keyword, Pageable pageable);

    /**
     * 공개 메인 최신 글 — 공개·미삭제 게시판의 노출·미삭제 게시글을 최신순(+id 보조)으로 {@code limit}건. 공개 조건(게시판 {@code publicYn ∧ ¬deleted},
     * 게시글 {@code useYn ∧ ¬deleted})은 이 메서드 안에 고정돼 있다. {@code boardId}가 null이 아니면 그 게시판만, {@code excludeBoardId}가
     * null이 아니면 그 게시판을 뺀다. 호출은 공개 메인 서비스 한 곳에서만 한다(공개 불변식 격리).
     * 예약 게시(④)가 들어오면 이 메서드에도 기간 조건을 넣어야 한다.
     */
    List<PublishedPostRow> findLatestPublished(Long boardId, Long excludeBoardId, int limit);
}
