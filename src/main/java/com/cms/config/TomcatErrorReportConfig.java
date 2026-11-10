package com.cms.config;

import org.apache.catalina.Container;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.Valve;
import org.apache.catalina.valves.ErrorReportValve;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 컨테이너 단계 오류 화면(Tomcat {@link ErrorReportValve})에서 서버 종류·버전과 상세 메시지를 항상 숨긴다(이슈 #223).
 *
 * <p>잘못된 퍼센트 인코딩 같은 요청은 Tomcat이 URI 디코딩 단계에서 거절해 Spring(필터·{@code CustomErrorController})까지
 * 오지 않고, 본문은 Host 파이프라인의 {@link ErrorReportValve}가 그린다. Boot 4.0.8도 이 밸브를 숨기지만
 * {@code spring.web.error.include-stacktrace=never}일 때만이다 — devtools(bootRun)가 이 값을 {@code always}로 바꿔
 * {@code Apache Tomcat/버전}과 {@code Invalid URI: [...]}가 노출됐다. 이 설정은 그 조건과 무관하게 숨긴다.
 *
 * <p>Host 시작 직후({@link Lifecycle#AFTER_START_EVENT}) 파이프라인의 모든 밸브를 고친다. 이 시점에는 Boot가 넣은 밸브와
 * {@code StandardHost}가 넣은 기본 밸브가 모두 들어와 있고 커넥터는 아직 요청을 받지 않는다. Boot는 기존 밸브를 확인하지 않고
 * 자기 밸브를 추가하므로, 커스터마이저 실행 시점에 고치거나 추가하면 실행 순서에 따라 밸브가 빠지거나 둘이 된다
 * (PLAN-tomcat-error-report.md 쟁점 1). 밸브는 추가하지 않는다.
 */
@Configuration
public class TomcatErrorReportConfig {

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> errorReportValveHardening() {
        return factory -> factory.addContextCustomizers(context -> {
            Container host = context.getParent();
            host.addLifecycleListener(event -> {
                if (Lifecycle.AFTER_START_EVENT.equals(event.getType())) {
                    hideServerInfo(host);
                }
            });
        });
    }

    private static void hideServerInfo(Container host) {
        for (Valve valve : host.getPipeline().getValves()) {
            if (valve instanceof ErrorReportValve errorReportValve) {
                errorReportValve.setShowServerInfo(false);
                errorReportValve.setShowReport(false);
            }
        }
    }
}
