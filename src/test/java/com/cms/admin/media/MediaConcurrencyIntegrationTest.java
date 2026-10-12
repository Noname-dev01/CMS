package com.cms.admin.media;

import com.cms.admin.banner.dto.request.BannerCreateRequest;
import com.cms.admin.banner.service.BannerService;
import com.cms.admin.contentimage.service.ContentImageService;
import com.cms.admin.media.service.MediaImageRemover;
import com.cms.admin.media.service.MediaService;
import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.permission.AdminFeature;
import com.cms.admin.permission.AdminPermissionEvaluator;
import com.cms.admin.permission.PermissionAction;
import com.cms.admin.popup.domain.PopupType;
import com.cms.admin.popup.dto.request.PopupCreateRequest;
import com.cms.admin.popup.service.PopupService;
import com.cms.common.exception.ConflictException;
import com.cms.common.image.ImageFileValidatorTest;
import com.cms.common.storage.FileStorage;
import com.cms.common.storage.StorageFileNotFoundException;
import com.cms.config.auth.CustomUserDetails;
import com.cms.support.CmsTestApplication;
import com.cms.support.LockWaitObserver;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.MediaTestSupport;
import com.cms.support.TestMembers;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 미디어 라이브러리의 잠금 계약(PLAN-media-library.md D5·D8, 적대적 리뷰 R2-1·R2-2·R3-1·R4-2)을 실제 MariaDB로 고정한다.
 * 여러 스레드가 독립 트랜잭션을 가져야 하므로 {@code @Transactional}을 붙이지 않고, 시험이 만든 행·파일·카운터를 직접 정리한다.
 *
 * <p>고정하는 것: ① 이미지를 지우는 최상위 진입점 셋이 READ_COMMITTED로 실행된다 ② 그 격리 수준 덕에 "참조 잠금 읽기"가 빈 범위에 갭 잠금을 걸어
 * 다른 이미지에 대한 참조 INSERT(직접 업로드)를 막지 않는다 ③ 공유 이미지를 쓰는 소유자들의 동시 삭제가 교착·고아 없이 끝난다
 * ④ 게시글 본문 저장의 이미지 {@code FOR SHARE}가 삭제를 기다리게 하고, 삭제가 먼저면 저장은 조용히 그 이미지를 뺀다.
 * 변이 실험(결과는 PLAN-media-library.md에 기록)으로 각 보호 장치를 빼면 해당 시험이 실패함을 확인했다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
class MediaConcurrencyIntegrationTest extends MariaDbContainerSupport {

    @Autowired MediaService mediaService;
    @Autowired BannerService bannerService;
    @Autowired PopupService popupService;
    @Autowired ContentImageService contentImageService;
    @Autowired MemberRepository memberRepository;
    @Autowired FileStorage fileStorage;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcConnectionDetails connectionDetails;
    @MockitoSpyBean MediaImageRemover imageRemover;
    @MockitoSpyBean AdminPermissionEvaluator permissionEvaluator;
    @PersistenceContext EntityManager entityManager;

    private Member admin;
    private long auditBaseline;

    @BeforeEach
    void setUp() {
        admin = TestMembers.save(memberRepository, "mc-admin", Role.ROLE_ADMIN);
        auditBaseline = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM admin_action_log", Long.class);
        clean();
    }

    @AfterEach
    void tearDown() {
        Mockito.reset(imageRemover, permissionEvaluator);
        SecurityContextHolder.clearContext();
        clean();
        TestMembers.delete(jdbc, List.of(admin.getId()));
        jdbc.update("DELETE FROM admin_action_log WHERE id > ?", auditBaseline);
    }

    private void clean() {
        jdbc.update("DELETE FROM content_image_ref WHERE owner_type = 'POST' AND owner_id = 424242");
        jdbc.update("DELETE FROM banner");
        jdbc.update("DELETE FROM popup");
        MediaTestSupport.cleanLibrary(jdbc, fileStorage);
    }

