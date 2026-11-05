package com.cms.admin.member.password;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.support.BrowserTestSupport;
import com.cms.support.CmsTestApplication;
import com.cms.support.TestMembers;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * 재설정 화면이 새 비밀번호 거부를 링크 문제와 구분해 보여 주고, 같은 화면에서 다시 입력해 끝낼 수 있음을 실제 브라우저로 고정한다
 * (PLAN-password-expiry-retirement-breach-check.md D5). 화면 JS의 분기라 MockMvc로는 시험할 수 없다.
 */
@SpringBootTest(classes = CmsTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PasswordResetRejectionBrowserTest extends BrowserTestSupport {

    private static final String TOKEN = "fedcba9876543210".repeat(4);
    private static final String BREACHED = "breached-password-value-1";
    private static final String GOOD = "good-password-value-22222";

    @LocalServerPort int port;
    @Autowired MemberRepository memberRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    @MockitoBean BreachedPasswordChecker breachedPasswordChecker;

    private BrowserContext context;
    private Page page;
    private Member member;

    @BeforeEach
    void setUp() {
        context = newContext();
        page = context.newPage();
    }

    @AfterEach
    void tearDown() {
        context.close();
        if (member != null) {
            TestMembers.delete(jdbc, List.of(member.getId()));
        }
    }

    private void saveMember(String tokenHash) {
        LocalDateTime now = LocalDateTime.now(clock);
        String unique = "reset-ui-" + System.nanoTime();
        member = memberRepository.save(Member.builder()
                .userId(unique)
                .pwd(passwordEncoder.encode("current-password-value-1"))
                .userName("재설정화면")
                .email(unique + "@breach.test")
                .userType(Role.ROLE_MANAGER)
                .status(MemberStatus.ACTIVE)
                .createDate(now)
                .updateDate(now)
                .passwordChangedAt(now)
                .resetToken(tokenHash)
                .resetTokenExpiryAt(now.plusMinutes(30))
                .build());
    }

    private static String sha256Hex(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private void submit(String password) {
        page.locator("#newPasswordInput").fill(password);
        page.locator("#confirmPasswordInput").fill(password);
        page.locator("#submitButton").click();
    }

    @Test
    @DisplayName("유출 거부는 서버 사유를 보여 주고 폼이 남으며, 다른 값으로 다시 제출하면 끝난다")
    void rejectionShowsReasonAndRetrySucceeds() throws Exception {
        saveMember(sha256Hex(TOKEN));
        given(breachedPasswordChecker.isBreached(BREACHED)).willReturn(true);

        page.navigate("http://localhost:" + port + "/admin/password-reset/confirm#token=" + TOKEN);
        submit(BREACHED);

        assertThat(page.locator("#resultAlert")).hasText("유출된 적이 있는 비밀번호입니다. 다른 비밀번호를 입력해 주세요.");
        assertThat(page.locator("#resetConfirmForm")).isVisible();
        assertThat(page.locator("#submitButton")).isEnabled();

        submit(GOOD);

        assertThat(page.locator("#resultAlert")).hasText("비밀번호가 변경되었습니다. 새 비밀번호로 로그인해 주세요.");
        assertThat(passwordEncoder.matches(GOOD, memberRepository.findById(member.getId()).orElseThrow().getPwd())).isTrue();
    }

    @Test
    @DisplayName("무효 토큰은 지금과 같은 링크 안내를 보여 준다")
    void invalidTokenKeepsLinkMessage() {
        page.navigate("http://localhost:" + port + "/admin/password-reset/confirm#token=" + "a".repeat(64));
        submit(GOOD);

        assertThat(page.locator("#resultAlert")).hasText("링크가 유효하지 않거나 만료되었습니다. 다시 요청해 주세요.");
    }
}
