package com.cms.publicweb.support;

import com.cms.admin.banner.domain.Banner;
import com.cms.admin.banner.repository.BannerRepository;
import com.cms.admin.contentimage.domain.ContentImage;
import com.cms.admin.contentimage.domain.ContentImageRef;
import com.cms.admin.contentimage.repository.ContentImageRefRepository;
import com.cms.admin.contentimage.repository.ContentImageRepository;
import com.cms.common.storage.FileStorage;
import com.cms.common.storage.StoredFileStream;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import org.apache.catalina.connector.Connector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

/**
 * 동시 상한을 <b>실제 Tomcat</b>으로 검증한다(PLAN-public-transfer-limit.md F1·F5). 클라이언트 중단·컨테이너 오류 재디스패치·요청 완료 시점의 슬롯 반환은 MockMvc로 재현되지 않는다.
 * 레이트리밋을 켜서 거절 순서까지 실제 체인으로 본다. 작은 상한(전체 3·IP별 2)으로 띄우므로 전용 컨텍스트 하나가 추가된다.
 */
@SpringBootTest(classes = CmsTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "cms.rate-limit.enabled=true",
        "cms.public-transfer.max-total=3",
        "cms.public-transfer.max-per-ip=2"
})
class PublicTransferLimitServerTest extends MariaDbContainerSupport {

    private static final String FAKE_KEY = "2099/01/01/transfer-limit-fake.png";
    private static final int SMALL_SIZE = 10_000;
    /** 소켓 버퍼로 흡수되지 않을 만큼 크다 — 클라이언트 중단 시 서버 쓰기가 실패해야 한다. */
    private static final int HUGE_SIZE = 256 * 1024 * 1024;
    private static final String OTHER_IP = "10.77.0.";

    @LocalServerPort int port;
    @Autowired PublicTransferLimiter limiter;
    @Autowired BannerRepository bannerRepository;
    @Autowired ContentImageRepository imageRepository;
    @Autowired ContentImageRefRepository refRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired ServletWebServerApplicationContext serverContext;
    @MockitoSpyBean FileStorage fileStorage;

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final List<String> heldByOthers = new ArrayList<>();
    private Long bannerId;
    private Long imageId;

