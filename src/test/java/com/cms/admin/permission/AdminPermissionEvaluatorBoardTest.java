package com.cms.admin.permission;

import com.cms.admin.member.domain.Role;
import com.cms.config.auth.CustomUserDetails;
import com.cms.support.TestMembers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 게시판 단위 판정(PLAN-board.md 쟁점 2·3)의 진리표 — 기능 단위(BOARD_SCOPED) 판정과 게시판별 판정을 함께 고정한다. */
class AdminPermissionEvaluatorBoardTest {

    private static final long MEMBER_ID = 1L;
    private static final long OTHER_MEMBER_ID = 2L;
    private static final long BOARD_A = 10L;
    private static final long BOARD_B = 20L;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static PermissionSnapshot boardGrants(long memberId, long boardId, PermissionAction... actions) {
        Set<PermissionSnapshot.BoardGrant> set = new HashSet<>();
        for (PermissionAction action : actions) {
            set.add(new PermissionSnapshot.BoardGrant(memberId, boardId, action));
        }
        return new PermissionSnapshot(Set.of(), set);
    }

    private static AdminPermissionEvaluator evaluatorWith(PermissionSnapshot snapshot) {
        PermissionCache cache = mock(PermissionCache.class);
        when(cache.snapshot()).thenReturn(snapshot);
        return new AdminPermissionEvaluator(cache);
    }

