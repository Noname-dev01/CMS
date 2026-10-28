package com.cms.publicweb.board.service;

import com.cms.admin.board.domain.Board;
import com.cms.admin.board.domain.Post;
import com.cms.admin.board.domain.PostAttachment;
import com.cms.admin.board.dto.response.PostAttachmentResponse;
import com.cms.admin.board.repository.BoardRepository;
import com.cms.admin.board.repository.PostAttachmentRepository;
import com.cms.admin.board.repository.PostRepository;
import com.cms.admin.board.service.PostAttachmentService;
import com.cms.admin.member.domain.Role;
import com.cms.admin.site.Site;
import com.cms.common.storage.FileStorage;
import com.cms.publicweb.board.dto.PublicPostAttachmentDownload;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.TestMembers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 공개 첨부 다운로드의 TOCTOU 재검증·IDOR 차단·StorageFileNotFoundException 경로를 실제 MariaDB로 검증한다(PLAN-public-notice-attachment.md
 * 결정 2). 공지는 이제 공지 게시판(V2가 시드한 {@code board_key='NOTICE'})의 게시글이다 — 같은 공개 계약을 공지 게시판 id로 {@link PublicBoardService}에서 확인한다(PLAN-board-definition.md PR 4b에서 공지 어댑터가 사라져 옮겨 옴).
 * 각 시나리오는 독립된 게시글을 사용한다(같은 fixture를 재사용하면 한 시나리오의 상태 변경이 다른 시나리오의 분기를 가려버릴 수 있다).
 *
 * <p>Testcontainers가 띄우는 일회용 MariaDB로 실행된다 — Docker만 있으면 된다({@link MariaDbContainerSupport}).
 */
@SpringBootTest(classes = CmsTestApplication.class)
class PublicBoardAttachmentIntegrationTest extends MariaDbContainerSupport {

    @Autowired
    BoardRepository boardRepository;

    @Autowired
    PostRepository postRepository;

    @Autowired
    PostAttachmentRepository postAttachmentRepository;

    @Autowired
    PostAttachmentService postAttachmentService;

    @Autowired
    PublicBoardService publicBoardService;

    @Autowired
    FileStorage fileStorage;

    @Autowired
    PlatformTransactionManager transactionManager;

    private final List<Long> createdPostIds = new ArrayList<>();
    private final List<String> createdStorageKeys = new ArrayList<>();

    /**
     * 첨부 업로드는 게시글 최종 수정자를 기록하므로 회원 아이디를 가진 관리자 주체({@code CustomUserDetails})가 필요하다 —
     * {@code @WithMockUser}의 주체에는 회원 아이디가 없어 현재 사용자가 null(403)이 된다.
     */
    @BeforeEach
    void authenticate() {
        var details = TestMembers.detached(1L, Role.ROLE_ADMIN);
        SecurityContextHolder.setContext(new SecurityContextImpl(new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities())));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void cleanUp() {
        for (Long id : createdPostIds) {
            try {
                postAttachmentRepository.findByPostIdOrderByIdAsc(id)
                        .forEach(a -> postAttachmentRepository.deleteById(a.getId()));
                postRepository.deleteById(id);
            } catch (Exception ignored) {
            }
        }
        for (String key : createdStorageKeys) {
            try {
                fileStorage.delete(key);
            } catch (Exception ignored) {
            }
        }
    }

    private Long noticeBoardId() {
        return boardRepository.findNoticeBoardId(Site.DEFAULT_PUBLIC_SITE_ID).orElseThrow();
    }

    private Post savePublishedNotice(String titlePrefix) {
        LocalDateTime now = LocalDateTime.now();
        Post saved = postRepository.save(Post.builder()
                .boardId(noticeBoardId())
                .title(titlePrefix + "-" + System.nanoTime())
                .content("공개 첨부 통합 테스트 본문")
                .useYn(true)
                .deleted(false)
                .authorId("admin01")
                .createDate(now)
                .updateDate(now)
                .build());
        createdPostIds.add(saved.getId());
        return saved;
    }

    private PostAttachment uploadAttachment(Long postId) {
        MockMultipartFile file = new MockMultipartFile("file", "report.pdf", "application/pdf", "content".getBytes());
        PostAttachmentResponse response = postAttachmentService.upload(noticeBoardId(), postId, file);
        PostAttachment saved = postAttachmentRepository.findById(response.getId()).orElseThrow();
        createdStorageKeys.add(saved.getStorageKey());
        return saved;
    }

    /** 컨트롤러와 같은 2단계 호출: 공개 조건 재검증(트랜잭션) → 파일 open(트랜잭션 밖). */
    private Optional<PublicPostAttachmentDownload> download(Long noticeId, Long attachmentId) {
        return publicBoardService.findPublishedAttachment(Site.DEFAULT_PUBLIC_SITE_ID, noticeBoardId(), noticeId, attachmentId)
                .flatMap(publicBoardService::openAttachment);
    }

