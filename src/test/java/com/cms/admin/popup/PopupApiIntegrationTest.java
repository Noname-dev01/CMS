package com.cms.admin.popup;

import com.cms.admin.log.constant.AdminActionTypes;
import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.popup.service.PopupService;
import com.cms.common.image.ImageFileValidatorTest;
import com.cms.common.storage.FileStorage;
import com.cms.common.storage.StorageFileNotFoundException;
import com.cms.publicweb.popup.PublicPopupService;
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
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
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

/**
 * 팝업 관리와 공개 메인·팝업 이미지의 실제 스택 시험(PLAN-popup.md): SecurityConfig·판정기·감사·MariaDB·로컬 스토리지.
 * MockMvc 호출이 실제로 커밋하므로 {@link #cleanUp()}이 만든 팝업과 파일을 지운다. 공개 요청은 시험마다 고유 원격 IP로 레이트리밋 버킷을 격리한다.
 * 기간은 시스템 시각 ±1일로 잡아 JVM 기본 시간대와 무관하게 경계에서 멀리 둔다(경계 자체는 {@code PopupRepositoryDataJpaTest}).
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class PopupApiIntegrationTest extends MariaDbContainerSupport {

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
        ip = "10.91." + (n / 250) + "." + (n % 250 + 1);
        admin = TestMembers.save(memberRepository, "pp-admin", Role.ROLE_ADMIN);
        auditBaseline = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM admin_action_log", Long.class);
        cleanPopups();
    }

    @AfterEach
    void cleanUp() {
        cleanPopups();
        TestMembers.delete(jdbc, List.of(admin.getId()));
        jdbc.update("DELETE FROM admin_action_log WHERE id > ?", auditBaseline);
    }

    private void cleanPopups() {
        for (String key : jdbc.queryForList("SELECT storage_key FROM popup WHERE storage_key IS NOT NULL", String.class)) {
            fileStorage.delete(key, PopupService.STORAGE_NAMESPACE);
        }
        jdbc.update("DELETE FROM popup");
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

    /** 서버가 받는 "yyyy-MM-ddTHH:mm" 형식(엄격 파싱이라 LocalDateTime.toString()의 소수 초는 400이다). */
    private static String minute(LocalDateTime value) {
        return value.truncatedTo(java.time.temporal.ChronoUnit.MINUTES).toString();
    }

    private static byte[] png() throws Exception {
        return ImageFileValidatorTest.png(30, 12);
    }

    /** 이미지형 등록 요청 빌더. 필드는 호출자가 더 얹는다. */
    private MockMultipartHttpServletRequestBuilder imageRequest(String title, byte[] image, String contentType) {
        return multipart("/admin/api/popups")
                .file(new MockMultipartFile("image", "p.png", contentType, image))
                .param("title", title).param("popupType", "IMAGE")
                .with(asAdmin()).with(csrf());
    }

    private MockMultipartHttpServletRequestBuilder htmlRequest(String title, String content) {
        MockMultipartHttpServletRequestBuilder builder = multipart("/admin/api/popups")
                .param("title", title).param("popupType", "HTML").param("contentFormat", "HTML")
                .with(asAdmin()).with(csrf());
        if (content != null) {
            builder.param("content", content);
        }
        return builder;
    }

    private MvcResult run(RequestBuilder builder) throws Exception {
        return mockMvc.perform(builder).andReturn();
    }

    private long createImage(String title, String link, LocalDateTime start, LocalDateTime end, Boolean useYn) throws Exception {
        MockMultipartHttpServletRequestBuilder builder = imageRequest(title, png(), "image/png");
        if (link != null) { builder.param("linkUrl", link); }
        if (start != null) { builder.param("displayStart", minute(start)); }
        if (end != null) { builder.param("displayEnd", minute(end)); }
        if (useYn != null) { builder.param("useYn", String.valueOf(useYn)); }
        MvcResult result = run(builder);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        return json(result).get("id").asLong();
    }

    private long createHtml(String title, String content, Boolean useYn) throws Exception {
        MockMultipartHttpServletRequestBuilder builder = htmlRequest(title, content);
        if (useYn != null) { builder.param("useYn", String.valueOf(useYn)); }
        MvcResult result = run(builder);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        return json(result).get("id").asLong();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult putRaw(long id, String rawJson) throws Exception {
        return run(put("/admin/api/popups/{id}", id).with(asAdmin()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(rawJson));
    }

    private int putStatus(long id, Map<String, Object> body) throws Exception {
        return putRaw(id, objectMapper.writeValueAsString(body)).getResponse().getStatus();
    }

    private int revisionOf(long id) {
        return jdbc.queryForObject("SELECT revision FROM popup WHERE id = ?", Integer.class, id);
    }

    private static Map<String, Object> imageUpdate() {
        return new java.util.LinkedHashMap<>(Map.of("title", "t", "posX", 10, "posY", 10, "width", 300, "useYn", true));
    }

    private static Map<String, Object> htmlUpdate(String content) {
        Map<String, Object> body = imageUpdate();
        body.put("content", content);
        body.put("contentFormat", "HTML");
        return body;
    }

    // ===== 생명주기 =====

    @Test
    @DisplayName("이미지형 등록→목록(본문 없음)→상세→이미지 미리보기→수정(전체 교체, null 해제, revision +1)→삭제 왕복, 감사 로그와 파일 정리까지")
    void imageLifecycle() throws Exception {
        byte[] original = png();
        LocalDateTime start = LocalDateTime.now().plusDays(1).withSecond(30).withNano(0);
        LocalDateTime end = start.plusDays(5);
        MockMultipartHttpServletRequestBuilder builder = imageRequest("추석 안내", original, "image/png")
                .param("linkUrl", "/boards/2").param("displayStart", start.toString()).param("displayEnd", end.toString())
                .param("posX", "30").param("posY", "40").param("width", "320");
        MvcResult created = run(builder);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = json(created);
        long id = body.get("id").asLong();
        assertThat(body.get("popupType").asText()).isEqualTo("IMAGE");
        assertThat(LocalDateTime.parse(body.get("displayStart").asText())).as("초 단위는 분으로 절단").isEqualTo(start.withSecond(0));
        assertThat(body.get("status").asText()).as("시작 전이면 예약됨").isEqualTo("SCHEDULED");
        assertThat(body.get("revision").asInt()).isZero();
        assertThat(body.get("posX").asInt()).isEqualTo(30);
        assertThat(body.get("width").asInt()).isEqualTo(320);

        String storageKey = jdbc.queryForObject("SELECT storage_key FROM popup WHERE id = ?", String.class, id);
        assertThat(fileStorage.load(storageKey, PopupService.STORAGE_NAMESPACE)).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT content_html FROM popup WHERE id = ?", String.class, id)).as("이미지형은 본문 없음").isNull();

        // 목록은 본문을 싣지 않고, 상세는 싣는다(이미지형은 둘 다 null이지만 키는 같은 응답 형태다)
        MvcResult list = run(get("/admin/api/popups").with(asAdmin()));
        assertThat(list.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(list).get(0).get("id").asLong()).isEqualTo(id);
        assertThat(json(run(get("/admin/api/popups/{id}", id).with(asAdmin()))).get("id").asLong()).isEqualTo(id);

        // 관리자 미리보기는 노출 여부와 무관하게 저장된 바이트를 그대로 준다
        MvcResult image = run(get("/admin/api/popups/{id}/image", id).with(asAdmin()));
        assertThat(image.getResponse().getStatus()).isEqualTo(200);
        assertThat(image.getResponse().getContentAsByteArray()).isEqualTo(original);
        assertThat(image.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");

        // 수정: 전체 교체 — 링크·기간을 null로 해제하고 노출을 끈다
        Map<String, Object> update = imageUpdate();
        update.put("title", "새 제목");
        update.put("useYn", false);
        MvcResult updated = putRaw(id, objectMapper.writeValueAsString(update));
        assertThat(updated.getResponse().getStatus()).isEqualTo(200);
        JsonNode updatedBody = json(updated);
        assertThat(updatedBody.get("title").asText()).isEqualTo("새 제목");
        assertThat(updatedBody.path("linkUrl").isNull() || updatedBody.path("linkUrl").isMissingNode()).isTrue();
        assertThat(updatedBody.path("displayStart").isNull() || updatedBody.path("displayStart").isMissingNode()).isTrue();
        assertThat(updatedBody.get("status").asText()).isEqualTo("HIDDEN");
        assertThat(updatedBody.get("revision").asInt()).as("수정마다 revision +1").isEqualTo(1);

        assertThat(run(delete("/admin/api/popups/{id}", id).with(asAdmin()).with(csrf())).getResponse().getStatus()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM popup WHERE id = ?", Integer.class, id)).isZero();
        assertThatThrownBy(() -> fileStorage.load(storageKey, PopupService.STORAGE_NAMESPACE))
                .as("커밋 후 이미지 파일도 지워진다").isInstanceOf(StorageFileNotFoundException.class);
        assertThat(run(delete("/admin/api/popups/{id}", id).with(asAdmin()).with(csrf())).getResponse().getStatus()).as("이미 삭제됨").isEqualTo(404);

        List<String> actions = jdbc.queryForList("SELECT action_type FROM admin_action_log WHERE id > ? AND target_type = 'POPUP' ORDER BY id",
                String.class, auditBaseline);
        assertThat(actions).contains(AdminActionTypes.POPUP_CREATE, AdminActionTypes.POPUP_UPDATE, AdminActionTypes.POPUP_DELETE);
        assertThat(jdbc.queryForObject("SELECT target_id FROM admin_action_log WHERE id > ? AND action_type = 'POPUP_DELETE' AND action_result = 'SUCCESS'",
                Long.class, auditBaseline)).isEqualTo(id);
    }

    @Test
    @DisplayName("HTML형 등록→수정→삭제: 본문은 저장·출력 모두 정리되고(스크립트·이벤트 핸들러·img·javascript: 링크 제거) 목록에는 본문이 없고 상세에만 있다")
    void htmlLifecycle_sanitizesAndKeepsBodyOutOfList() throws Exception {
        String dirty = "<p>안내 <strong>굵게</strong></p><script>alert(1)</script><img src=\"x\" onerror=\"alert(2)\">"
                + "<a href=\"javascript:alert(3)\">나쁜</a> <a href=\"https://example.com\">좋은</a>";
        long id = createHtml("본문 팝업", dirty, true);

        String stored = jdbc.queryForObject("SELECT content_html FROM popup WHERE id = ?", String.class, id);
        assertThat(stored).contains("<strong>굵게</strong>").contains("href=\"https://example.com\"");
        assertThat(stored).doesNotContain("<script", "alert(1)", "onerror", "<img", "javascript:");
        assertThat(jdbc.queryForObject("SELECT storage_key FROM popup WHERE id = ?", String.class, id)).as("HTML형은 이미지 없음").isNull();

        JsonNode listItem = json(run(get("/admin/api/popups").with(asAdmin()))).get(0);
        assertThat(listItem.path("contentHtml").isNull() || listItem.path("contentHtml").isMissingNode()).as("목록에는 본문이 없다").isTrue();
        assertThat(json(run(get("/admin/api/popups/{id}", id).with(asAdmin()))).get("contentHtml").asText()).isEqualTo(stored);

        Map<String, Object> update = htmlUpdate("<p>수정된 본문</p>");
        update.put("height", 300);
        MvcResult updated = putRaw(id, objectMapper.writeValueAsString(update));
        assertThat(updated.getResponse().getStatus()).as(updated.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(json(updated).get("height").asInt()).isEqualTo(300);
        assertThat(revisionOf(id)).isEqualTo(1);

        assertThat(run(get("/admin/api/popups/{id}/image", id).with(asAdmin())).getResponse().getStatus()).as("HTML형은 이미지가 없다").isEqualTo(404);
        assertThat(run(delete("/admin/api/popups/{id}", id).with(asAdmin()).with(csrf())).getResponse().getStatus()).isEqualTo(204);
    }

    @Test
    @DisplayName("revision: 같은 초에 이어서 수정해도 매번 +1 되어 모두 다르다(수정 시각으로는 구분되지 않는 경우)")
    void revisionIncrementsOnEveryUpdate() throws Exception {
        long id = createImage("rev", null, null, null, true);
        int previous = revisionOf(id);
        for (int i = 0; i < 5; i++) {
            assertThat(putStatus(id, imageUpdate())).isEqualTo(200);
            int current = revisionOf(id);
            assertThat(current).isEqualTo(previous + 1);
            previous = current;
        }
    }

    // ===== 등록 검증 =====

    @Test
    @DisplayName("등록 검증(이미지형): 링크·이미지·제목·배치·기간 위반은 모두 400이고 행도 파일도 남기지 않는다")
    void createValidation_image() throws Exception {
        for (String badLink : List.of("javascript:alert(1)", "data:text/html,x", "//evil.example", "ftp://example.com", "x".repeat(501))) {
            assertThat(run(imageRequest("t", png(), "image/png").param("linkUrl", badLink)).getResponse().getStatus()).as(badLink).isEqualTo(400);
        }
        assertThat(run(imageRequest("   ", png(), "image/png")).getResponse().getStatus()).as("빈 제목").isEqualTo(400);
        assertThat(run(imageRequest("t", "x".getBytes(), "text/plain")).getResponse().getStatus()).as("이미지 아닌 파일").isEqualTo(400);
        assertThat(run(imageRequest("t", new byte[2 * 1024 * 1024 + 1], "image/png")).getResponse().getStatus()).as("2MB 초과").isEqualTo(400);
        assertThat(run(imageRequest("t", ImageFileValidatorTest.png(2561, 8), "image/png")).getResponse().getStatus()).as("한 변 2560px 초과").isEqualTo(400);
        assertThat(run(multipart("/admin/api/popups").param("title", "t").param("popupType", "IMAGE").with(asAdmin()).with(csrf()))
                .getResponse().getStatus()).as("이미지 없음").isEqualTo(400);
        assertThat(run(multipart("/admin/api/popups").param("title", "t").with(asAdmin()).with(csrf())).getResponse().getStatus())
                .as("종류 누락").isEqualTo(400);
        assertThat(run(multipart("/admin/api/popups").param("title", "t").param("popupType", "VIDEO").with(asAdmin()).with(csrf()))
                .getResponse().getStatus()).as("알 수 없는 종류").isEqualTo(400);

        // 종류별 금지 필드
        assertThat(run(imageRequest("t", png(), "image/png").param("content", "<p>x</p>").param("contentFormat", "HTML")).getResponse().getStatus())
                .as("이미지형에 본문").isEqualTo(400);
        assertThat(run(imageRequest("t", png(), "image/png").param("height", "300")).getResponse().getStatus()).as("이미지형에 높이").isEqualTo(400);

        // 배치 범위
        assertThat(run(imageRequest("t", png(), "image/png").param("posX", "-1")).getResponse().getStatus()).isEqualTo(400);
        assertThat(run(imageRequest("t", png(), "image/png").param("posY", "2001")).getResponse().getStatus()).isEqualTo(400);
        assertThat(run(imageRequest("t", png(), "image/png").param("width", "199")).getResponse().getStatus()).isEqualTo(400);
        assertThat(run(imageRequest("t", png(), "image/png").param("width", "1001")).getResponse().getStatus()).isEqualTo(400);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM popup", Integer.class)).isZero();
    }

    @Test
    @DisplayName("등록 검증(기간): 범위 밖 연도·역전·소수 초 절단 후 같음·오프셋·24:00·날짜만·존재하지 않는 날짜는 400, 빈 입력란은 '없음'으로 200")
    void createValidation_period() throws Exception {
        for (String bad : List.of("0001-01-01T00:00", "1999-12-31T23:59", "2100-01-01T00:00", "2026-10-10T09:00Z", "2026-10-10T09:00+09:00",
                "2026-10-10T24:00", "2026-10-10", "2026-02-30T09:00", "2026-10-10T09:00:00.500", "not-a-date")) {
            assertThat(run(imageRequest("t", png(), "image/png").param("displayStart", bad)).getResponse().getStatus()).as("start " + bad).isEqualTo(400);
            assertThat(run(imageRequest("t", png(), "image/png").param("displayEnd", bad)).getResponse().getStatus()).as("end " + bad).isEqualTo(400);
        }
        assertThat(run(imageRequest("t", png(), "image/png").param("displayStart", "2026-10-20T00:00").param("displayEnd", "2026-10-10T00:00"))
                .getResponse().getStatus()).as("역전").isEqualTo(400);
        assertThat(run(imageRequest("t", png(), "image/png").param("displayStart", "2026-10-10T12:00:10").param("displayEnd", "2026-10-10T12:00:50"))
                .getResponse().getStatus()).as("초 절단 후 같아짐").isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM popup", Integer.class)).isZero();

        assertThat(run(imageRequest("t", png(), "image/png").param("displayStart", "").param("displayEnd", "")).getResponse().getStatus())
                .as("폼의 빈 입력란은 없음").isEqualTo(201);
    }

    @Test
    @DisplayName("등록 검증(HTML형): 본문 없음·형식 표식 누락·이미지 첨부·링크 지정·본문 비게 되는 입력·원문/글자 상한 초과는 400")
    void createValidation_html() throws Exception {
        assertThat(run(htmlRequest("t", null)).getResponse().getStatus()).as("본문 없음").isEqualTo(400);
        assertThat(run(htmlRequest("t", "   ")).getResponse().getStatus()).as("공백 본문").isEqualTo(400);
        assertThat(run(htmlRequest("t", "<script>alert(1)</script>")).getResponse().getStatus()).as("정리하면 비는 본문").isEqualTo(400);
        assertThat(run(multipart("/admin/api/popups").param("title", "t").param("popupType", "HTML").param("content", "<p>x</p>")
                .with(asAdmin()).with(csrf())).getResponse().getStatus()).as("형식 표식 누락").isEqualTo(400);
        assertThat(run(htmlRequest("t", "<p>x</p>").file(new MockMultipartFile("image", "p.png", "image/png", png()))).getResponse().getStatus())
                .as("HTML형에 이미지").isEqualTo(400);
        assertThat(run(htmlRequest("t", "<p>x</p>").param("linkUrl", "/boards/2")).getResponse().getStatus()).as("HTML형에 링크").isEqualTo(400);
        assertThat(run(htmlRequest("t", "<p>" + "가".repeat(2001) + "</p>")).getResponse().getStatus()).as("보이는 글자 2,000자 초과").isEqualTo(400);
        assertThat(run(htmlRequest("t", "<p>" + "a".repeat(100_001) + "</p>")).getResponse().getStatus()).as("원문 100,000바이트 초과").isEqualTo(400);
        assertThat(run(htmlRequest("t", "<p>x</p>").param("height", "143")).getResponse().getStatus()).as("높이 범위").isEqualTo(400);
        assertThat(run(htmlRequest("t", "<p>x</p>").param("height", "1001")).getResponse().getStatus()).as("높이 범위").isEqualTo(400);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM popup", Integer.class)).isZero();
        assertThat(run(htmlRequest("경계", "<p>" + "가".repeat(2000) + "</p>")).getResponse().getStatus()).as("2,000자는 통과").isEqualTo(201);
        assertThat(run(htmlRequest("높이경계", "<p>x</p>").param("height", "144")).getResponse().getStatus()).as("높이 하한 144는 통과").isEqualTo(201);
    }

    // ===== 수정 검증 =====

    @Test
    @DisplayName("수정 검증(기간): null만 해제이고 빈 문자열·오프셋·24:00·범위 밖·역전은 400, 없는 팝업은 404 — 400이면 DB 불변")
    void updateValidation_period() throws Exception {
        long id = createImage("대상", null, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1), true);
        int revision = revisionOf(id);

        for (String bad : List.of("\"\"", "\"  \"", "\"2026-10-10T09:00Z\"", "\"2026-10-10T24:00\"", "\"0001-01-01T00:00\"", "\"2100-01-01T00:00\"")) {
            assertThat(putRaw(id, rawImageUpdate("\"displayStart\":" + bad)).getResponse().getStatus()).as("start " + bad).isEqualTo(400);
            assertThat(putRaw(id, rawImageUpdate("\"displayEnd\":" + bad)).getResponse().getStatus()).as("end " + bad).isEqualTo(400);
        }
        assertThat(putRaw(id, rawImageUpdate("\"displayStart\":\"2026-10-20T00:00\",\"displayEnd\":\"2026-10-10T00:00\"")).getResponse().getStatus())
                .as("역전").isEqualTo(400);
        assertThat(revisionOf(id)).as("거부된 수정은 revision을 올리지 않는다").isEqualTo(revision);
        assertThat(jdbc.queryForObject("SELECT display_end FROM popup WHERE id = ?", Object.class, id)).as("기간 불변").isNotNull();

        assertThat(putStatus(999_999L, imageUpdate())).isEqualTo(404);

        // 종료만 지우기(만료된 팝업을 되살리는 시나리오): 시작만 보내면 종료는 null로 해제된다
        jdbc.update("UPDATE popup SET display_end = ? WHERE id = ?", LocalDateTime.now().minusDays(2), id);
        assertThat(putRaw(id, rawImageUpdate("\"displayStart\":\"" + minute(LocalDateTime.now().minusDays(3)) + "\"")).getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT display_end FROM popup WHERE id = ?", Object.class, id)).isNull();
    }

    private static String rawImageUpdate(String extra) {
        return "{\"title\":\"t\",\"posX\":10,\"posY\":10,\"width\":300,\"useYn\":true," + extra + "}";
    }

    @Test
    @DisplayName("수정 검증(높이): null은 해제, 빈 문자열·공백·소수·문자열은 400(Jackson이 빈 문자열을 null로 바꾸지 못하게 한다)")
    void updateValidation_height() throws Exception {
        long id = createHtml("높이", "<p>x</p>", true);
        String base = "{\"title\":\"t\",\"posX\":10,\"posY\":10,\"width\":300,\"useYn\":true,\"content\":\"<p>y</p>\",\"contentFormat\":\"HTML\",";

        assertThat(putRaw(id, base + "\"height\":400}").getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT height FROM popup WHERE id = ?", Integer.class, id)).isEqualTo(400);
        int revision = revisionOf(id);

        for (String bad : List.of("\"\"", "\"   \"", "\"300\"", "300.5", "true")) {
            assertThat(putRaw(id, base + "\"height\":" + bad + "}").getResponse().getStatus()).as("height " + bad).isEqualTo(400);
        }
        assertThat(putRaw(id, base + "\"height\":143}").getResponse().getStatus()).as("범위").isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT height FROM popup WHERE id = ?", Integer.class, id)).as("400이면 높이 불변").isEqualTo(400);
        assertThat(revisionOf(id)).isEqualTo(revision);

        assertThat(putRaw(id, base + "\"height\":null}").getResponse().getStatus()).as("null은 해제").isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT height FROM popup WHERE id = ?", Object.class, id)).isNull();
        assertThat(putRaw(id, base.substring(0, base.length() - 1) + "}").getResponse().getStatus()).as("키가 없어도 해제(전체 교체)").isEqualTo(200);
    }

    @Test
    @DisplayName("수정 검증(종류별): 이미지형에 본문·높이, HTML형에 링크·본문 없음은 400. 배치·노출 여부 누락도 400")
    void updateValidation_typeSpecific() throws Exception {
        long image = createImage("이미지", null, null, null, true);
        long html = createHtml("본문", "<p>x</p>", true);

        assertThat(putStatus(image, htmlUpdate("<p>x</p>"))).as("이미지형에 본문").isEqualTo(400);
        Map<String, Object> withHeight = imageUpdate();
        withHeight.put("height", 300);
        assertThat(putStatus(image, withHeight)).as("이미지형에 높이").isEqualTo(400);
        Map<String, Object> linkOnHtml = htmlUpdate("<p>x</p>");
        linkOnHtml.put("linkUrl", "/boards/1");
        assertThat(putStatus(html, linkOnHtml)).as("HTML형에 링크").isEqualTo(400);
        assertThat(putStatus(html, imageUpdate())).as("HTML형에 본문 없음").isEqualTo(400);
        assertThat(putStatus(html, htmlUpdate("<script>x</script>"))).as("정리하면 비는 본문").isEqualTo(400);

        for (String missing : List.of("posX", "posY", "width", "useYn")) {
            Map<String, Object> body = imageUpdate();
            body.remove(missing);
            assertThat(putStatus(image, body)).as(missing + " 누락").isEqualTo(400);
        }
        Map<String, Object> badLayout = imageUpdate();
        badLayout.put("width", 5000);
        assertThat(putStatus(image, badLayout)).isEqualTo(400);
    }

    @Test
    @DisplayName("소프트 상한: 30개까지 등록되고 31번째는 409")
    void softLimit() throws Exception {
        for (int i = 0; i < PopupService.MAX_POPUPS; i++) {
            createHtml("p" + i, "<p>x</p>", false);
        }
        assertThat(run(htmlRequest("초과", "<p>x</p>")).getResponse().getStatus()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM popup", Integer.class)).isEqualTo(PopupService.MAX_POPUPS);
    }

    @Test
    @DisplayName("CSRF 없는 쓰기는 403이고, 비로그인 API는 401")
    void csrfAndAuthentication() throws Exception {
        assertThat(run(delete("/admin/api/popups/1").with(asAdmin())).getResponse().getStatus()).isEqualTo(403);
        assertThat(run(get("/admin/api/popups")).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("감사: 실패한 등록의 errorMessage는 고정 문구라 입력한 본문이 섞이지 않는다")
    void auditFailureMessageIsFixed() throws Exception {
        String marker = "SECRET_BODY_MARKER";
        assertThat(run(htmlRequest("t", "<p>" + marker + "</p>").param("posX", "-5")).getResponse().getStatus()).isEqualTo(400);

        List<String> messages = jdbc.queryForList("SELECT error_message FROM admin_action_log WHERE id > ? AND action_type = 'POPUP_CREATE' AND action_result = 'FAIL'",
                String.class, auditBaseline);
        assertThat(messages).isNotEmpty().allSatisfy(message -> assertThat(message).isEqualTo("팝업 등록에 실패했습니다.").doesNotContain(marker));
    }

    // ===== 공개 팝업 이미지 =====

    @Test
    @DisplayName("공개 이미지: 노출 중이면 익명 200(저장 바이트·nosniff·no-store·형식), HEAD는 본문 없이 같은 헤더")
    void publicImage_active() throws Exception {
        byte[] original = png();
        long id = json(run(imageRequest("공개", original, "image/png"))).get("id").asLong();

        MvcResult get = run(get("/popups/{id}/image", id).with(fromIp()));
        assertThat(get.getResponse().getStatus()).isEqualTo(200);
        assertThat(get.getResponse().getContentAsByteArray()).isEqualTo(original);
        assertThat(get.getResponse().getContentType()).isEqualTo("image/png");
        assertThat(get.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(get.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(get.getResponse().getHeader("Content-Disposition")).isNull();

        MvcResult head = run(head("/popups/{id}/image", id).with(fromIp()));
        assertThat(head.getResponse().getStatus()).isEqualTo(200);
        assertThat(head.getResponse().getContentLength()).isEqualTo(original.length);
        assertThat(head.getResponse().getContentAsByteArray()).isEmpty();
    }

    @Test
    @DisplayName("공개 이미지: 비노출·시작 전·종료 후·HTML형·없는 ID·비숫자·0·범위 밖은 모두 같은 404 (존재 여부 비노출)")
    void publicImage_notDisplayable_allSame404() throws Exception {
        long hidden = createImage("숨김", null, null, null, false);
        long scheduled = createImage("예약", null, LocalDateTime.now().plusDays(1), null, true);
        long expired = createImage("만료", null, null, LocalDateTime.now().minusDays(1), true);
        long htmlType = createHtml("본문형", "<p>x</p>", true);
        long active = createImage("노출", null, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1), true);

        String reference = run(get("/popups/{id}/image", "999999").with(fromIp())).getResponse().getContentAsString();
        for (String id : List.of(String.valueOf(hidden), String.valueOf(scheduled), String.valueOf(expired), String.valueOf(htmlType), "999999", "abc", "0", "-1",
                "99999999999999999999")) {
            MvcResult get = run(get("/popups/{id}/image", id).with(fromIp()));
            assertThat(get.getResponse().getStatus()).as("GET " + id).isEqualTo(404);
            assertThat(get.getResponse().getContentAsString()).as("본문도 같다 " + id).isEqualTo(reference);
            assertThat(run(head("/popups/{id}/image", id).with(fromIp())).getResponse().getStatus()).as("HEAD " + id).isEqualTo(404);
        }
        assertThat(run(get("/popups/{id}/image", active).with(fromIp())).getResponse().getStatus()).isEqualTo(200);

        // 비노출로 바꾸면 즉시 404 — 캐시되지 않는다
        Map<String, Object> hide = imageUpdate();
        hide.put("useYn", false);
        assertThat(putStatus(active, hide)).isEqualTo(200);
        assertThat(run(get("/popups/{id}/image", active).with(fromIp())).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("공개 이미지: 행은 있는데 파일이 없으면 404(저장소 불일치를 500으로 드러내지 않는다)")
    void publicImage_missingFile_404() throws Exception {
        long id = createImage("파일없음", null, null, null, true);
        String key = jdbc.queryForObject("SELECT storage_key FROM popup WHERE id = ?", String.class, id);
        fileStorage.delete(key, PopupService.STORAGE_NAMESPACE);

        assertThat(run(get("/popups/{id}/image", id).with(fromIp())).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("공개 이미지 경로의 쓰기·하위 경로는 막힌다(익명 CSRF 포함 302, 인증 403)")
    void publicImage_writesDenied() throws Exception {
        long id = createImage("쓰기금지", null, null, null, true);

        assertThat(run(post("/popups/{id}/image", id).with(csrf()).with(fromIp())).getResponse().getStatus()).isEqualTo(302);
        assertThat(run(post("/popups/{id}/image", id).with(asAdmin()).with(csrf())).getResponse().getStatus()).isEqualTo(403);
        assertThat(run(get("/popups/{id}", id).with(fromIp())).getResponse().getStatus()).isEqualTo(302);
        assertThat(run(get("/popups").with(fromIp())).getResponse().getStatus()).isEqualTo(302);
    }

    // ===== 공개 메인 =====

    @Test
    @DisplayName("공개 메인: 노출 중인 팝업만 등록 순서대로, 숨김 상태·data-src·revision, 제목·링크 이스케이프, 이미지는 src가 아니라 data-src로만")
    void home_showsOnlyDisplayablePopupsInOrder() throws Exception {
        long first = createImage("첫째 <b>굵게</b>", "/boards/9", null, null, true);
        long second = createImage("둘째", "https://example.com/ext", null, null, true);
        long third = createImage("셋째-링크없음", null, null, null, true);
        long body = createHtml("본문팝업", "<p>환영 <strong>합니다</strong></p>", true);
        createImage("숨김팝업", null, null, null, false);
        createImage("예약팝업", null, LocalDateTime.now().plusDays(1), null, true);
        createImage("만료팝업", null, null, LocalDateTime.now().minusDays(1), true);

        MvcResult result = run(get("/").with(fromIp()));
        String html = result.getResponse().getContentAsString();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(html).contains("data-src=\"/popups/" + first + "/image\"", "data-src=\"/popups/" + second + "/image\"",
                "data-src=\"/popups/" + third + "/image\"");
        assertThat(html.indexOf("data-popup-id=\"" + first + "\"")).isLessThan(html.indexOf("data-popup-id=\"" + second + "\""));
        assertThat(html.indexOf("data-popup-id=\"" + second + "\"")).isLessThan(html.indexOf("data-popup-id=\"" + third + "\""));
        assertThat(html.indexOf("data-popup-id=\"" + third + "\"")).isLessThan(html.indexOf("data-popup-id=\"" + body + "\""));
        assertThat(html).doesNotContain("숨김팝업", "예약팝업", "만료팝업");
        assertThat(html).as("제목은 이스케이프").doesNotContain("<b>굵게</b>").contains("&lt;b&gt;굵게&lt;/b&gt;");
        assertThat(html).contains("href=\"/boards/9\"").contains("href=\"https://example.com/ext\"").contains("rel=\"noopener noreferrer\"");
        assertThat(html).contains("<strong>합니다</strong>");
        assertThat(html).as("팝업은 서버가 숨김으로 그리고 revision을 data-version으로 내보낸다").contains("hidden").contains("data-version=\"0\"");
        assertThat(html).as("이미지는 src로 직접 내보내지 않는다(스크립트가 보이기로 한 것만 채운다)").doesNotContainPattern("\\ssrc=\"/popups/");
        assertThat(html).contains("/js/public/home-popup.js");
    }

    @Test
    @DisplayName("공개 메인: 팝업이 없으면 팝업 조각도 스크립트도 없다")
    void home_withoutPopups_hasNoPopupMarkup() throws Exception {
        String html = run(get("/").with(fromIp())).getResponse().getContentAsString();

        assertThat(html).doesNotContain("home-popup", "home-popup.js");
    }

    @Test
    @DisplayName("공개 메인: 수정하면 data-version(revision)이 바뀌어 '오늘 하루 보지 않기'가 풀린다")
    void home_versionChangesOnUpdate() throws Exception {
        long id = createImage("버전", null, null, null, true);
        String before = run(get("/").with(fromIp())).getResponse().getContentAsString();
        assertThat(before).contains("data-popup-id=\"" + id + "\"", "data-version=\"0\"");

        assertThat(putStatus(id, imageUpdate())).isEqualTo(200);
        assertThat(putStatus(id, imageUpdate())).isEqualTo(200);

        String after = run(get("/").with(fromIp())).getResponse().getContentAsString();
        assertThat(after).contains("data-version=\"2\"").doesNotContain("data-version=\"0\"");
    }

    @Test
    @DisplayName("공개 메인: 노출 중인 팝업이 5개를 넘으면 등록 순서대로 5개만 나온다")
    void home_capsAtFive() throws Exception {
        List<Long> ids = new java.util.ArrayList<>();
        for (int i = 0; i < PublicPopupService.MAX_DISPLAYED + 1; i++) {
            ids.add(createHtml("p" + i, "<p>x</p>", true));
        }

        String html = run(get("/").with(fromIp())).getResponse().getContentAsString();

        for (int i = 0; i < PublicPopupService.MAX_DISPLAYED; i++) {
            assertThat(html).contains("data-popup-id=\"" + ids.get(i) + "\"");
        }
        assertThat(html).doesNotContain("data-popup-id=\"" + ids.get(PublicPopupService.MAX_DISPLAYED) + "\"");
    }

    @Test
    @DisplayName("공개 메인: DB에 직접 들어간 위험한 링크(javascript:)와 본문의 script·img는 출력 시 걸러진다")
    void home_unsafeStoredContentIsDroppedAtOutput() throws Exception {
        long image = createImage("위험링크", null, null, null, true);
        jdbc.update("UPDATE popup SET link_url = 'javascript:alert(1)' WHERE id = ?", image);
        long html = createHtml("위험본문", "<p>정상</p>", true);
        jdbc.update("UPDATE popup SET content_html = ? WHERE id = ?", "<p>정상</p><script>alert(9)</script><img src=\"/content-images/1\"><a href=\"javascript:alert(8)\">x</a>", html);
        long emptied = createHtml("무효본문", "<p>x</p>", true);
        jdbc.update("UPDATE popup SET content_html = '<script>alert(7)</script>' WHERE id = ?", emptied);

        String page = run(get("/").with(fromIp())).getResponse().getContentAsString();

        assertThat(page).contains("data-popup-id=\"" + image + "\"").doesNotContain("javascript:");
        assertThat(page).contains("<p>정상</p>").doesNotContain("alert(9)", "alert(8)", "alert(7)", "<img src=\"/content-images/1\">");
        assertThat(page).as("정리하면 비는 본문은 팝업 자체를 만들지 않는다").doesNotContain("data-popup-id=\"" + emptied + "\"");
    }
}
