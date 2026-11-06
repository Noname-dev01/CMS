package com.cms.publicweb.support;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.TestMembers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;

/**
 * 전송 상한의 적용 범위와 면제를 실제 보안·MVC 스택으로 고정한다(PLAN-public-transfer-limit.md D1·D5·D6, F3·F4). 슬롯 판정이 공개 판정보다 먼저라 파일이 없는 id로도
 * 게이트를 시험할 수 있다 — 포화면 503/429, 게이트를 지나면 404다. 반환·화면 렌더링은 {@link PublicTransferLimitServerTest}가 본다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class PublicTransferLimitIntegrationTest extends MariaDbContainerSupport {

    private static final String ATTACHMENT = "/main/boards/no-such-board/posts/1/attachments/1";
    private static final String CONTENT_IMAGE = "/content-images/999999999";
    private static final String BANNER = "/banners/999999999/image";
    private static final String POPUP = "/popups/999999999/image";

    @Autowired MockMvc mockMvc;
    @Autowired PublicTransferLimiter limiter;
    @Autowired MemberRepository memberRepository;
    @Autowired JdbcTemplate jdbc;

    private final List<String> held = new ArrayList<>();
    private final List<Long> memberIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        held.forEach(limiter::release);
        held.clear();
        TestMembers.delete(jdbc, memberIds);
    }

    private void hold(String ip, int count) {
        for (int i = 0; i < count; i++) {
            assertThat(limiter.tryAcquire(ip)).isEqualTo(PublicTransferLimiter.Decision.ACQUIRED);
            held.add(ip);
        }
    }

    /** 기본 설정(전체 100·IP별 20)의 전체 상한을 다른 IP 5개로 채운다. */
    private void saturateTotal() {
        for (int i = 0; i < 5; i++) {
            hold("10.66.0." + i, 20);
        }
    }

    private static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private Member admin() {
        Member member = TestMembers.save(memberRepository, "xfer", Role.ROLE_ADMIN);
        memberIds.add(member.getId());
        return member;
    }

    private int status(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(from("10.66.1.1"))).andReturn().getResponse().getStatus();
    }

    @ParameterizedTest
    @ValueSource(strings = {ATTACHMENT, CONTENT_IMAGE, BANNER, POPUP})
    @DisplayName("전체 상한이 차면 네 전송 경로의 익명 GET은 503과 Retry-After다")
    void saturatedTotalRejectsAnonymousGet(String path) throws Exception {
        saturateTotal();

        var response = mockMvc.perform(get(path).with(from("10.66.1.1"))).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("5");
    }

    @ParameterizedTest
    @ValueSource(strings = {ATTACHMENT, CONTENT_IMAGE, BANNER, POPUP})
    @DisplayName("상한이 비어 있으면 네 경로 모두 기존 404 그대로이고 슬롯이 남지 않는다")
    void freeSlotsKeepExistingBehavior(String path) throws Exception {
        assertThat(status(get(path))).isEqualTo(404);
        assertThat(limiter.inUse()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {ATTACHMENT, CONTENT_IMAGE, BANNER, POPUP})
    @DisplayName("HEAD는 본문을 읽지 않아 상한을 받지 않는다")
    void headIsNotLimited(String path) throws Exception {
        saturateTotal();

        assertThat(status(head(path))).isEqualTo(404);
        assertThat(limiter.inUse()).isEqualTo(100);
    }

    @Test
    @DisplayName("없는 사이트의 첨부 요청은 포화 상태에서도 사이트 해석의 같은 404다 — 사이트 해석이 슬롯 판정보다 먼저다")
    void unknownSiteIsResolvedBeforeSlot() throws Exception {
        saturateTotal();

        assertThat(status(get("/no-such-site/boards/x/posts/1/attachments/1"))).isEqualTo(404);
    }

    @Test
    @DisplayName("포화 상태에서 로그인 세션의 본문 이미지는 슬롯 없이 통과하고 남의 슬롯도 반환하지 않는다")
    void authenticatedImageBypassesLimit() throws Exception {
        saturateTotal();

        int status = mockMvc.perform(get(CONTENT_IMAGE).with(TestMembers.asMember(admin())))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(404);
        assertThat(limiter.inUse()).isEqualTo(100);
    }

    @Test
    @DisplayName("포화 상태에서 공개 페이지와 관리자 화면은 정상이다")
    void pagesSurviveSaturation() throws Exception {
        saturateTotal();

        assertThat(status(get("/main"))).isEqualTo(200);
        int adminStatus = mockMvc.perform(get("/admin").with(TestMembers.asMember(admin())))
                .andReturn().getResponse().getStatus();
        assertThat(adminStatus).isEqualTo(200);
        assertThat(limiter.inUse()).isEqualTo(100);
    }

    @Test
    @DisplayName("IP별 상한이 차면 그 IP만 429이고 다른 IP는 게이트를 지난다")
    void perIpLimitAffectsOnlyThatIp() throws Exception {
        hold("10.66.2.1", 20);

        var rejected = mockMvc.perform(get(BANNER).with(from("10.66.2.1"))).andReturn().getResponse();
        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getHeader("Retry-After")).isEqualTo("5");

        assertThat(mockMvc.perform(get(BANNER).with(from("10.66.2.2"))).andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("두 상한이 함께 차 있으면 429다")
    void bothLimitsAnswerTooManyRequests() throws Exception {
        saturateTotal();

        assertThat(mockMvc.perform(get(BANNER).with(from("10.66.0.0"))).andReturn().getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("X-Forwarded-For를 바꿔도 IP별 집계를 벗어나거나 다른 IP로 집계되지 않는다")
    void forwardedHeaderIsIgnored() throws Exception {
        hold("10.66.3.1", 20);

        int spoofedOut = mockMvc.perform(get(BANNER).with(from("10.66.3.1")).header("X-Forwarded-For", "1.2.3.4"))
                .andReturn().getResponse().getStatus();
        assertThat(spoofedOut).as("가득 찬 IP가 헤더로 벗어나려는 경우").isEqualTo(429);

        int spoofedIn = mockMvc.perform(get(BANNER).with(from("10.66.3.2")).header("X-Forwarded-For", "10.66.3.1"))
                .andReturn().getResponse().getStatus();
        assertThat(spoofedIn).as("다른 IP를 사칭해 막으려는 경우").isEqualTo(404);
    }
}
