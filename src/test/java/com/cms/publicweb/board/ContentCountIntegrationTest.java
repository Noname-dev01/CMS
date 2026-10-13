package com.cms.publicweb.board;

import com.cms.admin.board.domain.Post;
import com.cms.admin.board.repository.PostRepository;
import com.cms.admin.board.service.ContentCounterWriter;
import com.cms.admin.dashboard.dto.response.ContentStatsResponse;
import com.cms.admin.dashboard.service.ContentStatsService;
import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.permission.PermissionCache;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;

/**
 * 조회수·다운로드 수 집계(PLAN-view-download-count.md) 실제 스택 시험 — 공개 판정 정합(404·비공개·HEAD는 세지 않음)·중복 억제·공지 경로·
 * 집계 실패 격리·관리자 락 대기 중 응답·동시성·엔티티가 카운터를 덮어쓰지 않음·대시보드 권한 한정을 확인한다.
 * 각 점검은 고유한 원격 IP를 써 레이트리밋 버킷과 중복 억제 캐시를 격리한다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class ContentCountIntegrationTest extends MariaDbContainerSupport {

    private static final AtomicInteger IP_SEQUENCE = new AtomicInteger(1);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired FileStorage fileStorage;
    @Autowired MemberRepository memberRepository;
    @Autowired PostRepository postRepository;
    @Autowired PermissionCache cache;
    @Autowired ContentStatsService contentStatsService;
    @Autowired TransactionTemplate transactionTemplate;
    @MockitoSpyBean ContentCounterWriter writer;

    private final List<Long> boardIds = new ArrayList<>();
    private final List<Long> memberIds = new ArrayList<>();
    private final List<String> storageKeys = new ArrayList<>();
    private long noticeBoardId;
    private long boardId;
    private String ip;

    @BeforeEach
    void setUp() {
        noticeBoardId = jdbc.queryForObject("SELECT id FROM board WHERE board_key = 'NOTICE'", Long.class);
        boardId = insertBoard("cc-게시판-" + System.nanoTime());
        nextIp();
    }

    @AfterEach
    void cleanUp() {
        for (Long id : boardIds) {
            jdbc.update("DELETE FROM post_attachment WHERE post_id IN (SELECT id FROM post WHERE board_id = ?)", id);
            jdbc.update("DELETE FROM member_board_permission WHERE board_id = ?", id);
            jdbc.update("DELETE FROM post WHERE board_id = ?", id);
            jdbc.update("DELETE FROM board WHERE id = ?", id);
        }
        jdbc.update("DELETE FROM post_attachment WHERE post_id IN (SELECT id FROM post WHERE title LIKE 'cc-notice-%')");
        jdbc.update("DELETE FROM post WHERE title LIKE 'cc-notice-%'");
        for (String key : storageKeys) {
            try {
                fileStorage.delete(key);
            } catch (RuntimeException ignored) {
            }
        }
        TestMembers.delete(jdbc, memberIds);
        cache.invalidate();
    }

    private void nextIp() {
        int n = IP_SEQUENCE.getAndIncrement();
        ip = "10.88." + (n / 250) + "." + (n % 250 + 1);
    }

    private RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private RequestPostProcessor fromTestIp() {
        return from(ip);
    }

    // ── 픽스처 ────────────────────────────────────────────────────

    private long insertBoard(String name) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO board (name, public_yn, attachment_yn, deleted, create_date, update_date) VALUES (?, 1, 1, 0, NOW(6), NOW(6))",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            return ps;
        }, keys);
        long id = keys.getKey().longValue();
        boardIds.add(id);
        return id;
    }

    private long insertPost(long board, String title, boolean useYn, boolean deleted) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO post (board_id, title, content, use_yn, deleted, author_id, create_date, update_date)"
                            + " VALUES (?, ?, ?, ?, ?, 'tester', NOW(6), NOW(6))", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, board);
            ps.setString(2, title);
            ps.setString(3, "<p>본문</p>");
            ps.setBoolean(4, useYn);
            ps.setBoolean(5, deleted);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private long insertAttachment(long postId, String filename, boolean withFile) {
        byte[] bytes = "첨부 내용".getBytes();
        String key = withFile ? fileStorage.store(bytes, "a.pdf") : "missing-file-key-" + System.nanoTime();
        if (withFile) {
            storageKeys.add(key);
        }
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO post_attachment (post_id, original_filename, content_type, file_size, storage_key, create_date)"
                            + " VALUES (?, ?, 'application/pdf', ?, ?, NOW(6))", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, postId);
            ps.setString(2, filename);
            ps.setLong(3, bytes.length);
            ps.setString(4, key);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private long views(long postId) {
        return jdbc.queryForObject("SELECT view_count FROM post WHERE id = ?", Long.class, postId);
    }

    private long downloads(long attachmentId) {
        return jdbc.queryForObject("SELECT download_count FROM post_attachment WHERE id = ?", Long.class, attachmentId);
    }

    private int status(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    // ── 조회수 ────────────────────────────────────────────────────

    @Test
    @DisplayName("상세 GET은 1 증가하고, 같은 IP의 반복은 세지 않으며, 다른 IP는 센다")
    void detailGet_countsOncePerIp() throws Exception {
        long post = insertPost(boardId, "cc-post", true, false);

        assertThat(status(get("/boards/{b}/posts/{p}", boardId, post).with(fromTestIp()))).isEqualTo(200);
        assertThat(views(post)).isEqualTo(1);
        assertThat(status(get("/boards/{b}/posts/{p}", boardId, post).with(fromTestIp()))).isEqualTo(200);
        assertThat(views(post)).as("같은 IP 반복").isEqualTo(1);

        nextIp();
        assertThat(status(get("/boards/{b}/posts/{p}", boardId, post).with(fromTestIp()))).isEqualTo(200);
        assertThat(views(post)).as("다른 IP").isEqualTo(2);
    }

    @Test
    @DisplayName("HEAD·목록·404(없음·비노출·삭제·다른 게시판 경로)는 조회수를 올리지 않는다")
    void headListAndNotFound_doNotCount() throws Exception {
        long post = insertPost(boardId, "cc-post", true, false);
        long hidden = insertPost(boardId, "cc-hidden", false, false);
        long deleted = insertPost(boardId, "cc-deleted", true, true);
        long otherBoard = insertBoard("cc-다른-" + System.nanoTime());

        assertThat(status(head("/boards/{b}/posts/{p}", boardId, post).with(fromTestIp()))).isEqualTo(200);
        assertThat(status(get("/boards/{b}", boardId).with(fromTestIp()))).isEqualTo(200);
        assertThat(status(get("/boards/{b}/posts/{p}", boardId, hidden).with(fromTestIp()))).isEqualTo(404);
        assertThat(status(get("/boards/{b}/posts/{p}", boardId, deleted).with(fromTestIp()))).isEqualTo(404);
        assertThat(status(get("/boards/{b}/posts/{p}", otherBoard, post).with(fromTestIp()))).isEqualTo(404);
        assertThat(status(get("/boards/{b}/posts/{p}", boardId, 999_999_999L).with(fromTestIp()))).isEqualTo(404);

        assertThat(views(post)).isZero();
        assertThat(views(hidden)).isZero();
        assertThat(views(deleted)).isZero();
    }

    @Test
    @DisplayName("공지 상세 GET도 같은 규칙으로 센다 (공지 게시판의 게시글)")
    void noticeDetail_counts() throws Exception {
        long notice = insertPost(noticeBoardId, "cc-notice-" + System.nanoTime(), true, false);

        assertThat(status(head("/notices/{id}", notice).with(fromTestIp()))).isEqualTo(200);
        assertThat(views(notice)).as("HEAD").isZero();
        assertThat(status(get("/notices/{id}", notice).with(fromTestIp()))).isEqualTo(200);
        assertThat(views(notice)).isEqualTo(1);
        assertThat(status(get("/notices/{id}", notice).with(fromTestIp()))).isEqualTo(200);
        assertThat(views(notice)).as("같은 IP 반복").isEqualTo(1);
    }

    // ── 다운로드 수 ───────────────────────────────────────────────

    @Test
    @DisplayName("첨부 다운로드는 GET만 센다 — HEAD·파일 없음 404·타 글의 첨부 ID는 세지 않는다")
    void attachmentDownload_countsOnlyGet() throws Exception {
        long post = insertPost(boardId, "cc-post", true, false);
        long otherPost = insertPost(boardId, "cc-other", true, false);
        long attachment = insertAttachment(post, "a.pdf", true);
        long missingFile = insertAttachment(post, "gone.pdf", false);

        assertThat(status(head("/boards/{b}/posts/{p}/attachments/{a}", boardId, post, attachment).with(fromTestIp()))).isEqualTo(200);
        assertThat(downloads(attachment)).as("HEAD").isZero();
        assertThat(status(get("/boards/{b}/posts/{p}/attachments/{a}", boardId, otherPost, attachment).with(fromTestIp()))).isEqualTo(404);
        assertThat(status(get("/boards/{b}/posts/{p}/attachments/{a}", boardId, post, missingFile).with(fromTestIp()))).isEqualTo(404);
        assertThat(downloads(attachment)).as("타 글 경로").isZero();
        assertThat(downloads(missingFile)).as("파일 없음").isZero();

        assertThat(status(get("/boards/{b}/posts/{p}/attachments/{a}", boardId, post, attachment).with(fromTestIp()))).isEqualTo(200);
        assertThat(downloads(attachment)).isEqualTo(1);
        assertThat(status(get("/boards/{b}/posts/{p}/attachments/{a}", boardId, post, attachment).with(fromTestIp()))).isEqualTo(200);
        assertThat(downloads(attachment)).as("같은 IP 반복").isEqualTo(1);
    }

    @Test
    @DisplayName("공지 첨부 다운로드도 센다")
    void noticeAttachment_counts() throws Exception {
        long notice = insertPost(noticeBoardId, "cc-notice-" + System.nanoTime(), true, false);
        long attachment = insertAttachment(notice, "n.pdf", true);

        assertThat(status(get("/notices/{id}/attachments/{a}", notice, attachment).with(fromTestIp()))).isEqualTo(200);
        assertThat(downloads(attachment)).isEqualTo(1);
    }

    // ── 실패 격리 ─────────────────────────────────────────────────

    @Test
    @DisplayName("집계 쓰기가 실패해도 상세·다운로드 응답은 정상이다 (카운트만 유실)")
    void counterFailure_doesNotAffectResponses() throws Exception {
        long post = insertPost(boardId, "cc-post", true, false);
        long attachment = insertAttachment(post, "a.pdf", true);
        doThrow(new IllegalStateException("tx begin failed")).when(writer).incrementView(any());
        doThrow(new IllegalStateException("commit failed")).when(writer).incrementDownload(any());

        assertThat(status(get("/boards/{b}/posts/{p}", boardId, post).with(fromTestIp()))).isEqualTo(200);
        var download = mockMvc.perform(get("/boards/{b}/posts/{p}/attachments/{a}", boardId, post, attachment).with(fromTestIp()))
                .andReturn().getResponse();
        assertThat(download.getStatus()).isEqualTo(200);
        assertThat(download.getContentAsByteArray()).isEqualTo("첨부 내용".getBytes());
        assertThat(views(post)).isZero();
        assertThat(downloads(attachment)).isZero();
    }

    @Test
    @DisplayName("관리자가 게시글 행을 FOR UPDATE로 잡고 있어도 공개 상세는 정상 응답하고(카운트만 유실) 커넥션이 반환된다")
    void rowLockedByAdmin_responseStillOk() throws Exception {
        long post = insertPost(boardId, "cc-post", true, false);

        try (Connection admin = dataSource.getConnection()) {
            admin.setAutoCommit(false);
            try (Statement st = admin.createStatement()) {
                st.execute("SELECT id FROM post WHERE id = " + post + " FOR UPDATE");
                long started = System.nanoTime();
                assertThat(status(get("/boards/{b}/posts/{p}", boardId, post).with(fromTestIp()))).isEqualTo(200);
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                assertThat(elapsedMs).as("락 대기 상한(innodb_lock_wait_timeout=1s) 안에서 응답").isLessThan(8_000);
            } finally {
                admin.rollback();
            }
        }
        assertThat(views(post)).as("락 때문에 유실").isZero();

        // 락이 풀린 뒤에는 (다른 IP가) 정상적으로 센다 — 풀의 커넥션이 반환됐다는 간접 증거
        nextIp();
        assertThat(status(get("/boards/{b}/posts/{p}", boardId, post).with(fromTestIp()))).isEqualTo(200);
        assertThat(views(post)).isEqualTo(1);
    }

    // ── 동시성 ────────────────────────────────────────────────────

    @Test
    @DisplayName("동시 쓰기 상한 이내(3개)의 서로 다른 IP가 같은 글을 동시에 열면 정확히 그만큼 증가한다")
    void concurrentDistinctIps_withinCap_exactCount() throws Exception {
        long post = insertPost(boardId, "cc-post", true, false);
        assertThat(openConcurrently(post, 3)).isEqualTo(3);
    }

    @Test
    @DisplayName("상한을 넘는 폭주(12개 IP 동시)에서도 응답은 모두 정상이고, 집계는 일부 버려질 수 있으나 0이 아니며 12를 넘지 않는다")
    void concurrentBurst_allResponsesOk_countsBestEffort() throws Exception {
        long post = insertPost(boardId, "cc-post", true, false);
        long counted = openConcurrently(post, 12);
        assertThat(counted).isBetween(1L, 12L);
    }

    /** n개의 고유 IP가 동시에 상세를 열고(전부 200이어야 한다) 그 결과 증가한 조회수를 돌려준다. */
    private long openConcurrently(long post, int n) throws Exception {
        List<String> ips = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            nextIp();
            ips.add(ip);
        }
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<java.util.concurrent.Future<Integer>> results = new ArrayList<>();
        for (String address : ips) {
            results.add(pool.submit(() -> {
                go.await();
                return status(get("/boards/{b}/posts/{p}", boardId, post).with(from(address)));
            }));
        }
        go.countDown();
        for (var result : results) {
            assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        }
        pool.shutdown();
        return views(post);
    }

    // ── 엔티티가 카운터를 덮어쓰지 않음 (updatable=false 변이 검출) ──────

    @Test
    @DisplayName("영속성 컨텍스트에 로드된 엔티티를 수정·flush해도 그 사이 증가한 카운터를 덮어쓰지 않는다")
    void entityUpdate_doesNotOverwriteCounter() {
        long id = insertPost(boardId, "cc-original", true, false);

        transactionTemplate.executeWithoutResult(status -> {
            Post loaded = postRepository.findById(id).orElseThrow();
            assertThat(loaded.getViewCount()).isZero();
            // 로드 뒤 다른 경로(공개 조회)가 카운터를 올린다 — 같은 JDBC 트랜잭션이라 변경이 같은 연결에서 보인다
            jdbc.update("UPDATE post SET view_count = view_count + 5 WHERE id = ?", id);
            loaded.update("cc-changed", null, null, java.time.LocalDateTime.now());
            postRepository.saveAndFlush(loaded);
        });

        assertThat(views(id)).as("엔티티 flush가 낡은 0으로 덮어쓰면 0이 된다").isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT title FROM post WHERE id = ?", String.class, id)).isEqualTo("cc-changed");
    }

    // ── 대시보드 권한 한정 ────────────────────────────────────────

    private Authentication auth(Member member) {
        CustomUserDetails details = new CustomUserDetails(member);
        return new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());
    }

    @Test
    @DisplayName("대시보드: MANAGER는 READ 게시판의 항목만, ADMIN은 전부 본다 — 권한 없는 게시판 제목은 새지 않는다")
    void dashboard_scopedByReadableBoards() {
        long secretBoard = insertBoard("cc-secret-" + System.nanoTime());
        long visible = insertPost(boardId, "cc-visible-title", true, false);
        long secret = insertPost(secretBoard, "cc-secret-title", true, false);
        long deleted = insertPost(boardId, "cc-deleted-title", true, true);
        long visibleAttachment = insertAttachment(visible, "visible.pdf", false);
        long secretAttachment = insertAttachment(secret, "secret.pdf", false);
        jdbc.update("UPDATE post SET view_count = 7 WHERE id = ?", visible);
        jdbc.update("UPDATE post SET view_count = 100 WHERE id = ?", secret);
        jdbc.update("UPDATE post SET view_count = 50 WHERE id = ?", deleted);
        jdbc.update("UPDATE post_attachment SET download_count = 3 WHERE id = ?", visibleAttachment);
        jdbc.update("UPDATE post_attachment SET download_count = 9 WHERE id = ?", secretAttachment);

        Member manager = TestMembers.save(memberRepository, "cc-manager", Role.ROLE_MANAGER);
        memberIds.add(manager.getId());
        jdbc.update("INSERT INTO member_board_permission (member_id, board_id, action) VALUES (?, ?, 'READ')", manager.getId(), boardId);
        cache.invalidate();

        ContentStatsResponse forManager = contentStatsService.getContentStats(auth(manager));
        assertThat(forManager).isNotNull();
        assertThat(forManager.totalViews()).as("삭제 글·권한 없는 게시판 제외").isEqualTo(7);
        assertThat(forManager.totalDownloads()).isEqualTo(3);
        assertThat(forManager.topPosts()).extracting(ContentStatsResponse.TopPost::title).containsExactly("cc-visible-title");
        assertThat(forManager.topAttachments()).extracting(ContentStatsResponse.TopAttachment::filename).containsExactly("visible.pdf");

        Member admin = TestMembers.save(memberRepository, "cc-admin", Role.ROLE_ADMIN);
        memberIds.add(admin.getId());
        ContentStatsResponse forAdmin = contentStatsService.getContentStats(auth(admin));
        assertThat(forAdmin).isNotNull();
        assertThat(forAdmin.topPosts()).extracting(ContentStatsResponse.TopPost::title)
                .contains("cc-secret-title", "cc-visible-title").doesNotContain("cc-deleted-title");
        assertThat(forAdmin.totalViews()).isGreaterThanOrEqualTo(107);
    }

    @Test
    @DisplayName("대시보드: 읽을 수 있는 게시판이 없는 MANAGER는 0과 빈 목록이다")
    void dashboard_managerWithoutBoards_isEmpty() {
        insertPost(boardId, "cc-hidden-from-manager", true, false);
        jdbc.update("UPDATE post SET view_count = 9 WHERE title = 'cc-hidden-from-manager'");
        Member manager = TestMembers.save(memberRepository, "cc-manager0", Role.ROLE_MANAGER);
        memberIds.add(manager.getId());
        cache.invalidate();

        ContentStatsResponse stats = contentStatsService.getContentStats(auth(manager));

        assertThat(stats).isNotNull();
        assertThat(stats.totalViews()).isZero();
        assertThat(stats.totalDownloads()).isZero();
        assertThat(stats.topPosts()).isEmpty();
        assertThat(stats.topAttachments()).isEmpty();
    }
}
