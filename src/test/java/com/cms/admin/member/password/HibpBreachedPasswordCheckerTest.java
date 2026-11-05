package com.cms.admin.member.password;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HIBP 판정기를 로컬 HTTP 서버로 시험한다. 가짜 구현으로는 잡을 수 없는 응답 해석·벽시계 상한·전송 값을 고정한다.
 * 시험 비밀번호 {@code password}의 SHA-1은 {@code 5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8}이다.
 */
@ExtendWith(OutputCaptureExtension.class)
class HibpBreachedPasswordCheckerTest {

    private static final String PASSWORD = "password";
    private static final String PREFIX = "5BAA6";
    private static final String SUFFIX = "1E4C9B93F3F0682250B6CF8331B7EE68FD8";
    private static final String OTHER_SUFFIX = "00000000000000000000000000000000000";

    private HttpServer server;
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<Headers> lastHeaders = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void stopServer() {
        release.countDown();
        server.stop(0);
    }

    private HibpBreachedPasswordChecker checker(boolean enabled) {
        PasswordBreachCheckProperties properties = new PasswordBreachCheckProperties();
        properties.setEnabled(enabled);
        properties.setRangeUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/range/");
        return new HibpBreachedPasswordChecker(properties);
    }

    private void respond(int status, String body) {
        server.createContext("/range/", exchange -> {
            requests.incrementAndGet();
            lastPath.set(exchange.getRequestURI().getPath());
            lastHeaders.set(exchange.getRequestHeaders());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
        });
    }

    @Test
    @DisplayName("접미부가 횟수 1 이상으로 일치하면 유출 — CRLF 응답·소문자 접미부도 일치한다")
    void matchWithPositiveCount_breached() {
        respond(200, OTHER_SUFFIX + ":3\r\n" + SUFFIX.toLowerCase(java.util.Locale.ROOT) + ":1\r\n");

        assertThat(checker(true).isBreached(PASSWORD)).isTrue();
    }

    @Test
    @DisplayName("SHA-1 앞 5자만 경로로 전송하고, User-Agent와 패딩 헤더를 보낸다")
    void sendsOnlyPrefixAndRequiredHeaders() {
        respond(200, OTHER_SUFFIX + ":1\r\n");

        checker(true).isBreached(PASSWORD);

        assertThat(lastPath.get()).isEqualTo("/range/" + PREFIX);
        assertThat(lastHeaders.get().getFirst("User-Agent")).isEqualTo("CMS-Password-Breach-Check");
        assertThat(lastHeaders.get().getFirst("Add-Padding")).isEqualTo("true");
        assertThat(lastHeaders.get().toString()).doesNotContain(PASSWORD).doesNotContain(SUFFIX);
    }

    @Test
    @DisplayName("다른 접미부만 있으면 미유출이고 WARN이 없다")
    void noMatch_notBreached(CapturedOutput output) {
        respond(200, OTHER_SUFFIX + ":3\r\n");

        assertThat(checker(true).isBreached(PASSWORD)).isFalse();
        assertThat(output).doesNotContain("유출 비밀번호 검사 불가");
    }

    @Test
    @DisplayName("패딩 행(횟수 0)은 접미부가 일치해도 유출이 아니다")
    void paddingRowCountZero_notBreached(CapturedOutput output) {
        respond(200, SUFFIX + ":0\r\n" + OTHER_SUFFIX + ":2\r\n");

        assertThat(checker(true).isBreached(PASSWORD)).isFalse();
        assertThat(output).doesNotContain("유출 비밀번호 검사 불가");
    }

    @Test
    @DisplayName("일치가 없고 손상된 줄이 섞이면 판정 불가 — 통과시키고 WARN을 남긴다")
    void mixedMalformed_failOpenWithWarn(CapturedOutput output) {
        respond(200, OTHER_SUFFIX + ":1\r\nnot-a-valid-line\r\n");

        assertThat(checker(true).isBreached(PASSWORD)).isFalse();
        assertThat(output).contains("유출 비밀번호 검사 불가");
    }

    @Test
    @DisplayName("일치 행의 횟수가 범위를 넘으면 판정 불가다")
    void overflowCount_failOpenWithWarn(CapturedOutput output) {
        respond(200, SUFFIX + ":99999999999999999999999\r\n" + OTHER_SUFFIX + ":1\r\n");

        assertThat(checker(true).isBreached(PASSWORD)).isFalse();
        assertThat(output).contains("유출 비밀번호 검사 불가");
    }

    @Test
    @DisplayName("손상된 줄이 있어도 양수 일치가 있으면 유출이다")
    void positiveMatchWinsOverMalformed() {
        respond(200, "garbage\r\n" + SUFFIX + ":5\r\n");

        assertThat(checker(true).isBreached(PASSWORD)).isTrue();
    }

    @Test
    @DisplayName("200 빈 응답은 판정 불가다 — 패딩 응답은 비어 있을 수 없다")
    void emptyBody_failOpenWithWarn(CapturedOutput output) {
        respond(200, "");

        assertThat(checker(true).isBreached(PASSWORD)).isFalse();
        assertThat(output).contains("유출 비밀번호 검사 불가");
    }

    @Test
    @DisplayName("200이 아니면 본문에 일치가 있어도 판정 불가다")
    void non200_failOpenWithWarn(CapturedOutput output) {
        respond(503, SUFFIX + ":5\r\n");

        assertThat(checker(true).isBreached(PASSWORD)).isFalse();
        assertThat(output).contains("유출 비밀번호 검사 불가").contains("503");
    }

    @Test
    @DisplayName("리다이렉트를 따르지 않는다")
    void redirectNotFollowed() {
        server.createContext("/range/", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/other");
            exchange.sendResponseHeaders(302, -1);
        });
        server.createContext("/other", exchange -> {
            requests.incrementAndGet();
            byte[] bytes = (SUFFIX + ":5").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });

        assertThat(checker(true).isBreached(PASSWORD)).isFalse();
        assertThat(requests.get()).isZero();
    }

    @Test
    @DisplayName("연결할 수 없으면 false — 로그에 비밀번호·해시가 없다")
    void unreachable_failOpen(CapturedOutput output) {
        PasswordBreachCheckProperties properties = new PasswordBreachCheckProperties();
        properties.setRangeUrl("http://127.0.0.1:1/range/");

        assertThat(new HibpBreachedPasswordChecker(properties).isBreached("unique-raw-password-value")).isFalse();
        assertThat(output).contains("유출 비밀번호 검사 불가").doesNotContain("unique-raw-password-value");
    }

    @Test
    @DisplayName("헤더만 보내고 본문을 멈추는 서버 — 벽시계 상한으로 끝나고 false다")
    void stalledBody_boundedByWallClock() {
        server.createContext("/range/", exchange -> {
            exchange.sendResponseHeaders(200, 1024);
            exchange.getResponseBody().flush();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });

        long started = System.nanoTime();
        boolean result = checker(true).isBreached(PASSWORD);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertThat(result).isFalse();
        assertThat(elapsedMillis).isLessThan((HibpBreachedPasswordChecker.TOTAL_TIMEOUT_SECONDS + 2) * 1000L);
    }

    @Test
    @DisplayName("꺼져 있으면 외부로 나가지 않고 false다")
    void disabled_noRequest() {
        respond(200, SUFFIX + ":5\r\n");

        assertThat(checker(false).isBreached(PASSWORD)).isFalse();
        assertThat(requests.get()).isZero();
    }
}
