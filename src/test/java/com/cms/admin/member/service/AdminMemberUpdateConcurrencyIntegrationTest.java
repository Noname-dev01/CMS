package com.cms.admin.member.service;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.dto.request.AdminMemberUpdateRequest;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.common.exception.ConflictException;
import com.cms.config.auth.AdminSessionRevokeEvent;
import com.cms.config.auth.AdminSessionService;
import com.cms.support.CmsTestApplication;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 타 관리자 수정의 동시성 제어를 실제 MariaDB로 검증하는 통합 테스트.
 * Mockito 단위 테스트는 잠금 호출 여부만 확인할 뿐 실제 직렬화를 증명하지 못한다.
 *
 * <p>두 스레드가 독립 트랜잭션과 비관적 락을 획득해야 하므로 {@code @Transactional}을 붙이지
 * 않는다. 생성/변경한 row는 {@link #cleanUp()}에서 수동 복원한다.
 *
 * <p>로컬 실행: DB(make dev-db) 기동 + DB_PASS/MAIL_USER/MAIL_PASS 환경변수 설정 필요.
 * CI는 ci.yml에서 자동 주입된다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
class AdminMemberUpdateConcurrencyIntegrationTest {

    @Autowired
    AdminMemberService adminMemberService;

    @Autowired
    MemberRepository memberRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    ApplicationEventPublisher eventPublisher;

    @MockitoSpyBean
    AdminSessionService adminSessionService;

    @PersistenceContext
    EntityManager entityManager;

    private final List<Long> createdMemberIds = new ArrayList<>();
    private final List<Long> temporarilyDisabledAdminIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        // 실패해도 나머지 복원이 계속되도록 개별 격리 — dev DB의 기존 관리자를 DISABLED로 남기지 않는다
        for (Long id : temporarilyDisabledAdminIds) {
            try {
                memberRepository.findById(id).ifPresent(member -> {
                    member.changeStatus(MemberStatus.ACTIVE);
                    memberRepository.save(member);
                });
            } catch (Exception ignored) {
            }
        }
        for (Long id : createdMemberIds) {
            try {
                memberRepository.deleteById(id);
            } catch (Exception ignored) {
            }
        }
    }

    private Member createMember(String userIdPrefix, Role role, MemberStatus status) {
        LocalDateTime now = LocalDateTime.now();
        String unique = userIdPrefix + "-" + System.nanoTime();
        Member saved = memberRepository.save(Member.builder()
                .userId(unique.substring(0, Math.min(50, unique.length())))
                .pwd("encoded")
                .userName("동시성테스트")
                .email(unique + "@concurrency.test")
                .userType(role)
                .status(status)
                .createDate(now)
                .updateDate(now)
                .passwordChangedAt(now)
                .build());
        createdMemberIds.add(saved.getId());
        return saved;
    }

    private long countActiveAdmins() {
        return memberRepository.findAll().stream()
                .filter(m -> m.getUserType() == Role.ROLE_ADMIN && m.getStatus() == MemberStatus.ACTIVE)
                .count();
    }

    @Test
    @DisplayName("두 활성 ADMIN이 동시에 서로를 잠가도 활성 ADMIN은 최소 1명 유지된다 (최후 활성 ADMIN 가드)")
    void mutualLock_keepsAtLeastOneActiveAdmin() throws Exception {
        // 정확히 2명의 활성 ADMIN만 남긴다 — 기존 활성 ADMIN은 잠시 DISABLED (cleanUp에서 복원)
        memberRepository.findAll().stream()
                .filter(m -> m.getUserType() == Role.ROLE_ADMIN && m.getStatus() == MemberStatus.ACTIVE)
                .forEach(m -> {
                    m.changeStatus(MemberStatus.DISABLED);
                    memberRepository.save(m);
                    temporarilyDisabledAdminIds.add(m.getId());
                });

        Member adminA = createMember("conc-a", Role.ROLE_ADMIN, MemberStatus.ACTIVE);
        Member adminB = createMember("conc-b", Role.ROLE_ADMIN, MemberStatus.ACTIVE);

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicReference<Exception> unexpectedError = new AtomicReference<>();

        Runnable aLocksB = () -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                adminMemberService.updateAdminMember(adminA.getId(), adminB.getId(),
                        AdminMemberUpdateRequest.builder().status(MemberStatus.LOCKED).build());
            } catch (ConflictException | PessimisticLockingFailureException expected) {
                // 가드 409 또는 데드락 감지 롤백 — 정상적인 거부 결과
            } catch (Exception e) {
                unexpectedError.set(e);
            }
        };
        Runnable bLocksA = () -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                adminMemberService.updateAdminMember(adminB.getId(), adminA.getId(),
                        AdminMemberUpdateRequest.builder().status(MemberStatus.LOCKED).build());
            } catch (ConflictException | PessimisticLockingFailureException expected) {
                // 정상적인 거부 결과
            } catch (Exception e) {
                unexpectedError.set(e);
            }
        };

        executor.submit(aLocksB);
        executor.submit(bLocksA);
        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS), "두 스레드가 제한 시간 내에 종료되어야 한다");

        if (unexpectedError.get() != null) {
            throw unexpectedError.get();
        }

        // 단언 대상은 최종 DB 상태 — 응답 코드 조합이 아니라 불변식(활성 ADMIN ≥ 1)
        assertTrue(countActiveAdmins() >= 1,
                "동시 상호 잠금 후에도 활성 ADMIN이 최소 1명 남아야 한다");
    }

    @Test
    @DisplayName("findActiveAdminIdsForUpdate는 실제로 FOR UPDATE 행 잠금을 획득한다 (락 실증)")
    void guardQuery_actuallyAcquiresRowLocks() throws Exception {
        // 잠글 활성 ADMIN 행이 최소 1개 존재하도록 보장
        createMember("lock-proof", Role.ROLE_ADMIN, MemberStatus.ACTIVE);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        executor.submit(() -> tx.execute(status -> {
            memberRepository.findActiveAdminIdsForUpdate();
            lockHeld.countDown();
            try {
                release.await(15, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));

        assertTrue(lockHeld.await(10, TimeUnit.SECONDS), "트랜잭션 1이 락을 획득해야 한다");

        Throwable thrown = null;
        try {
            tx.executeWithoutResult(status -> {
                // 같은 커넥션 세션에만 짧은 락 대기 타임아웃 적용
                entityManager.createNativeQuery("SET SESSION innodb_lock_wait_timeout = 1").executeUpdate();
                memberRepository.findActiveAdminIdsForUpdate();
            });
        } catch (Throwable t) {
            thrown = t;
        } finally {
            release.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS));
        }

        // FOR UPDATE가 발행되지 않으면 트랜잭션 2가 즉시 성공해 thrown == null → 테스트 실패
        assertNotNull(thrown, "락 보유 중인 행 집합 재조회는 락 대기 타임아웃으로 실패해야 한다 (FOR UPDATE 미발행 의심)");
    }

    @Test
    @DisplayName("같은 대상에 대한 email-only PATCH와 잠금 PATCH가 동시 실행돼도 잠금이 되살아나지 않는다 (lost update 방지)")
    void concurrentEmailAndLock_preservesRevocation() throws Exception {
        Member caller = createMember("conc-caller", Role.ROLE_ADMIN, MemberStatus.ACTIVE);
        Member target = createMember("conc-target", Role.ROLE_MANAGER, MemberStatus.ACTIVE);
        String newEmail = "updated-" + System.nanoTime() + "@concurrency.test";

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicReference<Exception> unexpectedError = new AtomicReference<>();

        Runnable emailOnly = () -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                adminMemberService.updateAdminMember(caller.getId(), target.getId(),
                        AdminMemberUpdateRequest.builder().email(newEmail).build());
            } catch (PessimisticLockingFailureException expected) {
                // 락 충돌은 정상 결과
            } catch (Exception e) {
                unexpectedError.set(e);
            }
        };
        Runnable lock = () -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                adminMemberService.updateAdminMember(caller.getId(), target.getId(),
                        AdminMemberUpdateRequest.builder().status(MemberStatus.LOCKED).build());
            } catch (PessimisticLockingFailureException expected) {
                // 락 충돌은 정상 결과
            } catch (Exception e) {
                unexpectedError.set(e);
            }
        };

        executor.submit(emailOnly);
        executor.submit(lock);
        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));

        if (unexpectedError.get() != null) {
            throw unexpectedError.get();
        }

        Member finalTarget = memberRepository.findById(target.getId()).orElseThrow();
        // 핵심 불변식: 잠금(권한 회수)이 email-only 트랜잭션의 낡은 값으로 되살아나지 않는다
        assertEquals(MemberStatus.LOCKED, finalTarget.getStatus(),
                "행 잠금 직렬화로 잠금 결과가 보존되어야 한다 (lost update 발생 의심)");
        // 세션 만료 이벤트도 유실되지 않았다 (AFTER_COMMIT 리스너 호출 확인)
        verify(adminSessionService, atLeastOnce()).expireSessionsFor(target.getId());
    }

    @Test
    @DisplayName("이벤트는 커밋 후에만 소비된다 — 커밋 전·롤백 시 세션 만료 미실행")
    void revokeEvent_consumedOnlyAfterCommit() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        // 롤백 시 미실행
        tx.executeWithoutResult(status -> {
            eventPublisher.publishEvent(new AdminSessionRevokeEvent(999_999L));
            status.setRollbackOnly();
        });
        verify(adminSessionService, never()).expireSessionsFor(anyLong());

        // 커밋 전 미실행, 커밋 후 실행
        tx.executeWithoutResult(status -> {
            eventPublisher.publishEvent(new AdminSessionRevokeEvent(999_999L));
            verify(adminSessionService, never()).expireSessionsFor(anyLong());
        });
        verify(adminSessionService).expireSessionsFor(999_999L);
    }
}
