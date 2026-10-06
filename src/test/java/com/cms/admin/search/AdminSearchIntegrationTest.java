package com.cms.admin.search;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.notice.domain.Notice;
import com.cms.admin.notice.repository.NoticeRepository;
import com.cms.admin.permission.PermissionCache;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.TestMembers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 통합 검색의 권한 필터를 실제 SecurityConfig·판정기·캐시·MariaDB·메뉴 시드로 확인한다(PLAN-admin-unified-search.md §7 ⑥).
 * 시험마다 실제 MANAGER·ADMIN 회원 행과 공지 행을 만들고 지운다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class AdminSearchIntegrationTest extends MariaDbContainerSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PermissionCache cache;
    @Autowired MemberRepository memberRepository;
    @Autowired NoticeRepository noticeRepository;

    private String marker;
    private Member manager;
    private Member admin;
    private Notice notice;

    @BeforeEach
    void setUp() {
        marker = "srch" + System.nanoTime();
        manager = TestMembers.save(memberRepository, marker + "mgr", Role.ROLE_MANAGER);
        admin = TestMembers.save(memberRepository, marker + "adm", Role.ROLE_ADMIN);
        LocalDateTime now = LocalDateTime.now();
        notice = noticeRepository.save(Notice.builder()
                .title(marker + " 공지 제목")
                .content("본문")
                .useYn(true)
                .deleted(false)
                .authorId("admin01")
                .createDate(now)
                .updateDate(now)
                .build());
    }

    @AfterEach
    void cleanUp() {
        noticeRepository.deleteById(notice.getId());
        TestMembers.delete(jdbc, List.of(manager.getId(), admin.getId()));
        cache.invalidate();
    }

    private void grantNoticeRead(boolean granted) {
        jdbc.update("DELETE FROM member_permission WHERE member_id = ?", manager.getId());
        if (granted) {
            jdbc.update("INSERT INTO member_permission (member_id, feature, action) VALUES (?, 'NOTICE', 'READ')", manager.getId());
        }
        cache.invalidate();
    }

    @Test
    @DisplayName("ADMIN은 공지·관리자 섹션을 받고 응답에 이메일이 없다")
    void admin_getsNoticeAndMemberSections() throws Exception {
        mockMvc.perform(get("/admin/api/search-results").param("keyword", marker).with(TestMembers.asMember(admin)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.notices.total").value(1))
                .andExpect(jsonPath("$.notices.items[0].id").value(notice.getId()))
                .andExpect(jsonPath("$.members.total").value(2))
                .andExpect(content().string(not(containsString("@permission-test.example"))));
    }

    @Test
    @DisplayName("공지 읽기 권한이 있는 MANAGER는 공지를 보지만 관리자 섹션은 키 자체가 없고 회원 정보도 새지 않는다")
    void manager_withNoticeRead_seesNotice_neverMembers() throws Exception {
        grantNoticeRead(true);

        mockMvc.perform(get("/admin/api/search-results").param("keyword", marker).with(TestMembers.asMember(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notices.total").value(1))
                .andExpect(jsonPath("$.members").doesNotExist())
                .andExpect(content().string(not(containsString(manager.getUserId()))));
    }

    @Test
    @DisplayName("공지 읽기 권한이 없는 MANAGER는 공지 섹션이 키 자체가 없고, 제목이 응답 어디에도 나오지 않는다")
    void manager_withoutNoticeRead_noNoticeSection() throws Exception {
        grantNoticeRead(false);

        mockMvc.perform(get("/admin/api/search-results").param("keyword", marker).with(TestMembers.asMember(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notices").doesNotExist())
                .andExpect(jsonPath("$.members").doesNotExist())
                .andExpect(content().string(not(containsString("공지 제목"))));
    }

    @Test
    @DisplayName("권한을 부여·회수하면 같은 사용자의 다음 검색부터 공지 섹션이 나타나고 사라진다")
    void grantAndRevoke_takeEffectOnNextSearch() throws Exception {
        grantNoticeRead(false);
        mockMvc.perform(get("/admin/api/search-results").param("keyword", marker).with(TestMembers.asMember(manager)))
                .andExpect(jsonPath("$.notices").doesNotExist());

        grantNoticeRead(true);
        mockMvc.perform(get("/admin/api/search-results").param("keyword", marker).with(TestMembers.asMember(manager)))
                .andExpect(jsonPath("$.notices.total").value(1));

        grantNoticeRead(false);
        mockMvc.perform(get("/admin/api/search-results").param("keyword", marker).with(TestMembers.asMember(manager)))
                .andExpect(jsonPath("$.notices").doesNotExist());
    }

    @Test
    @DisplayName("메뉴 결과는 사이드바와 같은 가시성을 따른다 — 공지 읽기 권한이 없는 MANAGER에게 공지사항 메뉴는 나오지 않고, 권한을 받으면 나온다")
    void menus_followSidebarVisibility() throws Exception {
        grantNoticeRead(false);
        mockMvc.perform(get("/admin/api/search-results").param("keyword", "공지사항").with(TestMembers.asMember(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.menus.items[?(@.url=='/admin/notice/manage')]", hasSize(0)));

        grantNoticeRead(true);
        mockMvc.perform(get("/admin/api/search-results").param("keyword", "공지사항").with(TestMembers.asMember(manager)))
                .andExpect(jsonPath("$.menus.items[?(@.url=='/admin/notice/manage')]", hasSize(1)));
    }

    @Test
    @DisplayName("2자 미만 검색어는 조회 없이 섹션이 없는 빈 결과(200)다")
    void shortKeyword_emptyResult() throws Exception {
        mockMvc.perform(get("/admin/api/search-results").param("keyword", "a").with(TestMembers.asMember(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.menus").doesNotExist())
                .andExpect(jsonPath("$.notices").doesNotExist())
                .andExpect(jsonPath("$.members").doesNotExist());
    }

    @Test
    @DisplayName("비로그인 요청은 JSON 401이다")
    void unauthenticated_json401() throws Exception {
        mockMvc.perform(get("/admin/api/search-results").param("keyword", marker))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }
}
