package com.cms.admin.menu.service;

import com.cms.admin.menu.Menu;
import com.cms.admin.menu.MenuRepository;
import com.cms.admin.menu.dto.request.MenuUpdateRequest;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 비관적 락(PESSIMISTIC_WRITE)이 실제 트랜잭션에서 부모 row를 잠가 경쟁 조건을 직렬화하는지
 * 검증하는 통합 테스트. Mockito 기반 서비스 테스트는 findByIdForUpdate 호출 여부만 확인할 뿐
 * 실제 직렬화를 증명하지 못하므로 실제 MariaDB로 검증한다.
 *
 * 두 스레드가 각자 독립 트랜잭션과 비관적 락을 획득해야 하므로 이 테스트 클래스/메서드에는
 * {@code @Transactional}을 붙이지 않는다. 롤백이 없으므로 생성한 row는 {@link #cleanUp()}에서
 * 수동 삭제한다.
 *
 * Testcontainers가 띄우는 일회용 MariaDB로 실행된다 — 로컬 DB 기동·환경변수 주입 불필요,
 * Docker만 있으면 된다({@link MariaDbContainerSupport}).
 */
@SpringBootTest(classes = CmsTestApplication.class)
class MenuConcurrencyIntegrationTest extends MariaDbContainerSupport {

    @Autowired
    MenuService menuService;

    @Autowired
    MenuRepository menuRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @PersistenceContext
    EntityManager entityManager;

    private Long parentMenuNo;
    private Long childMenuNo;
    private final List<Long> extraMenuIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        if (childMenuNo != null) {
            menuRepository.deleteById(childMenuNo);
        }
        if (parentMenuNo != null) {
            menuRepository.deleteById(parentMenuNo);
        }
        for (Long menuNo : extraMenuIds) {
            try {
                menuRepository.deleteById(menuNo);
            } catch (Exception ignored) {
            }
        }
    }

    private Menu createMenu(String name) {
        LocalDateTime now = LocalDateTime.now();
        Menu saved = menuRepository.save(Menu.builder()
                .menuName(name)
                .useYn(true)
                .ord(0)
                .createDate(now)
                .updateDate(now)
                .build());
        extraMenuIds.add(saved.getMenuNo());
        return saved;
    }

    @Test
    void concurrentParentDeactivateAndChildReactivate_neverLeavesActiveChildUnderInactiveParent() throws Exception {
        LocalDateTime now = LocalDateTime.now();

        Menu parent = menuRepository.save(Menu.builder()
                .menuName("동시성 테스트 부모")
                .useYn(true)
                .ord(0)
                .createDate(now)
                .updateDate(now)
                .build());
        parentMenuNo = parent.getMenuNo();

        Menu child = menuRepository.save(Menu.builder()
                .menuName("동시성 테스트 자식")
                .useYn(false)
                .ord(0)
                .upMenuNo(parentMenuNo)
                .createDate(now)
                .updateDate(now)
                .build());
        childMenuNo = child.getMenuNo();

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicReference<Exception> unexpectedError = new AtomicReference<>();

        Runnable deactivateParent = () -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                menuService.deactivateMenu(parentMenuNo);
            } catch (Exception e) {
                // ConflictException(409)은 정상적인 거부 결과이므로 무시하고, 그 외 예기치 못한
                // 예외만 기록해 최종 검증 실패로 드러낸다.
                if (!e.getClass().getSimpleName().equals("ConflictException")) {
                    unexpectedError.set(e);
                }
            }
        };

        Runnable reactivateChild = () -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                menuService.updateMenu(childMenuNo, MenuUpdateRequest.builder().useYn(true).build());
            } catch (Exception e) {
                if (!e.getClass().getSimpleName().equals("InvalidRequestException")) {
                    unexpectedError.set(e);
                }
            }
        };

        executor.submit(deactivateParent);
        executor.submit(reactivateChild);
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "두 스레드가 제한 시간 내에 종료되어야 한다");

        if (unexpectedError.get() != null) {
            throw unexpectedError.get();
        }

        Menu finalParent = menuRepository.findById(parentMenuNo).orElseThrow();
        Menu finalChild = menuRepository.findById(childMenuNo).orElseThrow();

        boolean invariantViolated = !finalParent.getUseYn() && finalChild.getUseYn();
        assertFalse(invariantViolated,
                "비활성 부모 아래 활성 자식이 존재해서는 안 된다. parent.useYn=" + finalParent.getUseYn()
                        + ", child.useYn=" + finalChild.getUseYn());
    }

    /**
     * 감사 M-04(같은 행 쓰기의 비관적 잠금 일관화) 보조 검증 — 잠금 프리미티브 실증.
     * 수정 후 일반 수정(useYn 미포함)이 실제로 사용하는 잠금 조회({@code findByIdForUpdate})를
     * 트랜잭션 1에서 직접 호출해, 이 잠금이 실제로 동시 {@code deactivateMenu()}를 락 대기
     * 타임아웃으로 막는지 확인한다. {@code deactivateMenu()}는 수정 전부터 이미 같은 잠금
     * 조회를 썼으므로 이 테스트 자체는 수정 전/후 모두 통과한다 — "일반 수정이 이제 이 잠금을
     * 획득한다"는 사실은 {@link com.cms.admin.menu.service.MenuServiceTest}의 stub 검증이
     * 담당하고, "잠금이 실제로 경합을 막는다"는 이 테스트가 담당한다. lost update 자체의
     * 회귀 재현·수정 증명은 아래 {@code concurrentGeneralEditAndDeactivate_preservesDeactivation()}가
     * 담당한다(수정 전 코드에서 실제로 실패함을 확인했다).
     *
     * <p>{@code AdminMemberUpdateConcurrencyIntegrationTest}의 {@code guardQuery_actuallyAcquiresRowLocks}와
     * 동일한 락 실증 기법(세션별 {@code innodb_lock_wait_timeout} 단축)을 사용한다.
     */
    @Test
    @DisplayName("동시성 방어(락 실증): 일반 수정이 쓰는 잠금 조회는 실제로 동시 비활성화를 락 대기 타임아웃으로 막는다")
    void generalEditHoldsLock_blocksConcurrentDeactivate() throws Exception {
        Menu menu = createMenu("잠금 실증 대상");
        Long menuNo = menu.getMenuNo();

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        executor.submit(() -> tx.execute(status -> {
            // 일반 수정이 실제로 사용하는 것과 동일한 잠금 조회(findByIdForUpdate)로
            // 트랜잭션 1이 대상 행을 잠근 채 대기한다.
            menuRepository.findByIdForUpdate(menuNo);
            lockHeld.countDown();
            try {
                release.await(15, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));

        assertTrue(lockHeld.await(10, TimeUnit.SECONDS), "일반 수정 트랜잭션이 락을 획득해야 한다");

        Throwable thrown = null;
        try {
            tx.executeWithoutResult(status -> {
                // 같은 커넥션 세션에만 짧은 락 대기 타임아웃을 적용해 대기 여부를 빠르게 판별한다.
                // HikariCP는 세션 변수를 초기화하지 않고 커넥션을 풀에 반환하므로, 같은 물리
                // 커넥션이 반환되기 전에 반드시 원래 값으로 복원한다 — 그러지 않으면 이 커넥션을
                // 재사용하는 다른 테스트가 의도치 않게 1초 잠금 대기로 실행된다.
                Number original = (Number) entityManager
                        .createNativeQuery("SELECT @@session.innodb_lock_wait_timeout").getSingleResult();
                try {
                    entityManager.createNativeQuery("SET SESSION innodb_lock_wait_timeout = 1").executeUpdate();
                    menuService.deactivateMenu(menuNo);
                } finally {
                    entityManager.createNativeQuery("SET SESSION innodb_lock_wait_timeout = " + original)
                            .executeUpdate();
                }
            });
        } catch (Throwable t) {
            thrown = t;
        } finally {
            release.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS));
        }

        assertNotNull(thrown,
                "findByIdForUpdate로 잠긴 행에 대한 동시 deactivateMenu()는 락 대기 타임아웃으로 실패해야 한다"
                        + "(FOR UPDATE 미발행 의심)");
    }

    /**
     * 감사 M-04 회귀. 일반 수정(useYn 미포함)과 비활성화를 실제 barrier로 동시 제출한다.
     * 수정 전에는 일반 수정이 낡은 값을 그대로 재반영해 어느 스레드가 먼저 끝나느냐에 따라
     * 비활성화가 되돌아갈 수 있었다. 수정 후에는 두 경로가 같은 행 잠금으로 직렬화되므로,
     * 실행 순서와 무관하게 최종 상태는 항상 "비활성화 유지 + 새 이름 반영"이어야 한다.
     *
     * <p>두 스레드가 잠그는 대상은 하나뿐이고 잠금 순서도 같으므로(둘 다 이 menuNo 하나만
     * lock) 실제 데드락은 발생할 수 없다 — {@code PessimisticLockingFailureException}을
     * "정상 결과"로 흡수하지 않는다. 그렇게 흡수하면 일반 수정이 조용히 실패해도(이름이
     * 반영되지 않아도) 이 테스트가 통과해버려, 정작 지키려는 계약("둘 다 성공해야 한다")을
     * 검증하지 못한다(계획 리뷰에서 지적, v1 수정).
     */
    @Test
    @DisplayName("동시성 방어(lost update 없음): 일반 수정과 비활성화가 동시 실행돼도 둘 다 반영된다")
    void concurrentGeneralEditAndDeactivate_preservesDeactivation() throws Exception {
        Menu menu = createMenu("동시성 테스트 대상");
        Long menuNo = menu.getMenuNo();

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicReference<Exception> unexpectedError = new AtomicReference<>();

        Runnable generalEdit = () -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                menuService.updateMenu(menuNo, MenuUpdateRequest.builder().menuName("변경된 이름").build());
            } catch (Exception e) {
                unexpectedError.set(e);
            }
        };
        Runnable deactivate = () -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                menuService.deactivateMenu(menuNo);
            } catch (Exception e) {
                unexpectedError.set(e);
            }
        };

        executor.submit(generalEdit);
        executor.submit(deactivate);
        executor.shutdown();
        assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS), "두 스레드가 제한 시간 내에 종료되어야 한다");

        if (unexpectedError.get() != null) {
            throw unexpectedError.get();
        }

        Menu finalMenu = menuRepository.findById(menuNo).orElseThrow();
        // 핵심 불변식: 비활성화 결과가 일반 수정의 낡은 useYn=true로 되살아나지 않고,
        // 일반 수정의 이름 변경도 조용히 유실되지 않는다 — 둘 다 성공해야 한다.
        assertFalse(finalMenu.getUseYn(),
                "행 잠금 직렬화로 비활성화 결과가 보존되어야 한다 (lost update 발생 의심). "
                        + "최종 useYn=" + finalMenu.getUseYn() + ", menuName=" + finalMenu.getMenuName());
        assertEquals("변경된 이름", finalMenu.getMenuName(),
                "일반 수정의 이름 변경도 함께 보존되어야 한다 (조용한 실패 의심). "
                        + "최종 useYn=" + finalMenu.getUseYn() + ", menuName=" + finalMenu.getMenuName());
    }
}
