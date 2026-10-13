package com.cms.publicweb.inquiry;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.cms.admin.inquiry.service.InquiryService;
import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.notification.service.NotificationRecorder;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.TestMembers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 공개 문의 폼 실제 스택 시험(PLAN-inquiry.md) — 접수 왕복, 400 입력 보존·캡차 비호출, 소프트 상한 503, 로그 비유출, 알림 실패 격리.
 * 레이트리밋은 끈다(MockMvc의 원격 주소가 모두 같아 시험끼리 quota를 나눠 쓴다 — 429는 {@code PublicInquiryRateLimitIntegrationTest}가 따로 본다).
 * 캡차 검증기는 가짜로 대체한다(네트워크 없음).
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = "cms.rate-limit.enabled=false")
class PublicInquiryIntegrationTest extends MariaDbContainerSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberRepository memberRepository;
    @MockitoBean CaptchaVerifier captchaVerifier;
    @MockitoSpyBean InquiryService inquiryService;
    @MockitoSpyBean NotificationRecorder notificationRecorder;

    private Member admin;
    private final List<Long> memberIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM inquiry");
        admin = TestMembers.save(memberRepository, "pi-admin", Role.ROLE_ADMIN);
        memberIds.add(admin.getId());
        when(captchaVerifier.verify(any(), any())).thenReturn(true);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM inquiry");
        TestMembers.delete(jdbc, memberIds);
    }

    private MockHttpServletRequestBuilder submit(String name, String email, String title, String body, boolean consent) {
        MockHttpServletRequestBuilder request = post("/main/inquiry").with(csrf())
                .param("name", name).param("email", email).param("title", title).param("body", body)
                .param("cf-turnstile-response", "token-ok");
        if (consent) {
            request.param("consent", "true");
        }
        return request;
    }

    private MockHttpServletRequestBuilder validSubmit() {
        return submit("홍길동", "visitor@example.com", "배송 문의", "언제 도착하나요?\n빨리 알려 주세요.", true);
    }

    private int inquiryCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM inquiry", Integer.class);
    }

    private int unreadInquiryNotifications(Member member) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE member_id = ? AND type = 'INQUIRY_RECEIVED' AND read_at IS NULL",
                Integer.class, member.getId());
    }

    private void insertInquiries(int count, String status) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(new Object[]{"n", "n@example.com", "t", "b", status});
        }
        jdbc.batchUpdate("INSERT INTO inquiry (name, email, title, body, status, consent_at, create_date, update_date) "
                + "VALUES (?, ?, ?, ?, ?, NOW(6), NOW(6), NOW(6))", rows);
    }

    @Test
    @DisplayName("GET /inquiry: 폼·사이트 키·Turnstile 스크립트·CSRF hidden이 렌더링된다 (HEAD도 200)")
    void form_rendered() throws Exception {
        String html = mockMvc.perform(get("/main/inquiry")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("data-sitekey=\"1x00000000000000000000AA\"")
                .contains("https://challenges.cloudflare.com/turnstile/v0/api.js")
                .contains("name=\"_csrf\"")
                .contains("name=\"consent\"")
                .contains("개인정보 수집·이용");
        mockMvc.perform(head("/main/inquiry")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /inquiry: CSRF 토큰이 없으면 403이고 저장되지 않으며 캡차 검증기도 호출되지 않는다")
    void post_withoutCsrf_forbidden() throws Exception {
        mockMvc.perform(post("/main/inquiry").param("name", "n").param("email", "a@b.co").param("title", "t").param("body", "b")
                        .param("consent", "true").param("cf-turnstile-response", "t"))
                .andExpect(status().isForbidden());

        assertThat(inquiryCount()).isZero();
        verify(captchaVerifier, never()).verify(any(), any());
    }

    @Test
    @DisplayName("접수 왕복: 303 → /inquiry?sent, 문의 저장(상태 RECEIVED·동의 시각), 캡차 토큰 검증 1회, 관리자에게 상수 문장 알림, 완료 안내 화면")
    void submit_roundTrip() throws Exception {
        mockMvc.perform(validSubmit())
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/main/inquiry?sent"));

        assertThat(inquiryCount()).isEqualTo(1);
        var row = jdbc.queryForMap("SELECT name, email, title, body, status, consent_at, create_date FROM inquiry");
        assertThat(row.get("name")).isEqualTo("홍길동");
        assertThat(row.get("email")).isEqualTo("visitor@example.com");
        assertThat(row.get("title")).isEqualTo("배송 문의");
        assertThat(row.get("body")).isEqualTo("언제 도착하나요?\n빨리 알려 주세요.");
        assertThat(row.get("status")).isEqualTo("RECEIVED");
        assertThat(row.get("consent_at")).isNotNull().isEqualTo(row.get("create_date"));
        verify(captchaVerifier, times(1)).verify(any(), any());

        assertThat(unreadInquiryNotifications(admin)).isEqualTo(1);
        var notification = jdbc.queryForMap("SELECT message, link_url FROM notification WHERE member_id = ? AND type = 'INQUIRY_RECEIVED'", admin.getId());
        assertThat(notification.get("message")).isEqualTo("새 문의가 접수되었습니다. 문의함에서 확인해주세요.");
        assertThat((String) notification.get("message")).doesNotContain("홍길동", "visitor@example.com", "배송");
        assertThat(notification.get("link_url")).isEqualTo("/admin/inquiry/manage");

        mockMvc.perform(get("/main/inquiry").param("sent", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("문의가 접수되었습니다")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("name=\"email\""))));
    }

    @Test
    @DisplayName("검증 실패: 400, 입력 보존, 고정 오류 문구, 저장 없음, 캡차 검증기 호출 0회 (제어문자·형식·동의 누락)")
    void validationFailure_400_preservesInput_noCaptchaCall() throws Exception {
        String html = mockMvc.perform(submit("홍길동", "not-an-email", "제목\u0000", "본문 내용", false))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("value=\"홍길동\"").contains("본문 내용")
                .contains("올바른 이메일 주소를 입력해 주세요").contains("개인정보 수집·이용에 동의해야")
                .contains("제목은 1~100자");
        assertThat(inquiryCount()).isZero();
        verify(captchaVerifier, never()).verify(any(), any());
        verify(inquiryService, never()).submit(any());
    }

    @Test
    @DisplayName("보존된 입력은 이스케이프되어 되돌아온다 — 스크립트 태그가 그대로 출력되지 않는다")
    void preservedInput_isEscaped() throws Exception {
        String attack = "\"><script>alert(1)</script>";
        String html = mockMvc.perform(submit(attack, "bad", "t", "</textarea><script>alert(2)</script>", true))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("<script>alert(1)</script>").doesNotContain("<script>alert(2)</script>")
                .contains("&lt;script&gt;alert(1)&lt;/script&gt;").contains("&lt;script&gt;alert(2)&lt;/script&gt;");
    }

    @Test
    @DisplayName("변환할 수 없는 폼 값(consent=invalid)도 500·로그 복제 없이 400 폼으로 처리된다 — 입력 보존, 캡차·저장 없음 (코드 리뷰 R1-1)")
    void unbindableValue_is400_notAdvice500() throws Exception {
        String html = mockMvc.perform(post("/main/inquiry").with(csrf()).param("name", "홍길동").param("email", "a@b.co").param("title", "t")
                        .param("body", "본문").param("consent", "LEAK-bad-value").param("cf-turnstile-response", "token"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("개인정보 수집·이용에 동의해야").contains("value=\"홍길동\"");
        assertThat(inquiryCount()).isZero();
        verify(captchaVerifier, never()).verify(any(), any());
    }

    @Test
    @DisplayName("상한 사전 조회가 DB 예외를 던져도 입력을 보존한 500 폼으로 답하고 캡차·저장은 호출되지 않으며 예외 값이 로그에 남지 않는다 (코드 리뷰 R2-1)")
    void capCheckFailure_preservesInput() throws Exception {
        doThrow(new RuntimeException("db down LEAK-cap-9d1")).when(inquiryService).isClosed();

        String html = mockMvc.perform(validSubmit()).andExpect(status().isInternalServerError()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("일시적인 오류로 문의를 접수하지 못했습니다").contains("value=\"홍길동\"").doesNotContain("LEAK-cap-9d1");
        assertThat(inquiryCount()).isZero();
        verify(captchaVerifier, never()).verify(any(), any());
    }

    @Test
    @DisplayName("캡차 토큰이 없거나 비었거나 2048자를 넘으면 400, 외부 검증 호출 0회 (R3-2)")
    void captchaToken_formatErrors_neverCallVerifier() throws Exception {
        mockMvc.perform(post("/main/inquiry").with(csrf()).param("name", "n").param("email", "a@b.co").param("title", "t")
                        .param("body", "b").param("consent", "true"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/main/inquiry").with(csrf()).param("name", "n").param("email", "a@b.co").param("title", "t")
                        .param("body", "b").param("consent", "true").param("cf-turnstile-response", "   "))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/main/inquiry").with(csrf()).param("name", "n").param("email", "a@b.co").param("title", "t")
                        .param("body", "b").param("consent", "true").param("cf-turnstile-response", "x".repeat(2049)))
                .andExpect(status().isBadRequest());

        assertThat(inquiryCount()).isZero();
        verify(captchaVerifier, never()).verify(any(), any());

        // 정확히 2048자는 형식 검사를 통과해 검증기로 간다
        mockMvc.perform(post("/main/inquiry").with(csrf()).param("name", "n").param("email", "a@b.co").param("title", "t")
                        .param("body", "b").param("consent", "true").param("cf-turnstile-response", "x".repeat(2048)))
                .andExpect(status().isSeeOther());
        verify(captchaVerifier, times(1)).verify(any(), any());
    }

    @Test
    @DisplayName("캡차 검증 실패(fail-closed): 400, 입력 보존, 저장·알림 없음")
    void captchaRejected_400_nothingStored() throws Exception {
        when(captchaVerifier.verify(any(), any())).thenReturn(false);

        String html = mockMvc.perform(validSubmit()).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("자동 입력 방지 확인에 실패했습니다").contains("value=\"홍길동\"");
        assertThat(inquiryCount()).isZero();
        assertThat(unreadInquiryNotifications(admin)).isZero();
    }

    @Test
    @DisplayName("미처리 1,000건 소프트 상한: 도달하면 POST·GET 모두 503이고 POST는 입력을 보존·캡차 비호출, 답변 완료 건은 세지 않는다")
    void softCap_blocksNewInquiries() throws Exception {
        insertInquiries(999, "RECEIVED");
        insertInquiries(50, "ANSWERED");
        mockMvc.perform(validSubmit()).andExpect(status().isSeeOther());          // 미처리 999 → 1,000번째는 받는다
        assertThat(inquiryCount()).isEqualTo(1050);

        String html = mockMvc.perform(validSubmit()).andExpect(status().isServiceUnavailable()).andReturn().getResponse().getContentAsString();   // 미처리 1,000
        assertThat(html).contains("현재 문의를 받을 수 없습니다").contains("value=\"홍길동\"");
        assertThat(inquiryCount()).isEqualTo(1050);
        verify(captchaVerifier, times(1)).verify(any(), any());                   // 두 번째 시도는 캡차 호출 전에 막혔다
        mockMvc.perform(get("/main/inquiry")).andExpect(status().isServiceUnavailable());

        // 방금 상한을 채운 접수의 PRG 완료 화면은 상한과 무관하게 성공으로 안내한다 (코드 리뷰 R1-2)
        mockMvc.perform(get("/main/inquiry").param("sent", "")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("문의가 접수되었습니다")));

        jdbc.update("UPDATE inquiry SET status = 'ANSWERED' WHERE status = 'RECEIVED' LIMIT 10");
        mockMvc.perform(validSubmit()).andExpect(status().isSeeOther());          // 답변 완료로 바뀌면 다시 받는다
    }

    @Test
    @DisplayName("접수 중 예기치 않은 예외: 500 폼(입력 보존), 응답·서버 로그 어디에도 예외 메시지·원인 체인의 값이 남지 않고 클래스명만 남는다 (R1-2)")
    void unexpectedFailure_doesNotLeakIntoResponseOrLogs() throws Exception {
        String leak = "LEAK-7f3a9c-홍길동-body";
        doThrow(new RuntimeException("INSERT failed: " + leak, new IllegalStateException("cause " + leak)))
                .when(inquiryService).submit(any());
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            String html = mockMvc.perform(validSubmit()).andExpect(status().isInternalServerError()).andReturn().getResponse().getContentAsString();

            assertThat(html).contains("일시적인 오류로 문의를 접수하지 못했습니다").contains("value=\"홍길동\"").doesNotContain("LEAK-7f3a9c");
            assertThat(appender.list).as("실패가 ERROR로 남아야 한다").anyMatch(event -> event.getLevel().toString().equals("ERROR")
                    && event.getFormattedMessage().contains("java.lang.RuntimeException"));
            for (ILoggingEvent event : appender.list) {
                assertThat(event.getFormattedMessage()).doesNotContain("LEAK-7f3a9c");
                for (IThrowableProxy proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
                    assertThat(proxy.getMessage()).doesNotContain("LEAK-7f3a9c");
                }
            }
        } finally {
            root.detachAppender(appender);
        }
        assertThat(inquiryCount()).isZero();
    }

    @Test
    @DisplayName("알림 저장 실패는 접수를 되돌리지 않는다 — 303이고 문의는 저장돼 있다 (최선 노력 계약)")
    void notificationFailure_doesNotRollBackInquiry() throws Exception {
        doThrow(new IllegalStateException("notification store down")).when(notificationRecorder).recordInquiryReceived();

        mockMvc.perform(validSubmit()).andExpect(status().isSeeOther());

        assertThat(inquiryCount()).isEqualTo(1);
        assertThat(unreadInquiryNotifications(admin)).isZero();
    }

    @Test
    @DisplayName("미읽음 문의 알림이 있는 관리자에게는 새 알림을 만들지 않고, 읽은 뒤 접수하면 다시 만든다 (병합)")
    void unreadNotification_isMerged() throws Exception {
        mockMvc.perform(validSubmit()).andExpect(status().isSeeOther());
        mockMvc.perform(validSubmit()).andExpect(status().isSeeOther());
        mockMvc.perform(validSubmit()).andExpect(status().isSeeOther());

        assertThat(inquiryCount()).isEqualTo(3);
        assertThat(unreadInquiryNotifications(admin)).isEqualTo(1);

        jdbc.update("UPDATE notification SET read_at = NOW(6) WHERE member_id = ? AND type = 'INQUIRY_RECEIVED'", admin.getId());
        mockMvc.perform(validSubmit()).andExpect(status().isSeeOther());

        assertThat(unreadInquiryNotifications(admin)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE member_id = ? AND type = 'INQUIRY_RECEIVED'",
                Integer.class, admin.getId())).isEqualTo(2);
    }

    @Test
    @DisplayName("PUT /inquiry와 하위 경로는 라우트가 없고 SecurityConfig가 거부한다 — 저장 없음")
    void otherMethodsAndSubPaths_denied() throws Exception {
        mockMvc.perform(put("/main/inquiry").with(csrf())).andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/main/inquiry/1").with(csrf())).andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/main/inquiry/anything")).andExpect(status().is3xxRedirection());

        assertThat(inquiryCount()).isZero();
    }
}
