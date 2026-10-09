package com.cms.admin.notification;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.dto.request.AdminMemberUpdateRequest;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.member.service.AdminMemberService;
import com.cms.admin.notification.domain.Notification;
import com.cms.admin.notification.domain.NotificationType;
import com.cms.admin.notification.event.NotificationRequestedEvent;
import com.cms.admin.notification.repository.NotificationRepository;
import com.cms.admin.notification.service.NotificationRecorder;
import com.cms.admin.permission.PermissionAction;
import com.cms.admin.permission.PermissionCache;
import com.cms.admin.permission.dto.request.MemberPermissionUpdateRequest;
import com.cms.admin.permission.service.MemberPermissionService;
import com.cms.config.auth.AdminSessionService;
import com.cms.config.auth.LoginFailureService;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.TestMembers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

/**
 * 알림 생성 경로(E1·E2·E4)를 실제 트랜잭션·MariaDB로 검증한다(PLAN-admin-notification.md §7 ③, v3 R-1·R-11, v4 R-13, v6 R-17).
 * 서비스 호출은 자기 트랜잭션을 커밋하므로 {@code @Transactional}을 붙이지 않고, 만든 회원은 {@link #cleanUp()}에서 지운다(알림은 FK CASCADE).
 */
@SpringBootTest(classes = CmsTestApplication.class)
class NotificationGenerationIntegrationTest extends MariaDbContainerSupport {

    @Autowired AdminMemberService adminMemberService;
    @Autowired MemberPermissionService memberPermissionService;
    @Autowired LoginFailureService loginFailureService;
    @Autowired MemberRepository memberRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ApplicationEventPublisher eventPublisher;

    @MockitoSpyBean NotificationRecorder notificationRecorder;
    @MockitoSpyBean AdminSessionService adminSessionService;
    @MockitoSpyBean PermissionCache permissionCache;

    private final List<Long> memberIds = new ArrayList<>();
    private Member actor;     // 수정을 수행하는 ADMIN
    private Member target;    // 수정 대상(MANAGER)

    @BeforeEach
    void setUp() {
        actor = create("gen-actor", Role.ROLE_ADMIN, MemberStatus.ACTIVE);
        target = create("gen-target", Role.ROLE_MANAGER, MemberStatus.ACTIVE);
    }

    @AfterEach
    void cleanUp() {
        TestMembers.delete(jdbc, memberIds);
    }

    private Member create(String prefix, Role role, MemberStatus status) {
        Member saved = TestMembers.save(memberRepository, prefix, role);
        if (status != MemberStatus.ACTIVE) {
            jdbc.update("UPDATE member SET status = ? WHERE id = ?", status.name(), saved.getId());
        }
        memberIds.add(saved.getId());
        return saved;
    }

    private List<Notification> notificationsOf(Member member) {
        return notificationRepository.findPage(member.getId(), null, true, 50);
    }

    private static AdminMemberUpdateRequest status(MemberStatus status) {
        return AdminMemberUpdateRequest.builder().status(status).build();
    }

