package com.cms.admin.page;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.permission.PermissionAction;
import com.cms.admin.permission.PermissionCache;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 정적 페이지 핸들러 5개 × MANAGER 권한 조합 접근 매트릭스(PLAN-static-page.md 쟁점 9). 배너({@code BannerPermissionMatrixIntegrationTest})와 같은
 * 구조다 — 성공은 "403이 아님"이 아니라 <b>기대 성공 상태 코드와 실제 저장 결과</b>로, 거부는 403과 <b>변경 없음</b>으로 단언하고 동작 오표시를 잡으려고
 * 동작별 단일 권한 조합을 각각 돌린다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class SitePagePermissionMatrixIntegrationTest extends MariaDbContainerSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PermissionCache cache;
    @Autowired MemberRepository memberRepository;

    private Member manager;
    private final List<Long> extraMemberIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        manager = TestMembers.save(memberRepository, "spm-manager", Role.ROLE_MANAGER);
        cleanPages();
    }

    @AfterEach
    void cleanUp() {
        cleanPages();
        TestMembers.delete(jdbc, extraMemberIds);
        TestMembers.delete(jdbc, List.of(manager.getId()));
        cache.invalidate();
    }

    private void cleanPages() {
        jdbc.update("DELETE FROM site_page_revision");
        jdbc.update("DELETE FROM site_page");
    }

    private RequestPostProcessor asManager() {
        return TestMembers.asMember(manager);
    }

    private void grant(Member member, Set<PermissionAction> actions) {
        jdbc.update("DELETE FROM member_permission WHERE member_id = ?", member.getId());
        for (PermissionAction action : actions) {
            jdbc.update("INSERT INTO member_permission (member_id, feature, action) VALUES (?, 'SITE_PAGE', ?)", member.getId(), action.name());
        }
        cache.invalidate();
    }

    private long fixturePage(String slug) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO site_page (slug, current_revision_no, version, use_yn, footer_yn, create_date, update_date) VALUES (?, 1, 1, 1, 0, ?, ?)",
                slug, now, now);
        long id = jdbc.queryForObject("SELECT id FROM site_page WHERE slug = ?", Long.class, slug);
        jdbc.update("INSERT INTO site_page_revision (page_id, revision_no, title, content, create_date) VALUES (?, 1, 'spm-original', '<p>본문</p>', ?)", id, now);
        return id;
    }

    private int pageCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM site_page", Integer.class);
    }

    private String currentTitle(long id) {
        List<String> titles = jdbc.queryForList(
                "SELECT r.title FROM site_page p JOIN site_page_revision r ON r.page_id = p.id AND r.revision_no = p.current_revision_no WHERE p.id = ?",
                String.class, id);
        return titles.isEmpty() ? null : titles.get(0);
    }

    /** 한 핸들러를 실행하고 상태 코드를 돌려준다. */
    private int call(String handler, RequestPostProcessor who, long id) throws Exception {
        MvcResult result = switch (handler) {
            case "목록" -> mockMvc.perform(get("/admin/api/site-pages").with(who)).andReturn();
            case "상세" -> mockMvc.perform(get("/admin/api/site-pages/{id}", id).with(who)).andReturn();
            case "등록" -> mockMvc.perform(post("/admin/api/site-pages").with(who).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"slug\":\"spm-created\",\"title\":\"spm-created\",\"content\":\"<p>x</p>\",\"contentFormat\":\"HTML\"}")).andReturn();
            case "수정" -> mockMvc.perform(put("/admin/api/site-pages/{id}", id).with(who).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"spm-changed\",\"content\":\"<p>y</p>\",\"contentFormat\":\"HTML\",\"useYn\":true,\"footerYn\":false,\"siteScope\":\"COMMON\",\"version\":1}")).andReturn();
            case "삭제" -> mockMvc.perform(delete("/admin/api/site-pages/{id}", id).param("version", "1").with(who).with(csrf())).andReturn();
            default -> throw new IllegalArgumentException(handler);
        };
        return result.getResponse().getStatus();
    }

    private static PermissionAction requiredAction(String handler) {
        return switch (handler) {
            case "목록", "상세" -> PermissionAction.READ;
            case "등록" -> PermissionAction.CREATE;
            case "수정" -> PermissionAction.UPDATE;
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

    private static final List<String> HANDLERS = List.of("목록", "상세", "등록", "수정", "삭제");

    private static final List<Set<PermissionAction>> COMBOS = List.of(
            EnumSet.allOf(PermissionAction.class),
            EnumSet.noneOf(PermissionAction.class),
            EnumSet.of(PermissionAction.READ),
            EnumSet.of(PermissionAction.READ, PermissionAction.CREATE),
            EnumSet.of(PermissionAction.READ, PermissionAction.UPDATE),
            EnumSet.of(PermissionAction.READ, PermissionAction.DELETE),
            EnumSet.of(PermissionAction.CREATE, PermissionAction.UPDATE, PermissionAction.DELETE));   // READ 없는 쓰기

    @Test
    @DisplayName("핸들러 5개 × 권한 7조합: 허용은 기대 성공 상태와 저장 결과, 거부는 403과 변경 없음 (READ 없는 쓰기는 의존 규칙상 전부 거부)")
    void matrix() throws Exception {
        for (Set<PermissionAction> granted : COMBOS) {
            for (String handler : HANDLERS) {
                cleanPages();
                long id = fixturePage("spm-fixture");
                grant(manager, granted);
                int before = pageCount();

                int status = call(handler, asManager(), id);

                PermissionAction required = requiredAction(handler);
                boolean allowed = granted.contains(required) && granted.contains(PermissionAction.READ);
                String label = handler + " / " + granted;
                if (allowed) {
                    assertThat(status).as(label).isEqualTo(successStatus(handler));
                    switch (handler) {
                        case "등록" -> assertThat(pageCount()).as(label).isEqualTo(before + 1);
                        case "수정" -> assertThat(currentTitle(id)).as(label).isEqualTo("spm-changed");
                        case "삭제" -> assertThat(currentTitle(id)).as(label).isNull();
                        default -> { }
                    }
                } else {
                    assertThat(status).as(label).isEqualTo(403);
                    assertThat(pageCount()).as(label + " 변경 없음").isEqualTo(before);
                    assertThat(currentTitle(id)).as(label + " 변경 없음").isEqualTo("spm-original");
                }
            }
        }
    }

    @Test
    @DisplayName("신규 MANAGER(권한 0개)는 관리 페이지·API가 전부 403이고, ADMIN은 DB 권한 없이 전부 허용된다")
    void newManagerHasNothing_adminHasAll() throws Exception {
        long id = fixturePage("spm-fixture");

        for (String handler : HANDLERS) {
            assertThat(call(handler, asManager(), id)).as(handler).isEqualTo(403);
        }
        assertThat(mockMvc.perform(get("/admin/site-page/manage").with(asManager())).andReturn().getResponse().getStatus()).isEqualTo(403);

        Member admin = TestMembers.save(memberRepository, "spm-admin", Role.ROLE_ADMIN);
        extraMemberIds.add(admin.getId());
        assertThat(call("목록", TestMembers.asMember(admin), id)).isEqualTo(200);
        assertThat(mockMvc.perform(get("/admin/site-page/manage").with(TestMembers.asMember(admin))).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("READ를 받은 MANAGER는 관리 페이지(GET)가 열린다 — 사이드바 노출과 같은 기준")
    void readGrantOpensManagePage() throws Exception {
        grant(manager, EnumSet.of(PermissionAction.READ));

        assertThat(mockMvc.perform(get("/admin/site-page/manage").with(asManager())).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("교차 회원 격리: 회원 A에게만 준 정적 페이지 권한은 같은 MANAGER 역할의 회원 B에게 적용되지 않는다")
    void grantsAreIsolatedPerMember() throws Exception {
        long id = fixturePage("spm-fixture");
        Member other = TestMembers.save(memberRepository, "spm-other", Role.ROLE_MANAGER);
        extraMemberIds.add(other.getId());
        grant(manager, EnumSet.allOf(PermissionAction.class));

        assertThat(call("목록", asManager(), id)).isEqualTo(200);
        assertThat(call("목록", TestMembers.asMember(other), id)).isEqualTo(403);
        assertThat(call("삭제", TestMembers.asMember(other), id)).isEqualTo(403);
        assertThat(currentTitle(id)).isEqualTo("spm-original");
    }

    @Test
    @DisplayName("권한 회수는 같은 로그인 세션의 다음 요청부터 반영된다(재로그인 없이): 허용 → 회수 → 403")
    void revocationIsImmediate() throws Exception {
        long id = fixturePage("spm-fixture");
        grant(manager, EnumSet.of(PermissionAction.READ, PermissionAction.UPDATE));
        assertThat(call("수정", asManager(), id)).isEqualTo(200);

        grant(manager, EnumSet.noneOf(PermissionAction.class));

        assertThat(call("수정", asManager(), id)).isEqualTo(403);
        assertThat(call("목록", asManager(), id)).isEqualTo(403);
    }

    @Test
    @DisplayName("배너 권한은 정적 페이지를 열지 못한다 — 기능 단위 위임 두 기능은 서로 독립이다")
    void bannerGrantDoesNotOpenSitePage() throws Exception {
        long id = fixturePage("spm-fixture");
        jdbc.update("INSERT INTO member_permission (member_id, feature, action) VALUES (?, 'BANNER', 'READ')", manager.getId());
        cache.invalidate();

        assertThat(call("목록", asManager(), id)).as("배너 READ는 정적 페이지 READ가 아니다").isEqualTo(403);
    }
}
