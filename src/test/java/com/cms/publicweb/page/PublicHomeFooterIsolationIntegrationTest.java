package com.cms.publicweb.page;

import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 푸터 조회가 실패해도 공개 메인이 200으로 나오고 기존 섹션이 유지되는지(PLAN-static-page.md R1-3). 푸터는 {@code getHome()}과 별도 호출·별도
 * 트랜잭션이라 푸터의 예외가 메인 조회 트랜잭션을 rollback-only로 만들지 않는다 — 서비스에 실제 DB 예외를 주입해 확인한다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class PublicHomeFooterIsolationIntegrationTest extends MariaDbContainerSupport {

    @Autowired MockMvc mockMvc;
    @MockitoSpyBean PublicSitePageService sitePageService;

    @Test
    @DisplayName("푸터 조회가 DB 예외로 실패해도 메인은 200이고 푸터만 빠진다")
    void footerFailureDoesNotBreakHome() throws Exception {
        Mockito.doThrow(new DataAccessResourceFailureException("주입된 푸터 조회 실패")).when(sitePageService).findFooterLinks(Mockito.anyLong());

        MvcResult result = mockMvc.perform(get("/main").with(request -> {
            request.setRemoteAddr("10.92.0.1");
            return request;
        })).andReturn();

        String html = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(html).contains("home-container").doesNotContain("<footer").doesNotContain("주입된 푸터 조회 실패");
        Mockito.verify(sitePageService).findFooterLinks(Mockito.anyLong());
    }
}
