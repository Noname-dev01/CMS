package com.cms.config;

import org.apache.catalina.Container;
import org.apache.catalina.Valve;
import org.apache.catalina.valves.ErrorReportValve;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 커넥터 단계에서 거절되는 요청(잘못된 퍼센트 인코딩 등)을 보내기 위한 원시 HTTP/1.1 요청 도우미.
 *
 * <p>{@code java.net.http.HttpClient}는 {@code URI.create("/main/%zz")}에서 클라이언트 쪽 예외를 던져 이런 요청을
 * 보낼 수 없고, MockMvc는 Tomcat 커넥터를 거치지 않는다(PLAN-tomcat-error-report.md 쟁점 3).
 * 요청 언어를 영어로 고정한다 — {@link ErrorReportValve}가 {@code request.getLocales()}로 제목을 번역하기 때문이다.
 */
final class RawHttpRequests {

    /** 이슈 #223의 재현 경로 — 모두 Tomcat이 URI 디코딩·정규화 단계에서 400으로 거절한다. */
    static final List<String> MALFORMED_PATHS = List.of(
            "/main/%zz", "/admin/%zz", "/main/%00", "/main/a%2fb", "/main/..%2f..%2fadmin", "/main/boards/%");

    private static final int TIMEOUT_MILLIS = 5_000;

    private RawHttpRequests() {
    }

    /** 응답 전체(상태 줄·헤더·본문)를 문자열로 돌려준다. */
    static String get(int port, String rawPath) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), TIMEOUT_MILLIS);
            socket.setSoTimeout(TIMEOUT_MILLIS);
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + rawPath + " HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Accept: text/html\r\n"
                    + "Accept-Language: en\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = socket.getInputStream();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 잘못된 인코딩 요청 6종이 모두 Tomcat 단계 400이고 본문에 서버 종류·버전·내부 메시지가 없음을 단언한다.
     * 양성 단언(상태 줄 400·{@code HTTP Status 400} 제목)을 먼저 해 연결 실패·빈 본문으로 통과하지 않게 한다.
     */
    static void assertMalformedRequestsHideServerInfo(int port) throws IOException {
        for (String path : MALFORMED_PATHS) {
            String response = get(port, path);

            assertThat(response).as(path).startsWith("HTTP/1.1 400");
            assertThat(response).as(path).contains("HTTP Status 400");
            assertThat(response).as(path)
                    .doesNotContain("Apache Tomcat")
                    .doesNotContain("Tomcat/")
                    .doesNotContain("Invalid URI");
        }
    }

    /** 실행 중인 내장 Tomcat의 모든 Host 파이프라인에 있는 {@link ErrorReportValve}(하위 클래스 포함). */
    static List<ErrorReportValve> errorReportValves(WebServerApplicationContext context) {
        TomcatWebServer webServer = (TomcatWebServer) context.getWebServer();
        List<ErrorReportValve> valves = new ArrayList<>();
        for (Container host : webServer.getTomcat().getEngine().findChildren()) {
            for (Valve valve : host.getPipeline().getValves()) {
                if (valve instanceof ErrorReportValve errorReportValve) {
                    valves.add(errorReportValve);
                }
            }
        }
        return valves;
    }
}
