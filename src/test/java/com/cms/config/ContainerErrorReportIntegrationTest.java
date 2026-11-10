package com.cms.config;

import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import org.apache.catalina.valves.ErrorReportValve;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.core.env.Environment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring까지 오지 않는 컨테이너 단계 오류(잘못된 퍼센트 인코딩 400)의 본문에 서버 종류·버전·내부 메시지가 없음을
 * <b>Boot의 조건부 보호가 꺼진 구성</b>에서 실서버로 고정한다(이슈 #223, PLAN-tomcat-error-report.md).
 *
 * <p>Boot 4.0.8은 {@code spring.web.error.include-stacktrace=never}일 때만 Host에 서버 정보를 숨긴 {@link ErrorReportValve}를
 * 넣는다. devtools는 이 값을 {@code always}로 바꿔(bootRun에서만) 기본 밸브가 {@code Apache Tomcat/버전}과
 * {@code Invalid URI: [...]}를 그대로 보여 줬다. 이 시험은 그 값을 {@code always}로 두어 devtools 상황을 모사한다 —
 * {@code TomcatErrorReportConfig}가 없으면 실패한다. 기본 구성(운영과 같음)은 {@code DefaultDenyErrorDispatchIntegrationTest}가 고정한다.
 */
@SpringBootTest(classes = CmsTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.web.error.include-stacktrace=always")
class ContainerErrorReportIntegrationTest extends MariaDbContainerSupport {

    @LocalServerPort
    int port;

    @Autowired
    WebServerApplicationContext context;

    @Autowired
    Environment environment;

    @Test
    @DisplayName("include-stacktrace=always(devtools 모사)여도 잘못된 인코딩 요청 6종의 400 본문에 서버 버전·내부 메시지가 없다")
    void malformedRequests_hideServerInfo_evenWhenBootProtectionIsOff() throws Exception {
        assertThat(environment.getProperty("spring.web.error.include-stacktrace")).isEqualTo("always");

        RawHttpRequests.assertMalformedRequestsHideServerInfo(port);
    }

    @Test
    @DisplayName("include-stacktrace=always여도 Host의 오류 보고 밸브는 1개이고 서버 정보·상세 보고가 꺼져 있다")
    void errorReportValves_areHardened_evenWhenBootProtectionIsOff() {
        List<ErrorReportValve> valves = RawHttpRequests.errorReportValves(context);

        assertThat(valves).hasSize(1);
        assertThat(valves).allSatisfy(valve -> {
            assertThat(valve.isShowServerInfo()).isFalse();
            assertThat(valve.isShowReport()).isFalse();
        });
    }

    @Test
    @DisplayName("Spring까지 오는 400(방화벽이 거절한 ;jsessionid)은 그대로 프로젝트 오류 화면이다")
    void firewallRejectedRequest_stillRendersProjectErrorPage() throws Exception {
        String response = RawHttpRequests.get(port, "/main/;jsessionid=x");

        assertThat(response).startsWith("HTTP/1.1 400");
        assertThat(response).contains("/css/error.css").doesNotContain("HTTP Status 400");
    }
}
