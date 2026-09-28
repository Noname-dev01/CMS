package com.cms.admin.log.aspect;

import com.cms.admin.log.annotation.AdminActionLogged;
import com.cms.admin.log.constant.AdminActionTypes;
import com.cms.admin.log.domain.AdminActionLog;
import com.cms.admin.log.domain.AdminActionResult;
import com.cms.admin.log.repository.AdminActionLogRepository;
import com.cms.admin.menu.Menu;
import com.cms.admin.menu.MenuRepository;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code AdminActionLogAspect}의 {@code @Order(LOWEST_PRECEDENCE - 1)}가 실제로
 * "원 트랜잭션 커밋 완료 후에만 SUCCESS/FAIL이 확정"되도록 보장하는지 실 MariaDB로 검증한다
 * (감사 H-03·M-01). Mockito 기반 단위 테스트는 실제 AOP 어드바이저 순서·트랜잭션 커밋 시점을
 * 증명하지 못하므로 실 DB 왕복이 필요하다.
 *
 * <p>테스트 전용 {@link CommitOrderProbeService}는 {@code @Component}가 아니라
 * {@link ProbeConfig}의 {@code @Bean}으로만 등록된다 — {@code @Component} 계열로 선언하면
 * {@code AdminActionTypeSyncTest}의 클래스패스 스캔에 걸려 신규 actionType을
 * {@code AdminActionTypes}에 등록해야 하는 불필요한 프로덕션 코드 변경이 생긴다.
 * 기존 {@code AdminActionTypes.MENU_UPDATE}를 재사용해 이 문제를 피한다.
 *
 * <p>이 테스트는 프로브 전용이며, 실제 프로덕션 서비스(MenuService 등)가 향후 참여 호출로
 * 리팩터링되는 회귀를 감지하지 못한다 — 오늘 기준 감사 대상 4개 서비스의 11개 메서드 전부가
 * 최상위 트랜잭션 진입점이라는 사실(계획 문서 정찰 섹션)이 현재의 유일한 방어선이다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@Import(AdminActionLogCommitOrderIntegrationTest.ProbeConfig.class)
class AdminActionLogCommitOrderIntegrationTest extends MariaDbContainerSupport {

    private static final String PROBE_TARGET_TYPE = "MENU_PROBE";
    private static final String NAME_PREFIX = "commit-order-probe-";

    @TestConfiguration
    static class ProbeConfig {
        @Bean
        CommitOrderProbeService commitOrderProbeService(MenuRepository menuRepository) {
            return new CommitOrderProbeService(menuRepository);
        }
    }

    /** @Component 미부착 — AdminActionTypeSyncTest의 클래스패스 스캔 대상에서 제외된다. */
    static class CommitOrderProbeService {
        private final MenuRepository menuRepository;

        CommitOrderProbeService(MenuRepository menuRepository) {
            this.menuRepository = menuRepository;
        }

        @Transactional
        @AdminActionLogged(actionType = AdminActionTypes.MENU_UPDATE, targetType = PROBE_TARGET_TYPE, targetIdExpression = "menuNo")
        public Menu run(String menuName, boolean failAtCommit) {
            Menu saved = menuRepository.save(Menu.builder()
                    .menuName(menuName)
                    .useYn(true)
                    .ord(0)
                    .createDate(LocalDateTime.now())
                    .build());

            if (failAtCommit) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void beforeCommit(boolean readOnly) {
                        throw new IllegalStateException("simulated commit-time failure: " + menuName);
                    }
                });
            }

            return saved;
        }
    }

    @Autowired
    CommitOrderProbeService probeService;

    @Autowired
    MenuRepository menuRepository;

    @Autowired
    AdminActionLogRepository adminActionLogRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    @AfterEach
    void cleanUpProbeArtifacts() {
        menuRepository.findAll().stream()
                .filter(m -> m.getMenuName() != null && m.getMenuName().startsWith(NAME_PREFIX))
                .forEach(m -> menuRepository.deleteById(m.getMenuNo()));

        List<AdminActionLog> probeLogs = adminActionLogRepository.findAll().stream()
                .filter(log -> PROBE_TARGET_TYPE.equals(log.getTargetType()))
                .toList();
        adminActionLogRepository.deleteAll(probeLogs);
    }

    private List<AdminActionLog> probeLogs() {
        return adminActionLogRepository.findAll().stream()
                .filter(log -> PROBE_TARGET_TYPE.equals(log.getTargetType()))
                .toList();
    }

    private boolean menuPersisted(String menuName) {
        return menuRepository.findAll().stream()
                .anyMatch(m -> menuName.equals(m.getMenuName()));
    }

    @Test
    @DisplayName("최상위 진입점 + 정상 커밋: SUCCESS가 정확히 1건 기록되고 업무 행도 영속된다")
    void topLevelEntry_normalCommit_recordsSuccess() {
        String menuName = NAME_PREFIX + UUID.randomUUID();

        Menu saved = probeService.run(menuName, false);

        assertThat(menuRepository.findById(saved.getMenuNo())).isPresent();

        List<AdminActionLog> logs = probeLogs();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getActionResult()).isEqualTo(AdminActionResult.SUCCESS);
        assertThat(logs.get(0).getTargetId()).isEqualTo(saved.getMenuNo());
    }

    @Test
    @DisplayName("최상위 진입점 + 커밋 직전 실패: FAIL이 정확히 1건 기록되고 SUCCESS는 없으며 업무 행은 롤백된다 " +
            "(감사 H-03·M-01 — @Order 없이는 SUCCESS가 먼저 커밋될 수 있음, 회귀 재현은 구현 기록 참조)")
    void topLevelEntry_commitTimeFailure_recordsFailNotSuccess() {
        String menuName = NAME_PREFIX + UUID.randomUUID();

        assertThrows(RuntimeException.class, () -> probeService.run(menuName, true));

        assertThat(menuPersisted(menuName)).isFalse();

        List<AdminActionLog> logs = probeLogs();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getActionResult()).isEqualTo(AdminActionResult.FAIL);
        assertThat(logs.get(0).getErrorMessage()).contains("simulated commit-time failure");
    }

    @Test
    @DisplayName("알려진 한계 재현 테스트: 참여 트랜잭션에서는 업무 행이 롤백돼도 SUCCESS 감사 행이 남는다 — " +
            "이 계획은 이 경계를 해결하지 않고 고정 기록만 한다. 프로브 전용이며 실제 프로덕션 서비스의 " +
            "참여 호출 리팩터링 회귀는 감지하지 못한다(정찰 섹션 참조)")
    void knownLimitation_participatingTransaction_successSurvivesOuterRollback() {
        String menuName = NAME_PREFIX + UUID.randomUUID();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThrows(RuntimeException.class, () -> tx.execute(status -> {
            probeService.run(menuName, false);
            throw new RuntimeException("외부 트랜잭션 실패");
        }));

        // 업무 행은 외부 트랜잭션 롤백으로 영속되지 않는다.
        assertThat(menuPersisted(menuName)).isFalse();

        // 알려진 한계: 그럼에도 SUCCESS 감사 행은 남는다 — @Order는 이 프록시 안의 순서만 보장하며,
        // 이미 열린 외부 트랜잭션에 참여하는 경우 반환 시점이 물리 커밋과 무관해진다.
        List<AdminActionLog> logs = probeLogs();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getActionResult()).isEqualTo(AdminActionResult.SUCCESS);
    }
}
