package com.cms.publicweb.board.service;

import com.cms.admin.board.domain.Board;
import com.cms.admin.board.domain.Post;
import com.cms.admin.board.repository.BoardRepository;
import com.cms.admin.board.repository.PostAttachmentRepository;
import com.cms.admin.board.repository.PostRepository;
import com.cms.common.storage.FileStorage;
import com.cms.common.storage.StorageFileNotFoundException;
import com.cms.common.storage.StoredFileStream;
import com.cms.publicweb.board.dto.PublicBoardListResult;
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

import java.util.Optional;

/**
 * 공개(비로그인) 게시판 조회 전용 Service. 관리 서비스와 별도 클래스로 두는 이유는 공지와 같다 — 공개 불변식이 선택적 필터가 아니라 이 클래스가
 * 반환하는 모든 것의 조건이 되도록 타입 단위로 격리한다(PLAN-board.md 쟁점 10).
 *
 * <p><b>불변식</b>: 게시판 {@code publicYn=true ∧ deleted=false} ∧ 게시글 {@code useYn=true ∧ deleted=false} ∧ 게시글이 그 게시판 소속.
 * 위반·없음은 모두 empty이고 컨트롤러가 같은 404로 흡수한다(존재 여부 비노출).
 *
 * <p>{@code page} 원시 파싱(문자열 → int)은 컨트롤러의 책임이고, 이 서비스는 그 위의 규칙(음수 보정·상한·고정 크기·고정 정렬)만 책임진다.
 */
@Service
@RequiredArgsConstructor
public class PublicBoardService {

    private static final int PAGE_SIZE = 10;

    /** 단일 요청이 만들 수 있는 OFFSET 상한 — 문법적으로 유효한 거대 page 값이 큰 스캔 비용을 유발하지 못하게 한다(공지와 같은 상한). */
    private static final int MAX_PAGE = 1000;

    /** 검색어 상한 — {@code String.length()}(UTF-16 코드 유닛) 기준으로 목록 화면 입력란의 {@code maxlength}와 같다. */
    static final int MAX_KEYWORD_LENGTH = 100;

    private final BoardRepository boardRepository;
    private final PostRepository postRepository;
    private final PostAttachmentRepository postAttachmentRepository;
    private final FileStorage fileStorage;

    /**
     * 공개 목록. 공개 게시판이 아니면 empty. {@code rawKeyword}가 비어 있으면(공백만 포함) 전체 목록, 있으면 제목 검색이며 앞뒤 공백을 제거한다.
     * {@link #MAX_KEYWORD_LENGTH}를 넘으면 DB를 조회하지 않고 빈 페이지를 돌려준다(이상 입력 흡수).
     */
    @Transactional(readOnly = true)
    public Optional<PublicBoardListResult> getPublishedPosts(Long boardId, int page, String rawKeyword) {
        return boardRepository.findByIdAndDeletedFalseAndPublicYnTrue(boardId).map(board -> {
            Pageable pageable = PageRequest.of(normalizePage(page), PAGE_SIZE,
                    Sort.by(Sort.Order.desc("createDate"), Sort.Order.desc("id")));
            String keyword = rawKeyword == null ? "" : rawKeyword.strip();

            if (keyword.isEmpty()) {
                return new PublicBoardListResult(board.getId(), board.getName(),
                        postRepository.searchPublished(board.getId(), null, pageable).map(PublicPostSummary::from), null);
            }
            if (keyword.length() > MAX_KEYWORD_LENGTH) {
                return new PublicBoardListResult(board.getId(), board.getName(), Page.empty(pageable), keyword);
            }
            return new PublicBoardListResult(board.getId(), board.getName(),
                    postRepository.searchPublished(board.getId(), keyword, pageable).map(PublicPostSummary::from), keyword);
        });
    }

    @Transactional(readOnly = true)
    public Optional<PublicPostDetail> findPublishedPost(Long boardId, Long postId) {
        return boardRepository.findByIdAndDeletedFalseAndPublicYnTrue(boardId)
                .flatMap(board -> postRepository.findByIdAndBoardIdAndDeletedFalseAndUseYnTrue(postId, board.getId())
                        .map(post -> toDetail(board, post)));
    }

    /**
     * 다운로드 시점 재검증 — 게시판·게시글의 공개 조건을 먼저 확인해야 트랜잭션 스냅샷이 확정된다. 이 재검증이 보장하는 것은 재검증 SELECT를 실행한
     * 시점의 공개 상태뿐이다(약한 보장, 락 미사용 — 공지와 같다). 열린 자원 없이 파일 참조(메타데이터)만 반환한다 — 파일 열기는 트랜잭션 밖의
     * {@link #openAttachment}가 맡는다(열린 스트림이 트랜잭션 프록시를 통과하면 commit 실패 시 닫을 수 없다).
     */
    @Transactional(readOnly = true)
    public Optional<PublicPostAttachmentRef> findPublishedAttachment(Long boardId, Long postId, Long attachmentId) {
        Optional<Board> board = boardRepository.findByIdAndDeletedFalseAndPublicYnTrue(boardId);
        if (board.isEmpty() || postRepository.findByIdAndBoardIdAndDeletedFalseAndUseYnTrue(postId, board.get().getId()).isEmpty()) {
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

    private int normalizePage(int page) {
        if (page < 0 || page > MAX_PAGE) {
            return 0;
        }
        return page;
    }
}