    private static Authentication user(long id, Role role) {
        CustomUserDetails details = TestMembers.detached(id, role);
        return new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());
    }

    @Test
    @DisplayName("ADMIN은 모든 게시판·동작이 허용되고 캐시를 조회하지 않는다(게시판 존재 여부도 보지 않는다)")
    void adminAllowedWithoutCache() {
        PermissionCache cache = mock(PermissionCache.class);
        AdminPermissionEvaluator evaluator = new AdminPermissionEvaluator(cache);

        for (PermissionAction action : PermissionAction.values()) {
            assertThat(evaluator.allowsBoard(user(MEMBER_ID, Role.ROLE_ADMIN), 999L, action)).as(action.name()).isTrue();
        }
        verifyNoInteractions(cache);
    }

    @Test
    @DisplayName("MANAGER: 게시판 A 권한은 A에만 적용되고 B·다른 회원에게는 적용되지 않는다")
    void managerGrantIsBoardAndMemberScoped() {
        AdminPermissionEvaluator evaluator = evaluatorWith(boardGrants(MEMBER_ID, BOARD_A, PermissionAction.values()));

        for (PermissionAction action : PermissionAction.values()) {
            assertThat(evaluator.allowsBoard(user(MEMBER_ID, Role.ROLE_MANAGER), BOARD_A, action)).as("A " + action).isTrue();
            assertThat(evaluator.allowsBoard(user(MEMBER_ID, Role.ROLE_MANAGER), BOARD_B, action)).as("B " + action).isFalse();
            assertThat(evaluator.allowsBoard(user(OTHER_MEMBER_ID, Role.ROLE_MANAGER), BOARD_A, action)).as("다른 회원 " + action).isFalse();
        }
    }

    @Test
    @DisplayName("MANAGER: 쓰기 동작은 같은 게시판의 READ가 있어야 유효하다(의존 규칙)")
    void writeRequiresReadOnSameBoard() {
        Set<PermissionSnapshot.BoardGrant> grants = new HashSet<>();
        grants.add(new PermissionSnapshot.BoardGrant(MEMBER_ID, BOARD_A, PermissionAction.CREATE));       // A: READ 없는 쓰기
        grants.add(new PermissionSnapshot.BoardGrant(MEMBER_ID, BOARD_B, PermissionAction.READ));         // B: READ만
        AdminPermissionEvaluator evaluator = evaluatorWith(new PermissionSnapshot(Set.of(), grants));
        Authentication manager = user(MEMBER_ID, Role.ROLE_MANAGER);

        assertThat(evaluator.allowsBoard(manager, BOARD_A, PermissionAction.CREATE)).isFalse();
        assertThat(evaluator.allowsBoard(manager, BOARD_A, PermissionAction.READ)).isFalse();
        assertThat(evaluator.allowsBoard(manager, BOARD_B, PermissionAction.CREATE)).as("B의 READ가 A의 쓰기를 살리지 않는다").isFalse();
        assertThat(evaluator.allowsBoard(manager, BOARD_B, PermissionAction.READ)).isTrue();
    }

    @Test
    @DisplayName("기능 단위 BOARD 판정은 '어느 게시판이든 유효한 동작'이고, 게시판 권한이 없으면 거부된다")
    void featureLevelBoardIsAnyBoard() {
        AdminPermissionEvaluator withRead = evaluatorWith(boardGrants(MEMBER_ID, BOARD_A, PermissionAction.READ));
        AdminPermissionEvaluator none = evaluatorWith(PermissionSnapshot.EMPTY);
        Authentication manager = user(MEMBER_ID, Role.ROLE_MANAGER);

        assertThat(withRead.allows(manager, AdminFeature.BOARD, PermissionAction.READ)).isTrue();
        assertThat(withRead.allows(manager, AdminFeature.BOARD, PermissionAction.CREATE)).isFalse();
        assertThat(none.allows(manager, AdminFeature.BOARD, PermissionAction.READ)).isFalse();
        assertThat(withRead.allows(user(MEMBER_ID, Role.ROLE_ADMIN), AdminFeature.BOARD, PermissionAction.DELETE)).isTrue();
    }

    @Test
    @DisplayName("기능 단위 member_permission 행(NOTICE)은 게시판 판정에 영향이 없다 — 반대도 마찬가지")
    void featureAndBoardGrantsAreSeparate() {
        PermissionSnapshot onlyNotice = new PermissionSnapshot(
                Set.of(new PermissionSnapshot.Grant(MEMBER_ID, AdminFeature.NOTICE, PermissionAction.READ)), Set.of());
        AdminPermissionEvaluator noticeOnly = evaluatorWith(onlyNotice);
        AdminPermissionEvaluator boardOnly = evaluatorWith(boardGrants(MEMBER_ID, BOARD_A, PermissionAction.READ));
        Authentication manager = user(MEMBER_ID, Role.ROLE_MANAGER);

        assertThat(noticeOnly.allowsBoard(manager, BOARD_A, PermissionAction.READ)).isFalse();
        assertThat(noticeOnly.allows(manager, AdminFeature.BOARD, PermissionAction.READ)).isFalse();
        assertThat(boardOnly.allows(manager, AdminFeature.NOTICE, PermissionAction.READ)).isFalse();
    }

    @Test
    @DisplayName("식별 불가 주체·익명·null 게시판 ID·지원하지 않는 동작은 거부(fail-closed)")
    void failClosedInputs() {
        AdminPermissionEvaluator evaluator = evaluatorWith(boardGrants(MEMBER_ID, BOARD_A, PermissionAction.values()));
        Authentication noMemberId = new UsernamePasswordAuthenticationToken("manager", null,
                AuthorityUtils.createAuthorityList("ROLE_MANAGER"));

        assertThat(evaluator.allowsBoard(noMemberId, BOARD_A, PermissionAction.READ)).isFalse();
        assertThat(evaluator.allowsBoard(null, BOARD_A, PermissionAction.READ)).isFalse();
        assertThat(evaluator.allowsBoard(user(MEMBER_ID, Role.ROLE_MANAGER), null, PermissionAction.READ)).isFalse();
        assertThat(evaluator.allowsBoard(user(MEMBER_ID, Role.ROLE_USER), BOARD_A, PermissionAction.READ)).isFalse();
    }

    @Test
    @DisplayName("checkBoard(SpEL 진입점): null 게시판 ID·알 수 없는 동작 이름은 false, 정상 값은 현재 인증으로 판정")
    void checkBoardParsesAndFailsClosed() {
        AdminPermissionEvaluator evaluator = evaluatorWith(boardGrants(MEMBER_ID, BOARD_A, PermissionAction.READ));
        SecurityContextHolder.getContext().setAuthentication(user(MEMBER_ID, Role.ROLE_MANAGER));

        assertThat(evaluator.checkBoard(BOARD_A, "READ")).isTrue();
        assertThat(evaluator.checkBoard(BOARD_B, "READ")).isFalse();
        assertThat(evaluator.checkBoard(null, "READ")).as("경로 변수 이름을 못 읽으면 null").isFalse();
        assertThat(evaluator.checkBoard(BOARD_A, "read")).isFalse();
        assertThat(evaluator.checkBoard(BOARD_A, null)).isFalse();
    }

    @Test
    @DisplayName("boardActions: ADMIN은 전 동작, MANAGER는 의존 규칙을 적용한 허용 동작, 권한 없으면 빈 집합")
    void boardActions() {
        AdminPermissionEvaluator evaluator = evaluatorWith(
                boardGrants(MEMBER_ID, BOARD_A, PermissionAction.READ, PermissionAction.UPDATE));

        assertThat(evaluator.boardActions(evaluator::snapshot, user(MEMBER_ID, Role.ROLE_ADMIN), BOARD_B))
                .containsExactly(PermissionAction.values());
        assertThat(evaluator.boardActions(evaluator::snapshot, user(MEMBER_ID, Role.ROLE_MANAGER), BOARD_A))
                .containsExactly(PermissionAction.READ, PermissionAction.UPDATE);
        assertThat(evaluator.boardActions(evaluator::snapshot, user(MEMBER_ID, Role.ROLE_MANAGER), BOARD_B)).isEmpty();
    }
}
