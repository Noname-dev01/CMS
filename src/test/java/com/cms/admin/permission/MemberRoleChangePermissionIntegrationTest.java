package com.cms.admin.permission;

import com.cms.admin.log.constant.AdminActionTypes;
import com.cms.admin.log.domain.AdminActionLog;
import com.cms.admin.log.repository.AdminActionLogRepository;
import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.dto.request.AdminMemberUpdateRequest;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.member.service.AdminMemberService;
import com.cms.admin.permission.dto.request.MemberPermissionUpdateRequest;
import com.cms.admin.permission.service.MemberPermissionService;
import com.cms.common.exception.ConflictException;
import com.cms.config.auth.CustomUserDetails;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.TestMembers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 역할 변경과 개별 권한(PLAN-member-permission.md §5-E, §7-1 ⑥): 역할이 바뀌면 그 회원의 개별 허용 행이 같은 트랜잭션에서 삭제되고, 삭제 건수와
 * 무관하게 버전이 오르며(권한 0개 회원이 역할을 왕복해도 오래된 화면의 PUT은 409), 행이 지워졌을 때만 캐시가 무효화된다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class MemberRoleChangePermissionIntegrationTest extends MariaDbContainerSupport {

    private static final long CURRENT_ADMIN_ID = -1L;

    @Autowired AdminMemberService adminMemberService;
    @Autowired MemberPermissionService permissionService;
    @Autowired MemberRepository memberRepository;
    @Autowired AdminActionLogRepository auditRepository;
    @Autowired AdminPermissionEvaluator evaluator;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mockMvc;
    @MockitoSpyBean PermissionCache cache;

    private Member target;
    private long auditBaseline;
    private final List<Long> createdIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        target = newManager("rolechg");
        auditBaseline = auditRepository.findAll().stream().mapToLong(AdminActionLog::getId).max().orElse(0L);
    }

    @AfterEach
    void cleanUp() {
        auditRepository.findAll().stream()
                .filter(log -> log.getId() > auditBaseline
                        && (AdminActionTypes.PERMISSION_UPDATE.equals(log.getActionType())
                        || AdminActionTypes.ADMIN_UPDATE.equals(log.getActionType())))
                .forEach(auditRepository::delete);
        TestMembers.delete(jdbc, createdIds);
        cache.invalidate();
    }

    private Member newManager(String prefix) {
        Member saved = TestMembers.save(memberRepository, prefix, Role.ROLE_MANAGER);
        createdIds.add(saved.getId());
        return saved;
    }

    private void grantAllNotice(long memberId) {
        for (PermissionAction action : PermissionAction.values()) {
            jdbc.update("INSERT INTO member_permission (member_id, feature, action) VALUES (?, 'NOTICE', ?)", memberId, action.name());
        }
        cache.invalidate();
        clearInvocations(cache);
    }

    private long version(long memberId) {
        return jdbc.queryForObject("SELECT permission_version FROM member WHERE id = ?", Long.class, memberId);
    }

    private Set<String> actions(long memberId) {
        return new TreeSet<>(jdbc.queryForList("SELECT action FROM member_permission WHERE member_id = ?", String.class, memberId));
    }

    private void changeRole(long memberId, Role role) {
        adminMemberService.updateAdminMember(CURRENT_ADMIN_ID, memberId, AdminMemberUpdateRequest.builder().userType(role).build());
    }

    private boolean canReadNotice(Member member) throws Exception {
        Member fresh = memberRepository.findById(member.getId()).orElseThrow();
        CustomUserDetails details = new CustomUserDetails(fresh);
        return evaluator.allows(new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()),
                AdminFeature.NOTICE, PermissionAction.READ);
    }

    @Test
    @DisplayName("게시판별 권한도 역할 변경 때 함께 삭제되고(게시판 권한만 있어도 캐시 무효화) 재강등해도 부활하지 않는다(PLAN-board.md 쟁점 5)")
    void roleChange_deletesBoardPermissionsToo() {
        jdbc.update("INSERT INTO board (name, public_yn, attachment_yn, deleted) VALUES ('rolechg-board', 1, 1, 0)");
        long boardId = jdbc.queryForObject("SELECT MAX(id) FROM board", Long.class);
        try {
            jdbc.update("INSERT INTO member_board_permission (member_id, board_id, action) VALUES (?, ?, 'READ')", target.getId(), boardId);
            jdbc.update("INSERT INTO member_board_permission (member_id, board_id, action) VALUES (?, ?, 'UPDATE')", target.getId(), boardId);
            cache.invalidate();
            clearInvocations(cache);
            long before = version(target.getId());

            changeRole(target.getId(), Role.ROLE_ADMIN);
            changeRole(target.getId(), Role.ROLE_MANAGER);

            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member_board_permission WHERE member_id = ?", Long.class, target.getId()))
                    .isZero();
            assertThat(version(target.getId())).isEqualTo(before + 2);
            verify(cache, org.mockito.Mockito.atLeastOnce()).invalidate();
            Member fresh = memberRepository.findById(target.getId()).orElseThrow();
            CustomUserDetails details = new CustomUserDetails(fresh);
            assertThat(evaluator.allowsBoard(new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()),
                    boardId, PermissionAction.READ)).isFalse();
        } finally {
            jdbc.update("DELETE FROM member_board_permission WHERE board_id = ?", boardId);
            jdbc.update("DELETE FROM board WHERE id = ?", boardId);
        }
    }

    @Test
    @DisplayName("MANAGER → ADMIN: 개별 허용 행이 전부 삭제되고 버전이 +1, 캐시가 무효화되며 다시 MANAGER로 강등하면 공지 권한이 부활하지 않는다")
    void promotion_deletesRows_andDemotionDoesNotRestore() throws Exception {
        grantAllNotice(target.getId());
        long before = version(target.getId());
        assertThat(canReadNotice(target)).isTrue();

        changeRole(target.getId(), Role.ROLE_ADMIN);

        assertThat(actions(target.getId())).as("역할 변경이 개별 권한을 지웠다").isEmpty();
        assertThat(version(target.getId())).isEqualTo(before + 1);
        verify(cache, org.mockito.Mockito.atLeastOnce()).invalidate(); // 커밋 성공 경로 — AFTER_COMMIT·AFTER_COMPLETION 이중 폐기는 의도(멱등)

        changeRole(target.getId(), Role.ROLE_MANAGER);

        assertThat(version(target.getId())).as("강등도 역할 변경이라 버전이 오른다").isEqualTo(before + 2);
        assertThat(actions(target.getId())).as("예전 권한이 조용히 되살아나지 않는다").isEmpty();
        assertThat(canReadNotice(target)).isFalse();
        // 실제 요청 경로도 거부: URL 게이트(공지 READ) 403
        mockMvc.perform(get("/admin/api/notices").with(TestMembers.asMember(memberRepository.findById(target.getId()).orElseThrow())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("허용 행이 0개인 회원도 역할이 바뀔 때마다 버전이 오른다(무효화 이벤트는 없음) — MANAGER→ADMIN→MANAGER 왕복 뒤 왕복 이전 버전의 PUT은 409")
    void roundTripWithoutRows_bumpsVersion_staleReplaceConflicts() {
        long before = version(target.getId());
        assertThat(actions(target.getId())).isEmpty();

        changeRole(target.getId(), Role.ROLE_ADMIN);
        changeRole(target.getId(), Role.ROLE_MANAGER);

        assertThat(version(target.getId())).isEqualTo(before + 2);
        verify(cache, never()).invalidate(); // 지워진 행이 없어 캐시 무효화는 필요 없다

        MemberPermissionUpdateRequest stale = MemberPermissionUpdateRequest.builder().boardGrants(java.util.List.of()).version(before)
                .grants(List.of(new MemberPermissionUpdateRequest.Grant(AdminFeature.NOTICE, PermissionAction.READ))).build();
        assertThatThrownBy(() -> permissionService.replace(target.getId(), stale)).isInstanceOf(ConflictException.class);
        assertThat(actions(target.getId())).as("오래된 화면의 저장이 권한을 부여하지 못했다").isEmpty();
    }

    @Test
    @DisplayName("역할이 바뀌지 않는 수정(이름·상태)은 개별 권한 행과 버전을 건드리지 않는다")
    void nonRoleChanges_keepRowsAndVersion() {
        grantAllNotice(target.getId());
        long before = version(target.getId());

        adminMemberService.updateAdminMember(CURRENT_ADMIN_ID, target.getId(),
                AdminMemberUpdateRequest.builder().userName("새 이름").build());
        adminMemberService.updateAdminMember(CURRENT_ADMIN_ID, target.getId(),
                AdminMemberUpdateRequest.builder().status(MemberStatus.LOCKED).build());
        adminMemberService.updateAdminMember(CURRENT_ADMIN_ID, target.getId(),
                AdminMemberUpdateRequest.builder().status(MemberStatus.ACTIVE).build());
        // 같은 역할로 재저장도 역할 변경이 아니다
        adminMemberService.updateAdminMember(CURRENT_ADMIN_ID, target.getId(),
                AdminMemberUpdateRequest.builder().userType(Role.ROLE_MANAGER).build());

        assertThat(actions(target.getId())).containsExactlyInAnyOrder("READ", "CREATE", "UPDATE", "DELETE");
        assertThat(version(target.getId())).isEqualTo(before);
        verify(cache, never()).invalidate();
    }

    @Test
    @DisplayName("다른 회원의 개별 권한은 영향받지 않는다 — 한 회원의 역할 변경은 그 회원의 행만 지운다")
    void roleChange_doesNotTouchOtherMembersRows() throws Exception {
        Member other = newManager("rolechg-other");
        grantAllNotice(target.getId());
        grantAllNotice(other.getId());
        long otherVersion = version(other.getId());

        changeRole(target.getId(), Role.ROLE_ADMIN);

        assertThat(actions(other.getId())).containsExactlyInAnyOrder("READ", "CREATE", "UPDATE", "DELETE");
        assertThat(version(other.getId())).isEqualTo(otherVersion);
        assertThat(canReadNotice(other)).as("다른 회원은 그대로 허용").isTrue();
    }

    @Test
    @DisplayName("신규 MANAGER(생성 직후)는 개별 권한이 없어 공지를 읽을 수 없다 — 기본값은 권한 0개")
    void newManagerStartsWithoutGrants() throws Exception {
        Member fresh = newManager("rolechg-fresh");

        assertThat(actions(fresh.getId())).isEmpty();
        assertThat(version(fresh.getId())).isZero();
        assertThat(canReadNotice(fresh)).isFalse();
    }
}
