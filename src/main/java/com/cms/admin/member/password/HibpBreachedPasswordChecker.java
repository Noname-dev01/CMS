package com.cms.admin.member.password;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Have I Been Pwned Pwned Passwords range API 판정기(PLAN-password-expiry-retirement-breach-check.md D1). JDK {@link HttpClient}를 쓴다(새 의존성 없음).
 *
 * <p><b>나가는 값은 SHA-1 대문자 hex의 앞 5자뿐이다</b>. 비밀번호·해시·접두부·응답 본문은 로그에 남기지 않는다(예외 클래스명만).
 *
 * <p><b>호출 전체 3초 벽시계 제한</b>: {@code HttpRequest.timeout}은 응답 헤더를 받은 뒤 취소되어 본문 수신 지연을 막지 못하므로
 * ({@code TurnstileCaptchaVerifier}와 같은 이유) {@code sendAsync} 결과를 상한까지만 기다리고 초과·오류 때 요청을 취소한다.
 *
 * <p><b>판정 불가는 통과시키고 WARN을 남긴다(fail-open, D3)</b> — 외부 장애가 탈취 의심 시의 긴급 변경을 막으면 안 된다.
 * 패딩 응답은 비어 있을 수 없으므로, 일치가 없을 때 줄이 하나라도 형식에 어긋나거나 유효한 줄이 없으면 "미유출"이 아니라 판정 불가다.
 */
@Slf4j
@Component
public class HibpBreachedPasswordChecker implements BreachedPasswordChecker {

    static final int CONNECT_TIMEOUT_SECONDS = 2;
    static final int TOTAL_TIMEOUT_SECONDS = 3;

    private static final int PREFIX_LENGTH = 5;
    private static final String USER_AGENT = "CMS-Password-Breach-Check";

    /** 접미부 35자 hex + 횟수. 횟수를 18자리로 제한해 long 범위를 넘는 값을 형식 오류로 처리한다. */
    private static final Pattern LINE = Pattern.compile("^([0-9A-Fa-f]{35}):(\\d{1,18})$");

    private final PasswordBreachCheckProperties properties;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public HibpBreachedPasswordChecker(PasswordBreachCheckProperties properties) {
        this.properties = properties;
        if (!properties.isEnabled()) {
            log.warn("유출 비밀번호 검사가 꺼져 있습니다(cms.password-breach-check.enabled=false)");
        }
    }

    @Override
    public boolean isBreached(String rawPassword) {
        if (!properties.isEnabled()) {
            return false;
        }
        CompletableFuture<HttpResponse<String>> future = null;
        try {
            String hash = sha1Hex(rawPassword);
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getRangeUrl() + hash.substring(0, PREFIX_LENGTH)))
                    .timeout(Duration.ofSeconds(TOTAL_TIMEOUT_SECONDS))
                    .header("User-Agent", USER_AGENT)
                    .header("Add-Padding", "true")
                    .GET()
                    .build();
            future = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> response = future.get(TOTAL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (response.statusCode() != 200) {
                log.warn("유출 비밀번호 검사 불가 — 통과시킵니다 (응답 상태 {})", response.statusCode());
                return false;
            }
            return judge(response.body(), hash.substring(PREFIX_LENGTH));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancel(future);
            log.warn("유출 비밀번호 검사 중단 — 통과시킵니다 ({})", e.getClass().getName());
            return false;
        } catch (Exception e) {
            // 타임아웃·연결 실패 등 — 메시지·원인 체인은 남기지 않는다.
            cancel(future);
            log.warn("유출 비밀번호 검사 불가 — 통과시킵니다 ({})", e.getClass().getName());
            return false;
        }
    }

    private boolean judge(String body, String suffix) {
        int validLines = 0;
        boolean malformed = false;
        for (String line : body.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            Matcher matcher = LINE.matcher(line.strip());
            if (!matcher.matches()) {
                malformed = true;
                continue;
            }
            validLines++;
            // 패딩 행은 횟수가 0이다 — 일치해도 유출이 아니다.
            if (matcher.group(1).equalsIgnoreCase(suffix) && Long.parseLong(matcher.group(2)) > 0) {
                return true;
            }
        }
        if (malformed || validLines == 0) {
            log.warn("유출 비밀번호 검사 불가 — 통과시킵니다 (응답 형식 오류)");
        }
        return false;
    }

    private static String sha1Hex(String value) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-1").digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest).toUpperCase(Locale.ROOT);
    }

    private static void cancel(CompletableFuture<?> future) {
        if (future != null) {
            future.cancel(true);
        }
    }
}
