package com.cms.publicweb.board;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 공개 게시판(/boards)의 공개 노출 불변식과 게시판 본문 이미지 공개 판정의 실제 스택 시험(PLAN-board.md 쟁점 9·10, D-6, 완료 기준 "공개").
 * 비공개 게시판·삭제 게시판·미노출·삭제 게시글·다른 게시판 소속 게시글이 목록·COUNT·상세·첨부·이미지 모두에서 같은 404여야 한다.
 * 행은 JDBC로 직접 만든다(관리 API가 아니라 공개 서비스의 조건을 시험하므로). 각 요청은 테스트마다 고유한 원격 IP를 써 레이트리밋 버킷을 격리한다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class PublicBoardIntegrationTest extends MariaDbContainerSupport {

    private static final AtomicInteger IP_SEQUENCE = new AtomicInteger(1);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired FileStorage fileStorage;
    @Autowired MemberRepository memberRepository;
    @Autowired PermissionCache cache;

    private final List<Long> boardIds = new ArrayList<>();
    private final List<String> storageKeys = new ArrayList<>();
    private Member manager;
    private String ip;

    @BeforeEach
    void setUp() {
        int n = IP_SEQUENCE.getAndIncrement();
        ip = "10.88." + (n / 250) + "." + (n % 250 + 1);
        manager = TestMembers.save(memberRepository, "pb-manager", Role.ROLE_MANAGER);
    }

    @AfterEach
    void cleanUp() {
        for (Long boardId : boardIds) {
            jdbc.update("DELETE a FROM post_attachment a JOIN post p ON p.id = a.post_id WHERE p.board_id = ?", boardId);
            jdbc.update("DELETE FROM content_image_ref WHERE owner_type = 'POST' AND owner_id IN (SELECT id FROM post WHERE board_id = ?)", boardId);
            jdbc.update("DELETE FROM post WHERE board_id = ?", boardId);
            jdbc.update("DELETE FROM member_board_permission WHERE board_id = ?", boardId);
        }
        jdbc.update("DELETE r FROM content_image_ref r JOIN content_image i ON i.id = r.image_id WHERE i.uploader_id = 'pb-test'");
        jdbc.update("DELETE FROM content_image WHERE uploader_id = 'pb-test'");
        jdbc.update("UPDATE content_image_usage SET total_bytes = (SELECT COALESCE(SUM(file_size), 0) FROM content_image),"
                + " total_count = (SELECT COUNT(*) FROM content_image) WHERE id = 1");
        for (Long boardId : boardIds) {
            jdbc.update("DELETE FROM board WHERE id = ?", boardId);
        }
        for (String key : storageKeys) {
            try {
                fileStorage.delete(key);
            } catch (RuntimeException ignored) {
            }
        }
        TestMembers.delete(jdbc, List.of(manager.getId()));
        cache.invalidate();
    }

    /** 이 테스트 전용 원격 IP — 익명 요청이어도 레이트리밋 버킷이 테스트끼리 섞이지 않는다. */
    private RequestPostProcessor fromTestIp() {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private long insertBoard(String name, boolean publicYn, boolean deleted) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO board (name, public_yn, attachment_yn, deleted, create_date, update_date) VALUES (?, ?, 1, ?, NOW(6), NOW(6))",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setBoolean(2, publicYn);
            ps.setBoolean(3, deleted);
            return ps;
        }, keys);
        long id = keys.getKey().longValue();
        boardIds.add(id);
        return id;
    }

    private long insertPost(long boardId, String title, String content, boolean useYn, boolean deleted, LocalDateTime createDate) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO post (board_id, title, content, use_yn, deleted, author_id, create_date, update_date) VALUES (?, ?, ?, ?, ?, 'tester', ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, boardId);
            ps.setString(2, title);
            ps.setString(3, content);
            ps.setBoolean(4, useYn);
            ps.setBoolean(5, deleted);
            ps.setTimestamp(6, Timestamp.valueOf(createDate));
            ps.setTimestamp(7, Timestamp.valueOf(createDate));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private long insertPost(long boardId, String title, boolean useYn, boolean deleted) {
        return insertPost(boardId, title, "<p>본문 " + title + "</p>", useYn, deleted, LocalDateTime.now());
    }

    private long insertAttachment(long postId, String filename, byte[] bytes) {
        String key = fileStorage.store(bytes, filename);
        storageKeys.add(key);
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO post_attachment (post_id, original_filename, content_type, file_size, storage_key, create_date) VALUES (?, ?, 'application/pdf', ?, ?, NOW(6))",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, postId);
            ps.setString(2, filename);
            ps.setLong(3, bytes.length);
            ps.setString(4, key);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /** 이미지 행 + 파일. scopeType/scopeId가 이미지의 업로드 출처다. */
    private long insertImage(String scopeType, Long scopeId) throws Exception {
        byte[] png = ImageFileValidatorTest.png(8, 8);
        String key = fileStorage.store(png, "image.png");
        storageKeys.add(key);
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO content_image (storage_key, content_type, file_size, uploader_id, create_date, scope_type, scope_id)"
                            + " VALUES (?, 'image/png', ?, 'pb-test', NOW(6), ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, key);
            ps.setLong(2, png.length);
            ps.setString(3, scopeType);
            if (scopeId == null) {
                ps.setNull(4, java.sql.Types.BIGINT);
            } else {
                ps.setLong(4, scopeId);
            }
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private void reference(long postId, long imageId) {
        jdbc.update("INSERT INTO content_image_ref (owner_type, owner_id, image_id) VALUES ('POST', ?, ?)", postId, imageId);
    }

    private String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    // ── 목록 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("공개 목록: 노출·미삭제 게시글만, 최신순, 다른 게시판 글·미노출·삭제 글은 목록과 총 페이지 수에서 모두 빠진다")
    void listShowsOnlyPublishedPostsOfThatBoard() throws Exception {
        long board = insertBoard("공개자료실", true, false);
        long other = insertBoard("다른게시판", true, false);
        LocalDateTime now = LocalDateTime.now();
        insertPost(board, "오래된글", "<p>a</p>", true, false, now.minusDays(2));
        insertPost(board, "최신글", "<p>b</p>", true, false, now);
        insertPost(board, "숨김글", "<p>c</p>", false, false, now);
        insertPost(board, "삭제글", "<p>d</p>", true, true, now);
        insertPost(other, "남의글", "<p>e</p>", true, false, now);

        MvcResult result = mockMvc.perform(get("/boards/{id}", board).with(fromTestIp())).andExpect(status().isOk()).andReturn();

        String html = body(result);
        assertThat(html).contains("공개자료실", "오래된글", "최신글");
        assertThat(html).doesNotContain("숨김글", "삭제글", "남의글");
        assertThat(html.indexOf("최신글")).as("최신순").isLessThan(html.indexOf("오래된글"));
    }

    @Test
    @DisplayName("공개 목록 페이지네이션: 10건씩, 숨김 글이 COUNT에 섞이지 않아 총 페이지 수가 공개 글 기준이다")
    void paginationCountsOnlyPublished() throws Exception {
        long board = insertBoard("페이지", true, false);
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < 11; i++) {
            insertPost(board, "공개" + String.format("%02d", i), "<p>x</p>", true, false, now.minusMinutes(i));
        }
        for (int i = 0; i < 30; i++) {
            insertPost(board, "숨김" + i, "<p>x</p>", false, false, now);
        }

        String first = body(mockMvc.perform(get("/boards/{id}", board).with(fromTestIp())).andReturn());
        assertThat(first).contains("1 / 2").doesNotContain("숨김");
        String second = body(mockMvc.perform(get("/boards/{id}", board).param("page", "1").with(fromTestIp())).andReturn());
        assertThat(second).contains("2 / 2").contains("공개10").doesNotContain("숨김");
        // 비숫자·음수·범위 밖 page는 0으로 흡수된다
        assertThat(body(mockMvc.perform(get("/boards/{id}", board).param("page", "abc").with(fromTestIp())).andReturn())).contains("1 / 2");
        assertThat(body(mockMvc.perform(get("/boards/{id}", board).param("page", "-3").with(fromTestIp())).andReturn())).contains("1 / 2");
    }

    @Test
    @DisplayName("제목 검색: 공개 글만 일치하고 숨김 글은 일치해도 보이지 않는다, 100자 초과 검색어는 빈 결과")
    void titleSearch() throws Exception {
        long board = insertBoard("검색", true, false);
        insertPost(board, "공개 보고서", true, false);
        insertPost(board, "숨김 보고서", false, false);
        insertPost(board, "다른 글", true, false);

        String found = body(mockMvc.perform(get("/boards/{id}", board).param("keyword", "보고서").with(fromTestIp())).andReturn());
        assertThat(found).contains("공개 보고서").doesNotContain("숨김 보고서", "다른 글");
        String none = body(mockMvc.perform(get("/boards/{id}", board).param("keyword", "가".repeat(101)).with(fromTestIp())).andReturn());
        assertThat(none).contains("검색 결과가 없습니다");
        String hiddenOnly = body(mockMvc.perform(get("/boards/{id}", board).param("keyword", "숨김").with(fromTestIp())).andReturn());
        assertThat(hiddenOnly).contains("검색 결과가 없습니다").doesNotContain("1 / ");
    }

    @Test
    @DisplayName("비공개 게시판·삭제된 게시판·없는 게시판·비숫자 ID의 목록은 모두 같은 404")
    void unpublishedBoardListIs404() throws Exception {
        long privateBoard = insertBoard("비공개", false, false);
        long deletedBoard = insertBoard("삭제됨", true, true);
        insertPost(privateBoard, "비공개글", true, false);
        insertPost(deletedBoard, "삭제게시판글", true, false);

        for (String id : new String[]{String.valueOf(privateBoard), String.valueOf(deletedBoard), String.valueOf(Long.MAX_VALUE), "abc", "-1", "0"}) {
            MvcResult result = mockMvc.perform(get("/boards/{id}", id).with(fromTestIp())).andExpect(status().isNotFound()).andReturn();
            assertThat(body(result)).doesNotContain("비공개글", "삭제게시판글");
        }
    }

    // ── 상세 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("공개 상세: 200 + 정리된 본문, 제목·게시판 이름은 이스케이프(XSS), 작성자 아이디는 노출하지 않는다")
    void detailRendersSanitizedContentAndEscapesText() throws Exception {
        long board = insertBoard("<b>게시판</b>", true, false);
        long post = insertPost(board, "<script>alert(1)</script>제목", "<p>안전<script>alert(2)</script></p>", true, false, LocalDateTime.now());

        MvcResult result = mockMvc.perform(get("/boards/{b}/posts/{p}", board, post).with(fromTestIp())).andExpect(status().isOk()).andReturn();

        String html = body(result);
        assertThat(html).contains("안전");
        assertThat(html).doesNotContain("<script>alert").doesNotContain("<b>게시판</b>");
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;제목").contains("&lt;b&gt;게시판&lt;/b&gt;");
        assertThat(html).doesNotContain("tester");
    }

    @Test
    @DisplayName("상세 404: 미노출·삭제 게시글, 다른 게시판 경로의 글, 비공개·삭제 게시판의 글, 없는 글, 비숫자는 모두 같은 404")
    void detailInvariantViolationsAre404() throws Exception {
        long board = insertBoard("공개", true, false);
        long other = insertBoard("다른", true, false);
        long privateBoard = insertBoard("비공개", false, false);
        long deletedBoard = insertBoard("삭제", true, true);
        long visible = insertPost(board, "보이는글", true, false);
        long hidden = insertPost(board, "숨김글", false, false);
        long deleted = insertPost(board, "삭제글", true, true);
        long inPrivate = insertPost(privateBoard, "비공개게시판글", true, false);
        long inDeletedBoard = insertPost(deletedBoard, "삭제게시판글", true, false);

        mockMvc.perform(get("/boards/{b}/posts/{p}", board, visible).with(fromTestIp())).andExpect(status().isOk());
        String[][] cases = {
                {String.valueOf(board), String.valueOf(hidden)},
                {String.valueOf(board), String.valueOf(deleted)},
                {String.valueOf(other), String.valueOf(visible)},          // 다른 게시판 경로로 접근
                {String.valueOf(privateBoard), String.valueOf(inPrivate)},
                {String.valueOf(deletedBoard), String.valueOf(inDeletedBoard)},
                {String.valueOf(board), String.valueOf(Long.MAX_VALUE)},
                {String.valueOf(board), "abc"},
                {"abc", String.valueOf(visible)},
        };
        for (String[] c : cases) {
            MvcResult result = mockMvc.perform(get("/boards/{b}/posts/{p}", c[0], c[1]).with(fromTestIp())).andExpect(status().isNotFound()).andReturn();
            assertThat(body(result)).doesNotContain("숨김글", "삭제글", "비공개게시판글", "삭제게시판글");
        }
    }

    // ── 첨부 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("공개 첨부: GET은 octet-stream·attachment·nosniff·no-store로 본문을 내려주고 HEAD는 같은 헤더만, 상세 화면에는 내부 경로(storageKey)가 없다")
    void attachmentDownload() throws Exception {
        long board = insertBoard("자료", true, false);
        long post = insertPost(board, "첨부글", true, false);
        byte[] bytes = "첨부 내용".getBytes();
        long attachment = insertAttachment(post, "보고서.pdf", bytes);
        String key = jdbc.queryForObject("SELECT storage_key FROM post_attachment WHERE id = ?", String.class, attachment);

        MvcResult get = mockMvc.perform(get("/boards/{b}/posts/{p}/attachments/{a}", board, post, attachment).with(fromTestIp()))
                .andExpect(status().isOk()).andReturn();
        assertThat(get.getResponse().getContentAsByteArray()).isEqualTo(bytes);
        assertThat(get.getResponse().getContentType()).isEqualTo("application/octet-stream");
        assertThat(get.getResponse().getHeader("Content-Disposition")).startsWith("attachment");
        assertThat(get.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(get.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(get.getResponse().getContentLengthLong()).isEqualTo(bytes.length);

        MvcResult head = mockMvc.perform(head("/boards/{b}/posts/{p}/attachments/{a}", board, post, attachment).with(fromTestIp()))
                .andExpect(status().isOk()).andReturn();
        assertThat(head.getResponse().getContentAsByteArray()).isEmpty();
        assertThat(head.getResponse().getContentLengthLong()).isEqualTo(bytes.length);

        String detail = body(mockMvc.perform(get("/boards/{b}/posts/{p}", board, post).with(fromTestIp())).andReturn());
        assertThat(detail).contains("보고서.pdf").doesNotContain(key);
    }

    @Test
    @DisplayName("첨부 404 (GET·HEAD 동일): 미노출·삭제 게시글, 비공개·삭제 게시판, 다른 게시판 경로, 다른 게시글의 첨부 ID, 파일이 사라진 첨부, 비숫자")
    void attachmentInvariantViolationsAre404() throws Exception {
        long board = insertBoard("공개", true, false);
        long other = insertBoard("다른", true, false);
        long privateBoard = insertBoard("비공개", false, false);
        long deletedBoard = insertBoard("삭제", true, true);
        long visiblePost = insertPost(board, "보이는글", true, false);
        long otherPost = insertPost(board, "다른글", true, false);
        long hiddenPost = insertPost(board, "숨김글", false, false);
        long deletedPost = insertPost(board, "삭제글", true, true);
        long privatePost = insertPost(privateBoard, "비공개게시판글", true, false);
        long deletedBoardPost = insertPost(deletedBoard, "삭제게시판글", true, false);
        byte[] bytes = "x".getBytes();
        long ofVisible = insertAttachment(visiblePost, "a.pdf", bytes);
        long ofOther = insertAttachment(otherPost, "b.pdf", bytes);
        long ofHidden = insertAttachment(hiddenPost, "c.pdf", bytes);
        long ofDeleted = insertAttachment(deletedPost, "d.pdf", bytes);
        long ofPrivate = insertAttachment(privatePost, "e.pdf", bytes);
        long ofDeletedBoard = insertAttachment(deletedBoardPost, "f.pdf", bytes);
        long missingFile = insertAttachment(visiblePost, "g.pdf", bytes);
        fileStorage.delete(jdbc.queryForObject("SELECT storage_key FROM post_attachment WHERE id = ?", String.class, missingFile));

        mockMvc.perform(get("/boards/{b}/posts/{p}/attachments/{a}", board, visiblePost, ofVisible).with(fromTestIp())).andExpect(status().isOk());
        long[][] cases = {
                {board, hiddenPost, ofHidden},
                {board, deletedPost, ofDeleted},
                {privateBoard, privatePost, ofPrivate},
                {deletedBoard, deletedBoardPost, ofDeletedBoard},
                {other, visiblePost, ofVisible},              // 다른 게시판 경로
                {board, visiblePost, ofOther},                // 다른 게시글의 첨부 ID
                {board, visiblePost, Long.MAX_VALUE},
                {board, visiblePost, missingFile},            // DB 행은 있으나 파일이 사라짐
        };
        for (long[] c : cases) {
            mockMvc.perform(get("/boards/{b}/posts/{p}/attachments/{a}", c[0], c[1], c[2]).with(fromTestIp())).andExpect(status().isNotFound());
            mockMvc.perform(head("/boards/{b}/posts/{p}/attachments/{a}", c[0], c[1], c[2]).with(fromTestIp())).andExpect(status().isNotFound());
        }
        mockMvc.perform(get("/boards/{b}/posts/{p}/attachments/{a}", board, visiblePost, "abc").with(fromTestIp())).andExpect(status().isNotFound());
    }

    // ── 메서드 제한 ──────────────────────────────────────────────

    @Test
    @DisplayName("/boards/** 의 GET·HEAD 외 메서드는 거부된다 (CSRF 없으면 403, CSRF가 있어도 denyAll)")
    void nonReadMethodsAreDenied() throws Exception {
        long board = insertBoard("공개", true, false);

        mockMvc.perform(post("/boards/{id}", board).with(fromTestIp())).andExpect(status().isForbidden());
        int withCsrf = mockMvc.perform(post("/boards/{id}", board).with(csrf()).with(fromTestIp())).andReturn().getResponse().getStatus();
        assertThat(withCsrf).as("익명 + denyAll은 로그인 리다이렉트(302), 인증 사용자는 403").isIn(302, 403);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post WHERE board_id = ?", Long.class, board)).isZero();
    }

    // ── 본문 이미지 공개 판정 ────────────────────────────────────

    @Test
    @DisplayName("[D-6/R1-1] 공개 글이 참조하는 이미지 I1이 있어도, 같은 데이터셋의 비공개 게시판·미참조 이미지는 익명 GET·HEAD 모두 404")
    void unreferencedImageStaysHiddenEvenWhenAnotherImageIsPublished() throws Exception {
        long publicBoard = insertBoard("공개", true, false);
        long privateBoard = insertBoard("비공개", false, false);
        long publicPost = insertPost(publicBoard, "공개글", true, false);
        long i1 = insertImage("BOARD", publicBoard);
        reference(publicPost, i1);
        long i2 = insertImage("BOARD", privateBoard);                   // 비공개 게시판 출처, 미참조
        long i3 = insertImage("BOARD", publicBoard);                    // 공개 게시판 출처지만 미참조(작성 중)
        long privatePost = insertPost(privateBoard, "비공개글", true, false);
        long i4 = insertImage("BOARD", privateBoard);
        reference(privatePost, i4);                                     // 비공개 게시판 글이 참조

        mockMvc.perform(get("/content-images/{id}", i1).with(fromTestIp())).andExpect(status().isOk());
        mockMvc.perform(head("/content-images/{id}", i1).with(fromTestIp())).andExpect(status().isOk());
        for (long hidden : new long[]{i2, i3, i4}) {
            mockMvc.perform(get("/content-images/{id}", hidden).with(fromTestIp())).andExpect(status().isNotFound());
            mockMvc.perform(head("/content-images/{id}", hidden).with(fromTestIp())).andExpect(status().isNotFound());
        }
    }

    @Test
    @DisplayName("미노출·삭제 게시글, 삭제 게시판만 참조하는 이미지는 익명 404이고, 출처가 다른 참조 행(롤백 중 생성 모사)으로는 공개되지 않는다")
    void imageVisibilityFollowsPostAndBoardState() throws Exception {
        long board = insertBoard("공개", true, false);
        long deletedBoard = insertBoard("삭제", true, true);
        long hiddenPost = insertPost(board, "숨김", false, false);
        long deletedPost = insertPost(board, "삭제", true, true);
        long postInDeletedBoard = insertPost(deletedBoard, "삭제게시판글", true, false);
        long visiblePost = insertPost(board, "공개", true, false);

        long onHidden = insertImage("BOARD", board);
        reference(hiddenPost, onHidden);
        long onDeleted = insertImage("BOARD", board);
        reference(deletedPost, onDeleted);
        long onDeletedBoard = insertImage("BOARD", deletedBoard);
        reference(postInDeletedBoard, onDeletedBoard);
        long mismatchedScope = insertImage("BOARD", deletedBoard);       // 이미지 출처가 글의 게시판과 다르다
        reference(visiblePost, mismatchedScope);
        long noticeScoped = insertImage("NOTICE", null);                 // 공지 출처 이미지를 게시글이 참조
        reference(visiblePost, noticeScoped);

        for (long id : new long[]{onHidden, onDeleted, onDeletedBoard, mismatchedScope, noticeScoped}) {
            mockMvc.perform(get("/content-images/{id}", id).with(fromTestIp())).andExpect(status().isNotFound());
        }
    }

    @Test
    @DisplayName("게시판 X READ 권한자는 X 출처 이미지(미참조 포함)를 보고, 권한 회수 직후 404이며, NOTICE READ만 가진 MANAGER와 다른 게시판 권한자는 404")
    void boardScopedPreviewFollowsCurrentBoardReadPermission() throws Exception {
        long boardX = insertBoard("X", false, false);
        long boardY = insertBoard("Y", false, false);
        long image = insertImage("BOARD", boardX);                       // 비공개 게시판 X 출처, 미참조
        RequestPostProcessor asManager = TestMembers.asMember(manager);

        mockMvc.perform(get("/content-images/{id}", image).with(asManager).with(fromTestIp())).andExpect(status().isNotFound());

        jdbc.update("INSERT INTO member_board_permission (member_id, board_id, action) VALUES (?, ?, 'READ')", manager.getId(), boardY);
        jdbc.update("INSERT INTO member_permission (member_id, feature, action) VALUES (?, 'NOTICE', 'READ')", manager.getId());
        cache.invalidate();
        mockMvc.perform(get("/content-images/{id}", image).with(asManager).with(fromTestIp()))
                .andExpect(status().isNotFound());                       // 다른 게시판 READ·NOTICE READ로는 안 보인다

        jdbc.update("INSERT INTO member_board_permission (member_id, board_id, action) VALUES (?, ?, 'READ')", manager.getId(), boardX);
        cache.invalidate();
        mockMvc.perform(get("/content-images/{id}", image).with(asManager).with(fromTestIp())).andExpect(status().isOk());
        mockMvc.perform(head("/content-images/{id}", image).with(asManager).with(fromTestIp())).andExpect(status().isOk());

        jdbc.update("DELETE FROM member_board_permission WHERE member_id = ? AND board_id = ?", manager.getId(), boardX);   // 회수
        cache.invalidate();
        mockMvc.perform(get("/content-images/{id}", image).with(asManager).with(fromTestIp())).andExpect(status().isNotFound());
        mockMvc.perform(get("/content-images/{id}", image).with(fromTestIp())).andExpect(status().isNotFound());              // 익명도 404
    }
}
