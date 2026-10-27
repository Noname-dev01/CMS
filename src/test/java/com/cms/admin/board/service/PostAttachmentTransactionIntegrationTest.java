package com.cms.admin.board.service;

import com.cms.admin.board.domain.Board;
import com.cms.admin.board.domain.Post;
import com.cms.admin.board.domain.PostAttachment;
import com.cms.admin.board.dto.response.PostAttachmentResponse;
import com.cms.admin.board.repository.BoardRepository;
import com.cms.admin.board.repository.PostAttachmentRepository;
import com.cms.admin.board.repository.PostRepository;
import com.cms.admin.member.domain.Role;
import com.cms.common.storage.FileStorage;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PostAttachmentServiceTest(순수 Mockito 단위 테스트)로는 "실제 커밋/롤백 시점에
 * TransactionSynchronization 콜백이 정확히 호출되는가"를 증명할 수 없어(실제 트랜잭션 컨텍스트
 * 필요) 실제 MariaDB + 실제 트랜잭션으로 검증하는 통합 테스트.
 * (adversarial-review/plan/PLAN-notice-attachment.md 쟁점 7, 리뷰 2차 지적 #5 반영)
 *
 * <p>외부 트랜잭션(TransactionTemplate)으로 감싸고 {@code status.setRollbackOnly()}를 호출해
 * "업로드/삭제 자체는 성공했지만 같은 트랜잭션 안의 다른 이유로 전체가 롤백되는" 상황을
 * 재현한다 — 서비스 메서드의 {@code @Transactional}은 기본 전파(REQUIRED)라 외부 트랜잭션에
 * 참여하므로, 외부에서 강제한 롤백이 업로드·삭제 결과에도 그대로 적용된다.
 *
 * <p>Testcontainers가 띄우는 일회용 MariaDB로 실행된다 — 로컬 DB 기동·환경변수 주입 불필요,
 * Docker만 있으면 된다({@link MariaDbContainerSupport}).
 */
@SpringBootTest(classes = CmsTestApplication.class)
class PostAttachmentTransactionIntegrationTest extends MariaDbContainerSupport {

    @Autowired
    PostRepository postRepository;

    @Autowired
    PostAttachmentRepository postAttachmentRepository;

    @Autowired
    PostAttachmentService postAttachmentService;

    @Autowired
    FileStorage fileStorage;

    @Autowired
    PlatformTransactionManager transactionManager;
    @Autowired
    BoardRepository boardRepository;

    private Long noticeBoardId() {
        return boardRepository.findIdByBoardKey(Board.NOTICE_KEY).orElseThrow();
    }


    private final List<Long> createdPostIds = new ArrayList<>();
    private final List<String> createdStorageKeys = new ArrayList<>();

    /** 첨부 업로드·삭제는 게시글 최종 수정자를 기록하므로 인증된 관리자 주체가 필요하다(운영에서는 항상 인증된 요청). */
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

    private Post saveNotice(String titlePrefix) {
        LocalDateTime now = LocalDateTime.now();
        Post saved = postRepository.save(Post.builder()
                .boardId(noticeBoardId())
                .title(titlePrefix + "-" + System.nanoTime())
                .content("트랜잭션 정합성 테스트 본문")
                .useYn(true)
                .deleted(false)
                .authorId("admin01")
                .createDate(now)
                .updateDate(now)
                .build());
        createdPostIds.add(saved.getId());
        return saved;
    }

    private MockMultipartFile sampleFile() {
        return new MockMultipartFile("file", "report.pdf", "application/pdf", "trx-test-content".getBytes());
    }

    // ===================== 업로드 =====================

    @Test
    @DisplayName("업로드 트랜잭션이 커밋되면 파일이 유지된다")
    void upload_commit_fileKept() {
        Post notice = saveNotice("업로드_커밋");

        PostAttachmentResponse response = postAttachmentService.upload(noticeBoardId(), notice.getId(), sampleFile());

        PostAttachment saved = postAttachmentRepository.findById(response.getId()).orElseThrow();
        createdStorageKeys.add(saved.getStorageKey());

        assertArrayEquals("trx-test-content".getBytes(), fileStorage.load(saved.getStorageKey()));
    }

    @Test
    @DisplayName("업로드를 포함한 트랜잭션 전체가 롤백되면 파일이 삭제된다 (afterCompletion 정리)")
    void upload_rollback_fileDeleted() {
        Post notice = saveNotice("업로드_롤백");
        Post before = postRepository.findById(notice.getId()).orElseThrow();   // DB에 저장된 값(마이크로초 정밀도) 기준
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        String storageKey = tx.execute(status -> {
            PostAttachmentResponse response = postAttachmentService.upload(noticeBoardId(), notice.getId(), sampleFile());
            PostAttachment saved = postAttachmentRepository.findById(response.getId()).orElseThrow();
            // 업로드 자체는 성공했지만, 같은 트랜잭션 안의 다른 사정으로 전체가 롤백되는 상황을 재현한다.
            status.setRollbackOnly();
            return saved.getStorageKey();
        });

        assertThrows(IllegalStateException.class, () -> fileStorage.load(storageKey),
                "롤백된 업로드의 파일은 afterCompletion 콜백으로 정리되어야 한다");

        // DB 쪽도 실제로 롤백됐는지 함께 확인 — 정리 대상 목록에 추가할 필요 없음(행 자체가 없음).
        assertTrue(postAttachmentRepository.findByPostIdOrderByIdAsc(notice.getId()).isEmpty());
        // 게시글의 최종 수정자·수정 시각 갱신도 같은 트랜잭션이라 함께 되돌아간다
        Post after = postRepository.findById(notice.getId()).orElseThrow();
        assertEquals(null, after.getUpdaterId(), "롤백된 업로드는 최종 수정자를 남기지 않는다");
        assertEquals(before.getUpdateDate(), after.getUpdateDate(), "롤백된 업로드는 수정 시각을 바꾸지 않는다");
    }

    // ===================== 삭제 =====================

    @Test
    @DisplayName("첨부 삭제 트랜잭션이 커밋되면 파일도 함께 삭제된다 (afterCommit)")
    void delete_commit_fileDeleted() {
        Post notice = saveNotice("삭제_커밋");
        PostAttachmentResponse uploaded = postAttachmentService.upload(noticeBoardId(), notice.getId(), sampleFile());
        String storageKey = postAttachmentRepository.findById(uploaded.getId()).orElseThrow().getStorageKey();

        postAttachmentService.delete(noticeBoardId(), notice.getId(), uploaded.getId());

        assertThrows(IllegalStateException.class, () -> fileStorage.load(storageKey),
                "커밋된 삭제는 afterCommit 시점에 즉시 파일을 지워야 한다");
        assertFalse(postAttachmentRepository.findById(uploaded.getId()).isPresent());
    }

    @Test
    @DisplayName("첨부 삭제를 포함한 트랜잭션 전체가 롤백되면 DB 행과 파일이 모두 유지된다")
    void delete_rollback_fileAndRowKept() {
        Post notice = saveNotice("삭제_롤백");
        PostAttachmentResponse uploaded = postAttachmentService.upload(noticeBoardId(), notice.getId(), sampleFile());
        String storageKey = postAttachmentRepository.findById(uploaded.getId()).orElseThrow().getStorageKey();
        createdStorageKeys.add(storageKey);
        Post beforeDelete = postRepository.findById(notice.getId()).orElseThrow();

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        var otherAdmin = TestMembers.detached(2L, Role.ROLE_ADMIN);
        SecurityContextHolder.setContext(new SecurityContextImpl(new UsernamePasswordAuthenticationToken(otherAdmin, null, otherAdmin.getAuthorities())));
        tx.executeWithoutResult(status -> {
            postAttachmentService.delete(noticeBoardId(), notice.getId(), uploaded.getId());
            // 삭제 자체는 성공했지만, 같은 트랜잭션 안의 다른 사정으로 전체가 롤백되는 상황을 재현한다.
            status.setRollbackOnly();
        });

        assertTrue(postAttachmentRepository.findById(uploaded.getId()).isPresent(),
                "롤백되면 DB 삭제도 되돌아가야 한다");
        assertArrayEquals("trx-test-content".getBytes(), fileStorage.load(storageKey),
                "afterCommit 콜백은 실제 커밋 시에만 실행되므로 롤백 시 파일은 그대로 남아있어야 한다");
        // 다른 관리자가 시도한 삭제가 롤백됐으므로 최종 수정자·수정 시각은 업로드 때 값 그대로다
        Post after = postRepository.findById(notice.getId()).orElseThrow();
        assertEquals("user1", beforeDelete.getUpdaterId(), "업로드가 최종 수정자를 남겼다(전제)");
        assertEquals(beforeDelete.getUpdaterId(), after.getUpdaterId(), "롤백된 삭제는 최종 수정자를 바꾸지 않는다");
        assertEquals(beforeDelete.getUpdateDate(), after.getUpdateDate(), "롤백된 삭제는 수정 시각을 바꾸지 않는다");
    }
}
