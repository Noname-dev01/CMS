package com.cms.publicweb.board;

import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 공개 목록 쿼리 계약(PLAN-public-list-projection.md D1·D2) — 생성 SQL이 본문을 선택하지 않는다는 것과, 웹진형의 목록·앞부분 조회가 같은 스냅샷을 본다는 것을
 * 실제 스택으로 고정한다. 타입(record)만으로는 보장되지 않는다 — 엔티티를 읽어 변환해도 컴파일된다.
 */
@SpringBootTest(classes = CmsTestApplication.class, properties =
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.cms.publicweb.board.PublicListQueryContractIntegrationTest$CapturingInspector")
@AutoConfigureMockMvc
class PublicListQueryContractIntegrationTest extends MariaDbContainerSupport {

    /** {@code post.content} 컬럼 — 단어 경계라 {@code content_image} 등과 섞이지 않는다. */
    private static final Pattern CONTENT_COLUMN = Pattern.compile("\\bcontent\\b");
    private static final AtomicInteger IP_SEQUENCE = new AtomicInteger(1);

    /** Hibernate가 인스턴스를 만들므로 정적 필드로 시험과 주고받는다. */
    public static class CapturingInspector implements StatementInspector {
        static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();
        static final AtomicReference<Runnable> BEFORE_HEAD_QUERY = new AtomicReference<>();

        @Override
        public String inspect(String sql) {
            String normalized = sql.toLowerCase(Locale.ROOT);
            STATEMENTS.add(normalized);
            if (normalized.contains("substring(content")) {
                Runnable hook = BEFORE_HEAD_QUERY.getAndSet(null);
                if (hook != null) {
                    hook.run();
                }
            }
            return sql;
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    private final List<Long> boardIds = new ArrayList<>();
    private String ip;

    @BeforeEach
    void setUp() {
        int n = IP_SEQUENCE.getAndIncrement();
        ip = "10.92." + (n / 250) + "." + (n % 250 + 1);
        CapturingInspector.STATEMENTS.clear();
        CapturingInspector.BEFORE_HEAD_QUERY.set(null);
    }

    @AfterEach
    void cleanUp() {
        CapturingInspector.BEFORE_HEAD_QUERY.set(null);
        for (Long boardId : boardIds) {
            jdbc.update("DELETE FROM post WHERE board_id = ?", boardId);
            jdbc.update("DELETE FROM board WHERE id = ?", boardId);
        }
    }

    private long insertBoard(String type, int pinMax) {
        jdbc.update("INSERT INTO board (name, public_yn, attachment_yn, deleted, board_type, pin_max_count, create_date, update_date)"
                + " VALUES (?, 1, 1, 0, ?, ?, NOW(6), NOW(6))", "PLQ_" + System.nanoTime(), type, pinMax);
        long id = jdbc.queryForObject("SELECT MAX(id) FROM board", Long.class);
        boardIds.add(id);
        return id;
    }

    private long insertPost(long boardId, String title, String content, boolean pinned) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO post (board_id, title, content, use_yn, deleted, pinned, author_id, create_date, update_date)"
                + " VALUES (?, ?, ?, 1, 0, ?, 'plq-admin', ?, ?)", boardId, title, content, pinned, now, now);
        return jdbc.queryForObject("SELECT MAX(id) FROM post WHERE board_id = ?", Long.class, boardId);
    }

    private String boardList(long boardId, String... params) throws Exception {
        var request = get("/main/boards/board-{id}", boardId).with(r -> {
            r.setRemoteAddr(ip);
            return r;
        });
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        var response = mockMvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        return response.getContentAsString();
    }

    private List<String> statementsReadingContent() {
        return CapturingInspector.STATEMENTS.stream().filter(sql -> CONTENT_COLUMN.matcher(sql).find()).toList();
    }

    @Test
    @DisplayName("목록형·갤러리형 목록은 고정 영역·검색을 포함해 어떤 문장도 본문 컬럼을 읽지 않는다")
    void nonWebzineNeverSelectsContent() throws Exception {
        for (String type : List.of("LIST", "GALLERY")) {
            long board = insertBoard(type, 3);
            insertPost(board, "계약-고정", "<p>고정 본문</p>", true);
            insertPost(board, "계약-일반", "<p>일반 본문</p>", false);
            CapturingInspector.STATEMENTS.clear();

            String page = boardList(board);
            boardList(board, "keyword", "계약");

            assertThat(page).contains("계약-고정").contains("계약-일반");
            assertThat(CapturingInspector.STATEMENTS).as("쿼리가 잡혀야 검사가 의미 있다").anyMatch(sql -> sql.contains("from post"));
            assertThat(statementsReadingContent()).as(type).isEmpty();
        }
    }

    @Test
    @DisplayName("웹진형에서 본문 컬럼을 읽는 문장은 SUBSTRING 앞부분 조회 하나뿐이다")
    void webzineReadsOnlyContentHead() throws Exception {
        long board = insertBoard("WEBZINE", 3);
        insertPost(board, "계약-고정", "<p>고정 본문</p>", true);
        insertPost(board, "계약-일반", "<p>일반 본문</p>", false);
        CapturingInspector.STATEMENTS.clear();

        String page = boardList(board);

        assertThat(page).contains("일반 본문");
        assertThat(statementsReadingContent()).hasSize(1)
                .allSatisfy(sql -> assertThat(sql).contains("substring(content, 1, ?)").doesNotContain("p1_0.content"));
    }

    @Test
    @DisplayName("웹진 앞부분은 목록과 같은 스냅샷이다 — 목록 조회 뒤 비공개로 바꾸며 고친 본문은 요약으로 나가지 않는다")
    void webzineHeadReadsListSnapshot() throws Exception {
        long board = insertBoard("WEBZINE", 0);
        long post = insertPost(board, "계약-스냅샷", "<p>공개 당시 본문</p>", false);
        AtomicReference<Integer> isolation = new AtomicReference<>();
        AtomicReference<Throwable> writerFailure = new AtomicReference<>();
        // 앞부분 조회 직전에 별도 연결(별도 스레드)이 커밋한다 — 같은 스레드의 JdbcTemplate은 진행 중인 트랜잭션의 연결을 쓴다
        CapturingInspector.BEFORE_HEAD_QUERY.set(() -> {
            isolation.set(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
            Thread writer = new Thread(() -> jdbc.update("UPDATE post SET content = '<p>비공개 뒤 새 본문</p>', use_yn = 0 WHERE id = ?", post));
            writer.setUncaughtExceptionHandler((thread, failure) -> writerFailure.set(failure));
            writer.start();
            try {
                writer.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        });

        String page = boardList(board);

        assertThat(writerFailure.get()).isNull();
        assertThat(jdbc.queryForObject("SELECT use_yn FROM post WHERE id = ?", Boolean.class, post)).as("수정이 실제로 커밋됐다").isFalse();
        assertThat(isolation.get()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
        assertThat(page).contains("공개 당시 본문").doesNotContain("비공개 뒤 새 본문");
    }
}