    @BeforeEach
    void createBanner() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        bannerId = bannerRepository.save(Banner.builder()
                .title("상한").useYn(true).ord(0).createDate(now).updateDate(now).build()).getId();
        imageId = imageRepository.save(ContentImage.builder()
                .storageKey(FAKE_KEY).contentType("image/png").fileSize((long) SMALL_SIZE).uploaderId("t")
                .scopeType("BANNER").namespace("banner").createDate(now).build()).getId();
        refRepository.save(new ContentImageRef("BANNER", bannerId, imageId));
        stubOpen(new FillerStream(SMALL_SIZE, -1), SMALL_SIZE);
    }

    @AfterEach
    void cleanUp() throws Exception {
        heldByOthers.forEach(limiter::release);
        heldByOthers.clear();
        jdbc.update("DELETE FROM content_image_ref WHERE owner_type = 'BANNER' AND owner_id = ?", bannerId);
        bannerRepository.deleteById(bannerId);
        imageRepository.deleteById(imageId);
        awaitInUse(0);
    }

    private void stubOpen(InputStream stream, long size) throws Exception {
        doReturn(new StoredFileStream(stream, size)).when(fileStorage).open(FAKE_KEY, "banner");
    }

    private String imageUrl() {
        return "http://localhost:" + port + "/banners/" + bannerId + "/image";
    }

    private HttpResponse<byte[]> get(String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    /** 슬롯 반환은 응답이 끝나는 순간과 경합하므로 제한 시간 안에 기대값이 되기를 기다린다. */
    private void awaitInUse(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (limiter.inUse() != expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(limiter.inUse()).isEqualTo(expected);
    }

    /** 다른 IP들이 전체 상한을 채운 상태를 만든다. */
    private void saturateTotalFromOtherIps() {
        for (int i = 0; i < 3; i++) {
            String ip = OTHER_IP + i;
            assertThat(limiter.tryAcquire(ip)).isEqualTo(PublicTransferLimiter.Decision.ACQUIRED);
            heldByOthers.add(ip);
        }
    }

    @Test
    @DisplayName("실제 커넥터의 maxThreads가 기동 검증에 쓴 기본값 200과 같다 — Boot 기본값이 바뀌면 검증이 어긋나는 것을 잡는다")
    void connectorThreadsMatchValidatedValue() {
        Connector connector = ((TomcatWebServer) serverContext.getWebServer()).getTomcat().getConnector();
        assertThat(connector.getProperty("maxThreads")).isEqualTo(200);
    }

    @Test
    @DisplayName("전송 중에는 슬롯을 쥐고, 끝나면 반환한다")
    void slotHeldDuringTransferAndReleasedAfter() throws Exception {
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        stubOpen(new BlockingStream(SMALL_SIZE, reached, release), SMALL_SIZE);

        CompletableFuture<HttpResponse<byte[]>> transfer = client.sendAsync(
                HttpRequest.newBuilder(URI.create(imageUrl())).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(reached.await(15, TimeUnit.SECONDS)).isTrue();
        assertThat(limiter.inUse()).isEqualTo(1);

        release.countDown();
        assertThat(transfer.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        awaitInUse(0);
    }

    @Test
    @DisplayName("전송을 붙잡은 채 같은 IP의 HEAD·공개 페이지 요청이 끝나도 사용 수가 그대로다 — 남의 슬롯을 반환하지 않는다")
    void otherRequestsDoNotReleaseHeldSlot() throws Exception {
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        stubOpen(new BlockingStream(SMALL_SIZE, reached, release), SMALL_SIZE);
        CompletableFuture<HttpResponse<byte[]>> transfer = client.sendAsync(
                HttpRequest.newBuilder(URI.create(imageUrl())).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(reached.await(15, TimeUnit.SECONDS)).isTrue();

        try {
            stubOpen(new FillerStream(SMALL_SIZE, -1), SMALL_SIZE);
            HttpResponse<byte[]> head = client.send(
                    HttpRequest.newBuilder(URI.create(imageUrl())).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(head.statusCode()).isEqualTo(200);
            assertThat(get("http://localhost:" + port + "/main").statusCode()).isEqualTo(200);

            assertThat(limiter.inUse()).as("HEAD·페이지 요청이 끝난 뒤").isEqualTo(1);
        } finally {
            release.countDown();
        }
        assertThat(transfer.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        awaitInUse(0);
    }

    @Test
    @DisplayName("전송 중 읽기 실패 뒤 슬롯을 반환한다")
    void slotReleasedAfterReadFailure() throws Exception {
        stubOpen(new FillerStream(100_000, 40_000), 100_000);

        HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(URI.create(imageUrl())).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            body.readAllBytes();
        } catch (IOException expectedTruncation) {
            // 서버가 연결을 끊으면 클라이언트 읽기가 실패한다 — 이 시험의 관심사는 슬롯 반환이다
        }

        awaitInUse(0);
    }

    @Test
    @DisplayName("클라이언트가 전송 중에 끊으면 슬롯을 반환한다")
    void slotReleasedAfterClientAbort() throws Exception {
        stubOpen(new FillerStream(HUGE_SIZE, -1), HUGE_SIZE);

        HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(URI.create(imageUrl())).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        InputStream body = response.body();
        assertThat(body.readNBytes(8192)).hasSize(8192);
        assertThat(limiter.inUse()).as("끊기 전에는 슬롯을 쥔다").isEqualTo(1);

        body.close();

        awaitInUse(0);
    }

    @Test
    @DisplayName("404와 301로 끝난 요청도 슬롯을 반환한다")
    void slotReleasedAfterNotFoundAndRedirect() throws Exception {
        assertThat(get("http://localhost:" + port + "/banners/999999999/image").statusCode()).isEqualTo(404);
        awaitInUse(0);

        String slug = "xfer-" + System.nanoTime();
        jdbc.update("INSERT INTO board (name, public_yn, attachment_yn, deleted, slug, create_date, update_date)"
                + " VALUES ('상한', 1, 1, 0, ?, NOW(6), NOW(6))", slug);
        Long boardId = jdbc.queryForObject("SELECT id FROM board WHERE slug = ?", Long.class, slug);
        try {
            assertThat(get("http://localhost:" + port + "/main/boards/board-" + boardId + "/posts/1/attachments/1").statusCode())
                    .isEqualTo(301);
            awaitInUse(0);
        } finally {
            jdbc.update("DELETE FROM board WHERE id = ?", boardId);
        }
    }

    @Test
    @DisplayName("IP별 상한 초과는 429 HTML 화면과 Retry-After이고 파일을 열지 않으며, 다른 전송에 영향이 없다")
    void perIpLimitRendersTooManyRequests() throws Exception {
        CountDownLatch reached = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        // 두 요청이 같은 스파이 응답을 쓰지 않도록 호출마다 새 스트림을 준다
        org.mockito.Mockito.doAnswer(invocation -> new StoredFileStream(new BlockingStream(SMALL_SIZE, reached, release), SMALL_SIZE))
                .when(fileStorage).open(FAKE_KEY, "banner");
        CompletableFuture<HttpResponse<byte[]>> first = client.sendAsync(
                HttpRequest.newBuilder(URI.create(imageUrl())).build(), HttpResponse.BodyHandlers.ofByteArray());
        CompletableFuture<HttpResponse<byte[]>> second = client.sendAsync(
                HttpRequest.newBuilder(URI.create(imageUrl())).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(reached.await(15, TimeUnit.SECONDS)).isTrue();

        try {
            HttpResponse<byte[]> rejected = get(imageUrl());

            assertThat(rejected.statusCode()).isEqualTo(429);
            assertThat(rejected.headers().firstValue("Retry-After")).contains("5");
            assertThat(rejected.headers().firstValue("Content-Type").orElse("")).startsWith("text/html");
            assertThat(limiter.inUse()).isEqualTo(2);
        } finally {
            release.countDown();
        }
        assertThat(first.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        assertThat(second.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        awaitInUse(0);
    }

    @Test
    @DisplayName("전체 상한 초과는 503 전용 화면과 Retry-After이고, 자리가 나면 곧바로 회복한다")
    void totalLimitRendersServiceUnavailableAndRecovers() throws Exception {
        saturateTotalFromOtherIps();

        HttpResponse<byte[]> rejected = get(imageUrl());

        assertThat(rejected.statusCode()).isEqualTo(503);
        assertThat(rejected.headers().firstValue("Retry-After")).contains("5");
        assertThat(rejected.headers().firstValue("Content-Type").orElse("")).startsWith("text/html");
        assertThat(new String(rejected.body(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("이용자가 많아").doesNotContain(FAKE_KEY);
        assertThat(limiter.inUse()).isEqualTo(3);

        limiter.release(heldByOthers.remove(0));
        assertThat(get(imageUrl()).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("상한이 찬 상태에서 공개 페이지와 health는 정상이다")
    void pagesAndHealthSurviveSaturation() throws Exception {
        saturateTotalFromOtherIps();

        assertThat(get("http://localhost:" + port + "/main").statusCode()).isEqualTo(200);
        assertThat(get("http://localhost:" + port + "/actuator/health").statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("레이트리밋이 동시 상한보다 먼저 거절한다 — 진입 버킷이 비면 503이 아니라 429이고 슬롯을 건드리지 않는다")
    void rateLimitRejectsBeforeTransferLimit() throws Exception {
        saturateTotalFromOtherIps();
        String attachmentUrl = "http://localhost:" + port + "/main/boards/no-such-board/posts/1/attachments/1";

        List<Integer> statuses = new ArrayList<>();
        // 첨부 규칙은 IP당 20/60초다. 없는 게시판이어도 슬롯 판정이 먼저라 503이 나오다가 버킷이 비면 429로 바뀐다.
        for (int i = 0; i < 40 && !statuses.contains(429); i++) {
            statuses.add(get(attachmentUrl).statusCode());
        }

        assertThat(statuses).contains(503).endsWith(429);
        assertThat(statuses.subList(0, statuses.size() - 1)).containsOnly(503);
        assertThat(limiter.inUse()).isEqualTo(3);
    }

    /** filler 바이트를 내보내다가 failAfter에 도달하면 IOException(-1이면 끝까지). */
    private static final class FillerStream extends InputStream {
        private final int total;
        private final int failAfter;
        private int position;

        FillerStream(int total, int failAfter) {
            this.total = total;
            this.failAfter = failAfter;
        }

        @Override
        public int read() {
            throw new UnsupportedOperationException();
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
    }

    /** 첫 청크를 내보낸 뒤 latch에서 기다린다 — 전송이 진행 중인 시점을 만든다. */
    private static final class BlockingStream extends InputStream {
        private static final int BLOCK_AT = 8192;
        private final int total;
        private final CountDownLatch reached;
        private final CountDownLatch release;
        private int position;

        BlockingStream(int total, CountDownLatch reached, CountDownLatch release) {
            this.total = total;
            this.reached = reached;
            this.release = release;
        }

        @Override
        public int read() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (position >= total) {
                return -1;
            }
            if (position >= BLOCK_AT) {
                reached.countDown();
                try {
                    if (!release.await(30, TimeUnit.SECONDS)) {
                        throw new IOException("시험 latch 시간 초과");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
            int limit = position < BLOCK_AT ? BLOCK_AT : total;
            int n = Math.min(len, limit - position);
            java.util.Arrays.fill(b, off, off + n, (byte) 'A');
            position += n;
            return n;
        }
    }
}
