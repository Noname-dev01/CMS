package com.cms.admin.banner;

import com.cms.admin.banner.service.BannerService;
import com.cms.admin.log.constant.AdminActionTypes;
import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.common.image.ImageFileValidatorTest;
import com.cms.common.storage.FileStorage;
import com.cms.common.storage.StorageFileNotFoundException;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.TestMembers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 배너 관리와 공개 메인·배너 이미지의 실제 스택 시험(PLAN-public-home-banner.md): SecurityConfig·판정기·감사·MariaDB·로컬 스토리지.
 * MockMvc 호출이 실제로 커밋하므로 {@link #cleanUp()}이 만든 배너와 파일을 지운다. 공개 요청은 시험마다 고유 원격 IP로 레이트리밋 버킷을 격리한다.
 * 기간은 시스템 시각 ±1일로 잡아 JVM 기본 시간대(UTC/KST)와 무관하게 경계에서 멀리 둔다(경계 자체는 {@code BannerRepositoryDataJpaTest}).
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class BannerApiIntegrationTest extends MariaDbContainerSupport {

    private static final AtomicInteger IP_SEQUENCE = new AtomicInteger(1);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberRepository memberRepository;
    @Autowired FileStorage fileStorage;
    @Autowired ObjectMapper objectMapper;

    private Member admin;
    private String ip;
    private long auditBaseline;

    @BeforeEach
    void setUp() {
        int n = IP_SEQUENCE.getAndIncrement();
        ip = "10.89." + (n / 250) + "." + (n % 250 + 1);
        admin = TestMembers.save(memberRepository, "bn-admin", Role.ROLE_ADMIN);
        auditBaseline = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM admin_action_log", Long.class);
        cleanBanners();
    }

    @AfterEach
    void cleanUp() {
        cleanBanners();
        TestMembers.delete(jdbc, List.of(admin.getId()));
        jdbc.update("DELETE FROM admin_action_log WHERE id > ?", auditBaseline);
    }

    private void cleanBanners() {
        for (String key : jdbc.queryForList("SELECT storage_key FROM banner", String.class)) {
            fileStorage.delete(key, BannerService.STORAGE_NAMESPACE);
        }
        jdbc.update("DELETE FROM banner");
    }

    private RequestPostProcessor asAdmin() {
        return TestMembers.asMember(admin);
    }

    private RequestPostProcessor fromIp() {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private static byte[] png() throws Exception {
        return ImageFileValidatorTest.png(30, 12);
    }

    private MvcResult createBanner(String title, String link, String start, String end, Boolean useYn, byte[] image, String contentType) throws Exception {
        var builder = multipart("/admin/api/banners")
                .file(new MockMultipartFile("image", "b.png", contentType, image))
                .param("title", title)
                .with(asAdmin()).with(csrf());
        if (link != null) { builder.param("linkUrl", link); }
        if (start != null) { builder.param("displayStart", start); }
        if (end != null) { builder.param("displayEnd", end); }
        if (useYn != null) { builder.param("useYn", String.valueOf(useYn)); }
        return mockMvc.perform(builder).andReturn();
    }

    private long createOk(String title, String link, LocalDateTime start, LocalDateTime end, Boolean useYn) throws Exception {
        MvcResult result = createBanner(title, link, start == null ? null : start.toString(), end == null ? null : end.toString(),
                useYn, png(), "image/png");
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    // ===== 생명주기 =====

    @Test
    @DisplayName("ADMIN 등록→조회→수정(전체 교체, null 해제)→이미지 미리보기→순서 저장→삭제 왕복, 감사 로그와 파일 정리까지")
    void lifecycle() throws Exception {
        byte[] original = png();
        LocalDateTime start = LocalDateTime.now().plusDays(1).withSecond(30).withNano(0);
        LocalDateTime end = start.plusDays(5);
        MvcResult created = createBanner("가을 행사", "/boards/2", start.toString(), end.toString(), true, original, "image/png");
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = json(created);
        long id = body.get("id").asLong();
        assertThat(LocalDateTime.parse(body.get("displayStart").asText())).as("초 단위는 분으로 절단").isEqualTo(start.withSecond(0));
        assertThat(body.get("status").asText()).as("시작 전이면 예약됨").isEqualTo("SCHEDULED");
        assertThat(body.get("ord").asInt()).isZero();

        String storageKey = jdbc.queryForObject("SELECT storage_key FROM banner WHERE id = ?", String.class, id);
        assertThat(fileStorage.load(storageKey, BannerService.STORAGE_NAMESPACE)).isEqualTo(original);

        // 관리자 미리보기는 노출 여부와 무관하게 저장된 바이트를 그대로 준다
        MvcResult image = mockMvc.perform(get("/admin/api/banners/{id}/image", id).with(asAdmin())).andReturn();
        assertThat(image.getResponse().getStatus()).isEqualTo(200);
        assertThat(image.getResponse().getContentAsByteArray()).isEqualTo(original);
        assertThat(image.getResponse().getContentType()).isEqualTo("image/png");
        assertThat(image.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");

        // 수정: 전체 교체 — 링크·종료를 null로 해제하고 노출을 끈다
        String update = objectMapper.writeValueAsString(Map.of("title", "새 제목", "useYn", false));
        MvcResult updated = mockMvc.perform(put("/admin/api/banners/{id}", id).with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(update)).andReturn();
        assertThat(updated.getResponse().getStatus()).isEqualTo(200);
        JsonNode updatedBody = json(updated);
        assertThat(updatedBody.get("title").asText()).isEqualTo("새 제목");
        assertThat(updatedBody.path("linkUrl").isNull() || updatedBody.path("linkUrl").isMissingNode()).isTrue();
        assertThat(updatedBody.path("displayStart").isNull() || updatedBody.path("displayStart").isMissingNode()).isTrue();
        assertThat(updatedBody.get("status").asText()).isEqualTo("HIDDEN");

        long second = createOk("둘째", null, null, null, true);
        MvcResult ordered = mockMvc.perform(put("/admin/api/banners/order").with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[" + second + "," + id + "]}")).andReturn();
        assertThat(ordered.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(ordered).get(0).get("id").asLong()).isEqualTo(second);
        assertThat(jdbc.queryForObject("SELECT ord FROM banner WHERE id = ?", Integer.class, id)).isEqualTo(1);

        assertThat(mockMvc.perform(delete("/admin/api/banners/{id}", id).with(asAdmin()).with(csrf())).andReturn().getResponse().getStatus())
                .isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM banner WHERE id = ?", Integer.class, id)).isZero();
        assertThatThrownBy(() -> fileStorage.load(storageKey, BannerService.STORAGE_NAMESPACE))
                .as("커밋 후 이미지 파일도 지워진다").isInstanceOf(StorageFileNotFoundException.class);
        assertThat(mockMvc.perform(delete("/admin/api/banners/{id}", id).with(asAdmin()).with(csrf())).andReturn().getResponse().getStatus())
                .as("이미 삭제됨").isEqualTo(404);

        List<String> actions = jdbc.queryForList("SELECT action_type FROM admin_action_log WHERE id > ? AND target_type = 'BANNER' ORDER BY id",
                String.class, auditBaseline);
        assertThat(actions).contains(AdminActionTypes.BANNER_CREATE, AdminActionTypes.BANNER_UPDATE, AdminActionTypes.BANNER_ORDER,
                AdminActionTypes.BANNER_DELETE);
        assertThat(jdbc.queryForObject("SELECT target_id FROM admin_action_log WHERE id > ? AND action_type = 'BANNER_DELETE' AND action_result = 'SUCCESS'",
                Long.class, auditBaseline)).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_action_log WHERE id > ? AND action_type = 'BANNER_ORDER' AND target_id IS NULL",
                Integer.class, auditBaseline)).as("순서 저장은 targetId 없음").isEqualTo(1);
    }

    // ===== 검증 =====

    @Test
    @DisplayName("등록 검증: 허용 외 링크 스킴·프로토콜 상대·너무 긴 링크·역전된 기간·빈 제목·이미지 없음·이미지 아닌 파일·크기 초과는 모두 400이고 행도 파일도 남기지 않는다")
    void createValidation() throws Exception {
        for (String badLink : List.of("javascript:alert(1)", "data:text/html,x", "//evil.example", "ftp://example.com", "https://a@evil.example", "x".repeat(501))) {
            assertThat(createBanner("t", badLink, null, null, true, png(), "image/png").getResponse().getStatus()).as(badLink).isEqualTo(400);
        }
        assertThat(createBanner("t", null, "2026-10-20T00:00", "2026-10-10T00:00", true, png(), "image/png").getResponse().getStatus()).isEqualTo(400);
        assertThat(createBanner("t", null, "2026-10-10T12:00:00.100", "2026-10-10T12:00:00.900", true, png(), "image/png").getResponse().getStatus())
                .as("소수 초 절단 후 같아짐").isEqualTo(400);
        assertThat(createBanner("   ", null, null, null, true, png(), "image/png").getResponse().getStatus()).isEqualTo(400);
        assertThat(createBanner("t", null, null, null, true, "x".getBytes(), "text/plain").getResponse().getStatus()).isEqualTo(400);
        assertThat(createBanner("t", null, null, null, true, new byte[2 * 1024 * 1024 + 1], "image/png").getResponse().getStatus()).isEqualTo(400);
        assertThat(createBanner("t", null, null, null, true, ImageFileValidatorTest.png(2561, 8), "image/png").getResponse().getStatus())
                .as("한 변 2560px 초과").isEqualTo(400);
        MvcResult noImage = mockMvc.perform(multipart("/admin/api/banners").param("title", "t").with(asAdmin()).with(csrf())).andReturn();
        assertThat(noImage.getResponse().getStatus()).isEqualTo(400);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM banner", Integer.class)).isZero();
    }

    @Test
    @DisplayName("허용되는 링크: 내부 경로와 http/https 절대 주소")
    void createAllowedLinks() throws Exception {
        createOk("내부", "/boards/2", null, null, true);
        createOk("https", "https://example.com/a?b=c", null, null, true);
        createOk("http", "http://example.com", null, null, true);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM banner", Integer.class)).isEqualTo(3);
    }

    @Test
    @DisplayName("수정 검증: 링크 형식 위반·기간 역전·노출 여부 누락은 400, 없는 배너는 404, 기간 해제는 null로 가능하다")
    void updateValidationAndPeriodClearing() throws Exception {
        long id = createOk("대상", null, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1), true);

        assertThat(updateStatus(id, Map.of("title", "t", "useYn", true, "linkUrl", "javascript:1"))).isEqualTo(400);
        assertThat(updateStatus(id, Map.of("title", "t", "useYn", true, "displayStart", "2026-10-20T00:00", "displayEnd", "2026-10-10T00:00"))).isEqualTo(400);
        assertThat(updateStatus(id, Map.of("title", "t"))).as("useYn 누락").isEqualTo(400);
        assertThat(updateStatus(999_999L, Map.of("title", "t", "useYn", true))).isEqualTo(404);

        // 종료만 지우기(만료된 배너를 되살리는 시나리오)
        jdbc.update("UPDATE banner SET display_end = ? WHERE id = ?", LocalDateTime.now().minusDays(2), id);
        assertThat(updateStatus(id, Map.of("title", "다시", "useYn", true, "displayStart", LocalDateTime.now().minusDays(3).toString()))).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT display_end FROM banner WHERE id = ?", Object.class, id)).isNull();
    }

    private int updateStatus(long id, Map<String, Object> body) throws Exception {
        return mockMvc.perform(put("/admin/api/banners/{id}", id).with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body))).andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("개수 상한: 10개까지 등록되고 11번째는 409")
    void limit() throws Exception {
        for (int i = 0; i < BannerService.MAX_BANNERS; i++) {
            createOk("b" + i, null, null, null, true);
        }
        assertThat(createBanner("초과", null, null, null, true, png(), "image/png").getResponse().getStatus()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM banner", Integer.class)).isEqualTo(BannerService.MAX_BANNERS);
    }

    @Test
    @DisplayName("순서 저장: 집합이 다르면 409(낡은 화면), 중복·누락(null)은 400, 변경 없음은 200")
    void orderValidation() throws Exception {
        long a = createOk("A", null, null, null, true);
        long b = createOk("B", null, null, null, true);

        assertThat(orderStatus("[" + a + "]")).as("일부만").isEqualTo(409);
        assertThat(orderStatus("[" + a + "," + b + ",999999]")).as("없는 id 포함").isEqualTo(409);
        assertThat(orderStatus("[" + a + "," + a + "]")).as("중복").isEqualTo(400);
        assertThat(orderStatus("[" + a + ",null]")).as("null 원소").isEqualTo(400);
        assertThat(orderStatus("null")).as("ids 누락").isEqualTo(400);
        assertThat(orderStatus("[" + a + "," + b + "]")).as("변경 없음").isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT ord FROM banner WHERE id = ?", Integer.class, a)).isZero();
    }

    private int orderStatus(String idsJson) throws Exception {
        String body = "null".equals(idsJson) ? "{}" : "{\"ids\":" + idsJson + "}";
        return mockMvc.perform(put("/admin/api/banners/order").with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("CSRF 없는 배너 쓰기는 403이고, 비로그인 API는 401")
    void csrfAndAuthentication() throws Exception {
        assertThat(mockMvc.perform(delete("/admin/api/banners/1").with(asAdmin())).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mockMvc.perform(get("/admin/api/banners")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    // ===== 공개 배너 이미지 =====

    @Test
    @DisplayName("공개 이미지: 노출 중이면 익명 200(저장 바이트·nosniff·no-store·형식), HEAD는 본문 없이 같은 헤더")
    void publicImage_active() throws Exception {
        byte[] original = png();
        MvcResult created = createBanner("공개", null, null, null, true, original, "image/png");
        long id = json(created).get("id").asLong();

        MvcResult get = mockMvc.perform(get("/banners/{id}/image", id).with(fromIp())).andReturn();
        assertThat(get.getResponse().getStatus()).isEqualTo(200);
        assertThat(get.getResponse().getContentAsByteArray()).isEqualTo(original);
        assertThat(get.getResponse().getContentType()).isEqualTo("image/png");
        assertThat(get.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(get.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(get.getResponse().getHeader("Content-Disposition")).isNull();

        MvcResult head = mockMvc.perform(head("/banners/{id}/image", id).with(fromIp())).andReturn();
        assertThat(head.getResponse().getStatus()).isEqualTo(200);
        assertThat(head.getResponse().getContentLength()).isEqualTo(original.length);
        assertThat(head.getResponse().getContentAsByteArray()).isEmpty();
    }

    @Test
    @DisplayName("공개 이미지: 비노출·시작 전·종료 후·없는 ID·비숫자·0·범위 밖은 모두 같은 404 (존재 여부 비노출)")
    void publicImage_notDisplayable_allSame404() throws Exception {
        long hidden = createOk("숨김", null, null, null, false);
        long scheduled = createOk("예약", null, LocalDateTime.now().plusDays(1), null, true);
        long expired = createOk("만료", null, null, LocalDateTime.now().minusDays(1), true);
        long active = createOk("노출", null, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1), true);

        for (String id : List.of(String.valueOf(hidden), String.valueOf(scheduled), String.valueOf(expired), "999999", "abc", "0", "-1",
                "99999999999999999999")) {
            MvcResult get = mockMvc.perform(get("/banners/{id}/image", id).with(fromIp())).andReturn();
            assertThat(get.getResponse().getStatus()).as("GET " + id).isEqualTo(404);
            assertThat(mockMvc.perform(head("/banners/{id}/image", id).with(fromIp())).andReturn().getResponse().getStatus())
                    .as("HEAD " + id).isEqualTo(404);
        }
        assertThat(mockMvc.perform(get("/banners/{id}/image", active).with(fromIp())).andReturn().getResponse().getStatus()).isEqualTo(200);

        // 비노출로 바꾸면 즉시 404 — 캐시되지 않는다
        updateStatus(active, Map.of("title", "숨김", "useYn", false));
        assertThat(mockMvc.perform(get("/banners/{id}/image", active).with(fromIp())).andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("공개 이미지: 행은 있는데 파일이 없으면 404(저장소 불일치를 500으로 드러내지 않는다)")
    void publicImage_missingFile_404() throws Exception {
        long id = createOk("파일없음", null, null, null, true);
        String key = jdbc.queryForObject("SELECT storage_key FROM banner WHERE id = ?", String.class, id);
        fileStorage.delete(key, BannerService.STORAGE_NAMESPACE);

        assertThat(mockMvc.perform(get("/banners/{id}/image", id).with(fromIp())).andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("공개 이미지 경로의 쓰기·하위 경로는 막힌다(익명 CSRF 포함 302, 인증 403)")
    void publicImage_writesDenied() throws Exception {
        long id = createOk("쓰기금지", null, null, null, true);

        assertThat(mockMvc.perform(post("/banners/{id}/image", id).with(csrf()).with(fromIp())).andReturn().getResponse().getStatus()).isEqualTo(302);
        assertThat(mockMvc.perform(post("/banners/{id}/image", id).with(asAdmin()).with(csrf())).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mockMvc.perform(get("/banners/{id}", id).with(fromIp())).andReturn().getResponse().getStatus()).isEqualTo(302);
    }

    // ===== 공개 메인 =====

    @Test
    @DisplayName("공개 메인 /: 익명 200, 노출 중인 배너만 표시 순서대로(제목은 이스케이프, 비노출·예약·만료는 없음), 링크 종류별 속성")
    void home_showsOnlyDisplayableBannersInOrder() throws Exception {
        long first = createOk("첫째 <b>굵게</b>", "/boards/9", null, null, true);
        long second = createOk("둘째", "https://example.com/ext", null, null, true);
        long third = createOk("셋째-링크없음", null, null, null, true);
        createOk("숨김배너", null, null, null, false);
        createOk("예약배너", null, LocalDateTime.now().plusDays(1), null, true);
        createOk("만료배너", null, null, LocalDateTime.now().minusDays(1), true);
        mockMvc.perform(put("/admin/api/banners/order").with(asAdmin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[" + third + "," + first + "," + second + ","
                        + jdbc.queryForList("SELECT id FROM banner WHERE title IN ('숨김배너','예약배너','만료배너') ORDER BY id", Long.class)
                                .stream().map(String::valueOf).reduce((x, y) -> x + "," + y).orElse("") + "]}"));

        MvcResult result = mockMvc.perform(get("/").with(fromIp())).andReturn();
        String html = result.getResponse().getContentAsString();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(html).contains("/banners/" + first + "/image", "/banners/" + second + "/image", "/banners/" + third + "/image");
        assertThat(html.indexOf("/banners/" + third + "/image")).isLessThan(html.indexOf("/banners/" + first + "/image"));
        assertThat(html.indexOf("/banners/" + first + "/image")).isLessThan(html.indexOf("/banners/" + second + "/image"));
        assertThat(html).doesNotContain("숨김배너", "예약배너", "만료배너");
        assertThat(html).as("제목은 속성 이스케이프").doesNotContain("<b>굵게</b>").contains("&lt;b&gt;굵게&lt;/b&gt;");
        assertThat(html).contains("href=\"/boards/9\"");
        assertThat(html).contains("href=\"https://example.com/ext\"").contains("rel=\"noopener noreferrer\"");
        assertThat(html).as("첫 배너만 즉시 로딩, 나머지는 지연 로딩").contains("loading=\"eager\"").contains("loading=\"lazy\"");
    }

    @Test
    @DisplayName("공개 메인 /: DB에 직접 들어간 위험한 링크(javascript:)는 출력 시 걸러져 링크 없는 배너로 그려진다")
    void home_unsafeStoredLinkIsDropped() throws Exception {
        long id = createOk("위험링크", null, null, null, true);
        jdbc.update("UPDATE banner SET link_url = 'javascript:alert(1)' WHERE id = ?", id);

        String html = mockMvc.perform(get("/").with(fromIp())).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("/banners/" + id + "/image").doesNotContain("javascript:");
    }

    @Test
    @DisplayName("공개 메인 /: 배너·공지·새 글이 모두 없어도 200이고 빈 안내가 나온다")
    void home_emptyIsOk() throws Exception {
        MvcResult result = mockMvc.perform(get("/").with(fromIp())).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentType()).startsWith("text/html");
    }

    @Test
    @DisplayName("공개 메인 /: HEAD 200, 쓰기 메서드는 403/302")
    void home_headAndWrites() throws Exception {
        assertThat(mockMvc.perform(head("/").with(fromIp())).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mockMvc.perform(post("/").with(fromIp())).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mockMvc.perform(post("/").with(csrf()).with(fromIp())).andReturn().getResponse().getStatus()).isEqualTo(302);
    }
}
