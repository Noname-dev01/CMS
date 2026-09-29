package com.cms.publicweb.notice;

import com.cms.admin.notice.domain.Notice;
import com.cms.admin.notice.domain.NoticeAttachment;
import com.cms.admin.notice.repository.NoticeAttachmentRepository;
import com.cms.admin.notice.repository.NoticeRepository;
import com.cms.common.storage.FileStorage;
import com.cms.common.storage.StoredFileStream;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

/**
 * 공개 첨부 다운로드 스트리밍을 <b>실제 Tomcat·실제 웹 요청(OSIV 인터셉터 포함)·실제 DB</b>로 검증한다
 * (PLAN-public-notice-attachment.md 후속 작업 — 테스트 계획 3·6번). MockMvc는 서블릿 컨테이너의
 * 응답 커밋·연결 종료·OSIV를 재현하지 못한다.
 *
 * <p>실패 주입은 {@link FileStorage#open}만 스파이로 바꿔 "일부 바이트 후 예외" / "일부 바이트 후 대기"
 * 스트림을 돌려주는 방식이다. 이 테스트 클래스는 무인증 첨부 경로의 레이트리밋(IP당 20회/분)을 소비하므로
 * 요청 수를 그 한도 아래로 유지한다.
 */
@SpringBootTest(classes = CmsTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PublicAttachmentStreamingServerTest extends MariaDbContainerSupport {

    private static final String FAKE_KEY = "2099/01/01/streaming-fake.bin";
    private static final int DECLARED_SIZE = 100_000;

    @LocalServerPort
    int port;

    @Autowired
    NoticeRepository noticeRepository;

    @Autowired
    NoticeAttachmentRepository noticeAttachmentRepository;

    @Autowired
    DataSource dataSource;

    @MockitoSpyBean
    FileStorage fileStorage;

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    private Long noticeId;
    private Long attachmentId;

    @BeforeEach
    void createNoticeWithFakeAttachmentRow() {
        LocalDateTime now = LocalDateTime.now();
        Notice notice = noticeRepository.save(Notice.builder()
                .title("스트리밍-" + System.nanoTime())
                .content("스트리밍 서버 테스트")
                .useYn(true)
                .deleted(false)
                .authorId("admin01")
                .createDate(now)
                .updateDate(now)
                .build());
        noticeId = notice.getId();
        // 실파일 없이 행만 만든다 — 파일 내용은 매 테스트에서 fileStorage.open 스파이로 주입한다.
        NoticeAttachment attachment = noticeAttachmentRepository.save(NoticeAttachment.builder()
                .noticeId(noticeId)
                .originalFilename("report.bin")
                .contentType("application/octet-stream")
                .fileSize((long) DECLARED_SIZE)
                .storageKey(FAKE_KEY)
                .createDate(now)
                .build());
        attachmentId = attachment.getId();
    }

    @AfterEach
    void cleanUp() {
        noticeAttachmentRepository.deleteById(attachmentId);
        noticeRepository.deleteById(noticeId);
    }

    private String url() {
        return "http://localhost:" + port + "/notices/" + noticeId + "/attachments/" + attachmentId;
    }

    /** filler 바이트 'A'를 내보내다가 failAfter에 도달하면 IOException(-1이면 끝까지). 닫힘 여부를 기록한다. */
    static class FillerStream extends InputStream {
        private final int total;
        private final int failAfter;
        private int position = 0;
        final AtomicBoolean closed = new AtomicBoolean(false);

        FillerStream(int total, int failAfter) {
            this.total = total;
            this.failAfter = failAfter;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n == -1 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (failAfter >= 0 && position >= failAfter) {
                throw new IOException("디스크 읽기 실패 시뮬레이션");
            }
            if (position >= total) {
                return -1;
            }
            int limit = failAfter >= 0 ? Math.min(total, failAfter) : total;
            int n = Math.min(len, limit - position);
            java.util.Arrays.fill(b, off, off + n, (byte) 'A');
            position += n;
            return n;
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    private FillerStream stubOpen(FillerStream stream) throws Exception {
        doReturn(new StoredFileStream(stream, DECLARED_SIZE)).when(fileStorage).open(FAKE_KEY);
        return stream;
    }

    // ===================== 골든 패스 =====================

    @Test
    @DisplayName("GET은 200·Content-Length·보안 헤더·전체 본문을 반환하고 HEAD는 같은 헤더에 빈 본문이다")
    void goldenPath_getAndHead() throws Exception {
        FillerStream stream = stubOpen(new FillerStream(DECLARED_SIZE, -1));

        HttpResponse<byte[]> get = client.send(HttpRequest.newBuilder(URI.create(url())).build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertThat(get.statusCode()).isEqualTo(200);
        assertThat(get.headers().firstValue("Content-Length")).contains(String.valueOf(DECLARED_SIZE));
        assertThat(get.headers().firstValue("Content-Type").orElse("")).startsWith("application/octet-stream");
        assertThat(get.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(get.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(get.headers().firstValue("Content-Disposition").orElse("")).contains("report.bin");
        assertThat(get.body()).hasSize(DECLARED_SIZE);
        assertThat(stream.closed.get()).as("전송 후 스트림이 닫힘").isTrue();

        FillerStream headStream = stubOpen(new FillerStream(DECLARED_SIZE, -1));
        HttpResponse<byte[]> head = client.send(
                HttpRequest.newBuilder(URI.create(url())).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertThat(head.statusCode()).isEqualTo(200);
        assertThat(head.headers().firstValue("Content-Length")).contains(String.valueOf(DECLARED_SIZE));
        assertThat(head.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(head.body()).isEmpty();
        assertThat(headStream.closed.get()).as("HEAD도 스트림을 닫음").isTrue();
    }

    // ===================== 커넥션 비점유 (OSIV 회귀 가드, 계획서 테스트 6번) =====================

    /** 일부 바이트를 내보낸 뒤 latch에서 대기한다 — 전송이 "진행 중"인 시점을 만든다. */
    static class BlockingStream extends InputStream {
        private final CountDownLatch reachedBlock;
        private final CountDownLatch release;
        private int position = 0;
        private static final int BLOCK_AT = 8192;

        BlockingStream(CountDownLatch reachedBlock, CountDownLatch release) {
            this.reachedBlock = reachedBlock;
            this.release = release;
        }

        @Override
        public int read() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (position >= DECLARED_SIZE) {
                return -1;
            }
            if (position >= BLOCK_AT) {
                reachedBlock.countDown();
                try {
                    if (!release.await(20, TimeUnit.SECONDS)) {
                        throw new IOException("테스트 latch 타임아웃");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
            int limit = position < BLOCK_AT ? BLOCK_AT : DECLARED_SIZE;
            int n = Math.min(len, limit - position);
            java.util.Arrays.fill(b, off, off + n, (byte) 'A');
            position += n;
            return n;
        }
    }

    @Test
    @DisplayName("전송이 진행 중일 때(응답 완료 전) DB 활성 커넥션은 0이다 — OSIV가 켜지면 실패하는 회귀 가드")
    void whileStreaming_noDbConnectionIsHeld() throws Exception {
        CountDownLatch reachedBlock = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doReturn(new StoredFileStream(new BlockingStream(reachedBlock, release), DECLARED_SIZE))
                .when(fileStorage).open(FAKE_KEY);
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);

        CompletableFuture<HttpResponse<byte[]>> future = client.sendAsync(
                HttpRequest.newBuilder(URI.create(url())).build(), HttpResponse.BodyHandlers.ofByteArray());
        try {
            assertThat(reachedBlock.await(20, TimeUnit.SECONDS)).as("전송이 스트림 대기 지점에 도달").isTrue();
            assertThat(hikari.getHikariPoolMXBean().getActiveConnections())
                    .as("전송 진행 중 활성 커넥션(OSIV 켜짐이면 1)").isZero();
        } finally {
            release.countDown();
        }
        HttpResponse<byte[]> response = future.get(20, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).hasSize(DECLARED_SIZE);
    }

    // ===================== 전송 중 실패 (계획서 결정 S5, 테스트 계획 3번) =====================

    private static boolean containsHtml(byte[] body) {
        String text = new String(body, java.nio.charset.StandardCharsets.UTF_8);
        return text.contains("<html") || text.contains("<!DOCTYPE") || text.contains("<body");
    }

    @Test
    @DisplayName("첫 청크 읽기 실패(응답 무손대) — 완전한 HTML 500이고 첨부 헤더가 없으며 보안 헤더가 있고 스트림이 닫힌다")
    void firstReadFails_html500() throws Exception {
        FillerStream stream = stubOpen(new FillerStream(DECLARED_SIZE, 0));

        HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(URI.create(url())).build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.headers().firstValue("Content-Disposition")).isEmpty();
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/html");
        assertThat(containsHtml(response.body())).isTrue();
        assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(stream.closed.get()).isTrue();
    }

    @Test
    @DisplayName("첫 청크 이후 미커밋 구간에서 읽기 실패 — reset 후 완전한 HTML 500, 첨부 Content-Length·본문 잔존 없음, 보안 헤더 복원, 스트림 닫힘")
    void failureAfterFirstChunkBeforeCommit_resetThenHtml500() throws Exception {
        FillerStream stream = stubOpen(new FillerStream(DECLARED_SIZE, 4096));

        HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(URI.create(url())).build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.headers().firstValue("Content-Disposition")).isEmpty();
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/html");
        assertThat(response.headers().firstValue("Content-Length").map(Long::parseLong).orElse((long) response.body().length))
                .as("첨부 파일 길이가 오류 응답에 남지 않음").isNotEqualTo((long) DECLARED_SIZE);
        assertThat(containsHtml(response.body())).isTrue();
        assertThat(new String(response.body(), java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("AAAA");
        assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(stream.closed.get()).isTrue();
    }

    @Test
    @DisplayName("커밋 후 읽기 실패 — 연결이 중단되어 클라이언트는 선언된 길이보다 적게 받고 HTML이 섞이지 않으며 다음 요청은 정상이다")
    void failureAfterCommit_connectionAbortedNoHtml() throws Exception {
        FillerStream stream = stubOpen(new FillerStream(DECLARED_SIZE, 40_000));

        HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(URI.create(url())).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Length")).contains(String.valueOf(DECLARED_SIZE));

        // 서버가 연결을 실제로 끊지 않으면(예외를 삼켜 정상 완료로 처리하면) 클라이언트는 남은 바이트를
        // 무한정 기다린다 — 제한 시간 안에 읽기가 끝나야(연결 종료) 한다는 것을 단언한다.
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        CompletableFuture<Boolean> readFinished = CompletableFuture.supplyAsync(() -> {
            try (InputStream body = response.body()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = body.read(buf)) != -1) {
                    received.write(buf, 0, n);
                }
                return false;
            } catch (IOException e) {
                return true;
            }
        });
        boolean readFailed;
        try {
            readFailed = readFinished.get(10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            response.body().close();
            throw new AssertionError("서버가 잘린 응답의 연결을 끊지 않았다(클라이언트가 남은 바이트를 계속 기다림)", e);
        }

        assertThat(received.size()).as("선언된 길이보다 적게 수신").isLessThan(DECLARED_SIZE);
        assertThat(readFailed || received.size() < DECLARED_SIZE).isTrue();
        assertThat(containsHtml(received.toByteArray())).as("수신 본문에 오류 HTML이 섞이지 않음").isFalse();
        for (byte b : received.toByteArray()) {
            assertThat(b).isEqualTo((byte) 'A');
        }
        assertThat(stream.closed.get()).isTrue();

        // 잘린 응답 뒤에도 서버가 정상 동작하는지(연결 재사용 경계가 깨지지 않았는지) 확인한다.
        FillerStream healthy = stubOpen(new FillerStream(DECLARED_SIZE, -1));
        HttpResponse<byte[]> next = client.send(HttpRequest.newBuilder(URI.create(url())).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(next.statusCode()).isEqualTo(200);
        assertThat(next.body()).hasSize(DECLARED_SIZE);
        assertThat(healthy.closed.get()).isTrue();
    }
}