    private SecurityContext adminContext() {
        CustomUserDetails details = new CustomUserDetails(admin);
        SecurityContext context = new SecurityContextImpl(new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
        return context;
    }

    /** 이 스레드를 ADMIN으로 인증한다(서비스가 SecurityContextHolder를 읽는다). */
    private void authenticate() {
        SecurityContextHolder.setContext(adminContext());
    }

    private static byte[] png() throws Exception {
        return ImageFileValidatorTest.png(30, 12);
    }

    private long uploadLibraryImage() throws Exception {
        authenticate();
        return mediaService.upload(new MockMultipartFile("file", "lib.png", "image/png", png())).id();
    }

    private long createBannerWithUpload() throws Exception {
        authenticate();
        BannerCreateRequest request = new BannerCreateRequest();
        request.setTitle("동시성 배너");
        request.setImage(new MockMultipartFile("image", "b.png", "image/png", png()));
        return bannerService.createBanner(request).getId();
    }

    private long createPopupWithImageId(long imageId) {
        authenticate();
        PopupCreateRequest request = new PopupCreateRequest();
        request.setTitle("동시성 팝업");
        request.setPopupType(PopupType.IMAGE);
        request.setImageId(imageId);
        return popupService.createPopup(request).getId();
    }

    private long createPopupWithUpload() throws Exception {
        authenticate();
        PopupCreateRequest request = new PopupCreateRequest();
        request.setTitle("동시성 업로드 팝업");
        request.setPopupType(PopupType.IMAGE);
        request.setImage(new MockMultipartFile("image", "p.png", "image/png", png()));
        return popupService.createPopup(request).getId();
    }

    private long[] counter() {
        return jdbc.queryForObject("SELECT total_bytes, total_count FROM content_image_usage WHERE id = 1",
                (rs, n) -> new long[]{rs.getLong(1), rs.getLong(2)});
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(20, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ===== ① 격리 수준 =====

    @Test
    @DisplayName("이미지를 지우는 최상위 진입점 셋(라이브러리 삭제·배너 삭제·팝업 삭제)은 READ_COMMITTED 트랜잭션에서 이미지·참조를 잠근다")
    void deleteEntryPoints_runUnderReadCommitted() throws Exception {
        List<Integer> seen = new ArrayList<>();
        Mockito.doAnswer(invocation -> {
            seen.add(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
            return invocation.callRealMethod();
        }).when(imageRemover).lockRefs(Mockito.anyLong());
        Mockito.doAnswer(invocation -> {
            seen.add(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
            return invocation.callRealMethod();
        }).when(imageRemover).detachOwner(Mockito.anyString(), Mockito.anyLong(), Mockito.anyLong());

        long libraryImage = uploadLibraryImage();
        authenticate();
        mediaService.delete(libraryImage);
        long bannerId = createBannerWithUpload();
        authenticate();
        bannerService.deleteBanner(bannerId);
        long popupId = createPopupWithUpload();
        authenticate();
        popupService.deletePopup(popupId);

        assertThat(seen).as("세 진입점 모두 거친다").hasSize(3).containsOnly(java.sql.Connection.TRANSACTION_READ_COMMITTED);
    }

    // ===== ② 갭 락 순환 방지 =====

    @Test
    @DisplayName("삭제가 참조를 잠금 읽기로 확인하는 동안(빈 범위 포함) 다른 이미지에 대한 참조 INSERT가 막히지 않는다 — REPEATABLE READ였다면 갭 잠금으로 막힌다")
    void referenceLockRead_doesNotBlockInsertsForOtherImages() throws Exception {
        long target = uploadLibraryImage();
        long other = uploadLibraryImage();
        AtomicBoolean insertBlocked = new AtomicBoolean(false);
        Mockito.doAnswer(invocation -> {
            Object result = invocation.callRealMethod();             // 대상 이미지의 참조를 잠금 읽기(없음) — 이 시점에 삭제 트랜잭션이 열려 있다
            CompletableFuture<Integer> insert = CompletableFuture.supplyAsync(() -> jdbc.update(
                    "INSERT INTO content_image_ref (owner_type, owner_id, image_id) VALUES ('POST', 424242, ?)", other));
            try {
                insert.get(5, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                insertBlocked.set(true);                             // 갭 잠금에 막혔다 — 삭제 트랜잭션이 끝나야 풀리므로 여기서는 기다리지 않는다
            }
            return result;
        }).when(imageRemover).lockRefs(target);

        authenticate();
        mediaService.delete(target);

        assertThat(insertBlocked.get()).as("READ_COMMITTED면 갭 잠금이 없어 다른 이미지의 참조 INSERT가 즉시 끝난다").isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_image WHERE id = ?", Integer.class, target)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_image_ref WHERE image_id = ?", Integer.class, other)).isEqualTo(1);
    }

    @Test
    @DisplayName("미사용 라이브러리 이미지 삭제 ↔ 팝업 직접 업로드를 겹쳐 반복해도 교착·409 없이 둘 다 성공한다")
    void unusedDelete_vsPopupDirectUpload_noDeadlock() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 8; round++) {
                long unused = uploadLibraryImage();
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<?> delete = executor.submit(() -> {
                    authenticate();
                    barrier.await(10, TimeUnit.SECONDS);
                    return mediaService.delete(unused);
                });
                Future<Long> upload = executor.submit(() -> {
                    authenticate();
                    barrier.await(10, TimeUnit.SECONDS);
                    return popupService.createPopup(directUploadPopup()).getId();
                });
                delete.get(30, TimeUnit.SECONDS);
                assertThat(upload.get(30, TimeUnit.SECONDS)).isPositive();
                authenticate();
                popupService.deletePopup(upload.get());
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    private PopupCreateRequest directUploadPopup() throws Exception {
        PopupCreateRequest request = new PopupCreateRequest();
        request.setTitle("겹침 팝업");
        request.setPopupType(PopupType.IMAGE);
        request.setImage(new MockMultipartFile("image", "p.png", "image/png", png()));
        return request;
    }

    // ===== ③ 공유 이미지 동시 삭제 =====

    @Test
    @DisplayName("배너가 올린 이미지를 팝업 둘이 함께 쓸 때 소유자 셋을 동시에 지워도 교착 없이 끝나고, 마지막 삭제가 이미지 행·파일·카운터를 정리한다(고아 없음)")
    void sharedImage_concurrentOwnerDeletes_leaveNoOrphan() throws Exception {
        long[] baseline = counter();
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            for (int round = 0; round < 6; round++) {
                long bannerId = createBannerWithUpload();
                long imageId = jdbc.queryForObject("SELECT image_id FROM content_image_ref WHERE owner_type = 'BANNER' AND owner_id = ?", Long.class, bannerId);
                String key = jdbc.queryForObject("SELECT storage_key FROM content_image WHERE id = ?", String.class, imageId);
                long popupA = createPopupWithImageId(imageId);
                long popupB = createPopupWithImageId(imageId);

                CyclicBarrier barrier = new CyclicBarrier(3);
                List<Callable<Object>> deletes = List.of(
                        () -> { authenticate(); barrier.await(10, TimeUnit.SECONDS); return bannerService.deleteBanner(bannerId); },
                        () -> { authenticate(); barrier.await(10, TimeUnit.SECONDS); return popupService.deletePopup(popupA); },
                        () -> { authenticate(); barrier.await(10, TimeUnit.SECONDS); return popupService.deletePopup(popupB); });
                List<Future<Object>> futures = new ArrayList<>();
                for (Callable<Object> delete : deletes) {
                    futures.add(executor.submit(delete));
                }
                for (Future<Object> future : futures) {
                    future.get(30, TimeUnit.SECONDS);       // 예외(교착으로 인한 409/500 포함)가 있으면 여기서 실패한다
                }

                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_image WHERE id = ?", Integer.class, imageId))
                        .as("round " + round + ": 마지막 소유자가 사라지면 이미지 행도 사라진다").isZero();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_image_ref WHERE image_id = ?", Integer.class, imageId)).isZero();
                assertThat(counter()).as("카운터가 원래대로").containsExactly(baseline);
                assertThatThrownBy(() -> fileStorage.open(key, "banner")).as("파일도 정리됨").isInstanceOf(StorageFileNotFoundException.class);
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    // ===== ④ 게시글 본문 저장 ↔ 삭제 =====

    @Test
    @DisplayName("본문 저장이 쥔 이미지 공유 잠금은 삭제를 기다리게 하고, 저장이 커밋되면 삭제는 새 참조를 보고 409다")
    void replaceRefsShareLock_makesDeleteWait_thenConflict() throws Exception {
        long imageId = uploadLibraryImage();
        long boardId = jdbc.queryForObject("SELECT id FROM board WHERE board_key = 'NOTICE'", Long.class);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong holderConnection = new AtomicLong();
        try {
            Future<?> saver = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                authenticate();
                contentImageService.replaceRefs(ContentImageService.OWNER_POST, 424242L, ContentImageService.SCOPE_BOARD, boardId, List.of(imageId));
                holderConnection.set(((Number) entityManager.createNativeQuery("SELECT CONNECTION_ID()").getSingleResult()).longValue());
                locked.countDown();
                await(release);                                  // 저장 트랜잭션은 커밋을 미룬다
            }));
            await(locked);

            AtomicReference<Throwable> deleteFailure = new AtomicReference<>();
            Future<?> deleter = executor.submit(() -> {
                authenticate();
                try {
                    mediaService.delete(imageId);
                } catch (Throwable t) {
                    deleteFailure.set(t);
                }
            });
            new LockWaitObserver(connectionDetails.getJdbcUrl(), connectionDetails.getPassword())
                    .awaitWaiterFor(holderConnection.get(), Duration.ofSeconds(8));
            assertThat(deleter.isDone()).as("저장의 공유 잠금이 풀리기 전에는 삭제가 끝나지 않는다").isFalse();

            release.countDown();
            saver.get(15, TimeUnit.SECONDS);
            deleter.get(15, TimeUnit.SECONDS);

            assertThat(deleteFailure.get()).as("저장이 먼저 확정됐으니 삭제는 새 참조를 보고 409").isInstanceOf(ConflictException.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_image WHERE id = ?", Integer.class, imageId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_image_ref WHERE owner_type = 'POST' AND owner_id = 424242", Integer.class)).isEqualTo(1);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    @Test
    @DisplayName("존재 확인과 참조 INSERT 사이에 삭제가 끼어들 수 없다 — 이미지 공유 잠금 때문에 삭제는 저장이 끝날 때까지 기다리고, 저장은 FK 위반 없이 성공한다(R2-2의 세 번째 결과 제거)")
    void deleteCannotSlipBetweenExistenceCheckAndRefInsert() throws Exception {
        long imageId = uploadLibraryImage();
        long boardId = jdbc.queryForObject("SELECT id FROM board WHERE board_key = 'NOTICE'", Long.class);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicBoolean armed = new AtomicBoolean(true);
        AtomicBoolean deleteSlippedIn = new AtomicBoolean(false);
        AtomicReference<Future<Throwable>> deleter = new AtomicReference<>();
        // replaceRefs는 이미지를 공유 잠금으로 읽은 직후(라이브러리 이미지) MEDIA 읽기 권한을 판정한다 — 존재 확인과 참조 INSERT 사이의 이 지점에서 삭제를 시도한다
        Mockito.doAnswer(invocation -> {
            if (armed.compareAndSet(true, false)) {
                deleter.set(executor.submit(() -> {
                    authenticate();
                    try {
                        mediaService.delete(imageId);
                        return null;
                    } catch (Throwable t) {
                        return t;
                    }
                }));
                try {
                    deleter.get().get(2, TimeUnit.SECONDS);
                    deleteSlippedIn.set(true);               // 삭제가 끝나 버렸다 — 이미지 공유 잠금이 없다
                } catch (TimeoutException expected) {
                    // 삭제가 이미지 잠금에서 기다리는 중이다(정상)
                }
            }
            return invocation.callRealMethod();
        }).when(permissionEvaluator).allows(Mockito.any(), Mockito.eq(AdminFeature.MEDIA), Mockito.eq(PermissionAction.READ));

        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                authenticate();
                contentImageService.replaceRefs(ContentImageService.OWNER_POST, 424242L, ContentImageService.SCOPE_BOARD, boardId, List.of(imageId));
            });

            assertThat(deleteSlippedIn.get()).as("존재 확인 직후에도 삭제는 끼어들지 못한다").isFalse();
            assertThat(deleter.get().get(15, TimeUnit.SECONDS)).as("저장이 확정된 뒤 삭제는 새 참조를 보고 409").isInstanceOf(ConflictException.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_image_ref WHERE owner_type = 'POST' AND owner_id = 424242", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_image WHERE id = ?", Integer.class, imageId)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    @Test
    @DisplayName("삭제가 먼저 끝났다면 본문 저장은 그 이미지를 조용히 뺀다(예외 없이 성공, 참조 행 없음) — 존재 확인 뒤 FK 위반으로 저장이 롤백되는 일은 없다")
    void deleteFirst_thenSaveDropsMissingImageQuietly() throws Exception {
        long imageId = uploadLibraryImage();
        long boardId = jdbc.queryForObject("SELECT id FROM board WHERE board_key = 'NOTICE'", Long.class);
        authenticate();
        mediaService.delete(imageId);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            authenticate();
            contentImageService.replaceRefs(ContentImageService.OWNER_POST, 424242L, ContentImageService.SCOPE_BOARD, boardId, List.of(imageId));
        });

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_image_ref WHERE owner_type = 'POST' AND owner_id = 424242", Integer.class)).isZero();
    }
}
