package com.cms.publicweb.board.service;

import com.cms.admin.board.domain.Board;
import com.cms.admin.board.domain.BoardSlug;
import com.cms.admin.board.domain.Post;
import com.cms.admin.board.repository.BoardRepository;
import com.cms.admin.board.repository.PostAttachmentRepository;
import com.cms.admin.board.repository.PostRepository;
import com.cms.common.storage.FileStorage;
import com.cms.publicweb.support.PublicSite;
import com.cms.common.storage.StorageFileNotFoundException;
import com.cms.common.storage.StoredFileStream;
import com.cms.publicweb.board.dto.PublicBoardListResult;
import com.cms.publicweb.board.dto.PublicBoardRoute;
import com.cms.publicweb.board.dto.PublicPostAttachmentDownload;
import com.cms.publicweb.board.dto.PublicPostAttachmentRef;
import com.cms.publicweb.board.dto.PublicPostDetail;
import com.cms.publicweb.board.dto.PublicPostSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * 공개(비로그인) 게시판 조회 전용 Service. 관리 서비스와 별도 클래스로 두는 이유는 공지와 같다 — 공개 불변식이 선택적 필터가 아니라 이 클래스가
 * 반환하는 모든 것의 조건이 되도록 타입 단위로 격리한다(PLAN-board.md 쟁점 10).
 *
 * <p><b>불변식</b>: 게시판 {@code publicYn=true ∧ deleted=false} ∧ 게시글 {@code useYn=true ∧ deleted=false} ∧ 게시글의 노출 기간
 * {@code [publishStart, publishEnd)} 안(한쪽 null이면 그쪽 열림) ∧ 게시글이 그 게시판 소속. 판정 시각은 주입된 KST {@code Clock}으로 요청(메서드 호출)당 1회 산출한다.
 * 위반·없음은 모두 empty이고 컨트롤러가 같은 404로 흡수한다(존재 여부 비노출).
 *
 * <p>{@code page} 원시 파싱(문자열 → int)은 컨트롤러의 책임이고, 이 서비스는 그 위의 규칙(음수 보정·상한·고정 크기·고정 정렬)만 책임진다.
 */
@Service
@RequiredArgsConstructor
public class PublicBoardService {

    /**
     * 단일 요청이 만들 수 있는 OFFSET 상한(포함) — 문법적으로 유효한 거대 page 값이 큰 스캔 비용을 유발하지 못하게 한다. 게시판마다 목록 크기가 달라도
     * 이 값이 같아야 비용 계약이 유지되므로 허용 페이지를 {@code MAX_OFFSET / 목록 크기}로 계산한다(크기 10이면 예전 상한 1000과 같다).
     */
    static final int MAX_OFFSET = 10_000;

    /** 검색어 상한 — {@code String.length()}(UTF-16 코드 유닛) 기준으로 목록 화면 입력란의 {@code maxlength}와 같다. */
    static final int MAX_KEYWORD_LENGTH = 100;

    private final BoardRepository boardRepository;
    private final PostRepository postRepository;
    private final PostAttachmentRepository postAttachmentRepository;
    private final FileStorage fileStorage;
    private final Clock clock;

    /**
     * 공개 경로의 게시판 세그먼트 해석(PR 4a D4) — 기본 슬러그 {@code board-{id}} → 사용자 슬러그 형식 → 그 밖은 404 순서. 형식 검사를 DB 조회보다
     * 먼저 한다(컬럼 정렬이 대소문자를 구분하지 않는다). 공개 조건은 id·슬러그 두 조회가 같다 — 비공개·삭제·다른 사이트 게시판의 기본 슬러그는
     * 301 없이 404라 새 슬러그가 {@code Location}으로 새지 않는다. 숫자 id 세그먼트({@code 12})는 어느 형식도 아니라 404다(완전 교체).
     */
    @Transactional(readOnly = true)
    public PublicBoardRoute resolveBoard(Long siteId, String segment) {
        Long requiredSiteId = PublicSite.requireId(siteId);
        Long defaultId = BoardSlug.parseDefault(segment);
        if (defaultId != null) {
            return boardRepository.findPublicById(defaultId, requiredSiteId)
                    .<PublicBoardRoute>map(board -> board.isSlugChangeable()
                            ? new PublicBoardRoute.Found(board.getId())
                            : new PublicBoardRoute.Moved(board.getSlug()))
                    .orElseGet(PublicBoardRoute.NotFound::new);
        }
        if (BoardSlug.isAssignable(segment)) {
            return boardRepository.findPublicBySlug(segment, requiredSiteId)
                    .<PublicBoardRoute>map(board -> new PublicBoardRoute.Found(board.getId()))
                    .orElseGet(PublicBoardRoute.NotFound::new);
        }
        return new PublicBoardRoute.NotFound();
    }

    /**
     * 현재 사이트의 공지 게시판(전용 → 없으면 공통)의 현재 슬러그 — 공개 {@code /{siteKey}/notices}가 302로 보낼 곳(PLAN-board-definition.md PR 4b D1).
     * 공개 조건은 {@link #resolveBoard}와 같다({@code findPublicById}) — 공지 게시판이 없거나 비공개·삭제면 empty(호출자가 404).
     */
    @Transactional(readOnly = true)
    public Optional<String> findNoticeBoardSlug(Long siteId) {
        Long requiredSiteId = PublicSite.requireId(siteId);
        return boardRepository.findNoticeBoardId(requiredSiteId)
                .flatMap(id -> boardRepository.findPublicById(id, requiredSiteId))
                .map(Board::publicSlug);
    }

