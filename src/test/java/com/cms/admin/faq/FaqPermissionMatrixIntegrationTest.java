package com.cms.admin.faq;

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
 * FAQ 핸들러 9개 × MANAGER 권한 조합 접근 매트릭스(PLAN-faq.md 쟁점 2·15) — 네 번째 기능 단위 위임(DELEGABLE) 기능 {@code FAQ}.
 * 성공 판정은 "403이 아님"이 아니라 <b>기대 성공 상태 코드와 실제 저장 결과</b>로, 거부는 403과 <b>변경 없음</b>으로 단언한다. 동작 오표시
 * (예: 삭제를 UPDATE로 표시, 순서 반영을 CREATE로 표시)를 잡으려고 동작별 단일 권한 조합을 각각 돌린다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class FaqPermissionMatrixIntegrationTest extends MariaDbContainerSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PermissionCache cache;
    @Autowired MemberRepository memberRepository;

    private Member manager;
    private final List<Long> extraMemberIds = new ArrayList<>();

    private long categoryId;       // 카테고리 c — FAQ a(ord 0), b(ord 1)
    private long emptyCategoryId;  // 빈 카테고리 e — 삭제 시험용
    private long faqA;
    private long faqB;

    @BeforeEach
    void setUp() {
        manager = TestMembers.save(memberRepository, "fm-manager", Role.ROLE_MANAGER);
        resetFixtures();
    }

    @AfterEach
    void cleanUp() {
        cleanFaq();
        TestMembers.delete(jdbc, extraMemberIds);
        TestMembers.delete(jdbc, List.of(manager.getId()));
        cache.invalidate();
    }

    private void cleanFaq() {
        jdbc.update("DELETE FROM faq");
        jdbc.update("DELETE FROM faq_category");
        jdbc.update("UPDATE faq_lock SET version = 0");
    }

    private void resetFixtures() {
        cleanFaq();
        categoryId = insertCategory("fm-c", 0);
        emptyCategoryId = insertCategory("fm-e", 1);
        faqA = insertFaq(categoryId, "fm-a", 0);
        faqB = insertFaq(categoryId, "fm-b", 1);
    }

    private long insertCategory(String name, int ord) {
        jdbc.update("INSERT INTO faq_category (name, ord, use_yn, create_date, update_date) VALUES (?, ?, 1, ?, ?)",
                name, ord, LocalDateTime.now(), LocalDateTime.now());
        return jdbc.queryForObject("SELECT MAX(id) FROM faq_category", Long.class);
    }

    private long insertFaq(long category, String question, int ord) {
        jdbc.update("INSERT INTO faq (category_id, question, answer_html, ord, use_yn, create_date, update_date) VALUES (?, ?, '<p>a</p>', ?, 1, ?, ?)",
                category, question, ord, LocalDateTime.now(), LocalDateTime.now());
        return jdbc.queryForObject("SELECT MAX(id) FROM faq", Long.class);
    }

    private RequestPostProcessor asManager() {
        return TestMembers.asMember(manager);
    }

    private void grant(Member member, String feature, Set<PermissionAction> actions) {
        jdbc.update("DELETE FROM member_permission WHERE member_id = ?", member.getId());
        for (PermissionAction action : actions) {
            jdbc.update("INSERT INTO member_permission (member_id, feature, action) VALUES (?, ?, ?)", member.getId(), feature, action.name());
        }
        cache.invalidate();
    }

    private void grant(Member member, Set<PermissionAction> actions) {
        grant(member, "FAQ", actions);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private String faqQuestion(long id) {
        List<String> values = jdbc.queryForList("SELECT question FROM faq WHERE id = ?", String.class, id);
        return values.isEmpty() ? null : values.get(0);
    }

    private String categoryName(long id) {
        List<String> values = jdbc.queryForList("SELECT name FROM faq_category WHERE id = ?", String.class, id);
        return values.isEmpty() ? null : values.get(0);
    }

    private int faqOrd(long id) {
        return jdbc.queryForObject("SELECT ord FROM faq WHERE id = ?", Integer.class, id);
    }

    private static final String FAQ_BODY_TEMPLATE =
            "{\"categoryId\":%d,\"baseCategoryId\":%d,\"question\":\"fm-changed\",\"answer\":\"<p>x</p>\",\"contentFormat\":\"HTML\",\"useYn\":true}";

    /** 한 핸들러를 실행하고 상태 코드를 돌려준다. */
    private int call(String handler, RequestPostProcessor who) throws Exception {
        long version = jdbc.queryForObject("SELECT version FROM faq_lock WHERE id = 1", Long.class);
        MvcResult result = switch (handler) {
            case "트리" -> mockMvc.perform(get("/admin/api/faqs").with(who)).andReturn();
            case "상세" -> mockMvc.perform(get("/admin/api/faqs/{id}", faqA).with(who)).andReturn();
            case "FAQ 등록" -> mockMvc.perform(post("/admin/api/faqs").with(who).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(FAQ_BODY_TEMPLATE.formatted(categoryId, categoryId))).andReturn();
            case "카테고리 등록" -> mockMvc.perform(post("/admin/api/faq-categories").with(who).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"fm-new\",\"useYn\":true}")).andReturn();
            case "FAQ 수정" -> mockMvc.perform(put("/admin/api/faqs/{id}", faqA).with(who).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(FAQ_BODY_TEMPLATE.formatted(categoryId, categoryId))).andReturn();
            case "카테고리 수정" -> mockMvc.perform(put("/admin/api/faq-categories/{id}", categoryId).with(who).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"fm-renamed\",\"useYn\":true,\"siteScope\":\"COMMON\",\"version\":"
                            + jdbc.queryForObject("SELECT version FROM faq_category WHERE id = ?", Integer.class, categoryId) + "}")).andReturn();
            case "순서 반영" -> mockMvc.perform(put("/admin/api/faqs/order").with(who).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":%d,\"categories\":[{\"id\":%d,\"faqIds\":[]},{\"id\":%d,\"faqIds\":[%d,%d]}]}"
                            .formatted(version, emptyCategoryId, categoryId, faqB, faqA))).andReturn();
            case "FAQ 삭제" -> mockMvc.perform(delete("/admin/api/faqs/{id}", faqA).with(who).with(csrf())).andReturn();
            case "카테고리 삭제" -> mockMvc.perform(delete("/admin/api/faq-categories/{id}", emptyCategoryId).with(who).with(csrf())).andReturn();
            default -> throw new IllegalArgumentException(handler);
        };
        return result.getResponse().getStatus();
    }

    private static PermissionAction requiredAction(String handler) {
        return switch (handler) {
            case "트리", "상세" -> PermissionAction.READ;
            case "FAQ 등록", "카테고리 등록" -> PermissionAction.CREATE;
            case "FAQ 수정", "카테고리 수정", "순서 반영" -> PermissionAction.UPDATE;
            case "FAQ 삭제", "카테고리 삭제" -> PermissionAction.DELETE;
            default -> throw new IllegalArgumentException(handler);
        };
    }

    private static int successStatus(String handler) {
        return switch (handler) {
            case "FAQ 등록", "카테고리 등록" -> 201;
            case "FAQ 삭제", "카테고리 삭제" -> 204;
            default -> 200;
        };
    }

    private static final List<String> HANDLERS = List.of("트리", "상세", "FAQ 등록", "카테고리 등록", "FAQ 수정", "카테고리 수정", "순서 반영", "FAQ 삭제", "카테고리 삭제");

    private static final List<Set<PermissionAction>> COMBOS = List.of(
            EnumSet.allOf(PermissionAction.class),
            EnumSet.noneOf(PermissionAction.class),
            EnumSet.of(PermissionAction.READ),
            EnumSet.of(PermissionAction.READ, PermissionAction.CREATE),
            EnumSet.of(PermissionAction.READ, PermissionAction.UPDATE),
            EnumSet.of(PermissionAction.READ, PermissionAction.DELETE),
            EnumSet.of(PermissionAction.CREATE, PermissionAction.UPDATE, PermissionAction.DELETE));   // READ 없는 쓰기

    @Test
    @DisplayName("핸들러 9개 × 권한 7조합: 허용은 기대 성공 상태와 저장 결과, 거부는 403과 변경 없음 (READ 없는 쓰기는 의존 규칙상 전부 거부)")
    void matrix() throws Exception {
        for (Set<PermissionAction> granted : COMBOS) {
            for (String handler : HANDLERS) {
                resetFixtures();
                grant(manager, granted);
                int faqsBefore = count("faq");
                int categoriesBefore = count("faq_category");

                int status = call(handler, asManager());

                PermissionAction required = requiredAction(handler);
                boolean allowed = granted.contains(required) && granted.contains(PermissionAction.READ);
                String label = handler + " / " + granted;
                if (allowed) {
                    assertThat(status).as(label).isEqualTo(successStatus(handler));
                    switch (handler) {
                        case "FAQ 등록" -> assertThat(count("faq")).as(label).isEqualTo(faqsBefore + 1);
                        case "카테고리 등록" -> assertThat(count("faq_category")).as(label).isEqualTo(categoriesBefore + 1);
                        case "FAQ 수정" -> assertThat(faqQuestion(faqA)).as(label).isEqualTo("fm-changed");
                        case "카테고리 수정" -> assertThat(categoryName(categoryId)).as(label).isEqualTo("fm-renamed");
                        case "순서 반영" -> assertThat(faqOrd(faqA)).as(label).isEqualTo(1);
                        case "FAQ 삭제" -> assertThat(faqQuestion(faqA)).as(label).isNull();
                        case "카테고리 삭제" -> assertThat(categoryName(emptyCategoryId)).as(label).isNull();
                        default -> { }
                    }
                } else {
                    assertThat(status).as(label).isEqualTo(403);
                    assertThat(count("faq")).as(label + " 변경 없음").isEqualTo(faqsBefore);
                    assertThat(count("faq_category")).as(label + " 변경 없음").isEqualTo(categoriesBefore);
                    assertThat(faqQuestion(faqA)).as(label + " 변경 없음").isEqualTo("fm-a");
                    assertThat(categoryName(categoryId)).as(label + " 변경 없음").isEqualTo("fm-c");
                    assertThat(faqOrd(faqA)).as(label + " 변경 없음").isZero();
                    assertThat(jdbc.queryForObject("SELECT version FROM faq_lock WHERE id = 1", Long.class)).as(label + " 버전 불변").isZero();
                }
            }
        }
    }

    @Test
    @DisplayName("신규 MANAGER(권한 0개)는 FAQ 페이지·API가 전부 403이고, ADMIN은 DB 권한 없이 전부 허용된다")
    void newManagerHasNothing_adminHasAll() throws Exception {
        for (String handler : HANDLERS) {
            assertThat(call(handler, asManager())).as(handler).isEqualTo(403);
        }
        assertThat(mockMvc.perform(get("/admin/faq/manage").with(asManager())).andReturn().getResponse().getStatus()).isEqualTo(403);

        Member admin = TestMembers.save(memberRepository, "fm-admin", Role.ROLE_ADMIN);
        extraMemberIds.add(admin.getId());
        assertThat(call("트리", TestMembers.asMember(admin))).isEqualTo(200);
        assertThat(mockMvc.perform(get("/admin/faq/manage").with(TestMembers.asMember(admin))).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("FAQ READ를 받은 MANAGER는 FAQ 관리 화면을 열 수 있다")
    void readOpensPage() throws Exception {
        grant(manager, EnumSet.of(PermissionAction.READ));

        assertThat(mockMvc.perform(get("/admin/faq/manage").with(asManager())).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("교차 회원 격리: 회원 A에게만 준 FAQ 권한은 같은 MANAGER 역할의 회원 B에게 적용되지 않는다")
    void grantsAreIsolatedPerMember() throws Exception {
        Member other = TestMembers.save(memberRepository, "fm-other", Role.ROLE_MANAGER);
        extraMemberIds.add(other.getId());
        grant(manager, EnumSet.allOf(PermissionAction.class));

        assertThat(call("트리", asManager())).isEqualTo(200);
        assertThat(call("트리", TestMembers.asMember(other))).isEqualTo(403);
        assertThat(call("FAQ 삭제", TestMembers.asMember(other))).isEqualTo(403);
        assertThat(faqQuestion(faqA)).isEqualTo("fm-a");
    }

    @Test
    @DisplayName("권한 회수는 같은 로그인 세션의 다음 요청부터 반영된다(재로그인 없이): 허용 → 회수 → 403")
    void revocationIsImmediate() throws Exception {
        grant(manager, EnumSet.of(PermissionAction.READ, PermissionAction.UPDATE));
        assertThat(call("FAQ 수정", asManager())).isEqualTo(200);

        grant(manager, EnumSet.noneOf(PermissionAction.class));

        assertThat(call("FAQ 수정", asManager())).isEqualTo(403);
        assertThat(call("트리", asManager())).isEqualTo(403);
    }

    @Test
    @DisplayName("다른 기능 권한(BANNER·POPUP·SITE_PAGE 전부)은 FAQ를 열지 못하고, FAQ 전부는 다른 기능을 열지 못한다")
    void otherFeaturePermissionsAreIndependent() throws Exception {
        for (String other : List.of("BANNER", "POPUP", "SITE_PAGE")) {
            grant(manager, other, EnumSet.allOf(PermissionAction.class));
            assertThat(call("트리", asManager())).as(other + " 전부는 FAQ가 아니다").isEqualTo(403);
        }

        grant(manager, "FAQ", EnumSet.allOf(PermissionAction.class));
        assertThat(call("트리", asManager())).isEqualTo(200);
        assertThat(mockMvc.perform(get("/admin/api/banners").with(asManager())).andReturn().getResponse().getStatus()).as("FAQ 전부는 BANNER가 아니다").isEqualTo(403);
        assertThat(mockMvc.perform(get("/admin/api/popups").with(asManager())).andReturn().getResponse().getStatus()).as("FAQ 전부는 POPUP이 아니다").isEqualTo(403);
    }
}
