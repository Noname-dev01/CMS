package com.cms.admin.board.service;

import com.cms.admin.board.domain.Post;
import com.cms.admin.board.dto.request.PostCreateRequest;
import com.cms.admin.board.dto.request.PostSearchRequest;
import com.cms.admin.board.dto.request.PostUpdateRequest;
import com.cms.admin.board.dto.response.PostPageResponse;
import com.cms.admin.board.dto.response.PostResponse;
import com.cms.admin.board.dto.response.PostSummaryResponse;
import com.cms.admin.board.repository.BoardRepository;
import com.cms.admin.board.repository.PostAttachmentRepository;
import com.cms.admin.board.repository.PostRepository;
import com.cms.admin.contentimage.service.ContentImageService;
import com.cms.admin.log.annotation.AdminActionLogged;
import com.cms.admin.log.constant.AdminActionTypes;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ResourceNotFoundException;
import com.cms.common.html.ContentBodyPolicy;
import com.cms.common.html.SanitizedHtml;
import com.cms.config.auth.AdminSecurityService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 게시글 관리(PLAN-board.md 쟁점 7). 공지({@code NoticeService})와 같은 구조 — 본문은 {@link ContentBodyPolicy}로 정리하고, 게시판별 권한은
 * 핸들러의 {@code @RequireBoardPermission}이 판정한다(서비스는 권한이 아니라 대상의 존재·소속만 본다).
 *
 * <p>잠금: 생성은 게시판 행 {@code FOR SHARE}(게시판 삭제의 {@code FOR UPDATE}와 직렬화 — 삭제 검사 직후 생기는 고아 게시글 방지),
 * 수정·삭제는 게시글 행 {@code FOR UPDATE}. 모든 조회는 경로의 게시판 소속을 함께 확인해(IDOR) 다른 게시판의 글은 같은 404다.
 */
@Service
@RequiredArgsConstructor
public class PostService {

    /** 목록 페이지 크기 상한 — 공지 목록과 같다. */
    private static final int MAX_PAGE_SIZE = 100;

    static final String BOARD_NOT_FOUND = "게시판을 찾을 수 없습니다.";
    static final String POST_NOT_FOUND = "게시글을 찾을 수 없습니다.";

