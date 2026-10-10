package com.cms.admin.permission;

import com.cms.admin.log.constant.AdminActionTypes;
import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.config.auth.CustomUserDetails;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 기능 단위 위임(DELEGABLE) 권한관리 경로의 실제 스택 시험 — 배너(BANNER)가 카탈로그의 첫 DELEGABLE 기능이 되며 공지 흡수 때 공백이 된
 * {@code member_permission} 경로(PUT 교체·검증·버전 충돌·변형 행 가드·즉시 반영)를 복구한다(PLAN-public-home-banner.md 쟁점 16).
 * 게시판 판은 {@code MemberPermissionApiIntegrationTest}가 고정한다. MockMvc 호출이 실제로 커밋하므로 만든 회원·행을 직접 지운다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class BannerPermissionApiIntegrationTest extends MariaDbContainerSupport {

    private static final String PASSWORD = "pw1234!";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberRepository memberRepository;
    @Autowired PermissionCache cache;
    @Autowired PasswordEncoder passwordEncoder;

    private Member manager;
    private long auditBaseline;

    @BeforeEach
    void setUp() {
        Member base = TestMembers.save(memberRepository, "bp-mgr", Role.ROLE_MANAGER);
        LocalDateTime now = LocalDateTime.now();
        manager = memberRepository.save(Member.builder().id(base.getId()).userId(base.getUserId())
                .pwd(passwordEncoder.encode(PASSWORD)).userName("bp-mgr").email(base.getEmail()).userType(Role.ROLE_MANAGER)
                .status(MemberStatus.ACTIVE).createDate(now).updateDate(now).passwordChangedAt(now)
                .permissionVersion(base.getPermissionVersion()).build());
        auditBaseline = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM admin_action_log", Long.class);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM visit_log WHERE visitor_user_id = ?", manager.getUserId());
        TestMembers.delete(jdbc, List.of(manager.getId()));
        jdbc.update("DELETE FROM admin_action_log WHERE id > ?", auditBaseline);
        cache.invalidate();
    }

    private String url() {
        return "/admin/api/members/" + manager.getId() + "/permissions";
    }

    private static RequestPostProcessor asAdmin() {
        Member member = Member.builder().id(1L).userId("bp-admin").userName("bp-admin").email("bp-admin@example.com")
                .userType(Role.ROLE_ADMIN).status(MemberStatus.ACTIVE).build();
        CustomUserDetails details = new CustomUserDetails(member);
        return authentication(new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
    }

    private long version() {
        return jdbc.queryForObject("SELECT permission_version FROM member WHERE id = ?", Long.class, manager.getId());
    }

    private Set<String> bannerActions() {
        return new TreeSet<>(jdbc.queryForList("SELECT action FROM member_permission WHERE member_id = ? AND feature = 'BANNER'",
                String.class, manager.getId()));
    }

    /** {@code features}에 BANNER 동작들을 담은 PUT 본문. 게시판 단위 허용은 비운다(boardGrants는 필수). */
    private static String body(long version, String... bannerActions) {
        StringBuilder grants = new StringBuilder();
        for (String action : bannerActions) {
            if (grants.length() > 0) {
                grants.append(',');
            }
            grants.append("{\"feature\":\"BANNER\",\"action\":\"").append(action).append("\"}");
        }
        return "{\"grants\":[" + grants + "],\"boardGrants\":[],\"version\":" + version + "}";
    }

    private int putGrants(String json) throws Exception {
        return mockMvc.perform(put(url()).with(asAdmin()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json))
                .andReturn().getResponse().getStatus();
    }

    private MockHttpSession loginAsManager() throws Exception {
        MockHttpSession session = (MockHttpSession) mockMvc.perform(post("/admin/login").with(csrf())
                        .param("username", manager.getUserId()).param("password", PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andReturn().getRequest().getSession(false);
        assertThat(session).isNotNull();
        return session;
    }

    @Test
    @DisplayName("ADMIN PUT: BANNER 행·버전이 바뀌고 SUCCESS 감사 1건(target_id = 대상 회원 ID, 라벨에 배너 항목, 낡은 화면 보호용 버전 +1)")
    void put_changesRowsVersionAndAudit() throws Exception {
        long before = version();

        assertThat(putGrants(body(before, "READ", "UPDATE"))).isEqualTo(200);

        assertThat(bannerActions()).containsExactly("READ", "UPDATE");
        assertThat(version()).isEqualTo(before + 1);
        List<String> labels = jdbc.queryForList(
                "SELECT target_label FROM admin_action_log WHERE id > ? AND action_type = ? AND action_result = 'SUCCESS' AND target_id = ?",
                String.class, auditBaseline, AdminActionTypes.PERMISSION_UPDATE, manager.getId());
        assertThat(labels).hasSize(1);
        assertThat(labels.get(0)).startsWith("v" + before + "→v" + (before + 1) + ": 추가 2·회수 0").contains("+배너.조회", "+배너.수정");
    }

    @Test
    @DisplayName("변경 없는 PUT은 버전·행이 그대로이고, 전부 비우면 회수된다")
    void put_noChangeAndRevokeAll() throws Exception {
        long v0 = version();
        assertThat(putGrants(body(v0, "READ"))).isEqualTo(200);
        long v1 = version();
        assertThat(putGrants(body(v1, "READ"))).isEqualTo(200);
        assertThat(version()).as("변경 없음").isEqualTo(v1);

        assertThat(putGrants(body(v1))).isEqualTo(200);

        assertThat(bannerActions()).isEmpty();
        assertThat(version()).isEqualTo(v1 + 1);
    }

    @Test
    @DisplayName("검증 실패는 400이고 DB·버전이 바뀌지 않는다: READ 없는 쓰기, 중복, 위임 불가(MENU)·게시판 단위(BOARD) 기능, 알 수 없는 기능 이름")
    void put_invalid_badRequest() throws Exception {
        long before = version();

        assertThat(putGrants(body(before, "CREATE"))).as("READ 없는 쓰기").isEqualTo(400);
        assertThat(putGrants(body(before, "READ", "READ"))).as("중복").isEqualTo(400);
        assertThat(putGrants("{\"grants\":[{\"feature\":\"MENU\",\"action\":\"READ\"}],\"boardGrants\":[],\"version\":" + before + "}"))
                .as("위임 불가 기능").isEqualTo(400);
        assertThat(putGrants("{\"grants\":[{\"feature\":\"BOARD\",\"action\":\"READ\"}],\"boardGrants\":[],\"version\":" + before + "}"))
                .as("게시판 단위 기능은 기능 행으로 부여할 수 없다").isEqualTo(400);
        assertThat(putGrants("{\"grants\":[{\"feature\":\"NOTICE\",\"action\":\"READ\"}],\"boardGrants\":[],\"version\":" + before + "}"))
                .as("카탈로그에서 제거된 기능 이름").isEqualTo(400);

        assertThat(bannerActions()).isEmpty();
        assertThat(version()).isEqualTo(before);
    }

    @Test
    @DisplayName("낡은 version은 409이고 행·버전이 바뀌지 않는다")
    void put_staleVersion_conflict() throws Exception {
        long before = version();
        assertThat(putGrants(body(before, "READ"))).isEqualTo(200);

        assertThat(putGrants(body(before, "READ", "DELETE"))).isEqualTo(409);

        assertThat(bannerActions()).containsExactly("READ");
    }

    @Test
    @DisplayName("GET 매트릭스: BANNER는 DELEGABLE이고 유효 허용값(READ 의존 적용)을 돌려준다")
    void get_returnsBannerRow() throws Exception {
        assertThat(putGrants(body(version(), "READ", "DELETE"))).isEqualTo(200);

        mockMvc.perform(get(url()).with(asAdmin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.features[?(@.feature=='BANNER')].kind").value("DELEGABLE"))
                .andExpect(jsonPath("$.features[?(@.feature=='BANNER')].grantedActions.length()").value(2))
                .andExpect(jsonPath("$.features[?(@.feature=='BOARD')].kind").value("BOARD_SCOPED"));
    }

    @Test
    @DisplayName("PK가 general_ci·PAD 비교라 충돌하는 변형 행(소문자 동작)이 있으면 409, 정리 후에는 200 — 자동 삭제하지 않는다")
    void variantRow_conflictUntilCleaned() throws Exception {
        jdbc.update("INSERT INTO member_permission (member_id, feature, action) VALUES (?, 'BANNER', 'read')", manager.getId());
        cache.invalidate();

        assertThat(putGrants(body(version(), "READ"))).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member_permission WHERE member_id = ?", Integer.class, manager.getId()))
                .as("변형 행을 자동 삭제하지 않는다").isEqualTo(1);

        jdbc.update("DELETE FROM member_permission WHERE member_id = ?", manager.getId());
        assertThat(putGrants(body(version(), "READ"))).isEqualTo(200);
    }

    @Test
    @DisplayName("같은 MANAGER 로그인 세션에서 ADMIN이 BANNER 권한을 부여·회수하면 다음 요청부터 API·페이지·사이드바에 반영된다(재로그인 없이)")
    void grantAndRevoke_takeEffectImmediatelyOnSameSession() throws Exception {
        MockHttpSession session = loginAsManager();
        mockMvc.perform(get("/admin/api/banners").session(session)).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/banner/manage").session(session)).andExpect(status().isForbidden());
        assertThat(mockMvc.perform(get("/admin").session(session)).andReturn().getResponse().getContentAsString())
                .doesNotContain("href=\"/admin/banner/manage\"");

        assertThat(putGrants(body(version(), "READ"))).isEqualTo(200);

        mockMvc.perform(get("/admin/api/banners").session(session)).andExpect(status().isOk());
        mockMvc.perform(get("/admin/banner/manage").session(session)).andExpect(status().isOk());
        assertThat(mockMvc.perform(get("/admin").session(session)).andReturn().getResponse().getContentAsString())
                .contains("href=\"/admin/banner/manage\"");
        // READ만 허용했으므로 쓰기는 403, 화면 버튼용 키도 READ뿐이다
        mockMvc.perform(multipart("/admin/api/banners").file(new org.springframework.mock.web.MockMultipartFile("image", "b.png", "image/png", new byte[] {1}))
                .session(session).with(csrf()).param("title", "x")).andExpect(status().isForbidden());

        assertThat(putGrants(body(version()))).isEqualTo(200);

        mockMvc.perform(get("/admin/api/banners").session(session)).andExpect(status().isForbidden());
        assertThat(mockMvc.perform(get("/admin").session(session)).andReturn().getResponse().getContentAsString())
                .doesNotContain("href=\"/admin/banner/manage\"");
        mockMvc.perform(get("/admin").session(session)).andExpect(status().isOk());   // 세션은 만료되지 않는다(권한 변경은 세션 만료 대상 아님)
    }

    @Test
    @DisplayName("MANAGER(BANNER 전 권한 보유)도 권한관리 API는 호출할 수 없다(403) — 자기 승격 차단, 행·버전 불변")
    void manager_cannotCallPermissionApi() throws Exception {
        jdbc.update("INSERT INTO member_permission (member_id, feature, action) VALUES (?, 'BANNER', 'READ')", manager.getId());
        cache.invalidate();
        MockHttpSession session = loginAsManager();
        long before = version();

        mockMvc.perform(put(url()).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body(before, "READ", "DELETE")))
                .andExpect(status().isForbidden());

        assertThat(bannerActions()).containsExactly("READ");
        assertThat(version()).isEqualTo(before);
    }
}
