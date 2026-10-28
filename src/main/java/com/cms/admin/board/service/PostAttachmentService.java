package com.cms.admin.board.service;

import com.cms.admin.board.domain.Board;
import com.cms.admin.board.domain.Post;
import com.cms.admin.board.domain.PostAttachment;
import com.cms.admin.board.dto.response.PostAttachmentDownload;
import com.cms.admin.board.dto.response.PostAttachmentResponse;
import com.cms.admin.board.repository.BoardRepository;
import com.cms.admin.board.repository.PostAttachmentRepository;
import com.cms.admin.board.repository.PostRepository;
import com.cms.admin.log.annotation.AdminActionLogged;
import com.cms.admin.log.constant.AdminActionTypes;
import com.cms.common.attachment.AttachmentFilePolicy;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ResourceNotFoundException;
import com.cms.common.storage.FileStorage;
import com.cms.common.storage.FileStorageTransactionSupport;
import com.cms.common.storage.StorageFileNotFoundException;
import com.cms.config.auth.AdminSecurityService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 게시글 첨부파일 업로드/목록/다운로드/삭제(PLAN-board.md 쟁점 7). 공지 첨부({@code NoticeAttachmentService})와 같은 구조이고 검증 규칙은
 * {@link AttachmentFilePolicy}를 함께 쓴다.
 *
 * <p>업로드·삭제는 게시글 비관적 락({@link PostRepository#findByIdAndBoardIdAndDeletedFalseForUpdate})을 먼저 획득한다 — 같은 게시글의
 * 첨부 개수 상한(5개) 검사와 동시 삭제 경합을 이 락 하나로 직렬화한다. 목록·다운로드는 읽기 전용이라 락이 불필요하다.
 * 게시판의 {@code attachmentYn=false}는 <b>새 업로드만</b> 막는다 — 기존 첨부의 조회·다운로드·삭제는 그대로 된다(끄는 순간 기존 공개 자료를
 * 숨기지 않는다). 파일은 스토리지 루트에 저장된다(네임스페이스 없음 — 수동 회수 절차의 보존 목록에 이 테이블의 키가 들어간다).
 * 업로드·삭제가 성공하면 같은 잠금 안에서 게시글의 수정 시각·최종 수정자를 갱신한다(첨부는 권한상 UPDATE 동작 — PLAN-board-definition.md PR 0).
 */
@Service
@RequiredArgsConstructor
public class PostAttachmentService {

    private static final String POST_NOT_FOUND = "게시글을 찾을 수 없습니다.";
    private static final String ATTACHMENT_NOT_FOUND = "첨부파일을 찾을 수 없습니다.";

    private final PostRepository postRepository;
    private final PostAttachmentRepository postAttachmentRepository;
    private final BoardRepository boardRepository;
    private final FileStorage fileStorage;
    private final AdminSecurityService adminSecurityService;
    private final Clock clock;

    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.POST_ATTACHMENT_UPLOAD, targetType = "POST_ATTACHMENT", targetIdExpression = "id")
    public PostAttachmentResponse upload(Long boardId, Long postId, MultipartFile file) {
        // 현재 사용자는 잠금 전에 — 403이 행 잠금을 쥔 채 나가지 않게 한다
        String updaterId = requireCurrentAdminUserId();
        Post post = postRepository.findByIdAndBoardIdAndDeletedFalseForUpdate(postId, boardId)
                .orElseThrow(() -> new ResourceNotFoundException(POST_NOT_FOUND));
        // 게시판 행 공유 잠금 — 쓰기 중지 전환(게시판 FOR UPDATE)과 진행 중 업로드를 직렬화한다. 순서는 게시글 → 게시판(PLAN-board-definition.md §4 D5)
        Board board = boardRepository.findByIdAndDeletedFalseForShare(boardId)
                .orElseThrow(() -> new ResourceNotFoundException(PostService.BOARD_NOT_FOUND));
        PostService.requireWritable(board);
        if (!Boolean.TRUE.equals(board.getAttachmentYn())) {
            throw new InvalidRequestException("첨부를 허용하지 않는 게시판입니다.");
        }

        if (file == null || file.isEmpty()) {
            throw new InvalidRequestException("업로드할 파일을 선택해주세요.");
        }
        String filename = AttachmentFilePolicy.sanitizeFilename(file.getOriginalFilename());
        String extension = AttachmentFilePolicy.requireAllowedExtension(filename);
        AttachmentFilePolicy.validateContentType(extension, file.getContentType());
        if (file.getSize() > AttachmentFilePolicy.MAX_FILE_SIZE) {
            throw new InvalidRequestException("첨부파일은 10MB 이하만 업로드할 수 있습니다.");
        }
        if (postAttachmentRepository.countByPostId(postId) >= AttachmentFilePolicy.MAX_COUNT_PER_OWNER) {
            throw new ConflictException("게시글당 첨부파일은 최대 5개까지 업로드할 수 있습니다.");
        }

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new InvalidRequestException("파일을 읽을 수 없습니다.");
        }

        String contentType = AttachmentFilePolicy.normalizeContentType(file.getContentType());

        String storageKey = fileStorage.store(content, filename);
        FileStorageTransactionSupport.deleteOnRollback(fileStorage, storageKey);

        LocalDateTime now = LocalDateTime.now(clock);
        PostAttachment saved = postAttachmentRepository.save(
                PostAttachment.builder()
                        .postId(postId)
                        .originalFilename(filename)
                        .contentType(contentType)
                        .fileSize(file.getSize())
                        .storageKey(storageKey)
                        .createDate(now)
                        .build()
        );
        post.touch(updaterId, now);

        return PostAttachmentResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<PostAttachmentResponse> list(Long boardId, Long postId) {
        requireBoard(boardId);
        postRepository.findByIdAndBoardIdAndDeletedFalse(postId, boardId)
                .orElseThrow(() -> new ResourceNotFoundException(POST_NOT_FOUND));

        return postAttachmentRepository.findByPostIdOrderByIdAsc(postId).stream()
                .map(PostAttachmentResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public PostAttachmentDownload download(Long boardId, Long postId, Long attachmentId) {
        requireBoard(boardId);
        postRepository.findByIdAndBoardIdAndDeletedFalse(postId, boardId)
                .orElseThrow(() -> new ResourceNotFoundException(POST_NOT_FOUND));
        PostAttachment attachment = postAttachmentRepository.findByIdAndPostId(attachmentId, postId)
                .orElseThrow(() -> new ResourceNotFoundException(ATTACHMENT_NOT_FOUND));

        // DB 행 조회 직후 다른 요청의 삭제 트랜잭션이 커밋되어 실파일이 이미 제거된 경우 파일 없음만 404로 변환한다 —
        // 그 외 I/O 실패는 그대로 전파해 500 처리된다(디스크 장애 등 실제 서버 오류와 구분).
        try {
            byte[] content = fileStorage.load(attachment.getStorageKey());
            return new PostAttachmentDownload(attachment.getOriginalFilename(), content);
        } catch (StorageFileNotFoundException e) {
            throw new ResourceNotFoundException(ATTACHMENT_NOT_FOUND);
        }
    }

    @Transactional
    @AdminActionLogged(actionType = AdminActionTypes.POST_ATTACHMENT_DELETE, targetType = "POST_ATTACHMENT", targetIdExpression = "id")
    public PostAttachmentResponse delete(Long boardId, Long postId, Long attachmentId) {
        String updaterId = requireCurrentAdminUserId();
        Post post = postRepository.findByIdAndBoardIdAndDeletedFalseForUpdate(postId, boardId)
                .orElseThrow(() -> new ResourceNotFoundException(POST_NOT_FOUND));
        requireBoard(boardId);

        PostAttachment attachment = postAttachmentRepository.findByIdAndPostId(attachmentId, postId)
                .orElseThrow(() -> new ResourceNotFoundException(ATTACHMENT_NOT_FOUND));

        postAttachmentRepository.delete(attachment);
        // 커밋 후에만 파일을 지운다 — 롤백되면 행과 파일이 모두 남는다. 삭제 실패는 로그만 남기고 전파하지 않는다(수동 회수가 정리)
        FileStorageTransactionSupport.deleteAfterCommit(fileStorage, attachment.getStorageKey(),
                "postAttachmentId=" + attachmentId);
        post.touch(updaterId, LocalDateTime.now(clock));

        return PostAttachmentResponse.from(attachment);
    }

    private Board requireBoard(Long boardId) {
        return boardRepository.findByIdAndDeletedFalse(boardId)
                .orElseThrow(() -> new ResourceNotFoundException(PostService.BOARD_NOT_FOUND));
    }

    /** {@link PostService}와 같은 규칙 — 인가 통과 후 null이면 인증 연계 버그이므로 403으로 막아 수정자 없는 기록을 남기지 않는다. */
    private String requireCurrentAdminUserId() {
        String userId = adminSecurityService.getCurrentAdminUserId();
        if (userId == null) {
            throw new AccessDeniedException("인증 정보를 확인할 수 없습니다.");
        }
        return userId;
    }
}
