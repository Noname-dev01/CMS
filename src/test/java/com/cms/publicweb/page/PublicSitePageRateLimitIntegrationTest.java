package com.cms.publicweb.page;

import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 공개 정적 페이지 레이트리밋 규칙 {@code public-page}(PLAN-static-page.md 쟁점 12). 테스트 환경은 {@code CMS_RATE_LIMIT_ENABLED=false}라
 * 규칙을 켠 별도 컨텍스트에서 실제 {@code application.yml}의 규칙으로 확인한다 — 다른 시험의 컨텍스트에는 영향이 없다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = "cms.rate-limit.enabled=true")
class PublicSitePageRateLimitIntegrationTest extends MariaDbContainerSupport {

    @Autowired MockMvc mockMvc;

    private static RequestPostProcessor fromIp(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    @Test
    @DisplayName("같은 IP의 /pages/** 반복 요청은 한도(120/60초)를 넘으면 429이고 다른 IP는 영향이 없다 (존재하지 않는 슬러그로도 소비된다)")
    void rateLimited() throws Exception {
        int ok = 0;
        int limited = 0;
        for (int i = 0; i < 130; i++) {
            int status = mockMvc.perform(get("/pages/nothing-here").with(fromIp("10.93.0.1"))).andReturn().getResponse().getStatus();
            if (status == 429) {
                limited++;
            } else {
                assertThat(status).isEqualTo(404);
                ok++;
            }
        }

        // 토큰 버킷이라 버스트 상한 120에 요청하는 동안 채워진 몇 개(평균 2개/초)가 더해질 수 있다
        assertThat(ok).as("버스트 상한 120 + 그동안의 보충").isBetween(120, 126);
        assertThat(limited).as("한도 초과분은 429").isGreaterThanOrEqualTo(4);
        assertThat(mockMvc.perform(get("/pages/nothing-here").with(fromIp("10.93.0.2"))).andReturn().getResponse().getStatus())
                .as("다른 IP는 별도 버킷").isEqualTo(404);
    }
}
