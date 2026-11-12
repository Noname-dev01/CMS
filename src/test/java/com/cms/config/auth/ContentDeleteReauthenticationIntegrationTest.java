package com.cms.config.auth;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.permission.PermissionCache;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.TestMembers;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.RememberMeAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 복구할 수 없는 콘텐츠 삭제의 재인증(PLAN-content-delete-reauthentication.md) — 실제 SecurityConfig·폼 로그인·쿠키 자동 로그인·MariaDB.
 * 쿠키 자동 로그인 세션은 11종 삭제를 실행할 수 없고 403 {@code REAUTHENTICATION_REQUIRED}를 받는다. 재인증 API로 세션을 승격하면 된다.
 * 휴지통으로 복원할 수 있는 게시글 삭제는 대상이 아니다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class ContentDeleteReauthenticationIntegrationTest extends MariaDbContainerSupport {

    private static final String PASSWORD = "Content-Delete-Reauth-2026!";
    private static final String REAUTH = "REAUTHENTICATION_REQUIRED";
    private static final long MISSING = 987_654_321L;

    @Autowired MockMvc mvc;
    @Autowired MemberRepository memberRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired PermissionCache cache;

    private final List<Long> members = new ArrayList<>();
    private final List<Long> boards = new ArrayList<>();
    private final List<Long> inquiries = new ArrayList<>();
    private final List<Long> faqCategories = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long board : boards) {
            jdbc.update("DELETE FROM post_attachment WHERE post_id IN (SELECT id FROM post WHERE board_id = ?)", board);
            jdbc.update("DELETE FROM post WHERE board_id = ?", board);
            jdbc.update("DELETE FROM member_board_permission WHERE board_id = ?", board);
            jdbc.update("DELETE FROM board WHERE id = ?", board);
        }
        for (Long id : inquiries) {
            jdbc.update("DELETE FROM inquiry WHERE id = ?", id);
        }
        for (Long id : faqCategories) {
            jdbc.update("DELETE FROM faq_category WHERE id = ?", id);
        }
        for (Long id : members) {
            jdbc.update("DELETE FROM persistent_logins WHERE username = (SELECT user_id FROM member WHERE id = ?)", id);
        }
        TestMembers.delete(jdbc, members);
        cache.invalidate();
    }

    // ---------------------------------------------------------------- 도우미

    private Member member(Role role) {
        Member saved = TestMembers.save(memberRepository, "cdr", role);
        jdbc.update("UPDATE member SET pwd = ? WHERE id = ?", passwordEncoder.encode(PASSWORD), saved.getId());
        members.add(saved.getId());
        return memberRepository.findById(saved.getId()).orElseThrow();
    }

    private record Login(MockHttpSession session, Cookie cookie) {
    }

    /** 폼 로그인(로그인 상태 유지 체크) — 완전 인증 세션과 remember-me 쿠키. */
    private Login formLogin(Member m) throws Exception {
        MvcResult result = mvc.perform(post("/admin/login")
                        .param("username", m.getUserId()).param("password", PASSWORD).param("remember-me", "on").with(csrf()))
                .andReturn();
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/admin");
        Cookie cookie = null;
        for (Cookie c : result.getResponse().getCookies()) {
            if ("remember-me".equals(c.getName())) {
                cookie = c;
            }
        }
        return new Login((MockHttpSession) result.getRequest().getSession(false), cookie);
    }

    /** 브라우저 재시작 모사: 세션 없이 쿠키만으로 GET → 자동 로그인(remember-me) 세션. */
    private MockHttpSession rememberMeSession(Member m) throws Exception {
        Login form = formLogin(m);
        MvcResult result = mvc.perform(get("/admin/api/members/me").cookie(form.cookie())).andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        SecurityContext context = (SecurityContext) session.getAttribute("SPRING_SECURITY_CONTEXT");
        assertThat(context.getAuthentication()).isInstanceOf(RememberMeAuthenticationToken.class);
        return session;
    }

    /** 재인증 API로 쿠키 세션을 완전 인증으로 승격한다(화면의 비밀번호 모달이 하는 일). 세션 ID가 바뀌어도 같은 세션 객체다. */
    private void reauthenticate(MockHttpSession session) throws Exception {
        mvc.perform(post("/admin/api/members/me/reauthentication").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isNoContent());
    }

    /** 대상 11종 — 없는 ID를 향한 요청. 재인증 거부는 서비스 전이라 ID 존재와 무관하게 403이다. */
    private static Map<String, MockHttpServletRequestBuilder> targetDeletes(long boardId, long postId) {
        Map<String, MockHttpServletRequestBuilder> r = new LinkedHashMap<>();
        r.put("배너", delete("/admin/api/banners/" + MISSING));
        r.put("팝업", delete("/admin/api/popups/" + MISSING));
        r.put("게시판", delete("/admin/api/boards/" + MISSING).param("version", "1"));
        r.put("첨부", delete("/admin/api/boards/" + boardId + "/posts/" + postId + "/attachments/" + MISSING));
        r.put("FAQ", delete("/admin/api/faqs/" + MISSING));
        r.put("FAQ 분류", delete("/admin/api/faq-categories/" + MISSING));
        r.put("문의", delete("/admin/api/inquiries/" + MISSING));
        r.put("미디어", delete("/admin/api/media/" + MISSING));
        r.put("메뉴", delete("/admin/api/menus/" + MISSING));
        r.put("정적 페이지", delete("/admin/api/site-pages/" + MISSING).param("version", "1"));
        r.put("사이트", delete("/admin/api/sites/" + MISSING));
        return r;
    }

    private long insertBoard() {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO board (name, public_yn, attachment_yn, deleted, create_date, update_date) VALUES ('재인증 시험', 1, 1, 0, NOW(6), NOW(6))",
                    Statement.RETURN_GENERATED_KEYS);
            return ps;
        }, keys);
        long id = keys.getKey().longValue();
        boards.add(id);
        return id;
    }

    private long insertPost(long boardId) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO post (board_id, title, content, use_yn, deleted, author_id, create_date, update_date)"
                            + " VALUES (?, '재인증 시험 글', '<p>본문</p>', 1, 0, 'tester', NOW(6), NOW(6))", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, boardId);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private long insertAttachment(long postId) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO post_attachment (post_id, original_filename, content_type, file_size, storage_key, create_date)"
                            + " VALUES (?, 'a.pdf', 'application/pdf', 3, ?, NOW(6))", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, postId);
            ps.setString(2, "missing-file-key-" + System.nanoTime());
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private long insertInquiry() {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO inquiry (name, email, title, body, status, consent_at, create_date, update_date)"
                            + " VALUES ('방문자', 'visitor@reauth.example', '문의', '본문', 'RECEIVED', NOW(6), NOW(6), NOW(6))",
                    Statement.RETURN_GENERATED_KEYS);
            return ps;
        }, keys);
        long id = keys.getKey().longValue();
        inquiries.add(id);
        return id;
    }

    private long insertFaqCategory() {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO faq_category (name, ord, use_yn) VALUES ('재인증 분류', 999, 1)", Statement.RETURN_GENERATED_KEYS);
            return ps;
        }, keys);
        long id = keys.getKey().longValue();
        faqCategories.add(id);
        return id;
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    // ---------------------------------------------------------------- F1 — 쿠키 세션은 11종 모두 거부

    @Test
    @DisplayName("쿠키로 복원한 ADMIN 세션의 복구 불가 삭제 11종은 전부 403 REAUTHENTICATION_REQUIRED다(없는 ID여도 404가 아니다)")
    void rememberMeAdmin_allTargetDeletes_requireReauthentication() throws Exception {
        Member admin = member(Role.ROLE_ADMIN);
        long board = insertBoard();
        long post = insertPost(board);
        MockHttpSession restored = rememberMeSession(admin);

        for (Map.Entry<String, MockHttpServletRequestBuilder> e : targetDeletes(board, post).entrySet()) {
            MvcResult result = mvc.perform(e.getValue().session(restored).with(csrf())).andReturn();
            assertThat(result.getResponse().getStatus()).as(e.getKey()).isEqualTo(403);
            assertThat(result.getResponse().getContentAsString()).as(e.getKey()).contains(REAUTH);
        }
    }

    @Test
    @DisplayName("거부된 삭제는 실제 행을 지우지 않는다 — 재인증 API로 승격하면 같은 삭제가 된다(문의·FAQ 분류·첨부)")
    void rememberMeAdmin_rowsSurviveUntilReauthenticated() throws Exception {
        Member admin = member(Role.ROLE_ADMIN);
        long inquiry = insertInquiry();
        long category = insertFaqCategory();
        long board = insertBoard();
        long post = insertPost(board);
        long attachment = insertAttachment(post);
        MockHttpSession restored = rememberMeSession(admin);
        List<MockHttpServletRequestBuilder> deletes = List.of(
                delete("/admin/api/inquiries/" + inquiry),
                delete("/admin/api/faq-categories/" + category),
                delete("/admin/api/boards/" + board + "/posts/" + post + "/attachments/" + attachment));

        for (MockHttpServletRequestBuilder d : deletes) {
            mvc.perform(d.session(restored).with(csrf()))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(REAUTH));
        }
        assertThat(count("SELECT COUNT(*) FROM inquiry WHERE id = ?", inquiry)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM faq_category WHERE id = ?", category)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM post_attachment WHERE id = ?", attachment)).isEqualTo(1);

        reauthenticate(restored);
        for (MockHttpServletRequestBuilder d : List.of(
                delete("/admin/api/inquiries/" + inquiry),
                delete("/admin/api/faq-categories/" + category),
                delete("/admin/api/boards/" + board + "/posts/" + post + "/attachments/" + attachment))) {
            mvc.perform(d.session(restored).with(csrf())).andExpect(status().isNoContent());
        }
        assertThat(count("SELECT COUNT(*) FROM inquiry WHERE id = ?", inquiry)).isZero();
        assertThat(count("SELECT COUNT(*) FROM faq_category WHERE id = ?", category)).isZero();
        assertThat(count("SELECT COUNT(*) FROM post_attachment WHERE id = ?", attachment)).isZero();
    }

    // ---------------------------------------------------------------- F3 — 평소 사용은 그대로

    @Test
    @DisplayName("폼 로그인(완전 인증) 세션은 표지에 막히지 않는다 — 없는 ID면 각 서비스의 404")
    void formLoginAdmin_targetDeletes_reachService() throws Exception {
        Member admin = member(Role.ROLE_ADMIN);
        long board = insertBoard();
        long post = insertPost(board);
        MockHttpSession session = formLogin(admin).session();

        for (Map.Entry<String, MockHttpServletRequestBuilder> e : targetDeletes(board, post).entrySet()) {
            MvcResult result = mvc.perform(e.getValue().session(session).with(csrf())).andReturn();
            assertThat(result.getResponse().getStatus()).as(e.getKey()).isEqualTo(404);
        }
    }

    @Test
    @DisplayName("제외 대상: 쿠키 세션의 게시글 삭제는 재인증 없이 휴지통으로 간다")
    void rememberMeAdmin_postDelete_isNotGated() throws Exception {
        Member admin = member(Role.ROLE_ADMIN);
        long board = insertBoard();
        long post = insertPost(board);
        MockHttpSession restored = rememberMeSession(admin);

        mvc.perform(delete("/admin/api/boards/" + board + "/posts/" + post).session(restored).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT deleted FROM post WHERE id = ?", Boolean.class, post)).isTrue();
    }

    // ---------------------------------------------------------------- 쟁점 4 — 인가와의 순서

    @Test
    @DisplayName("위임 기능: 배너 READ+DELETE MANAGER 쿠키 세션은 재인증 필요, READ만이면 재인증 뒤 ACCESS_DENIED, 권한 없으면 URL에서 바로 ACCESS_DENIED")
    void rememberMeManager_delegatedFeatureOrder() throws Exception {
        Member full = member(Role.ROLE_MANAGER);
        Member readOnly = member(Role.ROLE_MANAGER);
        Member none = member(Role.ROLE_MANAGER);
        jdbc.update("INSERT INTO member_permission (member_id, feature, action) VALUES (?, 'BANNER', 'READ'), (?, 'BANNER', 'DELETE')",
                full.getId(), full.getId());
        jdbc.update("INSERT INTO member_permission (member_id, feature, action) VALUES (?, 'BANNER', 'READ')", readOnly.getId());
        cache.invalidate();

        MockHttpSession fullSession = rememberMeSession(full);
        mvc.perform(delete("/admin/api/banners/" + MISSING).session(fullSession).with(csrf()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(REAUTH));
        reauthenticate(fullSession);
        mvc.perform(delete("/admin/api/banners/" + MISSING).session(fullSession).with(csrf())).andExpect(status().isNotFound());

        MockHttpSession readSession = rememberMeSession(readOnly);
        mvc.perform(delete("/admin/api/banners/" + MISSING).session(readSession).with(csrf()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(REAUTH));
        reauthenticate(readSession);
        mvc.perform(delete("/admin/api/banners/" + MISSING).session(readSession).with(csrf()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mvc.perform(delete("/admin/api/banners/" + MISSING).session(rememberMeSession(none)).with(csrf()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("ADMIN 전용 삭제(사이트·메뉴·게시판 정의): MANAGER 쿠키 세션은 URL 규칙에서 바로 ACCESS_DENIED")
    void rememberMeManager_adminOnlyDeletes_accessDeniedFirst() throws Exception {
        MockHttpSession restored = rememberMeSession(member(Role.ROLE_MANAGER));

        for (MockHttpServletRequestBuilder d : List.of(
                delete("/admin/api/sites/" + MISSING),
                delete("/admin/api/menus/" + MISSING),
                delete("/admin/api/boards/" + MISSING).param("version", "1"))) {
            mvc.perform(d.session(restored).with(csrf()))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }
    }

    @Test
    @DisplayName("첨부(게시판 UPDATE 판정): READ+UPDATE면 재인증 뒤 삭제, READ+DELETE(UPDATE 없음)면 재인증 뒤 ACCESS_DENIED")
    void rememberMeManager_attachmentUsesBoardUpdate() throws Exception {
        long board = insertBoard();
        long post = insertPost(board);
        long attachment = insertAttachment(post);
        Member updater = member(Role.ROLE_MANAGER);
        Member deleter = member(Role.ROLE_MANAGER);
        jdbc.update("INSERT INTO member_board_permission (member_id, board_id, action) VALUES (?, ?, 'READ'), (?, ?, 'UPDATE')",
                updater.getId(), board, updater.getId(), board);
        jdbc.update("INSERT INTO member_board_permission (member_id, board_id, action) VALUES (?, ?, 'READ'), (?, ?, 'DELETE')",
                deleter.getId(), board, deleter.getId(), board);
        cache.invalidate();
        String url = "/admin/api/boards/" + board + "/posts/" + post + "/attachments/" + attachment;

        MockHttpSession deleterSession = rememberMeSession(deleter);
        mvc.perform(delete(url).session(deleterSession).with(csrf()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(REAUTH));
        reauthenticate(deleterSession);
        mvc.perform(delete(url).session(deleterSession).with(csrf()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        assertThat(count("SELECT COUNT(*) FROM post_attachment WHERE id = ?", attachment)).isEqualTo(1);

        MockHttpSession updaterSession = rememberMeSession(updater);
        mvc.perform(delete(url).session(updaterSession).with(csrf()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(REAUTH));
        reauthenticate(updaterSession);
        mvc.perform(delete(url).session(updaterSession).with(csrf())).andExpect(status().isNoContent());
        assertThat(count("SELECT COUNT(*) FROM post_attachment WHERE id = ?", attachment)).isZero();
    }
}