    /**
     * 공개 목록. 공개 게시판이 아니면 empty. {@code rawKeyword}가 비어 있으면(공백만 포함) 전체 목록, 있으면 제목 검색이며 앞뒤 공백을 제거한다.
     * {@link #MAX_KEYWORD_LENGTH}를 넘으면 DB를 조회하지 않고 빈 페이지를 돌려준다(이상 입력 흡수).
     */
    @Transactional(readOnly = true)
    public Optional<PublicBoardListResult> getPublishedPosts(Long siteId, Long boardId, int page, String rawKeyword) {
        LocalDateTime now = LocalDateTime.now(clock);
        return boardRepository.findPublicById(boardId, PublicSite.requireId(siteId)).map(board -> {
            int pageSize = board.getListPageSize();
            int normalizedPage = normalizePage(page, pageSize);
            Pageable pageable = PageRequest.of(normalizedPage, pageSize,
                    Sort.by(Sort.Order.desc("createDate"), Sort.Order.desc("id")));
            String keyword = rawKeyword == null ? "" : rawKeyword.strip();
            boolean showViewCount = Boolean.TRUE.equals(board.getShowViewCount());
            int badgeDays = board.getNewBadgeDays();
            // 조회수·배지는 게시판 설정에 따라 서비스가 DTO를 만들 때 거른다(템플릿이 아니라 여기서)
            Function<Post, PublicPostSummary> toSummary = post -> PublicPostSummary.from(post, showViewCount,
                    NewBadge.isNew(post.getCreateDate(), post.getPublishStart(), badgeDays, now));

            if (keyword.isEmpty()) {
                // 고정 영역은 검색하지 않은 1페이지에서만, 게시판 고정 상한이 0보다 클 때만 조회한다(그 밖에는 쿼리 없이 빈 목록)
                List<PublicPostSummary> pinned = normalizedPage == 0 && board.getPinMaxCount() > 0
                        ? postRepository.findPinnedPublished(board.getId(), now, Math.min(board.getPinMaxCount(), Board.MAX_PIN_COUNT))
                                .stream().map(toSummary).toList()
                        : List.of();
                return result(board, pinned, postRepository.searchPublished(board.getId(), null, now, pageable).map(toSummary), null);
            }
            if (keyword.length() > MAX_KEYWORD_LENGTH) {
                return result(board, List.of(), Page.empty(pageable), keyword);
            }
            return result(board, List.of(), postRepository.searchPublished(board.getId(), keyword, now, pageable).map(toSummary), keyword);
        });
    }

    @Transactional(readOnly = true)
    public Optional<PublicPostDetail> findPublishedPost(Long siteId, Long boardId, Long postId) {
        LocalDateTime now = LocalDateTime.now(clock);
        return boardRepository.findPublicById(boardId, PublicSite.requireId(siteId))
                .flatMap(board -> postRepository.findPublishedById(postId, board.getId(), now)
                        .map(post -> toDetail(board, post)));
    }

    /**
     * 다운로드 시점 재검증 — 게시판·게시글의 공개 조건을 먼저 확인해야 트랜잭션 스냅샷이 확정된다. 이 재검증이 보장하는 것은 재검증 SELECT를 실행한
     * 시점의 공개 상태뿐이다(약한 보장, 락 미사용 — 공지와 같다). 열린 자원 없이 파일 참조(메타데이터)만 반환한다 — 파일 열기는 트랜잭션 밖의
     * {@link #openAttachment}가 맡는다(열린 스트림이 트랜잭션 프록시를 통과하면 commit 실패 시 닫을 수 없다).
     */
    @Transactional(readOnly = true)
    public Optional<PublicPostAttachmentRef> findPublishedAttachment(Long siteId, Long boardId, Long postId, Long attachmentId) {
        LocalDateTime now = LocalDateTime.now(clock);
        Optional<Board> board = boardRepository.findPublicById(boardId, PublicSite.requireId(siteId));
        if (board.isEmpty() || postRepository.findPublishedById(postId, board.get().getId(), now).isEmpty()) {
            return Optional.empty();
        }
        return postAttachmentRepository.findByIdAndPostId(attachmentId, postId)
                .map(attachment -> new PublicPostAttachmentRef(attachment.getOriginalFilename(), attachment.getStorageKey()));
    }

    /**
     * 파일을 스트림으로 연다. <b>트랜잭션 없음</b>(의도) — {@link #findPublishedAttachment} 이후에 호출한다. 반환된 {@link PublicPostAttachmentDownload}는
     * 받은 쪽이 반드시 닫아야 한다. 파일이 없으면({@link StorageFileNotFoundException}) empty로 흡수해 404로 매핑되게 하고, 그 외 실패는 전파한다(500).
     */
    public Optional<PublicPostAttachmentDownload> openAttachment(PublicPostAttachmentRef ref) {
        try {
            StoredFileStream opened = fileStorage.open(ref.storageKey());
            return Optional.of(new PublicPostAttachmentDownload(ref.originalFilename(), opened.size(), opened.inputStream()));
        } catch (StorageFileNotFoundException e) {
            return Optional.empty();
        }
    }

    private PublicPostDetail toDetail(Board board, Post post) {
        return PublicPostDetail.from(board, post, postAttachmentRepository.findByPostIdOrderByIdAsc(post.getId()));
    }

    private static PublicBoardListResult result(Board board, List<PublicPostSummary> pinned, Page<PublicPostSummary> page, String keyword) {
        return new PublicBoardListResult(board.getId(), board.publicSlug(), board.getName(), board.getDescription(), board.getAuthorLabel(), pinned, page, keyword);
    }

    private static int normalizePage(int page, int pageSize) {
        if (page < 0 || page > MAX_OFFSET / pageSize) {
            return 0;
        }
        return page;
    }
}
