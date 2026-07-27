package com.cms.config.auth;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.dto.request.AdminMyPasswordChangeRequest;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.member.service.AdminMemberService;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.cms.config.auth.PasswordExpiryService.PASSWORD_EXPIRY_DAYS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 비밀번호 90일 만료의 실배선(SecurityConfig 필터 체인) 검증 —
 * 만료 전이 커밋 유지(noRollbackFor)·실패 카운트 미증가·경계·복구 흐름은
 * 실제 로그인 트랜잭션 없이는 증명되지 않는다.
 *
 * <p>MockMvc 로그인은 실제 트랜잭션을 커밋하므로 {@code @Transactional}을 붙이지 않고,
 * 생성한 회원·방문 로그를 {@link #cleanUp()}에서 대상 한정 삭제한다 (공유 dev DB — deleteAll() 금지).
 *
 * <p>Testcontainers가 띄우는 일회용 MariaDB로 실행된다 — 로컬 DB 기동·환경변수 주입 불필요,
 * Docker만 있으면 된다({@link MariaDbContainerSupport}).
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class PasswordExpiryIntegrationTest extends MariaDbContainerSupport {

    private static final String RAW_PASSWORD = "Expiry1234!";
    private static final String NEW_RAW_PASSWORD = "NewExpiry1234!";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    MemberRepository memberRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    AdminMemberService adminMemberService;

    @Autowired
    VisitLoggingAuthenticationSuccessHandler successHandler;

    @Autowired
    SessionRegistry sessionRegistry;

    @Autowired
    Clock clock;

    private final List<Long> createdMemberIds = new ArrayList<>();
    private final List<String> createdUserIds = new ArrayList<>();

    private Member createActiveAdmin(LocalDateTime passwordChangedAt) {
        String unique = "pwexpiry-it-" + System.nanoTime();
        String userId = unique.substring(0, Math.min(50, unique.length()));
        Member saved = memberRepository.save(Member.builder()
                .userId(userId)
                .pwd(passwordEncoder.encode(RAW_PASSWORD))
                .userName("만료통합테스트")
                .email(unique + "@pwexpiry-it.test")
                .userType(Role.ROLE_ADMIN)
                .status(MemberStatus.ACTIVE)
                .createDate(LocalDateTime.now(clock))
                .updateDate(LocalDateTime.now(clock))
                .passwordChangedAt(passwordChangedAt)
                .build());
        createdMemberIds.add(saved.getId());
        createdUserIds.add(saved.getUserId());
        return saved;
    }

    @BeforeEach
    void ensureCleanState() {
        createdMemberIds.clear();
        createdUserIds.clear();
    }

    @AfterEach
    void cleanUp() {
        try {
            sessionRegistry.getAllPrincipals().stream()
                    .filter(p -> p instanceof CustomUserDetails details
                            && createdUserIds.contains(details.getUsername()))
                    .forEach(p -> sessionRegistry.getAllSessions(p, true)
                            .forEach(info -> sessionRegistry.removeSessionInformation(info.getSessionId())));
        } catch (Exception ignored) {
        }
        for (String userId : createdUserIds) {
            try {
                jdbcTemplate.update("DELETE FROM visit_log WHERE visitor_user_id = ?", userId);
            } catch (Exception ignored) {
            }
        }
        for (Long id : createdMemberIds) {
            try {
                jdbcTemplate.update("DELETE FROM admin_action_log WHERE target_id = ?", id);
            } catch (Exception ignored) {
            }
            try {
                memberRepository.deleteById(id);
            } catch (Exception ignored) {
            }
        }
    }

    private void attemptLogin(String userId, String password, String expectedRedirect) throws Exception {
        mockMvc.perform(formLogin("/admin/login").user(userId).password(password))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(expectedRedirect));
    }

    private Member reload(Long id) {
        return memberRepository.findById(id).orElseThrow();
    }

    @Test
    @DisplayName("91일 경과 계정 로그인 → 거부 + PASSWORD_EXPIRED 커밋 유지(noRollbackFor) + 실패 카운트 미증가")
    void expiredAccount_loginRejected_transitionCommitted_noFailureCount() throws Exception {
        Member member = createActiveAdmin(LocalDateTime.now(clock).minusDays(PASSWORD_EXPIRY_DAYS + 1));

        // 올바른 비밀번호여도 거부 — 만료 전이가 비밀번호 검증보다 먼저
        attemptLogin(member.getUserId(), RAW_PASSWORD, "/admin/login-error");

        Member found = reload(member.getId());
        // 인증 예외(CredentialsExpiredException)에도 전이가 롤백되지 않고 커밋 유지됐는지 실증
        assertThat(found.getStatus()).isEqualTo(MemberStatus.PASSWORD_EXPIRED);
        // CredentialsExpiredException은 BadCredentialsException이 아니다 — 카운트 미증가 규약
        assertThat(found.getFailedLoginCount()).isZero();
    }

    @Test
    @DisplayName("만료 계정의 두 번째 로그인 시도도 거부되고 상태는 그대로다 (재전이 없음 — 0행 멱등)")
    void expiredAccount_secondAttempt_stillRejected() throws Exception {
        Member member = createActiveAdmin(LocalDateTime.now(clock).minusDays(PASSWORD_EXPIRY_DAYS + 1));

        attemptLogin(member.getUserId(), RAW_PASSWORD, "/admin/login-error");
        attemptLogin(member.getUserId(), RAW_PASSWORD, "/admin/login-error");

        assertThat(reload(member.getId()).getStatus()).isEqualTo(MemberStatus.PASSWORD_EXPIRED);
    }

    @Test
    @DisplayName("89일 경과 계정은 정상 로그인된다 (90일 미달)")
    void account89days_loginSucceeds() throws Exception {
        Member member = createActiveAdmin(LocalDateTime.now(clock).minusDays(PASSWORD_EXPIRY_DAYS - 1));

        attemptLogin(member.getUserId(), RAW_PASSWORD, "/admin");

        assertThat(reload(member.getId()).getStatus()).isEqualTo(MemberStatus.ACTIVE);
    }

    @Test
    @DisplayName("만료 계정이 changeMyPassword로 비밀번호를 바꾸면 ACTIVE 복귀 + 새 비밀번호로 재로그인 성공")
    void expiredAccount_changeMyPassword_revivesAndLoginSucceeds() throws Exception {
        Member member = createActiveAdmin(LocalDateTime.now(clock).minusDays(PASSWORD_EXPIRY_DAYS + 1));
        // 만료 전이 확정 (살아있는 세션 시나리오의 전제 상태)
        attemptLogin(member.getUserId(), RAW_PASSWORD, "/admin/login-error");
        assertThat(reload(member.getId()).getStatus()).isEqualTo(MemberStatus.PASSWORD_EXPIRED);

        // 살아있는 세션의 내 비밀번호 변경 — 도메인 관문의 PASSWORD_EXPIRED → ACTIVE 복귀
        LocalDateTime before = LocalDateTime.now(clock);
        adminMemberService.changeMyPassword(member.getId(), AdminMyPasswordChangeRequest.builder()
                .currentPassword(RAW_PASSWORD)
                .newPassword(NEW_RAW_PASSWORD)
                .confirmPassword(NEW_RAW_PASSWORD)
                .build());

        Member changed = reload(member.getId());
        assertThat(changed.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(changed.getPasswordChangedAt()).isAfterOrEqualTo(before.minusSeconds(1));

        attemptLogin(member.getUserId(), NEW_RAW_PASSWORD, "/admin");
    }

    @Test
    @DisplayName("성공 핸들러 재판정 — ACTIVE + 91일 경과 상태로 성공 경로 진입 시 만료 전이 + 거부 (인증 중 경계 통과 등가)")
    void successHandler_reverifiesExpiry_andRejects() throws Exception {
        // loadUserByUsername 판정 이후 90일 경계를 넘은 상황과 등가인 DB 상태:
        // ACTIVE인데 passwordChangedAt은 이미 cutoff를 지났다
        Member member = createActiveAdmin(LocalDateTime.now(clock).minusDays(PASSWORD_EXPIRY_DAYS + 1));

        Authentication auth = UsernamePasswordAuthenticationToken.authenticated(
                new CustomUserDetails(member), null,
                new CustomUserDetails(member).getAuthorities());
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        successHandler.onAuthenticationSuccess(request, response, auth);

        // 재판정이 만료를 전이시키고 성공 처리를 거부(로그인 에러로 리다이렉트)한다
        assertThat(response.getRedirectedUrl()).isEqualTo("/admin/login-error");
        assertThat(reload(member.getId()).getStatus()).isEqualTo(MemberStatus.PASSWORD_EXPIRED);
    }
}