    private final PostRepository postRepository;
    private final PostAttachmentRepository postAttachmentRepository;
    private final BoardRepository boardRepository;
    private final ContentImageService contentImageService;
    private final AdminSecurityService adminSecurityService;
    private final Clock clock;

    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.POST_CREATE, targetType = "POST", targetIdExpression = "id")
    public PostResponse createPost(Long boardId, PostCreateRequest request) {
        String authorId = requireCurrentAdminUserId();
        String title = requireNonBlank(request.getTitle(), "제목은 공백일 수 없습니다.");
        SanitizedHtml content = ContentBodyPolicy.sanitize(request.getContent(), request.getContentFormat());
        boolean useYn = request.getUseYn() == null || request.getUseYn();

        // 게시판 행 공유 잠금 — 삭제된 게시판이면 404, 이 트랜잭션이 끝날 때까지 게시판 삭제(FOR UPDATE)가 기다린다
        boardRepository.findByIdAndDeletedFalseForShare(boardId)
                .orElseThrow(() -> new ResourceNotFoundException(BOARD_NOT_FOUND));

        LocalDateTime now = LocalDateTime.now(clock);
        Post saved = postRepository.save(
                Post.builder()
                        .boardId(boardId)
                        .title(title)
                        .content(content.html())
                        .useYn(useYn)
                        .deleted(false)
                        .authorId(authorId)
                        .createDate(now)
                        .updateDate(now)
                        .build()
        );
        contentImageService.replaceRefs(ContentImageService.OWNER_POST, saved.getId(),
                ContentImageService.SCOPE_BOARD, boardId, content.imageIds());

        return PostResponse.from(saved);
    }

    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.POST_UPDATE, targetType = "POST", targetIdExpression = "id")
    public PostResponse updatePost(Long boardId, Long postId, PostUpdateRequest request) {
        if (request.getTitle() == null && request.getContent() == null && request.getUseYn() == null) {
            throw new InvalidRequestException("변경할 필드가 없습니다.");
        }

        Post target = lockPost(boardId, postId);

        String title = request.getTitle() != null
                ? requireNonBlank(request.getTitle(), "제목은 공백일 수 없습니다.")
                : null;
        SanitizedHtml content = request.getContent() != null
                ? ContentBodyPolicy.sanitize(request.getContent(), request.getContentFormat())
                : null;

        target.update(title, content != null ? content.html() : null, request.getUseYn(), LocalDateTime.now(clock));
        if (content != null) {
            // 게시글 행 잠금 안에서 본문과 참조를 함께 바꾼다 — 같은 게시글의 동시 저장이 직렬화된다
            contentImageService.replaceRefs(ContentImageService.OWNER_POST, target.getId(),
                    ContentImageService.SCOPE_BOARD, boardId, content.imageIds());
        }

        return PostResponse.from(target);
    }

    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.POST_DELETE, targetType = "POST", targetIdExpression = "id")
    public PostResponse deletePost(Long boardId, Long postId) {
        Post target = lockPost(boardId, postId);

        // 첨부가 남아 있으면 삭제를 차단한다 — 게시글 소프트 삭제는 API로 도달 불가능해지므로 그 상태에서 첨부만 남으면 영구 오펀이 된다
        // (공지와 같은 규칙). 관리자가 첨부를 먼저 모두 삭제해야 한다.
        if (postAttachmentRepository.countByPostId(postId) > 0) {
            throw new ConflictException("첨부파일이 남아있어 삭제할 수 없습니다. 첨부를 먼저 삭제해주세요.");
        }

        target.softDelete(LocalDateTime.now(clock));

        return PostResponse.from(target);
    }

    @Transactional(readOnly = true)
    public PostResponse getPost(Long boardId, Long postId) {
        requireBoard(boardId);
        Post post = postRepository.findByIdAndBoardIdAndDeletedFalse(postId, boardId)
                .orElseThrow(() -> new ResourceNotFoundException(POST_NOT_FOUND));
        return PostResponse.from(post);
    }

    @Transactional(readOnly = true)
    public PostPageResponse getPosts(Long boardId, PostSearchRequest request, Pageable pageable) {
        requireBoard(boardId);
        Pageable effectivePageable = pageable.getPageSize() > MAX_PAGE_SIZE
                ? PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort())
                : pageable;

        Page<Post> page = postRepository.searchPosts(boardId, request, effectivePageable);

        return PostPageResponse.builder()
                .content(page.getContent().stream().map(PostSummaryResponse::from).toList())
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .last(page.isLast())
                .build();
    }

    /** 게시글 행을 잠그고(FOR UPDATE) 게시판이 삭제되지 않았는지 확인한다. 소속이 다른 글·삭제된 글·삭제된 게시판은 같은 404. */
    private Post lockPost(Long boardId, Long postId) {
        Post target = postRepository.findByIdAndBoardIdAndDeletedFalseForUpdate(postId, boardId)
                .orElseThrow(() -> new ResourceNotFoundException(POST_NOT_FOUND));
        requireBoard(boardId);
        return target;
    }

    private void requireBoard(Long boardId) {
        boardRepository.findByIdAndDeletedFalse(boardId)
                .orElseThrow(() -> new ResourceNotFoundException(BOARD_NOT_FOUND));
    }

    /**
     * 현재 로그인 관리자의 userId를 반환한다. 인가 통과 후라면 null이 되지 않는 방어 코드지만, null이면 AccessDeniedException(→ 403)으로 처리해
     * 인증 연계 버그가 조용히 작성자 없는 데이터로 이어지는 것을 막는다.
     */
    private String requireCurrentAdminUserId() {
        String userId = adminSecurityService.getCurrentAdminUserId();
        if (userId == null) {
            throw new AccessDeniedException("인증 정보를 확인할 수 없습니다.");
        }
        return userId;
    }

    private static String requireNonBlank(String value, String message) {
        String trimmed = value.trim();
        if (trimmed.isBlank()) {
            throw new InvalidRequestException(message);
        }
        return trimmed;
    }
}
