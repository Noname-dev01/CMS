package com.cms.admin.menu.service;

import com.cms.admin.menu.Menu;
import com.cms.admin.menu.MenuRepository;
import com.cms.admin.menu.dto.request.MenuUpdateRequest;
import com.cms.support.CmsTestApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 비관적 락(PESSIMISTIC_WRITE)이 실제 트랜잭션에서 부모 row를 잠가 경쟁 조건을 직렬화하는지
 * 검증하는 통합 테스트. Mockito 기반 서비스 테스트는 findByIdForUpdate 호출 여부만 확인할 뿐
 * 실제 직렬화를 증명하지 못하므로(Codex 적대적 리뷰 지적) 실제 MariaDB로 검증한다.
 *
 * 두 스레드가 각자 독립 트랜잭션과 비관적 락을 획득해야 하므로 이 테스트 클래스/메서드에는
 * {@code @Transactional}을 붙이지 않는다. 롤백이 없으므로 생성한 row는 {@link #cleanUp()}에서
 * 수동 삭제한다.
 *
 * 로컬 실행: DB(make dev-db) 기동 + DB_PASS/MAIL_USER/MAIL_PASS 환경변수 설정 필요.
 * CI는 ci.yml에서 자동 주입되어 별도 조치 없이 통과한다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
class MenuConcurrencyIntegrationTest {

    @Autowired
    MenuService menuService;

    @Autowired
    MenuRepository menuRepository;

    private Long parentMenuNo;
    private Long childMenuNo;

    @AfterEach
    void cleanUp() {
        if (childMenuNo != null) {
            menuRepository.deleteById(childMenuNo);
        }
        if (parentMenuNo != null) {
            menuRepository.deleteById(parentMenuNo);
        }
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
}