    // ── E1 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("E1: 관리자가 상태를 잠금으로 바꾸면 커밋 뒤 대상 본인에게 ACCOUNT_STATUS 알림이 1건 생긴다")
    void adminLocksAccount_createsAccountStatusNotification() {
        adminMemberService.updateAdminMember(actor.getId(), target.getId(), status(MemberStatus.LOCKED));

        List<Notification> notifications = notificationsOf(target);
        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0).getType()).isEqualTo(NotificationType.ACCOUNT_STATUS);
        assertThat(notifications.get(0).getMessage()).contains("잠금");
        assertThat(notifications.get(0).getReadAt()).isNull();
        assertThat(notificationsOf(actor)).isEmpty();   // 행위자에게는 만들지 않는다
    }

    @Test
    @DisplayName("E1: 잠금을 풀어 활성으로 되돌리면 '다시 활성화' 알림이 생긴다")
    void adminReactivates_createsReactivationNotification() {
        jdbc.update("UPDATE member SET status = 'LOCKED' WHERE id = ?", target.getId());

        adminMemberService.updateAdminMember(actor.getId(), target.getId(), status(MemberStatus.ACTIVE));

        assertThat(notificationsOf(target)).extracting(Notification::getMessage).containsExactly("관리자가 내 계정을 다시 활성화했습니다.");
    }

    @Test
    @DisplayName("E1: 같은 상태로 다시 저장해 실제 변경이 없으면 알림이 없다")
    void noChange_noNotification() {
        adminMemberService.updateAdminMember(actor.getId(), target.getId(), status(MemberStatus.ACTIVE));

        assertThat(notificationsOf(target)).isEmpty();
    }

    // ── E2 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("E2: 역할을 바꾸면 PERMISSION 알림이 생기고 문장에 이전·이후 역할 라벨이 들어간다")
    void roleChange_createsPermissionNotification() {
        adminMemberService.updateAdminMember(actor.getId(), target.getId(),
                AdminMemberUpdateRequest.builder().userType(Role.ROLE_ADMIN).build());

        List<Notification> notifications = notificationsOf(target);
        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0).getType()).isEqualTo(NotificationType.PERMISSION);
        assertThat(notifications.get(0).getMessage()).contains("매니저").contains("관리자");
    }

    @Test
    @DisplayName("E2: 개별 권한을 부여·회수하면 +/- 항목이 담긴 알림이 생기고, 변경 없는 저장은 알림이 없다")
    void permissionReplace_createsNotificationOnlyOnRealChange() {
        long version = memberRepository.findById(target.getId()).orElseThrow().getPermissionVersion();
        long noticeBoardId = jdbc.queryForObject("SELECT id FROM board WHERE board_key = 'NOTICE'", Long.class);
        var grant = new MemberPermissionUpdateRequest.BoardGrant(noticeBoardId, PermissionAction.READ);

        memberPermissionService.replace(target.getId(), new MemberPermissionUpdateRequest(version, List.of(), List.of(grant)));
        assertThat(notificationsOf(target)).hasSize(1);
        assertThat(notificationsOf(target).get(0).getMessage()).contains("+게시판 #" + noticeBoardId + " 조회");

        long next = memberRepository.findById(target.getId()).orElseThrow().getPermissionVersion();
        memberPermissionService.replace(target.getId(), new MemberPermissionUpdateRequest(next, List.of(), List.of(grant)));   // 같은 집합 — 변경 없음
        assertThat(notificationsOf(target)).hasSize(1);

        memberPermissionService.replace(target.getId(), new MemberPermissionUpdateRequest(next, List.of(), List.of()));
        List<Notification> all = notificationsOf(target);
        assertThat(all).hasSize(2);
        assertThat(all.get(0).getMessage()).contains("-게시판 #" + noticeBoardId + " 조회");
    }

    // ── 롤백·실패 격리 ─────────────────────────────────────────

    @Test
    @DisplayName("이벤트를 발행한 뒤 외부 트랜잭션이 롤백되면 알림이 생기지 않는다(AFTER_COMMIT) — 이벤트 발행 전 종료하는 409 시험으로는 증명되지 않는 계약")
    void eventPublishedThenRolledBack_createsNothing() {
        new TransactionTemplate(transactionManager).executeWithoutResult(txStatus -> {
            eventPublisher.publishEvent(new NotificationRequestedEvent(target.getId(), NotificationType.ACCOUNT_STATUS, "롤백될 알림", null));
            txStatus.setRollbackOnly();
        });

        assertThat(notificationsOf(target)).isEmpty();
    }

    @Test
    @DisplayName("알림 저장이 실패해도(상태·역할·개별 권한 변경 각각) 원 업무는 이미 커밋돼 있다")
    void recorderFailure_doesNotUndoCommittedChange() {
        doThrow(new IllegalStateException("알림 저장 실패 주입")).when(notificationRecorder).record(anyLong(), any(), anyString(), any());

        adminMemberService.updateAdminMember(actor.getId(), target.getId(), status(MemberStatus.LOCKED));
        assertThat(memberRepository.findById(target.getId()).orElseThrow().getStatus()).isEqualTo(MemberStatus.LOCKED);

        adminMemberService.updateAdminMember(actor.getId(), target.getId(),
                AdminMemberUpdateRequest.builder().userType(Role.ROLE_ADMIN).build());
        assertThat(memberRepository.findById(target.getId()).orElseThrow().getUserType()).isEqualTo(Role.ROLE_ADMIN);

        // 개별 권한은 MANAGER 대상이어야 한다 — 새 MANAGER로 확인
        Member manager = create("gen-mgr2", Role.ROLE_MANAGER, MemberStatus.ACTIVE);
        long version = memberRepository.findById(manager.getId()).orElseThrow().getPermissionVersion();
        memberPermissionService.replace(manager.getId(), new MemberPermissionUpdateRequest(version,
                List.of(), List.of(new MemberPermissionUpdateRequest.BoardGrant(
                        jdbc.queryForObject("SELECT id FROM board WHERE board_key = 'NOTICE'", Long.class), PermissionAction.READ))));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member_board_permission WHERE member_id = ?", Long.class, manager.getId()))
                .isEqualTo(1L);

        assertThat(notificationsOf(target)).isEmpty();
        assertThat(notificationsOf(manager)).isEmpty();
    }

    // ── 리스너 순서(v4 R-13) ───────────────────────────────────

    @Test
    @DisplayName("같은 커밋의 세션 만료·권한 캐시 무효화가 알림 저장보다 먼저 실행된다 — 알림 저장이 막혀도 앞의 두 처리는 이미 끝나 있다")
    void sessionRevokeAndCacheInvalidation_runBeforeNotificationSave() {
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        doAnswer(invocation -> { order.add("session"); return invocation.callRealMethod(); })
                .when(adminSessionService).expireSessionsFor(anyLong());
        doAnswer(invocation -> { order.add("cache"); return invocation.callRealMethod(); })
                .when(permissionCache).invalidate();
        doAnswer(invocation -> { order.add("notification"); return invocation.callRealMethod(); })
                .when(notificationRecorder).record(anyLong(), any(), anyString(), any());

        // 역할 변경은 세션 만료·(개별 권한 행이 있으면) 캐시 무효화·알림 이벤트를 한 트랜잭션에서 발행한다
        jdbc.update("INSERT INTO member_board_permission (member_id, board_id, action) SELECT ?, id, 'READ' FROM board WHERE board_key = 'NOTICE'", target.getId());
        adminMemberService.updateAdminMember(actor.getId(), target.getId(),
                AdminMemberUpdateRequest.builder().userType(Role.ROLE_ADMIN).build());

        assertThat(order).contains("session", "cache", "notification");
        int session = order.indexOf("session");
        int firstCache = order.indexOf("cache");
        int notification = order.indexOf("notification");
        assertThat(session).isLessThan(notification);
        assertThat(firstCache).isLessThan(notification);   // AFTER_COMPLETION이 아니라 AFTER_COMMIT의 앞선 무효화가 알림보다 먼저다
    }

    // ── E1·E4 자동 잠금 ────────────────────────────────────────

    @Test
    @DisplayName("자동 잠금: 본인에게 E1 1건, 다른 ACTIVE ADMIN 전원에게 E4 1건(잠긴 본인·비ACTIVE ADMIN·MANAGER 제외), E4 문장·링크는 DB의 정규 아이디·잠금 시각을 쓴다")
    void autoLock_createsE1ForSelf_andE4ForOtherActiveAdmins() {
        Member lockedAdmin = create("gen-locked", Role.ROLE_ADMIN, MemberStatus.ACTIVE);
        Member otherActiveAdmin = actor;                                                    // ACTIVE ADMIN — 수신자
        Member inactiveAdmin = create("gen-inactive", Role.ROLE_ADMIN, MemberStatus.DISABLED);   // 수신자 아님
        Member manager = target;                                                             // MANAGER — 수신자 아님

        // 요청 username은 대소문자·후행 공백 변형 — 알림에는 DB의 정규 userId가 들어가야 한다
        String variant = lockedAdmin.getUserId().toUpperCase() + "          ";
        for (int i = 0; i < 5; i++) {
            loginFailureService.recordFailure(variant, "1.2.3.4", "/admin/login");
        }

        List<Notification> own = notificationsOf(lockedAdmin);
        assertThat(own).hasSize(1);
        assertThat(own.get(0).getType()).isEqualTo(NotificationType.ACCOUNT_STATUS);
        assertThat(own.get(0).getMessage()).contains("자동 잠금");

        List<Notification> forOtherAdmin = notificationsOf(otherActiveAdmin).stream()
                .filter(n -> n.getType() == NotificationType.ADMIN_ACCOUNT_LOCKED).toList();
        assertThat(forOtherAdmin).hasSize(1);
        assertThat(forOtherAdmin.get(0).getMessage()).contains("'" + lockedAdmin.getUserId() + "'").doesNotContain("          ");
        assertThat(forOtherAdmin.get(0).getLinkUrl()).isEqualTo("/admin/member/manage?id=" + lockedAdmin.getId());

        assertThat(notificationsOf(lockedAdmin)).noneMatch(n -> n.getType() == NotificationType.ADMIN_ACCOUNT_LOCKED);   // 잠긴 본인에게 E4 없음
        assertThat(notificationsOf(inactiveAdmin)).isEmpty();                                                            // ACTIVE가 아닌 ADMIN
        assertThat(notificationsOf(manager)).isEmpty();                                                                  // MANAGER
    }
}
