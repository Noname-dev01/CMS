package com.cms.publicweb.support;

import com.cms.publicweb.board.service.PublicBoardService;
import com.cms.publicweb.faq.PublicFaqService;
import com.cms.publicweb.home.PublicHomeService;
import com.cms.publicweb.notice.service.PublicNoticeService;
import com.cms.publicweb.page.PublicSitePageService;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공개 사이트 라우팅의 실서버 시험(PLAN-site-menu.md PR ② 리뷰 라운드 1 지적 6) — MockMvc는 {@code sendError(404)} 뒤의 ERROR 재디스패치를 실행하지 않아
 * "같은 404 <b>화면</b>"과 HEAD의 본문 없음을 증명하지 못한다. 없는·비활성·형식 위반·예약어 사이트는 접두어 없는 옛 URL과 <b>같은 화면</b>이고,
 * 거부된 사이트는 공개 서비스에 도달하지 않으며, {@code /error} 직접 요청은 거부된다.
 */
@SpringBootTest(classes = CmsTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PublicSiteRoutingServerTest extends MariaDbContainerSupport {

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;

    @MockitoSpyBean PublicNoticeService publicNoticeService;
    @MockitoSpyBean PublicBoardService publicBoardService;
    @MockitoSpyBean PublicFaqService publicFaqService;
    @MockitoSpyBean PublicSitePageService publicSitePageService;
    @MockitoSpyBean PublicHomeService publicHomeService;

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @BeforeEach
    void setUp() {
        cleanSites();
        Mockito.clearInvocations(publicNoticeService, publicBoardService, publicFaqService, publicSitePageService, publicHomeService);
    }

    @AfterEach
    void cleanUp() {
        cleanSites();
    }

    private void cleanSites() {
        jdbc.update("DELETE FROM site WHERE id > 2");
        jdbc.update("UPDATE site SET default_yn = CASE WHEN id = 2 THEN 1 ELSE 0 END, use_yn = 1");
    }

    private HttpResponse<String> send(String method, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .header("Accept", "text/html")
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("없는·비활성·형식 위반·예약어 사이트와 옛 URL은 같은 404 화면이다 (GET, 로그인 302·JSON 아님)")
    void rejectedSitesRenderTheSameNotFoundScreen() throws Exception {
        jdbc.update("INSERT INTO site (site_key, site_name, site_type, allow_external_link, use_yn, default_yn) VALUES ('offsite', '꺼진 사이트', 'PUBLIC', 0, 0, 0)");
        jdbc.update("INSERT INTO site (site_key, site_name, site_type, allow_external_link, use_yn, default_yn) VALUES ('opsadmin', '관리 타입', 'ADMIN', 0, 1, 0)");

        String reference = send("GET", "/notices/1").body();    // 접두어 없는 옛 URL — 핸들러가 없어 컨테이너 404
        assertThat(reference).as("기준 404 화면").isNotBlank();

        for (String path : List.of("/nosuch", "/nosuch/notices", "/nosuch/boards/1", "/nosuch/pages/terms", "/nosuch/faq", "/nosuch/inquiry",
                "/MAIN", "/MAIN/faq", "/a_b/notices", "/offsite", "/offsite/faq", "/opsadmin", "/opsadmin/notices", "/notices", "/boards", "/faq")) {
            HttpResponse<String> response = send("GET", path);
            assertThat(response.statusCode()).as(path).isEqualTo(404);
            assertThat(response.headers().firstValue("Location")).as(path).isEmpty();
            assertThat(response.body()).as(path + " — 기준 404 화면과 같아야 한다").isEqualTo(reference);
        }
    }

    @Test
    @DisplayName("HEAD는 같은 판정이고 본문이 없다 — 첨부 다운로드 HEAD도 거부된 사이트에서는 404")
    void headRequests() throws Exception {
        for (String path : List.of("/nosuch", "/nosuch/notices/1/attachments/1", "/nosuch/boards/1/posts/2/attachments/3", "/MAIN/faq")) {
            HttpResponse<String> response = send("HEAD", path);
            assertThat(response.statusCode()).as(path).isEqualTo(404);
            assertThat(response.body()).as(path).isEmpty();
        }
        HttpResponse<String> ok = send("HEAD", "/main");
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body()).isEmpty();
    }

    @Test
    @DisplayName("거부된 사이트는 공개 서비스에 도달하지 않는다 — 유효한 사이트에서만 서비스가 불린다")
    void rejectedSites_neverReachPublicServices() throws Exception {
        for (String path : List.of("/nosuch", "/nosuch/notices", "/nosuch/notices/1", "/nosuch/boards/1", "/nosuch/boards/1/posts/2",
                "/nosuch/pages/terms", "/nosuch/faq", "/MAIN", "/MAIN/notices", "/notices/1")) {
            send("GET", path);
        }
        Mockito.verifyNoInteractions(publicNoticeService, publicBoardService, publicFaqService, publicSitePageService, publicHomeService);

        send("GET", "/main");
        send("GET", "/main/faq");
        Mockito.verify(publicHomeService).getHome(Mockito.anyLong());
        Mockito.verify(publicFaqService).findPublicFaqs(Mockito.anyLong());
    }

    @Test
    @DisplayName("/ 는 기본 사이트로 302이고 /error 직접 요청은 거부된다 (ERROR 디스패치만 열려 있다)")
    void rootRedirect_andDirectErrorDenied() throws Exception {
        HttpResponse<String> root = send("GET", "/");
        assertThat(root.statusCode()).isEqualTo(302);
        assertThat(root.headers().firstValue("Location").orElse("")).endsWith("/main");

        HttpResponse<String> error = send("GET", "/error");
        assertThat(error.statusCode()).isEqualTo(302);
        assertThat(error.headers().firstValue("Location").orElse("")).endsWith("/admin/login");
    }
}
