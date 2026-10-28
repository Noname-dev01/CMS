package com.cms.publicweb.board;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.permission.PermissionCache;
import com.cms.common.display.DisplayPeriod;
import com.cms.common.image.ImageFileValidatorTest;
import com.cms.common.storage.FileStorage;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.MutableClock;
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
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;

/**
 * 게시글 노출 기간(예약 게시)의 <b>모든 공개 경로</b> 경계 시험(PLAN-post-publish-period.md 쟁점 9 — 가장 중요한 완료 기준). 가변 {@link MutableClock}으로
 * 시작 직전·시작·종료 직전·종료 시각을 만들어 {@code /boards} 목록·상세·첨부(GET·HEAD)·본문 이미지(GET·HEAD)와 {@code /notices} 목록·상세·첨부,
 * 공개 메인(공지·새 글)이 한 결과로 움직이는지 실제 스택(Security·MockMvc·MariaDB·스토리지)으로 확인한다. 한 경로라도 기간 조건이 빠지면
 * 예약 글이 미리 새거나 만료 글이 남는다. 행은 JDBC로 직접 만들고 각 점검은 고유한 원격 IP를 써 레이트리밋 버킷을 격리한다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(PostPublishPeriodPublicIntegrationTest.ClockConfig.class)
class PostPublishPeriodPublicIntegrationTest extends MariaDbContainerSupport {

    /** 공개 메인의 최신 5건에 들도록 아주 먼 미래의 생성 시각을 쓴다(다른 시험의 행보다 항상 최신). */
    private static final LocalDateTime NEWEST = LocalDateTime.of(2098, 1, 1, 0, 0);

    private static final LocalDateTime START = LocalDateTime.of(2090, 3, 1, 9, 0);
    private static final LocalDateTime END = LocalDateTime.of(2090, 3, 8, 0, 0);
    /** datetime(6)의 최소 단위 — "직전"은 마이크로초 하나 앞이다. */
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
    @Autowired PermissionCache cache;
    @Autowired MutableClock clock;

    private final List<Long> boardIds = new ArrayList<>();
    private final List<Long> postIds = new ArrayList<>();
    private final List<String> storageKeys = new ArrayList<>();
    private long noticeBoardId;
    private Member manager;
    private String ip;

    @BeforeEach
    void setUp() {
        noticeBoardId = jdbc.queryForObject("SELECT id FROM board WHERE board_key = 'NOTICE'", Long.class);
        manager = TestMembers.save(memberRepository, "pp-pub-manager", Role.ROLE_MANAGER);
        clock.set(LocalDateTime.of(2090, 1, 1, 0, 0));
        nextIp();
    }

    @AfterEach
    void cleanUp() {
        for (Long postId : postIds) {
            jdbc.update("DELETE FROM post_attachment WHERE post_id = ?", postId);
            jdbc.update("DELETE FROM content_image_ref WHERE owner_type = 'POST' AND owner_id = ?", postId);
            jdbc.update("DELETE FROM post WHERE id = ?", postId);
        }
        jdbc.update("DELETE r FROM content_image_ref r JOIN content_image i ON i.id = r.image_id WHERE i.uploader_id = 'pp-pub-test'");
        jdbc.update("DELETE FROM content_image WHERE uploader_id = 'pp-pub-test'");
        jdbc.update("UPDATE content_image_usage SET total_bytes = (SELECT COALESCE(SUM(file_size), 0) FROM content_image),"
                + " total_count = (SELECT COUNT(*) FROM content_image) WHERE id = 1");
        for (Long boardId : boardIds) {
            jdbc.update("DELETE FROM member_board_permission WHERE board_id = ?", boardId);
            jdbc.update("DELETE FROM post WHERE board_id = ?", boardId);
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

    /** 점검 묶음마다 새 원격 IP — 한 시험 안에서 요청이 많아도 레이트리밋(429)이 판정을 흐리지 않는다. */
    private void nextIp() {
        int n = IP_SEQUENCE.getAndIncrement();
        ip = "10.77." + (n / 250) + "." + (n % 250 + 1);
    }

    private RequestPostProcessor fromTestIp() {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
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

    private long insertPost(long boardId, String title, boolean useYn, LocalDateTime start, LocalDateTime end) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO post (board_id, title, content, use_yn, deleted, author_id, create_date, update_date, publish_start, publish_end)"
                            + " VALUES (?, ?, ?, ?, 0, 'tester', ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, boardId);
            ps.setString(2, title);
            ps.setString(3, "<p>본문 " + title + "</p>");
            ps.setBoolean(4, useYn);
            ps.setTimestamp(5, Timestamp.valueOf(NEWEST));
            ps.setTimestamp(6, Timestamp.valueOf(NEWEST));
            ps.setTimestamp(7, start == null ? null : Timestamp.valueOf(start));
            ps.setTimestamp(8, end == null ? null : Timestamp.valueOf(end));
            return ps;
        }, keys);
        long id = keys.getKey().longValue();
        postIds.add(id);
        return id;
    }

    private long insertAttachment(long postId) {
        byte[] bytes = "첨부 내용".getBytes();
        String key = fileStorage.store(bytes, "a.pdf");
        storageKeys.add(key);
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO post_attachment (post_id, original_filename, content_type, file_size, storage_key, create_date)"
                            + " VALUES (?, 'a.pdf', 'application/pdf', ?, ?, NOW(6))", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, postId);
            ps.setLong(2, bytes.length);
            ps.setString(3, key);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private long insertImage(long boardId) throws Exception {
        byte[] png = ImageFileValidatorTest.png(8, 8);
        String key = fileStorage.store(png, "image.png");
        storageKeys.add(key);
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO content_image (storage_key, content_type, file_size, uploader_id, create_date, scope_type, scope_id)"
                            + " VALUES (?, 'image/png', ?, 'pp-pub-test', NOW(6), 'BOARD', ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, key);
            ps.setLong(2, png.length);
            ps.setLong(3, boardId);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private void reference(long postId, long imageId) {
        jdbc.update("INSERT INTO content_image_ref (owner_type, owner_id, image_id) VALUES ('POST', ?, ?)", postId, imageId);
    }

    // ── 점검 ─────────────────────────────────────────────────────

    private int statusOf(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    private String bodyOf(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse().getContentAsString();
    }

    /** 공개 판정에 쓰인 시각을 {@code now}로 맞추고 한 묶음 점검을 새 IP로 시작한다. */
    private void at(LocalDateTime now) {
        clock.set(now);
        nextIp();
    }

    private static String expectation(boolean expected) {
        return expected ? "공개" : "비공개(404)";
    }

    private static LocalDateTime[] boundaries() {
        return new LocalDateTime[]{
                START.minusNanos(MICRO_NANOS), START, START.plusNanos(MICRO_NANOS),
                END.minusNanos(MICRO_NANOS), END, END.plusNanos(MICRO_NANOS)};
    }

    @Test
    @DisplayName("[start, end) 경계: 시작 직전·시작·종료 직전·종료에서 /boards·/notices·메인의 모든 공개 경로가 한 결과로 움직인다")
    void everyPublicPathFollowsTheBoundaries() throws Exception {
        long board = insertBoard("pp-pub-게시판");
        String boardTitle = "pp-pub-board-" + System.nanoTime();
        String noticeTitle = "pp-pub-notice-" + System.nanoTime();
        long boardPost = insertPost(board, boardTitle, true, START, END);
        long noticePost = insertPost(noticeBoardId, noticeTitle, true, START, END);
        long boardAttachment = insertAttachment(boardPost);
        long noticeAttachment = insertAttachment(noticePost);
        long boardImage = insertImage(board);
        long noticeImage = insertImage(noticeBoardId);
        reference(boardPost, boardImage);
        reference(noticePost, noticeImage);

        for (LocalDateTime now : boundaries()) {
            at(now);
            boolean expected = DisplayPeriod.isActive(START, END, now);
            String ctx = " now=" + now + " 기대=" + expectation(expected);
            int ok = expected ? 200 : 404;

            // /boards — 목록(제목 노출)·상세·첨부(GET·HEAD)·본문 이미지(GET·HEAD)
            assertThat(bodyOf(get("/main/boards/board-{b}", board).with(fromTestIp())).contains(boardTitle)).as("boards 목록" + ctx).isEqualTo(expected);
            assertThat(statusOf(get("/main/boards/board-{b}/posts/{p}", board, boardPost).with(fromTestIp()))).as("boards 상세" + ctx).isEqualTo(ok);
            assertThat(statusOf(get("/main/boards/board-{b}/posts/{p}/attachments/{a}", board, boardPost, boardAttachment).with(fromTestIp())))
                    .as("boards 첨부 GET" + ctx).isEqualTo(ok);
            assertThat(statusOf(head("/main/boards/board-{b}/posts/{p}/attachments/{a}", board, boardPost, boardAttachment).with(fromTestIp())))
                    .as("boards 첨부 HEAD" + ctx).isEqualTo(ok);
            assertThat(statusOf(get("/content-images/{i}", boardImage).with(fromTestIp()))).as("boards 이미지 GET" + ctx).isEqualTo(ok);
            assertThat(statusOf(head("/content-images/{i}", boardImage).with(fromTestIp()))).as("boards 이미지 HEAD" + ctx).isEqualTo(ok);

            // /notices — 공지는 공지 게시판의 글이라 같은 판정을 따라야 한다
            assertThat(bodyOf(get("/main/notices").with(fromTestIp())).contains(noticeTitle)).as("notices 목록" + ctx).isEqualTo(expected);
            assertThat(statusOf(get("/main/notices/{p}", noticePost).with(fromTestIp()))).as("notices 상세" + ctx).isEqualTo(ok);
            assertThat(statusOf(get("/main/notices/{p}/attachments/{a}", noticePost, noticeAttachment).with(fromTestIp())))
                    .as("notices 첨부 GET" + ctx).isEqualTo(ok);
            assertThat(statusOf(head("/main/notices/{p}/attachments/{a}", noticePost, noticeAttachment).with(fromTestIp())))
                    .as("notices 첨부 HEAD" + ctx).isEqualTo(ok);
            assertThat(statusOf(get("/content-images/{i}", noticeImage).with(fromTestIp()))).as("notices 이미지 GET" + ctx).isEqualTo(ok);

            // 공개 메인 — 공지 섹션(공지 게시판 글)과 새 글 섹션(그 밖의 공개 게시판 글)
            String home = bodyOf(get("/main").with(fromTestIp()));
            assertThat(home.contains(noticeTitle)).as("메인 공지 섹션" + ctx).isEqualTo(expected);
            assertThat(home.contains(boardTitle)).as("메인 새 글 섹션" + ctx).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("기간 형태: 시작만·종료만·양끝 null 각각이 공개 목록·상세에서 isActive와 일치한다")
    void openEndedShapes() throws Exception {
        long board = insertBoard("pp-pub-형태");
        long onlyStart = insertPost(board, "pp-pub-시작만", true, START, null);
        long onlyEnd = insertPost(board, "pp-pub-종료만", true, null, END);
        long none = insertPost(board, "pp-pub-없음", true, null, null);

        for (LocalDateTime now : boundaries()) {
            at(now);
            String ctx = " now=" + now;
            String list = bodyOf(get("/main/boards/board-{b}", board).with(fromTestIp()));

            assertThat(list.contains("pp-pub-시작만")).as("시작만 목록" + ctx).isEqualTo(DisplayPeriod.isActive(START, null, now));
            assertThat(list.contains("pp-pub-종료만")).as("종료만 목록" + ctx).isEqualTo(DisplayPeriod.isActive(null, END, now));
            assertThat(list.contains("pp-pub-없음")).as("없음 목록" + ctx).isTrue();
            assertThat(statusOf(get("/main/boards/board-{b}/posts/{p}", board, onlyStart).with(fromTestIp())))
                    .as("시작만 상세" + ctx).isEqualTo(DisplayPeriod.isActive(START, null, now) ? 200 : 404);
            assertThat(statusOf(get("/main/boards/board-{b}/posts/{p}", board, onlyEnd).with(fromTestIp())))
                    .as("종료만 상세" + ctx).isEqualTo(DisplayPeriod.isActive(null, END, now) ? 200 : 404);
            assertThat(statusOf(get("/main/boards/board-{b}/posts/{p}", board, none).with(fromTestIp()))).as("없음 상세" + ctx).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("useYn=false는 기간 안이어도 목록·상세·첨부·이미지·메인 어디에도 나오지 않는다 (둘 다 만족해야 노출)")
    void hiddenPostStaysHiddenInsideThePeriod() throws Exception {
        long board = insertBoard("pp-pub-비노출");
        String title = "pp-pub-hidden-" + System.nanoTime();
        long post = insertPost(board, title, false, START, END);
        long attachment = insertAttachment(post);
        long image = insertImage(board);
        reference(post, image);
        at(START.plusDays(1));

        assertThat(bodyOf(get("/main/boards/board-{b}", board).with(fromTestIp()))).doesNotContain(title);
        assertThat(statusOf(get("/main/boards/board-{b}/posts/{p}", board, post).with(fromTestIp()))).isEqualTo(404);
        assertThat(statusOf(get("/main/boards/board-{b}/posts/{p}/attachments/{a}", board, post, attachment).with(fromTestIp()))).isEqualTo(404);
        assertThat(statusOf(get("/content-images/{i}", image).with(fromTestIp()))).isEqualTo(404);
        assertThat(bodyOf(get("/main").with(fromTestIp()))).doesNotContain(title);
    }

    @Test
    @DisplayName("COUNT: 예약 글 20개가 총 페이지 수에 새지 않고, 시작 시각이 지나면 한 번에 반영된다")
    void paginationDoesNotCountScheduledPosts() throws Exception {
        long board = insertBoard("pp-pub-페이지");
        for (int i = 0; i < 11; i++) {
            insertPost(board, "pp-pub-공개" + String.format("%02d", i), true, null, null);
        }
        for (int i = 0; i < 20; i++) {
            insertPost(board, "pp-pub-예약" + i, true, START, null);
        }

        at(START.minusMinutes(1));
        String before = bodyOf(get("/main/boards/board-{b}", board).with(fromTestIp()));
        assertThat(before).contains("1 / 2").doesNotContain("pp-pub-예약");

        at(START);
        String after = bodyOf(get("/main/boards/board-{b}", board).with(fromTestIp()));
        assertThat(after).contains("1 / 4");   // 11 + 20 = 31건 → 4쪽
    }

    @Test
    @DisplayName("본문 이미지: 한 이미지를 기간이 다른 여러 글이 참조하면 하나라도 공개 중이면 공개, 모두 비공개일 때만 404")
    void sharedImageStaysPublicWhileAnyReferencingPostIsPublished() throws Exception {
        long board = insertBoard("pp-pub-공유이미지");
        long image = insertImage(board);
        LocalDateTime earlyEnd = START.plusDays(1);                   // 글 A: [START, START+1d)
        LocalDateTime lateEnd = START.plusDays(3);                    // 글 B: [START, START+3d)
        reference(insertPost(board, "pp-pub-공유A", true, START, earlyEnd), image);
        reference(insertPost(board, "pp-pub-공유B", true, START, lateEnd), image);

        at(START.minusMinutes(1));
        assertThat(statusOf(get("/content-images/{i}", image).with(fromTestIp()))).as("둘 다 예약 중").isEqualTo(404);
        at(START);
        assertThat(statusOf(get("/content-images/{i}", image).with(fromTestIp()))).as("둘 다 공개").isEqualTo(200);
        at(earlyEnd);
        assertThat(statusOf(get("/content-images/{i}", image).with(fromTestIp()))).as("A 만료, B 공개").isEqualTo(200);
        at(lateEnd);
        assertThat(statusOf(get("/content-images/{i}", image).with(fromTestIp()))).as("둘 다 만료").isEqualTo(404);
    }

    @Test
    @DisplayName("미리보기: 그 게시판 READ 권한자는 예약·만료 글의 이미지도 보고, 익명은 404다 (JPQL 기간 조건이 틀려도 권한자 분기가 가리지 않도록 익명으로 판정)")
    void readPermissionHolderPreviewsScheduledPostImage() throws Exception {
        long board = insertBoard("pp-pub-미리보기");
        long image = insertImage(board);
        reference(insertPost(board, "pp-pub-미리보기글", true, START, END), image);
        jdbc.update("INSERT INTO member_board_permission (member_id, board_id, action) VALUES (?, ?, 'READ')", manager.getId(), board);
        cache.invalidate();
        RequestPostProcessor asManager = TestMembers.asMember(manager);
        at(START.minusDays(1));                                       // 예약 중

        assertThat(statusOf(get("/content-images/{i}", image).with(fromTestIp()))).as("익명").isEqualTo(404);
        assertThat(statusOf(get("/content-images/{i}", image).with(asManager).with(fromTestIp()))).as("READ 권한자").isEqualTo(200);
        assertThat(statusOf(head("/content-images/{i}", image).with(asManager).with(fromTestIp()))).as("READ 권한자 HEAD").isEqualTo(200);

        at(END.plusDays(1));                                          // 만료
        assertThat(statusOf(get("/content-images/{i}", image).with(fromTestIp()))).as("익명(만료)").isEqualTo(404);
        assertThat(statusOf(get("/content-images/{i}", image).with(asManager).with(fromTestIp()))).as("READ 권한자(만료)").isEqualTo(200);
    }
}
