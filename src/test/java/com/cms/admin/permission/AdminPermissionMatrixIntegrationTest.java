package com.cms.admin.permission;

import com.cms.admin.board.domain.Board;
import com.cms.admin.board.domain.Post;
import com.cms.admin.board.repository.BoardRepository;
import com.cms.admin.board.repository.PostAttachmentRepository;
import com.cms.admin.board.repository.PostRepository;
import com.cms.admin.board.service.PostAttachmentService;
import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.common.storage.FileStorage;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 접근 매트릭스 통합 시험(PLAN-menu-permission-management.md 테스트 계획 2): 실제 SecurityConfig·판정기·캐시·MariaDB로
 * 게시글 핸들러 9개 × MANAGER 권한 조합을 시험한다. 공지가 공지 게시판으로 흡수되며(PLAN-notice-to-board.md) 옛 공지 핸들러 매트릭스를 공지 게시판의
 * 게시글 핸들러로 옮겼다 — 판정 키가 기능이 아니라 (회원, 게시판, 동작)이다. 동작 오표시(예: 삭제를 UPDATE로 표시)를 잡으려고 "전부"·"READ만"뿐 아니라
 * <b>동작별 단일 권한 조합</b>(READ+CREATE, READ+UPDATE, READ+DELETE)을 각각 돌리고, 성공 판정은 "403이 아님"이 아니라
 * <b>기대 성공 상태 코드와 실제 저장 결과</b>로 단언한다. 거부 케이스는 유효한 CSRF·본문을 실어 CSRF·검증 거부와 구분하고 대상이
 * 바뀌지 않았음도 단언한다.
 *
 * <p>MockMvc 호출은 실제 트랜잭션을 커밋하므로 {@code @Transactional}을 붙이지 않고 만든 행을 {@link #cleanUp()}에서 지운다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class AdminPermissionMatrixIntegrationTest extends MariaDbContainerSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PermissionCache cache;
    @Autowired MemberRepository memberRepository;
    @Autowired BoardRepository boardRepository;
    @Autowired PostRepository postRepository;
    @Autowired PostAttachmentRepository attachmentRepository;
    @Autowired PostAttachmentService attachmentService;
    @Autowired FileStorage fileStorage;

    private final List<Long> postIds = new ArrayList<>();
    private final List<String> titlePrefixes = new ArrayList<>();
    private final List<Long> extraMemberIds = new ArrayList<>();

    /** 권한 판정 키가 회원 ID라 실제 MANAGER 회원 행이 필요하다(member_board_permission FK). 시험마다 만들고 지운다. */
    private Member manager;
    private long boardId;
    private String base;

    @BeforeEach
    void createManager() {
        manager = TestMembers.save(memberRepository, "matrix-manager", Role.ROLE_MANAGER);
        boardId = boardRepository.findIdByBoardKey(Board.NOTICE_KEY).orElseThrow();
        base = "/admin/api/boards/" + boardId + "/posts";
        restoreSeed();
    }

    @AfterEach
    void cleanUp() {
        TestMembers.delete(jdbc, extraMemberIds);
        TestMembers.delete(jdbc, List.of(manager.getId()));
        cache.invalidate();
        for (String prefix : titlePrefixes) {
            postRepository.findAll().stream()
                    .filter(p -> p.getTitle().startsWith(prefix))
                    .forEach(p -> postIds.add(p.getId()));
        }
        for (Long id : postIds) {
            try {
                attachmentRepository.findByPostIdOrderByIdAsc(id).forEach(a -> {
                    try {
                        fileStorage.delete(a.getStorageKey());
                    } catch (Exception ignored) {
                        // 스토리지 정리 실패는 시험 결과와 무관
                    }
                    attachmentRepository.deleteById(a.getId());
                });
                postRepository.deleteById(id);
            } catch (Exception ignored) {
                // 이미 삭제된 행
            }
        }
    }

    // ── 인증 주체 ───────────────────────────────────────────────

    /**
     * 실제 로그인과 같은 주체({@link CustomUserDetails})를 쓴다 — 게시글 생성은 현재 로그인 사용자의 아이디를 작성자로 기록하므로
     * 기본 {@code user()} 주체(Spring User)로는 서비스가 로그인 정보를 읽지 못해 거부한다.
     */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor principal(String userId, Role role) {
        Member member = Member.builder().id(1L).userId(userId).userName(userId).email(userId + "@example.com")
                .userType(role).status(MemberStatus.ACTIVE).build();
        CustomUserDetails details = new CustomUserDetails(member);
        return authentication(new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
    }

    /** 시험 회원({@link #manager})으로 로그인한 것과 같은 요청 주체. */
    private org.springframework.test.web.servlet.request.RequestPostProcessor asManager() {
        return TestMembers.asMember(manager);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor asAdmin() {
        return principal("admin01", Role.ROLE_ADMIN);
    }

    // ── 권한 상태 조작 ───────────────────────────────────────────

    /** 시험 MANAGER 회원의 공지 게시판 허용 행을 지정한 동작 집합으로 바꾸고 캐시를 무효화한다(권한관리 저장과 같은 효과). */
    private void grant(Set<PermissionAction> actions) {
        jdbc.update("DELETE FROM member_board_permission WHERE member_id = ? AND board_id = ?", manager.getId(), boardId);
        for (PermissionAction action : actions) {
            jdbc.update("INSERT INTO member_board_permission (member_id, board_id, action) VALUES (?, ?, ?)",
                    manager.getId(), boardId, action.name());
        }
        cache.invalidate();
    }

    private void restoreSeed() {
        grant(EnumSet.allOf(PermissionAction.class));
    }

    // ── 픽스처 ─────────────────────────────────────────────────

    private record Fixture(long postId, Long attachmentId) { }

    private Fixture fixture(boolean withAttachment) {
        LocalDateTime now = LocalDateTime.now();
        Post post = postRepository.save(Post.builder()
                .boardId(boardId)
                .title("matrix-fixture-" + System.nanoTime())
                .content("접근 매트릭스 시험 본문")
                .useYn(true)
                .deleted(false)
                .authorId("admin01")
                .createDate(now)
                .updateDate(now)
                .build());
        postIds.add(post.getId());
        Long attachmentId = null;
        if (withAttachment) {
            attachmentId = attachmentService.upload(boardId, post.getId(),
                    new MockMultipartFile("file", "matrix.pdf", "application/pdf", "matrix-content".getBytes())).getId();
        }
        return new Fixture(post.getId(), attachmentId);
    }

    // ── 핸들러 케이스 ──────────────────────────────────────────

    /** 한 핸들러: 요구 동작, 기대 성공 상태, 요청 생성, 저장 결과 단언. */
    private record HandlerCase(String name, PermissionAction action, boolean needsAttachment, int successStatus,
                               java.util.function.Function<Fixture, AbstractMockHttpServletRequestBuilder<?>> request,
                               Consumer<Effect> effect) { }

    /** 효과 단언에 필요한 값: 실행 전후 비교용. */
    private record Effect(Fixture fixture, boolean applied, long createdBefore) { }

    private List<HandlerCase> cases() {
        return List.of(
                new HandlerCase("게시글 목록 조회", PermissionAction.READ, false, 200,
                        f -> get(base), e -> { }),
                new HandlerCase("게시글 상세 조회", PermissionAction.READ, false, 200,
                        f -> get(base + "/{id}", f.postId()), e -> { }),
                new HandlerCase("첨부 목록 조회", PermissionAction.READ, true, 200,
                        f -> get(base + "/{id}/attachments", f.postId()), e -> { }),
                new HandlerCase("첨부 다운로드", PermissionAction.READ, true, 200,
                        f -> get(base + "/{id}/attachments/{aid}/content", f.postId(), f.attachmentId()), e -> { }),
                new HandlerCase("게시글 생성", PermissionAction.CREATE, false, 201,
                        f -> post(base).contentType(MediaType.APPLICATION_JSON)
                                .content("{\"title\":\"matrix-created-" + System.nanoTime() + "\",\"content\":\"<p>본문</p>\",\"contentFormat\":\"HTML\",\"useYn\":true}"),
                        e -> assertThat(postCountByPrefix("matrix-created-") - e.createdBefore()).as("게시글 생성 저장 여부").isEqualTo(e.applied() ? 1L : 0L)),
                new HandlerCase("게시글 수정", PermissionAction.UPDATE, false, 200,
                        f -> patch(base + "/{id}", f.postId()).contentType(MediaType.APPLICATION_JSON)
                                .content("{\"title\":\"matrix-changed\"}"),
                        e -> assertThat(postRepository.findById(e.fixture().postId()).orElseThrow().getTitle().equals("matrix-changed"))
                                .as("게시글 수정 저장 여부").isEqualTo(e.applied())),
                new HandlerCase("첨부 업로드(UPDATE로 분류 — U4)", PermissionAction.UPDATE, false, 201,
                        f -> multipart(base + "/{id}/attachments", f.postId())
                                .file(new MockMultipartFile("file", "upload.pdf", "application/pdf", "upload-content".getBytes())),
                        e -> assertThat(!attachmentRepository.findByPostIdOrderByIdAsc(e.fixture().postId()).isEmpty())
                                .as("첨부 업로드 저장 여부").isEqualTo(e.applied())),
                new HandlerCase("첨부 삭제(UPDATE로 분류 — U4)", PermissionAction.UPDATE, true, 204,
                        f -> delete(base + "/{id}/attachments/{aid}", f.postId(), f.attachmentId()),
                        e -> assertThat(attachmentRepository.findById(e.fixture().attachmentId()).isEmpty())
                                .as("첨부 삭제 반영 여부").isEqualTo(e.applied())),
                new HandlerCase("게시글 삭제", PermissionAction.DELETE, false, 204,
                        f -> delete(base + "/{id}", f.postId()),
                        e -> assertThat(postRepository.findById(e.fixture().postId()).orElseThrow().getDeleted())
                                .as("게시글 삭제 반영 여부").isEqualTo(e.applied())));
    }

    private long postCountByPrefix(String prefix) {
        titlePrefixes.add(prefix);
        return postRepository.findAll().stream().filter(p -> p.getTitle().startsWith(prefix)).count();
    }

    private static boolean expectedAllowed(Set<PermissionAction> granted, PermissionAction required) {
        // URL 게이트가 기능 단위 READ를 먼저 요구하고, 쓰기 동작은 같은 게시판 READ 의존 규칙이 있다
        return granted.contains(PermissionAction.READ) && granted.contains(required);
    }

    private MvcResult perform(HandlerCase handlerCase, Fixture fixture, org.springframework.test.web.servlet.request.RequestPostProcessor auth)
            throws Exception {
        return mockMvc.perform(handlerCase.request().apply(fixture).with(auth).with(csrf())).andReturn();
    }

    // ── 시험 ───────────────────────────────────────────────────

    @Test
    @DisplayName("MANAGER × 권한 조합 × 게시글 핸들러 9개: 허용은 기대 성공 상태+저장 결과, 거부는 403+변경 없음")
    void managerMatrix() throws Exception {
        List<Set<PermissionAction>> combos = List.of(
                EnumSet.allOf(PermissionAction.class),
                EnumSet.noneOf(PermissionAction.class),
                EnumSet.of(PermissionAction.READ),
                EnumSet.of(PermissionAction.READ, PermissionAction.CREATE),
                EnumSet.of(PermissionAction.READ, PermissionAction.UPDATE),
                EnumSet.of(PermissionAction.READ, PermissionAction.DELETE),
                // READ 없이 쓰기만 — 게이트·의존 규칙 때문에 전부 거부여야 한다
                EnumSet.of(PermissionAction.CREATE, PermissionAction.UPDATE, PermissionAction.DELETE));

        List<String> failures = new ArrayList<>();
        for (Set<PermissionAction> granted : combos) {
            grant(granted);
            for (HandlerCase handlerCase : cases()) {
                Fixture fixture = fixture(handlerCase.needsAttachment());
                boolean allowed = expectedAllowed(granted, handlerCase.action());
                long createdBefore = postCountByPrefix("matrix-created-");
                String label = granted + " × " + handlerCase.name();
                try {
                    MvcResult result = perform(handlerCase, fixture, asManager());
                    int status = result.getResponse().getStatus();
                    if (allowed) {
                        assertThat(status).as(label + " 허용 상태").isEqualTo(handlerCase.successStatus());
                    } else {
                        assertThat(status).as(label + " 거부 상태").isEqualTo(403);
                        assertThat(result.getResponse().getContentAsString()).as(label + " 거부 본문(API JSON)").contains("ACCESS_DENIED");
                    }
                    handlerCase.effect().accept(new Effect(fixture, allowed, createdBefore));
                } catch (AssertionError e) {
                    failures.add(label + " → " + e.getMessage());
                }
            }
        }
        assertThat(failures).as("매트릭스 위반").isEmpty();
    }

    @Test
    @DisplayName("ADMIN은 허용 행이 하나도 없어도 모든 게시글 핸들러가 성공한다(DB 비의존)")
    void adminIsIndependentOfGrants() throws Exception {
        grant(EnumSet.noneOf(PermissionAction.class));
        for (HandlerCase handlerCase : cases()) {
            Fixture fixture = fixture(handlerCase.needsAttachment());
            long createdBefore = postCountByPrefix("matrix-created-");
            MvcResult result = perform(handlerCase, fixture, asAdmin());
            assertThat(result.getResponse().getStatus()).as(handlerCase.name()).isEqualTo(handlerCase.successStatus());
            handlerCase.effect().accept(new Effect(fixture, true, createdBefore));
        }
    }

    @Test
    @DisplayName("비로그인은 모든 게시글 API에서 401")
    void anonymousGets401() throws Exception {
        for (HandlerCase handlerCase : cases()) {
            Fixture fixture = fixture(handlerCase.needsAttachment());
            MvcResult result = mockMvc.perform(handlerCase.request().apply(fixture).with(csrf())).andReturn();
            assertThat(result.getResponse().getStatus()).as(handlerCase.name()).isEqualTo(401);
        }
    }

    @Test
    @DisplayName("권한을 회수하면 같은 MANAGER 사용자의 다음 요청부터 바로 거부된다(재로그인 없이, 즉시 반영)")
    void revocationTakesEffectOnNextRequest() throws Exception {
        grant(EnumSet.allOf(PermissionAction.class));
        assertThat(mockMvc.perform(get(base).with(asManager()))
                .andReturn().getResponse().getStatus()).isEqualTo(200);

        grant(EnumSet.noneOf(PermissionAction.class));

        assertThat(mockMvc.perform(get(base).with(asManager()))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("게시글 페이지 게이트: 조회 권한이 있으면 접근, 회수하면 HTML 403 — 옛 공지 화면 주소는 공지 게시판 게시글 화면으로 302")
    void postPageGateFollowsReadGrant() throws Exception {
        grant(EnumSet.of(PermissionAction.READ));
        int allowed = mockMvc.perform(get("/admin/board/posts").with(asManager()))
                .andReturn().getResponse().getStatus();
        assertThat(allowed).as("조회 권한 있음 — 게이트 통과").isNotIn(401, 403);
        MvcResult redirect = mockMvc.perform(get("/admin/notice/manage").param("id", "5").with(asManager())).andReturn();
        assertThat(redirect.getResponse().getStatus()).isEqualTo(302);
        assertThat(redirect.getResponse().getHeader("Location")).isEqualTo("/admin/board/posts?boardId=" + boardId + "&id=5");
        assertThat(mockMvc.perform(get("/admin/notice/manage").param("id", "x-y").with(asManager()))
                .andReturn().getResponse().getHeader("Location")).as("형식이 틀린 id는 버린다")
                .isEqualTo("/admin/board/posts?boardId=" + boardId);

        grant(EnumSet.noneOf(PermissionAction.class));
        MvcResult denied = mockMvc.perform(get("/admin/board/posts").with(asManager())).andReturn();
        assertThat(denied.getResponse().getStatus()).isEqualTo(403);
        assertThat(String.valueOf(denied.getResponse().getContentType())).as("페이지 차단은 JSON이 아니라 HTML 403").doesNotContain("json");
        assertThat(mockMvc.perform(get("/admin/notice/manage").with(asManager())).andReturn().getResponse().getStatus())
                .as("권한 없는 MANAGER는 리다이렉트 경로도 게이트에서 403").isEqualTo(403);
    }

    @Test
    @DisplayName("옛 공지 API 경로(/admin/api/notices/**)는 게이트가 사라져 ADMIN 캐치올로 떨어진다 — MANAGER는 허용 행이 전부 있어도 403")
    void legacyNoticeApiIsAdminOnlyNow() throws Exception {
        restoreSeed();
        for (String path : List.of("/admin/api/notices", "/admin/api/notices/1", "/admin/api/notices/content-images",
                "/admin/notice/manage/extra")) {
            assertThat(mockMvc.perform(get(path).with(asManager())).andReturn().getResponse().getStatus()).as(path).isEqualTo(403);
        }
        assertThat(mockMvc.perform(get("/admin/api/notices").with(asAdmin())).andReturn().getResponse().getStatus())
                .as("ADMIN에게도 핸들러가 없다").isEqualTo(404);
    }

    @Test
    @DisplayName("카탈로그 밖 /admin 경로와 위임 불가 기능은 허용 행이 전부 있어도 MANAGER에게 거부")
    void unknownAndAdminOnlyPathsDeniedForManager() throws Exception {
        restoreSeed();
        for (String path : List.of("/admin/unknown", "/admin/api/unknown", "/admin/menu/manage", "/admin/log/manage",
                "/admin/member/manage", "/admin/api/menus/tree", "/admin/api/logs", "/admin/api/members",
                // 게시판 정의·권한관리 자체는 위임 불가 — MANAGER는 화면·API 모두 403
                "/admin/board/manage", "/admin/api/boards",
                "/admin/permission/manage", "/admin/api/members/" + manager.getId() + "/permissions")) {
            MvcResult result = mockMvc.perform(get(path).with(asManager())).andReturn();
            assertThat(result.getResponse().getStatus()).as(path).isEqualTo(403);
        }
        // 시드 상태 MANAGER가 유효한 CSRF·본문으로 PUT해도 403이고 권한은 그대로다(스스로 승격할 수 없다)
        MvcResult put = mockMvc.perform(put("/admin/api/members/" + manager.getId() + "/permissions").with(asManager()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"boardGrants\":[],\"version\":0,\"grants\":[]}")).andReturn();
        assertThat(put.getResponse().getStatus()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member_board_permission WHERE member_id = ?", Integer.class, manager.getId())).isEqualTo(4);
    }

    @Test
    @DisplayName("경계 입력(후행 슬래시·매트릭스 파라미터·점 경로·HEAD·OPTIONS): 권한 회수 상태 MANAGER는 성공하지 못하고 변경도 없다")
    void boundaryInputsAreNotAllowedWhenRevoked() throws Exception {
        grant(EnumSet.noneOf(PermissionAction.class));
        Fixture fixture = fixture(false);
        long before = postRepository.count();

        List<AbstractMockHttpServletRequestBuilder<?>> attempts = List.of(
                get(base + "/"),
                get("/admin/api/boards/" + boardId + "/posts;x=1"),
                get(base + "/./"),
                get(base + "/" + fixture.postId() + "/"),
                head(base),
                options(base),
                post(base + "/").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"matrix-boundary\",\"content\":\"<p>본문</p>\",\"contentFormat\":\"HTML\",\"useYn\":true}"),
                delete(base + "/" + fixture.postId() + "/"),
                get("/admin/board/posts/"),
                get("/admin/board/posts;jsessionid=abc"),
                get("/admin/notice/manage/"),
                get("/admin/notice/manage;jsessionid=abc"));

        for (AbstractMockHttpServletRequestBuilder<?> attempt : attempts) {
            try {
                MvcResult result = mockMvc.perform(attempt.with(asManager()).with(csrf())).andReturn();
                assertThat(result.getResponse().getStatus()).as(attempt.toString()).matches(status -> status < 200 || status > 299);
            } catch (Exception rejected) {
                // 방화벽이 요청 자체를 거부(RequestRejectedException 등)하면 업무 핸들러는 실행되지 않은 것이다
            }
        }

        assertThat(postRepository.count()).as("경계 입력이 게시글을 만들거나 지우지 않았다").isEqualTo(before);
        assertThat(postRepository.findById(fixture.postId()).orElseThrow().getDeleted()).isFalse();
    }

    @Test
    @DisplayName("관리자 영역 밖의 알려지지 않은 경로는 기본 거부(MANAGER라도 성공하지 못함)")
    void unknownOutsideAdminIsDenied() throws Exception {
        restoreSeed();
        MvcResult result = mockMvc.perform(get("/unknown-outside-admin").with(asManager())).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
    }

    // ── 사용자별 권한(PLAN-member-permission.md §7-1 ②·⑦) ──────

    @Test
    @DisplayName("교차 회원 격리: 회원 A에게만 준 공지 게시판 권한은 같은 역할의 회원 B에게 적용되지 않는다(API·페이지 모두)")
    void grantsDoNotLeakToAnotherManager() throws Exception {
        grant(EnumSet.allOf(PermissionAction.class)); // 회원 A = manager
        Member other = TestMembers.save(memberRepository, "matrix-other", Role.ROLE_MANAGER);
        extraMemberIds.add(other.getId());
        cache.invalidate();

        assertThat(mockMvc.perform(get(base).with(asManager())).andReturn().getResponse().getStatus())
                .as("A는 허용").isEqualTo(200);
        MvcResult apiDenied = mockMvc.perform(get(base).with(TestMembers.asMember(other))).andReturn();
        assertThat(apiDenied.getResponse().getStatus()).as("B는 같은 역할이어도 거부").isEqualTo(403);
        assertThat(apiDenied.getResponse().getContentAsString()).contains("ACCESS_DENIED");
        assertThat(mockMvc.perform(get("/admin/board/posts").with(TestMembers.asMember(other))).andReturn().getResponse().getStatus())
                .as("B의 게시글 페이지").isEqualTo(403);
        assertThat(mockMvc.perform(post(base).with(TestMembers.asMember(other)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"matrix-created-leak\",\"content\":\"<p>본문</p>\",\"contentFormat\":\"HTML\",\"useYn\":true}"))
                .andReturn().getResponse().getStatus()).as("B의 게시글 생성").isEqualTo(403);
        assertThat(postCountByPrefix("matrix-created-leak")).isZero();
    }

    @Test
    @DisplayName("새로 만든 MANAGER(권한 0개)는 대시보드·내 정보만 접근하고 게시글은 API·페이지 모두 거부된다 — 사이드바에도 게시글 관리가 없다")
    void newManagerWithoutGrantsSeesOnlyAlwaysAllowed() throws Exception {
        Member fresh = TestMembers.save(memberRepository, "matrix-fresh", Role.ROLE_MANAGER);
        extraMemberIds.add(fresh.getId());

        assertThat(mockMvc.perform(get("/admin").with(TestMembers.asMember(fresh))).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mockMvc.perform(get("/admin/member/info").with(TestMembers.asMember(fresh))).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mockMvc.perform(get(base).with(TestMembers.asMember(fresh))).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mockMvc.perform(get("/admin/board/posts").with(TestMembers.asMember(fresh))).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mockMvc.perform(get("/admin/notice/manage").with(TestMembers.asMember(fresh))).andReturn().getResponse().getStatus()).isEqualTo(403);
        String dashboard = mockMvc.perform(get("/admin").with(TestMembers.asMember(fresh))).andReturn().getResponse().getContentAsString();
        assertThat(dashboard).doesNotContain("href=\"/admin/board/posts\"");
    }
}
