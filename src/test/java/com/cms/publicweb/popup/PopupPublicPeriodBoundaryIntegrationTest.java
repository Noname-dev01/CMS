package com.cms.publicweb.popup;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.popup.service.PopupService;
import com.cms.common.image.ImageFileValidatorTest;
import com.cms.common.storage.FileStorage;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.MutableClock;
import com.cms.support.MediaTestSupport;
import com.cms.support.TestMembers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;

/**
 * 팝업 노출 기간의 <b>모든 경로가 같은 시각 판정으로 움직이는지</b> 서비스 전 구간으로 확인한다(PLAN-popup.md 쟁점 6, 로드맵 ⑦ 완료 기준 "노출 기간 경계
 * 고정 Clock 테스트"). 가변 {@link MutableClock}으로 시작 직전·시작·종료 직전·종료 시각을 만들고 공개 메인(`/`)의 팝업 노출, 공개 이미지
 * (`GET·HEAD /popups/{id}/image`), 관리 목록의 상태 배지가 한 결과로 움직이는지 실제 스택(Security·MockMvc·MariaDB·스토리지)으로 본다. 한 경로라도
 * 기간 조건을 빼먹거나 `Clock` 대신 시스템 시각을 쓰면 경계에서 어긋난다 — {@code PopupRepositoryDataJpaTest}는 쿼리의 경계만, 이 시험은 서비스가
 * 주입된 시계를 쓰는 전 구간을 고정한다. 판정 시각이 항상 주입된 Clock에서 나온다는 규약({@code ClockUsageConventionTest})이 있어야 성립한다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
@Import(PopupPublicPeriodBoundaryIntegrationTest.ClockConfig.class)
class PopupPublicPeriodBoundaryIntegrationTest extends MariaDbContainerSupport {

    private static final LocalDateTime START = LocalDateTime.of(2090, 3, 1, 9, 0);
    private static final LocalDateTime END = LocalDateTime.of(2090, 3, 8, 0, 0);
    /** datetime(6)의 최소 단위 — "직전"은 마이크로초 하나 앞이다(나노초는 DB에서 반올림될 수 있다). */
    private static final long MICRO_NANOS = 1_000L;

    private static final AtomicInteger IP_SEQUENCE = new AtomicInteger(1);

    @TestConfiguration
    static class ClockConfig {
        /** 앱의 KST Clock 대신 주입되는 가변 시계. 시험이 {@code set}으로 판정 시각을 움직인다. */
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(LocalDateTime.of(2090, 1, 1, 0, 0));
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired FileStorage fileStorage;
    @Autowired MemberRepository memberRepository;
    @Autowired MutableClock clock;
    @Autowired ObjectMapper objectMapper;

    private Member admin;
    private String ip;
    private long periodImageId;
    private long periodHtmlId;
    private long alwaysId;
    private long hiddenId;

    @BeforeEach
    void setUp() throws Exception {
        cleanPopups();
        admin = TestMembers.save(memberRepository, "pop-clock-admin", Role.ROLE_ADMIN);
        clock.set(LocalDateTime.of(2090, 1, 1, 0, 0));
        int n = IP_SEQUENCE.getAndIncrement();
        ip = "10.78." + (n / 250) + "." + (n % 250 + 1);

        periodImageId = insertImage("기간 이미지", true, START, END);
        periodHtmlId = insertHtml("기간 본문", true, START, END);
        alwaysId = insertHtml("상시", true, null, null);
        hiddenId = insertHtml("비노출", false, null, null);
    }

    @AfterEach
    void tearDown() {
        cleanPopups();
        TestMembers.delete(jdbc, List.of(admin.getId()));
    }

    private void cleanPopups() {
        jdbc.update("DELETE FROM popup");
        MediaTestSupport.cleanLibrary(jdbc, fileStorage);
    }

    private long insertImage(String title, boolean useYn, LocalDateTime start, LocalDateTime end) throws Exception {
        String key = fileStorage.store(ImageFileValidatorTest.png(30, 12), "p.png", PopupService.STORAGE_NAMESPACE);
        jdbc.update("INSERT INTO popup (title, revision, popup_type, pos_x, pos_y, width, use_yn, display_start, display_end, create_date, update_date) "
                        + "VALUES (?, 0, 'IMAGE', 10, 10, 300, ?, ?, ?, ?, ?)",
                title, useYn, timestamp(start), timestamp(end), Timestamp.valueOf(START), Timestamp.valueOf(START));
        long popupId = jdbc.queryForObject("SELECT MAX(id) FROM popup", Long.class);
        jdbc.update("INSERT INTO content_image (storage_key, content_type, file_size, uploader_id, scope_type, namespace) VALUES (?, 'image/png', 10, 't', 'POPUP', 'popup')", key);
        long imageId = jdbc.queryForObject("SELECT id FROM content_image WHERE storage_key = ? AND namespace = 'popup'", Long.class, key);
        jdbc.update("INSERT INTO content_image_ref (owner_type, owner_id, image_id) VALUES ('POPUP', ?, ?)", popupId, imageId);
        return popupId;
    }

    private long insertHtml(String title, boolean useYn, LocalDateTime start, LocalDateTime end) {
        jdbc.update("INSERT INTO popup (title, revision, popup_type, content_html, pos_x, pos_y, width, use_yn, display_start, display_end, create_date, update_date) "
                        + "VALUES (?, 0, 'HTML', '<p>본문</p>', 10, 10, 300, ?, ?, ?, ?, ?)",
                title, useYn, timestamp(start), timestamp(end), Timestamp.valueOf(START), Timestamp.valueOf(START));
        return jdbc.queryForObject("SELECT MAX(id) FROM popup", Long.class);
    }

    private static Timestamp timestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private RequestPostProcessor fromTestIp() {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private boolean homeShows(long id) throws Exception {
        String html = mockMvc.perform(get("/").with(fromTestIp())).andReturn().getResponse().getContentAsString();
        return html.contains("data-popup-id=\"" + id + "\"");
    }

    private int imageStatus(long id) throws Exception {
        return mockMvc.perform(get("/popups/{id}/image", id).with(fromTestIp())).andReturn().getResponse().getStatus();
    }

    private int imageHeadStatus(long id) throws Exception {
        return mockMvc.perform(head("/popups/{id}/image", id).with(fromTestIp())).andReturn().getResponse().getStatus();
    }

    private String adminStatus(long id) throws Exception {
        String body = mockMvc.perform(get("/admin/api/popups").with(TestMembers.asMember(admin))).andReturn().getResponse().getContentAsString();
        for (JsonNode node : objectMapper.readTree(body)) {
            if (node.get("id").asLong() == id) {
                return node.get("status").asText();
            }
        }
        throw new AssertionError("관리 목록에 팝업 " + id + "이 없다");
    }

    /** 주어진 시각에서 기간 팝업(이미지형·HTML형)과 상시·비노출 대조군이 모든 경로에서 기대대로인지 한 번에 점검한다. */
    private void assertAt(LocalDateTime now, boolean periodVisible, String expectedAdminStatus) throws Exception {
        clock.set(now);
        String label = "now=" + now;

        assertThat(homeShows(periodImageId)).as(label + " 공개 메인 — 기간 이미지 팝업").isEqualTo(periodVisible);
        assertThat(homeShows(periodHtmlId)).as(label + " 공개 메인 — 기간 본문 팝업").isEqualTo(periodVisible);
        assertThat(imageStatus(periodImageId)).as(label + " GET /popups/{id}/image").isEqualTo(periodVisible ? 200 : 404);
        assertThat(imageHeadStatus(periodImageId)).as(label + " HEAD /popups/{id}/image").isEqualTo(periodVisible ? 200 : 404);
        assertThat(adminStatus(periodImageId)).as(label + " 관리 목록 상태 배지(이미지)").isEqualTo(expectedAdminStatus);
        assertThat(adminStatus(periodHtmlId)).as(label + " 관리 목록 상태 배지(본문)").isEqualTo(expectedAdminStatus);

        // 대조군: 기간이 비면 항상 노출, 노출 여부가 꺼지면 시각과 무관하게 숨김
        assertThat(homeShows(alwaysId)).as(label + " 상시 팝업").isTrue();
        assertThat(homeShows(hiddenId)).as(label + " 비노출 팝업").isFalse();
        assertThat(adminStatus(alwaysId)).isEqualTo("ACTIVE");
        assertThat(adminStatus(hiddenId)).isEqualTo("HIDDEN");
    }

    @Test
    @DisplayName("시작 직전에는 어느 경로에도 나오지 않고(예약됨), 시작 시각부터 모든 경로에서 한꺼번에 나온다")
    void startBoundary() throws Exception {
        assertAt(START.minusNanos(MICRO_NANOS), false, "SCHEDULED");
        assertAt(START, true, "ACTIVE");
        assertAt(START.plusNanos(MICRO_NANOS), true, "ACTIVE");
    }

    @Test
    @DisplayName("종료 직전까지는 모든 경로에서 나오고, 종료 시각(미포함)부터 한꺼번에 사라진다(만료)")
    void endBoundary() throws Exception {
        assertAt(END.minusNanos(MICRO_NANOS), true, "ACTIVE");
        assertAt(END, false, "EXPIRED");
        assertAt(END.plusNanos(MICRO_NANOS), false, "EXPIRED");
    }

    @Test
    @DisplayName("기간보다 한참 앞·뒤의 시각에서도 같은 판정이다(시계를 움직이면 결과가 따라 움직인다 — 시스템 시각을 쓰지 않는다는 증거)")
    void clockDrivesTheResult() throws Exception {
        assertAt(LocalDateTime.of(2090, 1, 1, 0, 0), false, "SCHEDULED");
        assertAt(LocalDateTime.of(2090, 3, 4, 12, 0), true, "ACTIVE");
        assertAt(LocalDateTime.of(2091, 1, 1, 0, 0), false, "EXPIRED");
        assertAt(LocalDateTime.of(2090, 3, 4, 12, 0), true, "ACTIVE");   // 되돌리면 다시 나온다
    }
}
