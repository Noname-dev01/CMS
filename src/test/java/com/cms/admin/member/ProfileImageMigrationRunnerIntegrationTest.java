package com.cms.admin.member;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.ProfileImageKind;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.common.storage.FileStorage;
import com.cms.common.storage.StorageFileNotFoundException;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link ProfileImageMigrationRunner}의 실 트랜잭션 커밋/롤백·동시 실행 동작을 Testcontainers
 * 기반 실 MariaDB로 검증한다. Mockito 단위 테스트({@link ProfileImageMigrationRunnerTest})는
 * 스킵·벌크초기화·행 단위 격리 경로만 검증하며, 그 파일 자신의 주석대로 "실제 트랜잭션
 * 커밋/롤백 검증은 이 통합 테스트가 별도로 담당한다".
 *
 * <p>설계 결정 전문(5라운드 적대적 리뷰 v1~v6)은
 * adversarial-review/plan/PLAN-profile-image-storage.md "후속 작업 계획 —
 * ProfileImageMigrationRunnerIntegrationTest(Testcontainers) 추가" 참조.
 *
 * <p>클래스 단위 {@code @BeforeAll}이 인스턴스 필드({@link #memberRepository})를 사용하므로
 * {@code @TestInstance(PER_CLASS)}가 필요하다(JUnit 5 기본 생명주기는 PER_METHOD).
 */
@SpringBootTest(classes = CmsTestApplication.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProfileImageMigrationRunnerIntegrationTest extends MariaDbContainerSupport {

    private static final Logger log = LoggerFactory.getLogger(ProfileImageMigrationRunnerIntegrationTest.class);

    @Autowired
    MemberRepository memberRepository;

    @Autowired
    FileStorage fileStorage;

    @Autowired
    ProfileImageMigrationRunner runner;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    PlatformTransactionManager transactionManager;

    @PersistenceContext
    EntityManager entityManager;

    private final List<Long> createdMemberIds = new ArrayList<>();
    private final List<String> createdStorageKeys = Collections.synchronizedList(new ArrayList<>());

    /**
     * 이 테스트 클래스의 어떤 메서드도 실행되기 전에 잔존 LEGACY_INLINE 행이 없는지 확인한다
     * — 이전 테스트 메서드의 정리 실패로 남은 잔존 행을 클래스 시작 시점에 즉시 검출한다
     * (쟁점 6). 단, {@code @SpringBootTest} 컨텍스트 최초 기동 시점의 러너 자동 실행
     * ({@code CommandLineRunner}, {@code @Profile} 제한 없음) 자체는 이 검사보다 먼저
     * 일어나므로 막지 못한다 — 이 코드베이스에서 LEGACY_INLINE 행을 실제로 남기는 테스트는
     * 이 클래스가 유일해 현재 오염 위험은 0이라는 전제로 사용자 승인 하에 수용한 잔여 위험이다.
     */
    @BeforeAll
    void beforeAllNoStrayLegacyRows() {
        assertTrue(memberRepository.findIdsByProfileImageKind(ProfileImageKind.LEGACY_INLINE).isEmpty(),
                "클래스 시작 시점에 잔존 LEGACY_INLINE 행이 없어야 한다(이전 테스트 정리 실패 감지)");
    }

    @AfterEach
    void cleanUp() {
        for (Long id : createdMemberIds) {
            try {
                memberRepository.deleteById(id);
            } catch (Exception ignored) {
            }
        }
        createdMemberIds.clear();
        for (String key : createdStorageKeys) {
            try {
                fileStorage.delete(key, "profile");
            } catch (Exception ignored) {
            }
        }
        createdStorageKeys.clear();
    }

    private byte[] validPngBytes() throws IOException {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private Long createLegacyInlineMember(String dataUri) {
        long unique = System.nanoTime();
        LocalDateTime now = LocalDateTime.now();
        Member member = Member.builder()
                .userId("migrun-" + unique)
                .pwd("encoded")
                .userName("이관테스트")
                .email("migrun-" + unique + "@test.com")
                .userType(Role.ROLE_ADMIN)
                .status(MemberStatus.ACTIVE)
                .createDate(now)
                .updateDate(now)
                .passwordChangedAt(now)
                .profileImageKind(ProfileImageKind.LEGACY_INLINE)
                .profileImageUrl(dataUri)
                .build();
        Member saved = memberRepository.saveAndFlush(member);
        createdMemberIds.add(saved.getId());
        return saved.getId();
    }

    /**
     * 대상 집합이 정확히 내 행 하나인지 단언한다(쟁점 6) — "실행 전 비어있음"이 아니라
     * "생성 직후 정확히 하나"로 검사해, 이 클래스의 이전 테스트가 남긴 잔존 행을 잡아낸다.
     */
    private void assertOnlyMyRowIsTarget(Long id) {
        List<Long> targets = memberRepository.findIdsByProfileImageKind(ProfileImageKind.LEGACY_INLINE);
        assertEquals(List.of(id), targets, "대상 집합이 정확히 내 행 하나여야 한다(오염 검출)");
    }

    // ===================== 쟁점 2: 정상 커밋 =====================

    @Test
    @DisplayName("정상 이관이 커밋되면 DB에 UPLOADED·storageKey·content-type이 반영되고 저장된 바이트가 원본과 일치한다")
    void run_commit_persistsMigratedStateAndFile() throws IOException {
        byte[] png = validPngBytes();
        String dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
        Long id = createLegacyInlineMember(dataUri);
        assertOnlyMyRowIsTarget(id);

        runner.run();

        Member reloaded = memberRepository.findById(id).orElseThrow();
        // 검증(assertEquals 등)이 중간에 실패해 메서드가 조기 종료돼도 @AfterEach가 파일을 정리할
        // 수 있도록, 어떤 assertion보다도 먼저(재조회 직후) storageKey처럼 보이는 값을 등록한다.
        if (reloaded.getProfileImageUrl() != null && !reloaded.getProfileImageUrl().startsWith("data:")) {
            createdStorageKeys.add(reloaded.getProfileImageUrl());
        }

        assertEquals(ProfileImageKind.UPLOADED, reloaded.getProfileImageKind());
        assertEquals("image/png", reloaded.getProfileImageContentType());
        assertNotEquals(dataUri, reloaded.getProfileImageUrl());
        assertFalse(reloaded.getProfileImageUrl().startsWith("data:"));

        byte[] stored = fileStorage.load(reloaded.getProfileImageUrl(), "profile");
        assertArrayEquals(png, stored);
    }

    // ===================== 쟁점 3: 롤백 =====================

    @Test
    @DisplayName("이관 트랜잭션이 롤백되면 DB는 LEGACY_INLINE으로 남고 저장된 파일도 정리된다")
    void run_rollback_keepsLegacyInlineAndCleansUpFile() throws IOException {
        byte[] png = validPngBytes();
        String dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
        Long id = createLegacyInlineMember(dataUri);
        assertOnlyMyRowIsTarget(id);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        String capturedStorageKey = tx.execute(status -> {
            runner.run();
            entityManager.flush();
            // 아직 커밋 전이지만 같은 트랜잭션 안에서는 방금 UPDATE된 값을 그대로 읽을 수 있다.
            String urlInTx = (String) entityManager
                    .createNativeQuery("SELECT profile_image_url FROM member WHERE id = :id")
                    .setParameter("id", id)
                    .getSingleResult();
            status.setRollbackOnly();
            return urlInTx;
        });

        assertNotNull(capturedStorageKey);
        assertFalse(capturedStorageKey.startsWith("data:"),
                "트랜잭션 내부에서는 이관된 storageKey가 커밋 전이어도 캡처되어야 한다");
        createdStorageKeys.add(capturedStorageKey); // deleteOnRollback 실패 시를 대비한 안전망

        Member reloaded = memberRepository.findById(id).orElseThrow();
        assertEquals(ProfileImageKind.LEGACY_INLINE, reloaded.getProfileImageKind(),
                "롤백 후 DB는 이관 전 원래 상태(LEGACY_INLINE)로 남아야 한다");
        assertEquals(dataUri, reloaded.getProfileImageUrl());

        assertThrows(StorageFileNotFoundException.class, () -> fileStorage.load(capturedStorageKey, "profile"),
                "롤백된 이관의 파일은 deleteOnRollback의 afterCompletion 콜백으로 정리되어(존재하지 않아)야 한다 — "
                        + "상위 타입(IllegalStateException)만 검사하면 권한 오류 등 무관한 저장소 실패도 " +
                        "\"정리됨\"으로 오인될 수 있다");
    }

    // ===================== 쟁점 4-A: findByIdForUpdate 락 결정적 증명 =====================

    @Test
    @DisplayName("findByIdForUpdate는 실제로 PESSIMISTIC_WRITE 행 잠금을 획득한다 (락 실증)")
    void findByIdForUpdate_actuallyAcquiresRowLock() throws Exception {
        byte[] png = validPngBytes();
        String dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
        Long id = createLegacyInlineMember(dataUri);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<?> holderFuture = executor.submit(() -> tx.execute(status -> {
                memberRepository.findByIdForUpdate(id);
                lockHeld.countDown();
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));

            assertTrue(lockHeld.await(10, TimeUnit.SECONDS), "트랜잭션 1이 락을 획득해야 한다");

            Throwable thrown = null;
            try {
                tx.executeWithoutResult(status -> {
                    Object original = entityManager
                            .createNativeQuery("SELECT @@innodb_lock_wait_timeout")
                            .getSingleResult();
                    try {
                        entityManager.createNativeQuery("SET SESSION innodb_lock_wait_timeout = 1").executeUpdate();
                        memberRepository.findByIdForUpdate(id);
                    } finally {
                        // 세션 변수 복원 실패는 별도로 격리해 로그만 남기고, 원래 캡처한 thrown 값을
                        // 덮어쓰지 않는다(리뷰 5차 지적 반영).
                        try {
                            entityManager.createNativeQuery(
                                    "SET SESSION innodb_lock_wait_timeout = " + original).executeUpdate();
                        } catch (RuntimeException restoreFailure) {
                            log.warn("innodb_lock_wait_timeout 세션 변수 복원 실패", restoreFailure);
                        }
                    }
                });
            } catch (Throwable t) {
                thrown = t;
            } finally {
                // assertion 실패로 회귀를 검출한 경우에도 트랜잭션 1을 무기한 대기시키지 않는다.
                release.countDown();
            }

            assertNotNull(thrown,
                    "락 보유 중인 행 재조회는 락 대기 타임아웃으로 실패해야 한다 " +
                            "(findByIdForUpdate의 @Lock 선언 누락 회귀 의심)");
            assertTrue(isLockTimeoutException(thrown),
                    "예외가 락 대기 타임아웃(PessimisticLockingFailureException 또는 MariaDB 오류 코드 1205)" +
                            "이어야 무관한 실패(세션 변수 오류 등)와 구분된다: " + thrown);

            holderFuture.get(15, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS), "실행기가 제한 시간 내에 종료되어야 한다");
        }
    }

    private boolean isLockTimeoutException(Throwable thrown) {
        Throwable cause = thrown;
        while (cause != null) {
            if (cause instanceof PessimisticLockingFailureException) {
                return true;
            }
            String message = cause.getMessage();
            if (message != null && (message.contains("1205") || message.toLowerCase().contains("lock wait timeout"))) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    // ===================== 쟁점 4-B: 러너 중복방지 보조검증 =====================

    @Test
    @DisplayName("같은 대상 행에 두 러너가 동시에 실행돼도 정확히 한 번만 이관되고 중복 처리되지 않는다 (보조 검증)")
    void run_concurrentRunners_migratesExactlyOnce() throws Exception {
        byte[] png = validPngBytes();
        String dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
        Long id = createLegacyInlineMember(dataUri);
        assertOnlyMyRowIsTarget(id);

        CyclicBarrier barrier = new CyclicBarrier(2);
        List<String> findByIdForUpdateOutcomes = Collections.synchronizedList(new ArrayList<>());

        // MemberRepository는 Spring Data JPA가 런타임에 생성하는 동적 프록시라, 이를 그대로
        // Mockito.spy()로 감싸면 프록시 클래스 자체를 서브클래싱해야 해 구조적으로 불안정하다
        // (실측: UnfinishedStubbingException 재현 — 리뷰 3·4차가 경고했던 위험이 실제로 발생).
        // 순수 mock() + 세 메서드 전부를 원본 memberRepository로 명시 위임하는 방식으로 전환해
        // 계측 대상(mock)과 실제 위임 대상(원본 빈)을 프록시 문제 없이 분리한다.
        MemberRepository repoMock = mock(MemberRepository.class);
        doAnswer(invocation -> {
            Long targetId = invocation.getArgument(0);
            try {
                barrier.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                findByIdForUpdateOutcomes.add("BARRIER_ERROR:" + e.getClass().getSimpleName());
                throw new IllegalStateException("barrier await 실패", e);
            }
            try {
                Optional<Member> result = memberRepository.findByIdForUpdate(targetId);
                findByIdForUpdateOutcomes.add("OK:" + result.map(m -> m.getProfileImageKind().name()).orElse("EMPTY"));
                return result;
            } catch (RuntimeException e) {
                findByIdForUpdateOutcomes.add("ERROR:" + e.getClass().getSimpleName());
                throw e;
            }
        }).when(repoMock).findByIdForUpdate(anyLong());
        doAnswer(invocation -> memberRepository.findIdsByProfileImageKind(invocation.getArgument(0)))
                .when(repoMock).findIdsByProfileImageKind(any(ProfileImageKind.class));
        doAnswer(invocation -> memberRepository.resetIfOversizedLegacyImage(invocation.getArgument(0), invocation.getArgument(1)))
                .when(repoMock).resetIfOversizedLegacyImage(anyLong(), anyInt());

        // FileStorage는 일반 구체 클래스(LocalDiskFileStorage)라 spy의 callRealMethod()가
        // 표준적으로 안전하다(Spring Data 동적 프록시가 아님).
        FileStorage fileStorageSpy = spy(fileStorage);
        List<String> storedKeys = Collections.synchronizedList(new ArrayList<>());
        doAnswer(invocation -> {
            String key = (String) invocation.callRealMethod();
            storedKeys.add(key);
            // store() 성공 즉시 정리 목록에 등록한다 — 이후 워커의 Future.get()이 타임아웃·예외로
            // 실패해 메서드가 조기 종료돼도(370행 이후 도달 못함) @AfterEach가 파일을 정리할 수 있다.
            createdStorageKeys.add(key);
            return key;
        }).when(fileStorageSpy).store(any(byte[].class), anyString(), eq("profile"));

        ProfileImageMigrationRunner concurrentRunner =
                new ProfileImageMigrationRunner(repoMock, fileStorageSpy, transactionTemplate);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Void>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    concurrentRunner.run();
                    return null;
                }));
            }
            for (Future<Void> f : futures) {
                f.get(20, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS), "실행기가 제한 시간 내에 종료되어야 한다");
        }

        assertEquals(2, findByIdForUpdateOutcomes.size(),
                "두 스레드 모두 실제 findByIdForUpdate 위임 호출까지 도달해야 한다: " + findByIdForUpdateOutcomes);
        assertTrue(findByIdForUpdateOutcomes.stream().noneMatch(o -> o.startsWith("ERROR") || o.startsWith("BARRIER_ERROR")),
                "위임 호출이 예외 없이 두 번 다 정상 완료돼야 한다(러너 내부 catch에 흡수된 실패가 없어야 함): "
                        + findByIdForUpdateOutcomes);

        verify(fileStorageSpy, times(1)).store(any(byte[].class), anyString(), eq("profile"));
        assertEquals(1, storedKeys.size(), "중복 이관 시 발생하는 두 번째 저장이 없어야 한다: " + storedKeys);

        Member reloaded = memberRepository.findById(id).orElseThrow();
        assertEquals(ProfileImageKind.UPLOADED, reloaded.getProfileImageKind());

        byte[] stored = fileStorage.load(storedKeys.get(0), "profile");
        assertArrayEquals(png, stored, "중복 저장이나 손상 없이 원본 바이트와 정확히 일치해야 한다");
    }
}
