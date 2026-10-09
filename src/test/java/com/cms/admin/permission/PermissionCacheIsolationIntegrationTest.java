package com.cms.admin.permission;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.support.CmsTestApplication;
import com.cms.support.TestMembers;
import org.junit.jupiter.api.BeforeEach;
import com.cms.support.MariaDbContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 캐시 로드가 호출자 트랜잭션과 격리되는지 실제 MariaDB(REPEATABLE READ)로 확인한다(PLAN-menu-permission-management.md §5, 리뷰 P-1).
 *
 * <p>시나리오: 호출자 읽기 트랜잭션이 먼저 어떤 조회로 스냅샷을 고정 → 다른 곳에서 권한을 회수하고 커밋·무효화 → 호출자가 <b>같은 트랜잭션
 * 안에서</b> 판정기를 호출. 로드가 호출자 트랜잭션에 참여하면 낡은 스냅샷의 허용값이 읽혀 회수된 권한이 "최신"으로 설치된다.
 * 독립 트랜잭션(REQUIRES_NEW)으로 읽으므로 회수된 값(거부)이 보여야 한다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
class PermissionCacheIsolationIntegrationTest extends MariaDbContainerSupport {

    @Autowired PermissionCache cache;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @Autowired MemberRepository memberRepository;

    private Member manager;
    private long boardId;

    @BeforeEach
    void createManagerWithAllBoardGrants() {
        boardId = jdbc.queryForObject("SELECT id FROM board WHERE board_key = 'NOTICE'", Long.class);
        manager = TestMembers.save(memberRepository, "cache-iso-manager", Role.ROLE_MANAGER);
        for (PermissionAction action : PermissionAction.values()) {
            jdbc.update("INSERT INTO member_board_permission (member_id, board_id, action) VALUES (?, ?, ?)", manager.getId(), boardId, action.name());
        }
        cache.invalidate();
    }

    @AfterEach
    void deleteManager() {
        TestMembers.delete(jdbc, List.of(manager.getId()));
        cache.invalidate();
    }

    @Test
    @DisplayName("호출자 읽기 트랜잭션이 스냅샷을 고정한 뒤 권한이 회수돼도, 같은 트랜잭션 안의 판정은 회수된 값을 본다")
    void loadIsIsolatedFromCallerTransactionSnapshot() {
        cache.invalidate();
        TransactionTemplate caller = new TransactionTemplate(transactionManager);
        caller.setReadOnly(true);

        Boolean deleteAllowedInsideCaller = caller.execute(status -> {
            // ① 호출자 트랜잭션이 member_board_permission 표를 읽어 REPEATABLE READ 스냅샷을 고정한다(사이드바가 메뉴를 먼저 조회하는 경우와 같다)
            Integer rowsAtSnapshot = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM member_board_permission WHERE member_id = ? AND board_id = ?", Integer.class, manager.getId(), boardId);
            assertThat(rowsAtSnapshot).isEqualTo(4);

            // ② 다른 트랜잭션(별도 커넥션)이 DELETE 권한을 회수하고 커밋한 뒤 무효화한다 — 호출자 스냅샷은 그대로 4행
            new TransactionTemplate(transactionManager) {{
                setPropagationBehavior(PROPAGATION_REQUIRES_NEW);
            }}.executeWithoutResult(inner -> jdbc.update(
                    "DELETE FROM member_board_permission WHERE member_id = ? AND board_id = ? AND action = 'DELETE'", manager.getId(), boardId));
            cache.invalidate();

            // ③ 호출자가 같은 트랜잭션 안에서 판정기를 부른다 — 호출자 스냅샷이 아니라 새 스냅샷을 읽어야 한다
            PermissionSnapshot snapshot = cache.snapshot();
            return snapshot.hasBoard(manager.getId(), boardId, PermissionAction.DELETE);
        });

        assertThat(deleteAllowedInsideCaller)
                .as("로드가 호출자 트랜잭션에 참여하면 낡은 스냅샷(4행)이 '최신'으로 설치돼 회수된 DELETE가 여전히 허용된다")
                .isFalse();
        assertThat(cache.snapshot().hasBoard(manager.getId(), boardId, PermissionAction.DELETE))
                .as("설치된 값도 회수 후 상태여야 한다").isFalse();
    }
}
