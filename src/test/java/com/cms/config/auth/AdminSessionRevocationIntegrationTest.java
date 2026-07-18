package com.cms.config.auth;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.dto.request.AdminMemberUpdateRequest;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.member.service.AdminMemberService;
import com.cms.support.CmsTestApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 세션 강제 만료의 배선(wiring) 검증 필수 게이트 — end-to-end:
 * 실제 로그인(SessionRegistry 등록) → 서비스 잠금(커밋 후 AFTER_COMMIT 리스너가 만료)
 * → 대상자의 다음 요청이 ConcurrentSessionFilter에서 거부되는 전 체인을 관통한다.
 * 리스너 미등록·SessionRegistry 미적재·principal 매칭 버그는 이 테스트가 잡는다.
 *
 * <p>로컬 실행: DB(make dev-db) 기동 + DB_PASS/MAIL_USER/MAIL_PASS 환경변수 설정 필요.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class AdminSessionRevocationIntegrationTest {

    private static final String TARGET_RAW_PASSWORD = "Target1234!";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    MemberRepository memberRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    AdminMemberService adminMemberService;

    private final List<Long> createdMemberIds = new ArrayList<>();
    private Long callerId;
    private Long targetId;
    private String targetUserId;

    @BeforeEach
    void setUp() {
        callerId = createMember("revoke-caller", Role.ROLE_ADMIN).getId();
        Member target = createMember("revoke-target", Role.ROLE_MANAGER);
        targetId = target.getId();
        targetUserId = target.getUserId();
    }

    @AfterEach
    void cleanUp() {
        for (Long id : createdMemberIds) {
            try {
                memberRepository.deleteById(id);
            } catch (Exception ignored) {
            }
        }
    }

    private Member createMember(String prefix, Role role) {
        LocalDateTime now = LocalDateTime.now();
        String unique = prefix + "-" + System.nanoTime();
        Member saved = memberRepository.save(Member.builder()
                .userId(unique.substring(0, Math.min(50, unique.length())))
                .pwd(passwordEncoder.encode(TARGET_RAW_PASSWORD))
                .userName("세션만료테스트")
                .email(unique + "@revoke.test")
                .userType(role)
                .status(MemberStatus.ACTIVE)
                .createDate(now)
                .updateDate(now)
                .passwordChangedAt(now)
                .build());
        createdMemberIds.add(saved.getId());
        return saved;
    }

    private MockHttpSession loginAsTarget() throws Exception {
        MvcResult loginResult = mockMvc.perform(formLogin("/admin/login")
                        .user(targetUserId)
                        .password(TARGET_RAW_PASSWORD))
                .andExpect(authenticated())
                .andReturn();
        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);
        assertNotNull(session, "로그인 성공 시 세션이 생성되어야 한다");
        return session;
    }

    @Test
    @DisplayName("로그인된 대상자를 잠그면 다음 API 요청이 JSON 401로 거부된다")
    void lockedTarget_nextApiRequest_rejectedWithJson401() throws Exception {
        MockHttpSession session = loginAsTarget();

        // 잠금 전에는 정상 접근 (MANAGER self API)
        mockMvc.perform(get("/admin/api/members/me").session(session))
                .andExpect(status().isOk());

        // 잠금 — 서비스 트랜잭션 커밋 후 AFTER_COMMIT 리스너가 세션을 만료 처리
        adminMemberService.updateAdminMember(callerId, targetId,
                AdminMemberUpdateRequest.builder().status(MemberStatus.LOCKED).build());

        // 대상자의 다음 API 요청은 JSON 401
        mockMvc.perform(get("/admin/api/members/me").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("로그인된 대상자를 잠그면 다음 페이지 요청은 로그인 페이지로 리다이렉트된다")
    void lockedTarget_nextPageRequest_redirectsToLogin() throws Exception {
        MockHttpSession session = loginAsTarget();

        mockMvc.perform(get("/admin/member/info").session(session))
                .andExpect(status().isOk());

        adminMemberService.updateAdminMember(callerId, targetId,
                AdminMemberUpdateRequest.builder().status(MemberStatus.DISABLED).build());

        mockMvc.perform(get("/admin/member/info").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login"));
    }
}