    // ===================== 1. 다운로드 성공 =====================

    @Test
    @DisplayName("공개·미삭제 공지의 첨부는 다운로드에 성공한다(스트림 내용·크기 일치)")
    void downloadPublishedAttachment_success() throws Exception {
        Post notice = savePublishedNotice("다운로드성공");
        PostAttachment attachment = uploadAttachment(notice.getId());

        Optional<PublicPostAttachmentDownload> result = download(notice.getId(), attachment.getId());

        assertTrue(result.isPresent());
        try (PublicPostAttachmentDownload opened = result.get()) {
            assertEquals("report.pdf", opened.originalFilename());
            assertEquals("content".length(), opened.contentLength());
            assertArrayEquals("content".getBytes(), opened.content().readAllBytes());
        }
    }

    // ===================== 2. TOCTOU =====================

    @Test
    @DisplayName("TOCTOU: useYn=false가 별도 트랜잭션에서 커밋된 뒤 재요청하면 empty를 반환한다")
    void downloadPublishedAttachment_toctou_hiddenAfterCommit_empty() {
        Post notice = savePublishedNotice("TOCTOU검증");
        PostAttachment attachment = uploadAttachment(notice.getId());

        // 목록에서 본 뒤 관리자가 별도 트랜잭션에서 비공개로 전환·커밋하는 상황을 재현한다.
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            Post managed = postRepository.findById(notice.getId()).orElseThrow();
            managed.update(null, null, false, null, "admin01", LocalDateTime.now());
            postRepository.save(managed);
        });

        Optional<PublicPostAttachmentDownload> result = download(notice.getId(), attachment.getId());

        assertTrue(result.isEmpty());
    }

    // ===================== 3. IDOR =====================

    @Test
    @DisplayName("IDOR: 공지 A의 id와 공지 B 소유 attachmentId 조합은 empty를 반환한다")
    void downloadPublishedAttachment_idor_wrongNotice_empty() {
        Post noticeA = savePublishedNotice("IDOR-A");
        Post noticeB = savePublishedNotice("IDOR-B");
        PostAttachment attachmentOfB = uploadAttachment(noticeB.getId());

        Optional<PublicPostAttachmentDownload> result = download(noticeA.getId(), attachmentOfB.getId());

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("다른 게시판의 게시글 ID는 공지 게시판 경로로 접근할 수 없다 — 게시판 소속 조건(같은 404)")
    void downloadPublishedAttachment_postOfOtherBoard_empty() {
        Board other = boardRepository.save(Board.builder().name("다른 게시판").publicYn(true).attachmentYn(true).deleted(false).build());
        Post foreign = postRepository.save(Post.builder()
                .boardId(other.getId()).title("타 게시판 글").content("x").useYn(true).deleted(false)
                .authorId("admin01").createDate(LocalDateTime.now()).updateDate(LocalDateTime.now()).build());
        createdPostIds.add(foreign.getId());
        MockMultipartFile file = new MockMultipartFile("file", "other.pdf", "application/pdf", "content".getBytes());
        PostAttachmentResponse response = postAttachmentService.upload(other.getId(), foreign.getId(), file);
        PostAttachment attachment = postAttachmentRepository.findById(response.getId()).orElseThrow();
        createdStorageKeys.add(attachment.getStorageKey());
        try {
            assertTrue(publicBoardService.findPublishedPost(Site.DEFAULT_PUBLIC_SITE_ID, noticeBoardId(), foreign.getId()).isEmpty());
            assertTrue(download(foreign.getId(), attachment.getId()).isEmpty());
        } finally {
            postAttachmentRepository.deleteById(attachment.getId());
            postRepository.deleteById(foreign.getId());
            createdPostIds.remove(foreign.getId());
            boardRepository.deleteById(other.getId());
        }
    }

    // ===================== 4. StorageFileNotFoundException =====================

    @Test
    @DisplayName("DB 행은 있는데 실파일이 없으면 empty를 반환한다(fail-closed)")
    void downloadPublishedAttachment_fileMissing_empty() {
        Post notice = savePublishedNotice("파일없음검증");
        PostAttachment attachment = uploadAttachment(notice.getId());

        // 행은 유지한 채 실파일만 직접 제거한다.
        fileStorage.delete(attachment.getStorageKey());
        createdStorageKeys.remove(attachment.getStorageKey());

        Optional<PublicPostAttachmentDownload> result = download(notice.getId(), attachment.getId());

        assertTrue(result.isEmpty());
    }
}
