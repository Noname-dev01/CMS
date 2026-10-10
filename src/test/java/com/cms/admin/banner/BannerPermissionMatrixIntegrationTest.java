package com.cms.admin.banner;

import com.cms.admin.banner.service.BannerService;
import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.permission.PermissionAction;
import com.cms.admin.permission.PermissionCache;
import com.cms.common.image.ImageFileValidatorTest;
import com.cms.common.storage.FileStorage;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 배너 핸들러 7개 × MANAGER 권한 조합 접근 매트릭스(PLAN-public-home-banner.md 쟁점 16) — 카탈로그의 <b>첫 기능 단위 위임(DELEGABLE)</b>
 * 기능이라 {@code member_permission} 경로(URL 게이트의 {@code featureReadGate} + 핸들러의 {@code @RequirePermission} + 캐시)를 실제 스택으로 시험한다.
 * 성공 판정은 "403이 아님"이 아니라 <b>기대 성공 상태 코드와 실제 저장 결과</b>로, 거부는 403과 <b>변경 없음</b>으로 단언한다. 동작 오표시
 * (예: 삭제를 UPDATE로 표시)를 잡으려고 동작별 단일 권한 조합(READ+CREATE, READ+UPDATE, READ+DELETE)을 각각 돌린다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class BannerPermissionMatrixIntegrationTest extends MariaDbContainerSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PermissionCache cache;
    @Autowired MemberRepository memberRepository;
    @Autowired FileStorage fileStorage;

    private Member manager;
    private final List<Long> extraMemberIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        manager = TestMembers.save(memberRepository, "bm-manager", Role.ROLE_MANAGER);
        cleanBanners();
    }

    @AfterEach
    void cleanUp() {
        cleanBanners();
        TestMembers.delete(jdbc, extraMemberIds);
        TestMembers.delete(jdbc, List.of(manager.getId()));
        cache.invalidate();
    }

    private void cleanBanners() {
        for (String key : jdbc.queryForList("SELECT storage_key FROM banner", String.class)) {
            fileStorage.delete(key, BannerService.STORAGE_NAMESPACE);
        }
        jdbc.update("DELETE FROM banner");
    }

    private RequestPostProcessor asManager() {
        return TestMembers.asMember(manager);
    }

    private void grant(Member member, Set<PermissionAction> actions) {
        jdbc.update("DELETE FROM member_permission WHERE member_id = ?", member.getId());
        for (PermissionAction action : actions) {
            jdbc.update("INSERT INTO member_permission (member_id, feature, action) VALUES (?, 'BANNER', ?)", member.getId(), action.name());
        }
        cache.invalidate();
    }

    private long fixtureBanner(String title, int ord) throws Exception {
        String key = fileStorage.store(ImageFileValidatorTest.png(20, 10), "b.png", BannerService.STORAGE_NAMESPACE);
        jdbc.update("INSERT INTO banner (title, storage_key, content_type, file_size, use_yn, ord, create_date, update_date) VALUES (?, ?, 'image/png', 10, 1, ?, ?, ?)",
                title, key, ord, LocalDateTime.now(), LocalDateTime.now());
        return jdbc.queryForObject("SELECT MAX(id) FROM banner", Long.class);
    }

    private int bannerCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM banner", Integer.class);
    }

    private String title(long id) {
        List<String> titles = jdbc.queryForList("SELECT title FROM banner WHERE id = ?", String.class, id);
        return titles.isEmpty() ? null : titles.get(0);
    }

    private int ord(long id) {
        return jdbc.queryForObject("SELECT ord FROM banner WHERE id = ?", Integer.class, id);
    }

    /** 한 핸들러를 실행하고 상태 코드를 돌려준다. {@code a}·{@code b}는 시험 시작 시 만든 배너 두 개. */
    private int call(String handler, RequestPostProcessor who, long a, long b) throws Exception {
        MvcResult result = switch (handler) {
            case "목록" -> mockMvc.perform(get("/admin/api/banners").with(who)).andReturn();
            case "상세" -> mockMvc.perform(get("/admin/api/banners/{id}", a).with(who)).andReturn();
            case "이미지" -> mockMvc.perform(get("/admin/api/banners/{id}/image", a).with(who)).andReturn();
            case "등록" -> mockMvc.perform(multipart("/admin/api/banners")
                    .file(new MockMultipartFile("image", "b.png", "image/png", ImageFileValidatorTest.png(20, 10)))
                    .param("title", "bm-created").with(who).with(csrf())).andReturn();
            case "수정" -> mockMvc.perform(put("/admin/api/banners/{id}", a).with(who).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"bm-changed\",\"useYn\":true}")).andReturn();
            case "순서" -> mockMvc.perform(put("/admin/api/banners/order").with(who).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[" + b + "," + a + "]}")).andReturn();
            case "삭제" -> mockMvc.perform(delete("/admin/api/banners/{id}", a).with(who).with(csrf())).andReturn();
            default -> throw new IllegalArgumentException(handler);
        };
        return result.getResponse().getStatus();
    }

    private static PermissionAction requiredAction(String handler) {
        return switch (handler) {
            case "목록", "상세", "이미지" -> PermissionAction.READ;
            case "등록" -> PermissionAction.CREATE;
            case "수정", "순서" -> PermissionAction.UPDATE;
            case "삭제" -> PermissionAction.DELETE;
            default -> throw new IllegalArgumentException(handler);
        };
    }

    private static int successStatus(String handler) {
        return switch (handler) {
            case "등록" -> 201;
            case "삭제" -> 204;
            default -> 200;
        };
    }

    private static final List<String> HANDLERS = List.of("목록", "상세", "이미지", "등록", "수정", "순서", "삭제");

    private static final List<Set<PermissionAction>> COMBOS = List.of(
            EnumSet.allOf(PermissionAction.class),
            EnumSet.noneOf(PermissionAction.class),
            EnumSet.of(PermissionAction.READ),
            EnumSet.of(PermissionAction.READ, PermissionAction.CREATE),
            EnumSet.of(PermissionAction.READ, PermissionAction.UPDATE),
            EnumSet.of(PermissionAction.READ, PermissionAction.DELETE),
            EnumSet.of(PermissionAction.CREATE, PermissionAction.UPDATE, PermissionAction.DELETE));   // READ 없는 쓰기

    @Test
    @DisplayName("핸들러 7개 × 권한 7조합: 허용은 기대 성공 상태와 저장 결과, 거부는 403과 변경 없음 (READ 없는 쓰기는 의존 규칙상 전부 거부)")
    void matrix() throws Exception {
        for (Set<PermissionAction> granted : COMBOS) {
            for (String handler : HANDLERS) {
                cleanBanners();
                long a = fixtureBanner("bm-a", 0);
                long b = fixtureBanner("bm-b", 1);
                grant(manager, granted);
                int before = bannerCount();

                int status = call(handler, asManager(), a, b);

                PermissionAction required = requiredAction(handler);
                boolean allowed = granted.contains(required) && granted.contains(PermissionAction.READ);
                String label = handler + " / " + granted;
                if (allowed) {
                    assertThat(status).as(label).isEqualTo(successStatus(handler));
                    switch (handler) {
                        case "등록" -> assertThat(bannerCount()).as(label).isEqualTo(before + 1);
                        case "수정" -> assertThat(title(a)).as(label).isEqualTo("bm-changed");
                        case "순서" -> assertThat(ord(b)).as(label).isZero();
                        case "삭제" -> assertThat(title(a)).as(label).isNull();
                        default -> { }
                    }
                } else {
                    assertThat(status).as(label).isEqualTo(403);
                    assertThat(bannerCount()).as(label + " 변경 없음").isEqualTo(before);
                    assertThat(title(a)).as(label + " 변경 없음").isEqualTo("bm-a");
                    assertThat(ord(a)).as(label + " 순서 불변").isZero();
                    assertThat(ord(b)).as(label + " 순서 불변").isEqualTo(1);
                }
            }
        }
    }

    @Test
    @DisplayName("신규 MANAGER(권한 0개)는 배너 페이지·API가 전부 403이고, ADMIN은 DB 권한 없이 전부 허용된다")
    void newManagerHasNothing_adminHasAll() throws Exception {
        long a = fixtureBanner("bm-a", 0);
        long b = fixtureBanner("bm-b", 1);

        for (String handler : HANDLERS) {
            assertThat(call(handler, asManager(), a, b)).as(handler).isEqualTo(403);
        }
        assertThat(mockMvc.perform(get("/admin/banner/manage").with(asManager())).andReturn().getResponse().getStatus()).isEqualTo(403);

        Member admin = TestMembers.save(memberRepository, "bm-admin", Role.ROLE_ADMIN);
        extraMemberIds.add(admin.getId());
        assertThat(call("목록", TestMembers.asMember(admin), a, b)).isEqualTo(200);
        assertThat(mockMvc.perform(get("/admin/banner/manage").with(TestMembers.asMember(admin))).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("교차 회원 격리: 회원 A에게만 준 배너 권한은 같은 MANAGER 역할의 회원 B에게 적용되지 않는다")
    void grantsAreIsolatedPerMember() throws Exception {
        long a = fixtureBanner("bm-a", 0);
        long b = fixtureBanner("bm-b", 1);
        Member other = TestMembers.save(memberRepository, "bm-other", Role.ROLE_MANAGER);
        extraMemberIds.add(other.getId());
        grant(manager, EnumSet.allOf(PermissionAction.class));

        assertThat(call("목록", asManager(), a, b)).isEqualTo(200);
        assertThat(call("목록", TestMembers.asMember(other), a, b)).isEqualTo(403);
        assertThat(call("삭제", TestMembers.asMember(other), a, b)).isEqualTo(403);
        assertThat(title(a)).isEqualTo("bm-a");
    }

    @Test
    @DisplayName("권한 회수는 같은 로그인 세션의 다음 요청부터 반영된다(재로그인 없이): 허용 → 회수 → 403")
    void revocationIsImmediate() throws Exception {
        long a = fixtureBanner("bm-a", 0);
        long b = fixtureBanner("bm-b", 1);
        grant(manager, EnumSet.of(PermissionAction.READ, PermissionAction.UPDATE));
        assertThat(call("수정", asManager(), a, b)).isEqualTo(200);

        grant(manager, EnumSet.noneOf(PermissionAction.class));

        assertThat(call("수정", asManager(), a, b)).isEqualTo(403);
        assertThat(call("목록", asManager(), a, b)).isEqualTo(403);
    }

    @Test
    @DisplayName("게시판 권한(member_board_permission의 READ)은 배너(기능 단위 권한)를 열지 못한다 — 두 권한 종류는 서로 독립이다")
    void boardPermissionDoesNotOpenBanner() throws Exception {
        long a = fixtureBanner("bm-a", 0);
        long b = fixtureBanner("bm-b", 1);
        Long boardId = jdbc.queryForObject("SELECT id FROM board WHERE board_key = 'NOTICE'", Long.class);
        jdbc.update("INSERT INTO member_board_permission (member_id, board_id, action) VALUES (?, ?, 'READ')", manager.getId(), boardId);
        cache.invalidate();
        try {
            assertThat(call("목록", asManager(), a, b)).as("게시판 READ는 배너 READ가 아니다").isEqualTo(403);
        } finally {
            jdbc.update("DELETE FROM member_board_permission WHERE member_id = ?", manager.getId());
            cache.invalidate();
        }
    }
}
