package com.cms.admin.permission;

import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 캐시 로드가 호출자 트랜잭션과 격리되는지 실제 MariaDB(REPEATABLE READ)로 확인한다(PLAN-menu-permission-management.md §5, 리뷰 P-1).
 *
 * <p>시나리오: 호출자 읽기 트랜잭션이 먼저 어떤 조회로 스냅샷을 고정 → 다른 곳에서 권한을 회수하고 커밋·무효화 → 호출자가 <b>같은 트랜잭션
 * 안에서</b> 판정기를 호출. 로드가 호출자 트랜잭션에 참여하면 낡은 스냅샷의 허용값이 읽혀 회수된 권한이 "최신"으로 설치된다.
 * 독립 트랜잭션(REQUIRES_NEW)으로 읽으므로 회수된 값(거부)이 보여야 한다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
class RolePermissionCacheIsolationIntegrationTest extends MariaDbContainerSupport {

    @Autowired RolePermissionCache cache;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @AfterEach
    void restoreSeed() {
        jdbc.update("DELETE FROM role_permission WHERE role = 'ROLE_MANAGER' AND feature = 'NOTICE'");
        for (PermissionAction action : PermissionAction.values()) {
            jdbc.update("INSERT INTO role_permission (role, feature, action) VALUES ('ROLE_MANAGER', 'NOTICE', ?)", action.name());
        }
        cache.invalidate();
    }

    @Test
    @DisplayName("호출자 읽기 트랜잭션이 스냅샷을 고정한 뒤 권한이 회수돼도, 같은 트랜잭션 안의 판정은 회수된 값을 본다")
    void loadIsIsolatedFromCallerTransactionSnapshot() {
        cache.invalidate();
        TransactionTemplate caller = new TransactionTemplate(transactionManager);
        caller.setReadOnly(true);

        Boolean deleteAllowedInsideCaller = caller.execute(status -> {
            // ① 호출자 트랜잭션이 permission 표를 읽어 REPEATABLE READ 스냅샷을 고정한다(사이드바가 메뉴를 먼저 조회하는 경우와 같다)
            Integer rowsAtSnapshot = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM role_permission WHERE role = 'ROLE_MANAGER' AND feature = 'NOTICE'", Integer.class);
            assertThat(rowsAtSnapshot).isEqualTo(4);

            // ② 다른 트랜잭션(별도 커넥션)이 DELETE 권한을 회수하고 커밋한 뒤 무효화한다 — 호출자 스냅샷은 그대로 4행
            new TransactionTemplate(transactionManager) {{
                setPropagationBehavior(PROPAGATION_REQUIRES_NEW);
            }}.executeWithoutResult(inner -> jdbc.update(
                    "DELETE FROM role_permission WHERE role = 'ROLE_MANAGER' AND feature = 'NOTICE' AND action = 'DELETE'"));
            cache.invalidate();

            // ③ 호출자가 같은 트랜잭션 안에서 판정기를 부른다 — 호출자 스냅샷이 아니라 새 스냅샷을 읽어야 한다
            PermissionSnapshot snapshot = cache.snapshot();
            return snapshot.has("ROLE_MANAGER", AdminFeature.NOTICE, PermissionAction.DELETE);
        });

        assertThat(deleteAllowedInsideCaller)
                .as("로드가 호출자 트랜잭션에 참여하면 낡은 스냅샷(4행)이 '최신'으로 설치돼 회수된 DELETE가 여전히 허용된다")
                .isFalse();
        assertThat(cache.snapshot().has("ROLE_MANAGER", AdminFeature.NOTICE, PermissionAction.DELETE))
                .as("설치된 값도 회수 후 상태여야 한다").isFalse();
    }
}
