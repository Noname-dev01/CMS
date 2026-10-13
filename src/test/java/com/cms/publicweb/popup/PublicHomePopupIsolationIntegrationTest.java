package com.cms.publicweb.popup;

import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 팝업 조회가 실패해도 공개 메인이 200으로 나오고 기존 섹션이 유지되는지(PLAN-popup.md 쟁점 8·R1-6). 팝업은 {@code getHome()}과 별도 호출·별도
 * 트랜잭션이라 팝업의 예외가 메인 조회 트랜잭션을 rollback-only로 만들지 않는다 — 서비스에 실제 DB 예외를 주입해 확인한다. 푸터와 함께 실패해도
 * 메인은 살아 있어야 한다. 조회 위치가 {@code getHome()} 안으로 옮겨 가는 회귀를 이 시험이 잡는다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class PublicHomePopupIsolationIntegrationTest extends MariaDbContainerSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean PublicPopupService popupService;

    private MvcResult getHome() throws Exception {
        return mockMvc.perform(get("/main").with(request -> {
            request.setRemoteAddr("10.93.0.1");
            return request;
        })).andReturn();
    }

    @Test
    @DisplayName("팝업 조회가 DB 예외로 실패해도 메인은 200이고 기존 섹션이 유지되며 팝업만 빠진다")
    void popupFailureDoesNotBreakHome() throws Exception {
        jdbc.update("INSERT INTO popup (title, popup_type, content_html, pos_x, pos_y, width, use_yn) VALUES ('iso-popup', 'HTML', '<p>x</p>', 10, 10, 300, 1)");
        try {
            // 실패를 주입하기 전에는 정상 노출됨을 먼저 확인한다(주입이 효과가 있는 시험임을 보장)
            assertThat(getHome().getResponse().getContentAsString()).contains("iso-popup");

            Mockito.doThrow(new DataAccessResourceFailureException("주입된 팝업 조회 실패")).when(popupService).findDisplayablePopups();

            MvcResult result = getHome();
            String html = result.getResponse().getContentAsString();
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(html).contains("home-container").doesNotContain("home-popup").doesNotContain("iso-popup").doesNotContain("주입된 팝업 조회 실패");
            Mockito.verify(popupService, Mockito.atLeastOnce()).findDisplayablePopups();
        } finally {
            Mockito.reset(popupService);
            jdbc.update("DELETE FROM popup WHERE title = 'iso-popup'");
        }
    }
}
