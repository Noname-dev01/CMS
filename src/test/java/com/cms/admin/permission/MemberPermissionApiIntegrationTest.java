package com.cms.admin.permission;

import com.cms.admin.log.constant.AdminActionTypes;
import com.cms.admin.log.domain.AdminActionLog;
import com.cms.admin.log.domain.AdminActionResult;
import com.cms.admin.log.repository.AdminActionLogRepository;
import com.cms.admin.log.service.AdminActionLogService;
import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.permission.dto.request.MemberPermissionUpdateRequest;
import com.cms.admin.permission.service.MemberPermissionService;
import com.cms.config.auth.CustomUserDetails;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.TestMembers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 회원별 권한관리 API 통합 시험(PLAN-member-permission.md §7-1 ④): 실제 SecurityConfig·판정기·캐시·감사 Aspect·MariaDB.
 * 저장 결과(DB 행·버전)·감사 로그(targetId = 대상 회원 ID)·캐시 무효화 시점·즉시 반영(실제 로그인 세션 재사용)·감사 저장 실패 격리·
 * 변형 행 가드·대상 검증(ADMIN·USER·삭제·없음)을 검증한다.
 *
 * <p>MockMvc 호출은 실제 트랜잭션을 커밋하므로 {@code @Transactional}을 붙이지 않고 {@link #cleanUp()}에서 만든 회원·행을 지운다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
@Import(MemberPermissionApiIntegrationTest.CommitFailureConfig.class)
class MemberPermissionApiIntegrationTest extends MariaDbContainerSupport {

    private static final String PASSWORD = "pw1234!";

    /**
     * 실패 주입용 설정. ① 커밋 직전 실패 — 실제 최상위 replace() 트랜잭션 안에서 이벤트를 받아 던진다(BEFORE_COMMIT 예외는 커밋을 막고 롤백시킨다).
     * ② 커밋 결과 유실 — DataSource 프록시가 <b>실제 커밋을 수행한 뒤</b> SQLException을 던진다(DB는 커밋됐지만 호출자는 커밋 예외를 받아
     * 트랜잭션 상태가 UNKNOWN이 된다 — AFTER_COMMIT 리스너는 COMMITTED에서만 실행되므로 이때 캐시가 안 비워지면 회수한 권한이 남는다).
     */
    @TestConfiguration
    static class CommitFailureConfig {
        @Bean
        CommitFailureProbe commitFailureProbe() {
            return new CommitFailureProbe();
        }

        @Bean
        static BeanPostProcessor commitLossDataSourcePostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof DataSource dataSource)) {
                        return bean;
                    }
                    return Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                            (proxy, method, args) -> {
                                Object result = invoke(dataSource, method, args);
                                if (!method.getName().equals("getConnection") || !(result instanceof Connection connection)) {
                                    return result;
                                }
                                return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                                        (connectionProxy, connectionMethod, connectionArgs) -> {
                                            Object value = invoke(connection, connectionMethod, connectionArgs);
                                            if (connectionMethod.getName().equals("commit") && loseCommitResult) {
                                                loseCommitResult = false; // 한 번만 — DB 커밋은 이미 끝났다
                                                throw new SQLException("커밋 응답 유실 모사");
                                            }
                                            return value;
                                        });
                            });
                }

                private Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
                    try {
                        return method.invoke(target, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                }
            };
        }
    }

    /** true면 다음 {@code commit()}을 실제로 수행한 뒤 예외를 던진다. */
    static volatile boolean loseCommitResult;

    static class CommitFailureProbe {
        volatile boolean fail;

        @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
        void beforeCommit(PermissionChangedEvent event) {
            if (fail) {
                throw new IllegalStateException("커밋 직전 실패 주입");
            }
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberPermissionService service;
    @Autowired AdminActionLogRepository auditRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired CommitFailureProbe probe;
    @MockitoSpyBean PermissionCache cache;
    @MockitoSpyBean AdminActionLogService auditService;

    private long auditBaseline;
    private Member manager;
    private final List<Long> createdMemberIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        manager = createMember("perm-mgr", Role.ROLE_MANAGER, MemberStatus.ACTIVE);
        restoreSeed();
        auditBaseline = auditRepository.findAll().stream().mapToLong(AdminActionLog::getId).max().orElse(0L);
        clearInvocations(cache, auditService);
        probe.fail = false;
        loseCommitResult = false;
    }

    @AfterEach
    void cleanUp() {
        probe.fail = false;
        loseCommitResult = false;
        auditRepository.findAll().stream()
                .filter(log -> log.getId() > auditBaseline && AdminActionTypes.PERMISSION_UPDATE.equals(log.getActionType()))
                .forEach(auditRepository::delete);
        for (Long id : createdMemberIds) {
            jdbc.update("DELETE FROM visit_log WHERE visitor_user_id = (SELECT user_id FROM member WHERE id = ?)", id);
        }
        TestMembers.delete(jdbc, createdMemberIds);
        cache.invalidate();
    }

    // ── 보조 ────────────────────────────────────────────────

    /** 실제 로그인이 가능한 회원(BCrypt 비밀번호)을 저장하고 정리 대상에 등록한다. */
    private Member createMember(String prefix, Role role, MemberStatus status) {
        Member base = TestMembers.save(memberRepository, prefix, role);
        LocalDateTime now = LocalDateTime.now();
        Member saved = memberRepository.save(Member.builder().id(base.getId()).userId(base.getUserId())
                .pwd(passwordEncoder.encode(PASSWORD)).userName(prefix).email(base.getEmail()).userType(role)
                .status(status).createDate(now).updateDate(now).passwordChangedAt(now)
                .permissionVersion(base.getPermissionVersion()).build());
        createdMemberIds.add(saved.getId());
        return saved;
    }

    private String url() {
        return url(manager.getId());
    }

    private static String url(Long memberId) {
        return "/admin/api/members/" + memberId + "/permissions";
    }

    /** 이 시험 회원에게 공지 4동작을 모두 허용한 상태로 만든다(신규 MANAGER는 권한 0개가 기본이라 시험이 명시적으로 부여한다). */
    private void restoreSeed() {
        jdbc.update("DELETE FROM member_permission WHERE member_id = ?", manager.getId());
        for (PermissionAction action : PermissionAction.values()) {
            jdbc.update("INSERT INTO member_permission (member_id, feature, action) VALUES (?, 'NOTICE', ?)", manager.getId(), action.name());
        }
        cache.invalidate();
    }

    private long version() {
        return jdbc.queryForObject("SELECT permission_version FROM member WHERE id = ?", Long.class, manager.getId());
    }

    /** 정확 일치(BINARY)로 읽은 NOTICE 허용 동작 — 대소문자 변형 행은 포함하지 않는다. */
    private Set<String> noticeActions() {
        return new TreeSet<>(jdbc.queryForList(
                "SELECT action FROM member_permission WHERE member_id = ? AND BINARY feature = 'NOTICE'", String.class, manager.getId()));
    }

    private static RequestPostProcessor asAdmin() {
        Member member = Member.builder().id(1L).userId("perm-admin").userName("perm-admin").email("perm-admin@example.com")
                .userType(Role.ROLE_ADMIN).status(MemberStatus.ACTIVE).build();
        CustomUserDetails details = new CustomUserDetails(member);
        return authentication(new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
    }

    private static String body(long version, PermissionAction... noticeActions) {
        String grants = Arrays.stream(noticeActions)
                .map(a -> "{\"feature\":\"NOTICE\",\"action\":\"" + a.name() + "\"}")
                .reduce((x, y) -> x + "," + y).orElse("");
        return "{\"version\":" + version + ",\"grants\":[" + grants + "]}";
    }

    private void putAsAdmin(long version, int expectedStatus, PermissionAction... actions) throws Exception {
        mockMvc.perform(put(url()).with(asAdmin()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body(version, actions)))
                .andExpect(status().is(expectedStatus));
    }

    private List<AdminActionLog> permissionAudits() {
        return auditRepository.findAll().stream()
                .filter(log -> log.getId() > auditBaseline && AdminActionTypes.PERMISSION_UPDATE.equals(log.getActionType()))
                .toList();
    }

    private static MemberPermissionUpdateRequest request(long version, PermissionAction... actions) {
        return MemberPermissionUpdateRequest.builder().version(version)
                .grants(Arrays.stream(actions).map(a -> new MemberPermissionUpdateRequest.Grant(AdminFeature.NOTICE, a)).toList())
                .build();
    }

    /** 실제 로그인(POST /admin/login, 실제 CSRF)으로 이 시험 MANAGER의 세션을 얻는다 — 이후 요청에 인증을 다시 주입하지 않는다. */
    private MockHttpSession loginAsManager() throws Exception {
        MockHttpSession session = (MockHttpSession) mockMvc.perform(post("/admin/login").with(csrf())
                        .param("username", manager.getUserId()).param("password", PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andReturn().getRequest().getSession(false);
        assertThat(session).as("로그인 성공 시 세션 발급").isNotNull();
        return session;
    }

    // ── ① 저장 성공 ─────────────────────────────────────────

    @Test
    @DisplayName("ADMIN PUT: 행·버전이 바뀌고 SUCCESS 감사 1건(target_type=MEMBER_PERMISSION, target_id=대상 회원 ID, 정확한 라벨)")
    void put_changesRowsVersionAndAudit() throws Exception {
        long before = version();

        putAsAdmin(before, 200, PermissionAction.READ);

        assertThat(noticeActions()).containsExactly("READ");
        assertThat(version()).isEqualTo(before + 1);
        List<AdminActionLog> audits = permissionAudits();
        assertThat(audits).hasSize(1);
        AdminActionLog audit = audits.get(0);
        assertThat(audit.getActionResult()).isEqualTo(AdminActionResult.SUCCESS);
        assertThat(audit.getTargetType()).isEqualTo("MEMBER_PERMISSION");
        assertThat(audit.getTargetId()).as("감사 targetId는 반환 객체의 getMemberId()에서 추출된다").isEqualTo(manager.getId());
        assertThat(audit.getTargetLabel())
                .isEqualTo("v" + before + "→v" + (before + 1) + ": -공지사항.생성, -공지사항.수정, -공지사항.삭제");
        verify(cache, times(1)).invalidate();
    }

    @Test
    @DisplayName("변경 없는 PUT: 버전·행 불변, 무효화 없음, SUCCESS 감사 1건(targetId 있음, 라벨 '변경 없음')")
    void put_noChange() throws Exception {
        long before = version();

        putAsAdmin(before, 200, PermissionAction.values());

        assertThat(version()).isEqualTo(before);
        assertThat(noticeActions()).containsExactlyInAnyOrder("READ", "CREATE", "UPDATE", "DELETE");
        assertThat(permissionAudits()).singleElement().satisfies(audit -> {
            assertThat(audit.getActionResult()).isEqualTo(AdminActionResult.SUCCESS);
            assertThat(audit.getTargetId()).as("변경 없음 결과도 대상 회원 ID를 노출한다").isEqualTo(manager.getId());
            assertThat(audit.getTargetLabel()).isEqualTo("v" + before + ": 변경 없음");
        });
        verify(cache, never()).invalidate();
    }

    @Test
    @DisplayName("낡은 version은 409: DB 불변·무효화 없음·FAIL 감사 1건(실패 감사는 라벨 없음)")
    void put_staleVersion_conflict() throws Exception {
        long before = version();

        putAsAdmin(before - 1, 409, PermissionAction.READ);

        assertThat(noticeActions()).containsExactlyInAnyOrder("READ", "CREATE", "UPDATE", "DELETE");
        assertThat(version()).isEqualTo(before);
        assertThat(permissionAudits()).singleElement().satisfies(audit -> {
            assertThat(audit.getActionResult()).isEqualTo(AdminActionResult.FAIL);
            assertThat(audit.getTargetLabel()).isNull();
        });
        verify(cache, never()).invalidate();
    }

    @Test
    @DisplayName("위임 불가 기능·READ 없는 쓰기는 400이고 DB·버전이 바뀌지 않는다")
    void put_invalid_badRequest() throws Exception {
        long before = version();

        mockMvc.perform(put(url()).with(asAdmin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + before + ",\"grants\":[{\"feature\":\"MENU\",\"action\":\"READ\"}]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        putAsAdmin(before, 400, PermissionAction.CREATE);

        assertThat(noticeActions()).hasSize(4);
        assertThat(version()).isEqualTo(before);
        verify(cache, never()).invalidate();
    }

    // ── 대상 검증 ───────────────────────────────────────────

    @Test
    @DisplayName("ADMIN 대상은 400, ROLE_USER·없는 회원은 404, 삭제된 계정은 PUT 409(GET은 200) — 어느 경우에도 허용 행이 생기지 않는다")
    void put_invalidTargets() throws Exception {
        Member admin = createMember("perm-target-admin", Role.ROLE_ADMIN, MemberStatus.ACTIVE);
        Member user = createMember("perm-target-user", Role.ROLE_USER, MemberStatus.ACTIVE);
        Member deleted = createMember("perm-target-deleted", Role.ROLE_MANAGER, MemberStatus.DELETED);
        long missingId = 999_999_999L;

        for (Member target : List.of(admin, user, deleted)) {
            long targetVersion = target.getPermissionVersion();
            int expected = target == admin ? 400 : target == user ? 404 : 409;
            mockMvc.perform(put(url(target.getId())).with(asAdmin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                            .content(body(targetVersion, PermissionAction.READ)))
                    .andExpect(status().is(expected));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member_permission WHERE member_id = ?", Integer.class, target.getId()))
                    .as(target.getUserId()).isZero();
        }
        mockMvc.perform(put(url(missingId)).with(asAdmin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(0, PermissionAction.READ)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get(url(admin.getId())).with(asAdmin())).andExpect(status().isBadRequest());
        mockMvc.perform(get(url(user.getId())).with(asAdmin())).andExpect(status().isNotFound());
        mockMvc.perform(get(url(missingId)).with(asAdmin())).andExpect(status().isNotFound());
        mockMvc.perform(get(url(deleted.getId())).with(asAdmin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DELETED"));
    }

    @Test
    @DisplayName("GET: 회원 식별 정보·버전과 시드 상태의 유효 허용값(공지 4동작)")
    void get_returnsMatrix() throws Exception {
        mockMvc.perform(get(url()).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").value(manager.getId()))
                .andExpect(jsonPath("$.userId").value(manager.getUserId()))
                .andExpect(jsonPath("$.version").value(version()))
                .andExpect(jsonPath("$.features[2].feature").value("NOTICE"))
                .andExpect(jsonPath("$.features[2].grantedActions.length()").value(4));
    }

    // ── ④ 즉시 반영(실제 로그인 세션 재사용) ───────────────────

    @Test
    @DisplayName("같은 MANAGER 로그인 세션에서 ADMIN이 권한을 회수하면 다음 요청부터 공지 API·페이지가 막히고 사이드바에서 사라진다")
    void revoke_takesEffectImmediatelyOnSameSession() throws Exception {
        MockHttpSession session = loginAsManager();
        mockMvc.perform(get("/admin/api/notices").session(session)).andExpect(status().isOk());
        assertThat(mockMvc.perform(get("/admin").session(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).contains("href=\"/admin/notice/manage\"");

        putAsAdmin(version(), 200); // 전부 회수

        mockMvc.perform(get("/admin/api/notices").session(session))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(get("/admin/notice/manage").session(session)).andExpect(status().isForbidden());
        assertThat(mockMvc.perform(get("/admin").session(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).doesNotContain("href=\"/admin/notice/manage\"");
        // 권한 변경은 세션을 만료시키지 않는다 — 대시보드(상시 허용)는 같은 세션으로 계속 열린다.
        mockMvc.perform(get("/admin").session(session)).andExpect(status().isOk());

        putAsAdmin(version(), 200, PermissionAction.READ, PermissionAction.CREATE);

        mockMvc.perform(get("/admin/api/notices").session(session)).andExpect(status().isOk());
        var model = mockMvc.perform(get("/admin/notice/manage").session(session)).andExpect(status().isOk())
                .andReturn().getModelAndView().getModel();
        @SuppressWarnings("unchecked")
        Set<String> mine = (Set<String>) model.get("myPermissions");
        assertThat(mine).contains("NOTICE:CREATE").doesNotContain("NOTICE:DELETE");
    }

    // ── ⑤ 트랜잭션 완료 직후 무효화(커밋·롤백·결과 불명 모두 — 보수적) ─────────

    @Test
    @DisplayName("바깥 트랜잭션이 롤백되면 DB는 불변이고 캐시는 보수적으로 폐기된다(바깥 트랜잭션 안의 감사 SUCCESS 잔존은 단언하지 않는 알려진 한계)")
    void outerRollback_dbUnchanged_cacheConservativelyInvalidated() {
        long before = version();
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        template.executeWithoutResult(status -> {
            service.replace(manager.getId(), request(before, PermissionAction.READ));
            status.setRollbackOnly();
        });

        verify(cache, times(1)).invalidate(); // 롤백이어도 완료 직후 폐기한다 — 값은 DB가 정하므로 안전
        assertThat(noticeActions()).hasSize(4);
        assertThat(version()).isEqualTo(before);
    }

    @Test
    @DisplayName("실제 최상위 replace()의 커밋 직전 실패: 행·버전 롤백, SUCCESS 감사 0건·FAIL 1건(캐시는 보수적으로 폐기)")
    void topLevelCommitFailure_rollsBackAndRecordsFail() {
        long before = version();
        probe.fail = true;

        assertThatThrownBy(() -> service.replace(manager.getId(), request(before, PermissionAction.READ)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(noticeActions()).hasSize(4);
        assertThat(version()).isEqualTo(before);
        verify(cache, times(1)).invalidate(); // 롤백이어도 완료 직후 폐기한다 — 값은 DB가 정하므로 안전
        assertThat(permissionAudits()).singleElement().satisfies(audit ->
                assertThat(audit.getActionResult()).isEqualTo(AdminActionResult.FAIL));
    }

    @Test
    @DisplayName("DB 커밋은 성공했는데 호출자가 커밋 예외를 받아도(응답 유실, 트랜잭션 상태 UNKNOWN) 회수한 권한이 캐시에 남지 않는다 — 다음 판정이 회수된 상태다")
    void commitResultLost_cacheStillInvalidated() {
        long before = version();
        cache.snapshot(); // 캐시를 시드 상태(READ·CREATE·UPDATE·DELETE)로 채워 둔다
        assertThat(cache.snapshot().has(manager.getId(), AdminFeature.NOTICE, PermissionAction.CREATE)).isTrue();
        clearInvocations(cache);
        loseCommitResult = true;

        assertThatThrownBy(() -> service.replace(manager.getId(), request(before, PermissionAction.READ)))
                .isInstanceOf(RuntimeException.class);

        // DB 커밋은 성공했다 — 예외는 호출자에게만 전달됐다
        assertThat(noticeActions()).containsExactly("READ");
        assertThat(version()).isEqualTo(before + 1);
        // 상태 UNKNOWN에서는 AFTER_COMMIT 리스너가 실행되지 않지만 AFTER_COMPLETION이 캐시를 폐기해, 회수된 CREATE가 계속 허용되지 않는다
        verify(cache, times(1)).invalidate();
        assertThat(cache.snapshot().has(manager.getId(), AdminFeature.NOTICE, PermissionAction.CREATE)).isFalse();
        assertThat(cache.snapshot().has(manager.getId(), AdminFeature.NOTICE, PermissionAction.READ)).isTrue();
    }

    // ── ⑦ 감사 저장 실패 격리 ───────────────────────────────

    @Test
    @DisplayName("감사 저장이 실패해도 권한 변경은 유지되고 캐시는 무효화된다(그래서 롤백 판단은 감사만 믿으면 안 된다)")
    void auditFailure_doesNotUndoPermissionChange() throws Exception {
        long before = version();
        doThrow(new IllegalStateException("감사 저장 실패 주입")).when(auditService).log(
                any(), any(), anyString(), any(), anyString(), any(), any(), any(), any(), any(), any());

        putAsAdmin(before, 200, PermissionAction.READ);

        assertThat(noticeActions()).containsExactly("READ");
        assertThat(version()).isEqualTo(before + 1);
        verify(cache, times(1)).invalidate();
    }

    // ── ⑧ 대소문자·공백 변형 행 ─────────────────────────────

    @Test
    @DisplayName("PK가 general_ci·PAD 비교라 충돌하는 변형 행(동작 소문자·기능 소문자·동작 후행 공백)이 있으면 409, 정리 후에는 200")
    void variantRows_conflictUntilCleaned() throws Exception {
        Object[][] variants = {
                {manager.getId(), "NOTICE", "read"},
                {manager.getId(), "notice", "READ"},
                {manager.getId(), "NOTICE", "READ "},
        };
        for (Object[] variant : variants) {
            jdbc.update("DELETE FROM member_permission WHERE member_id = ?", manager.getId());
            jdbc.update("INSERT INTO member_permission (member_id, feature, action) VALUES (?, ?, ?)", variant);
            cache.invalidate();
            clearInvocations(cache);
            long before = version();

            putAsAdmin(before, 409, PermissionAction.READ);

            assertThat(version()).as(Arrays.toString(variant)).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member_permission WHERE member_id = ?", Integer.class, manager.getId()))
                    .isEqualTo(1);
            verify(cache, never()).invalidate();

            jdbc.update("DELETE FROM member_permission WHERE member_id = ?", manager.getId());
            cache.invalidate();
            clearInvocations(cache);
            putAsAdmin(before, 200, PermissionAction.READ);
            assertThat(noticeActions()).as(Arrays.toString(variant)).containsExactly("READ");
        }
    }

    // ── 경로 경계 ───────────────────────────────────────────

    @Test
    @DisplayName("MANAGER(권한 전부 보유)는 어떤 회원의 권한 API도 호출할 수 없고(403) 자기 권한을 바꾸지 못한다 — 허용 행·버전 불변")
    void manager_cannotCallPermissionApi() throws Exception {
        long before = version();
        RequestPostProcessor asManager = TestMembers.asMember(manager);

        mockMvc.perform(get(url()).with(asManager)).andExpect(status().isForbidden());
        mockMvc.perform(put(url()).with(asManager).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(before, PermissionAction.READ)))
                .andExpect(status().isForbidden());
        // me 경로는 URL 게이트가 MANAGER에게 열려 있지만(내 정보 API) 핸들러가 숫자 ID를 요구해 400이다 — 권한 API에 닿지 못한다
        mockMvc.perform(get("/admin/api/members/me/permissions").with(asManager)).andExpect(status().isBadRequest());

        assertThat(version()).isEqualTo(before);
        assertThat(noticeActions()).hasSize(4);
        assertThat(permissionAudits()).as("서비스에 닿지 못했으므로 감사가 없다").isEmpty();
    }
}
