package com.cms.publicweb.inquiry;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Cloudflare Turnstile 서버 측 검증(PLAN-inquiry.md 쟁점 4). JDK {@link HttpClient}를 쓴다(새 의존성 없음).
 *
 * <p><b>호출 전체 5초 벽시계 제한(R2-1)</b>: {@code HttpRequest.timeout}은 OpenJDK 21에서 응답 <em>헤더</em>를 받은 뒤 타이머가 취소되어 본문 수신 지연을
 * 막지 못한다. 그래서 {@code sendAsync} 결과를 {@code get(5초)}로 기다리고, 초과·오류 때 {@code cancel(true)}로 진행 중 요청을 취소한다. 서블릿 스레드를
 * 최대 5초 점유한다(레이트리밋이 호출 빈도를 제한). 호출은 DB 트랜잭션 밖에서 일어난다 — 외부 호출 중 커넥션을 쥐지 않는다.
 *
 * <p>로그에는 예외 클래스명만 남긴다(토큰·시크릿·응답 본문을 남기지 않는다). 응답 JSON은 {@code success}만 신뢰한다.
 */
@Slf4j
@Component
public class TurnstileCaptchaVerifier implements CaptchaVerifier {

    static final int CONNECT_TIMEOUT_SECONDS = 3;
    static final int TOTAL_TIMEOUT_SECONDS = 5;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final InquiryTurnstileProperties properties;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
            .build();

    public TurnstileCaptchaVerifier(InquiryTurnstileProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean verify(String token, String remoteIp) {
        CompletableFuture<HttpResponse<String>> future = null;
        try {
            StringBuilder form = new StringBuilder()
                    .append("secret=").append(encode(properties.getSecretKey()))
                    .append("&response=").append(encode(token));
            if (remoteIp != null && !remoteIp.isBlank()) {
                form.append("&remoteip=").append(encode(remoteIp));
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getVerifyUrl()))
                    .timeout(Duration.ofSeconds(TOTAL_TIMEOUT_SECONDS))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
                    .build();
            future = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> response = future.get(TOTAL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (response.statusCode() != 200) {
                log.warn("캡차 검증 응답 상태 {}", response.statusCode());
                return false;
            }
            JsonNode body = MAPPER.readTree(response.body());
            return body.path("success").asBoolean(false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancel(future);
            log.warn("캡차 검증 중단 ({})", e.getClass().getName());
            return false;
        } catch (Exception e) {
            // 타임아웃·연결 실패·잘못된 JSON 등 — 모두 fail-closed. 메시지·원인 체인은 남기지 않는다.
            cancel(future);
            log.warn("캡차 검증 실패 ({})", e.getClass().getName());
            return false;
        }
    }

    private static void cancel(CompletableFuture<?> future) {
        if (future != null) {
            future.cancel(true);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
