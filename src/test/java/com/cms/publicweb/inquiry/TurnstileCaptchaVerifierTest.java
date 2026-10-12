package com.cms.publicweb.inquiry;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Turnstile 서버 검증기를 로컬 HTTP 서버로 시험한다(PLAN-inquiry.md 쟁점 4). 가짜 {@link CaptchaVerifier}로는 잡을 수 없는 실패 모드 —
 * 특히 <b>응답 헤더만 보내고 본문을 멈추는 서버</b>(OpenJDK 21의 {@code HttpRequest.timeout}은 헤더 수신 뒤 취소돼 막지 못한다, R2-1)를 고정한다.
 */
class TurnstileCaptchaVerifierTest {

    private HttpServer server;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void stopServer() {
        release.countDown();
        server.stop(0);
    }

    private TurnstileCaptchaVerifier verifier() {
        InquiryTurnstileProperties properties = new InquiryTurnstileProperties();
        properties.setEnabled(true);
        properties.setSecretKey("s3cr3t");
        properties.setVerifyUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/verify");
        return new TurnstileCaptchaVerifier(properties);
    }

    private void respond(int status, String body) {
        server.createContext("/verify", exchange -> {
            lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    @Test
    @DisplayName("success=true 응답이면 true — 시크릿·토큰·IP가 form 본문으로 전달된다")
    void success_true() {
        respond(200, "{\"success\":true}");

        assertThat(verifier().verify("tok en&x", "203.0.113.9")).isTrue();
        assertThat(lastRequestBody.get()).contains("secret=s3cr3t").contains("response=tok+en%26x").contains("remoteip=203.0.113.9");
    }

    @Test
    @DisplayName("success=false·success 없음·잘못된 JSON·HTTP 500은 모두 false (fail-closed)")
    void failures_false() {
        respond(200, "{\"success\":false,\"error-codes\":[\"invalid-input-response\"]}");
        assertThat(verifier().verify("t", null)).isFalse();

        server.removeContext("/verify");
        respond(200, "{}");
        assertThat(verifier().verify("t", null)).isFalse();

        server.removeContext("/verify");
        respond(200, "<html>not json</html>");
        assertThat(verifier().verify("t", null)).isFalse();

        server.removeContext("/verify");
        respond(500, "{\"success\":true}");
        assertThat(verifier().verify("t", null)).isFalse();
    }

    @Test
    @DisplayName("서버에 연결할 수 없으면 false — 예외를 던지지 않는다")
    void unreachable_false() {
        InquiryTurnstileProperties properties = new InquiryTurnstileProperties();
        properties.setSecretKey("s");
        properties.setVerifyUrl("http://127.0.0.1:1/verify");

        assertThat(new TurnstileCaptchaVerifier(properties).verify("t", null)).isFalse();
    }

    @Test
    @DisplayName("헤더만 보내고 본문을 멈추는 서버에서도 호출 전체 5초대 안에 fail-closed로 끝난다 (R2-1)")
    void stalledBody_returnsWithinBound() {
        server.createContext("/verify", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 1000);   // 헤더 + 본문 길이 약속 뒤 본문을 보내지 않고 멈춘다
            exchange.getResponseBody().flush();
            try {
                release.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });

        long start = System.nanoTime();
        boolean result = verifier().verify("t", null);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(result).isFalse();
        assertThat(elapsedMillis).as("벽시계 상한(5초) 근처에서 끝나야 한다").isBetween(4_000L, 7_000L);
    }
}
