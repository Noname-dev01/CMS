package com.cms.config;

import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.EndpointId;
import org.springframework.boot.actuate.endpoint.web.ExposableWebEndpoint;
import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * management.endpoints.web.exposure.include: health (application.yml 공통값,
 * PLAN-prod-profile.md 결정 2)가 실제로 적용돼 health 외 엔드포인트는 등록조차 되지 않는지
 * 검증한다. SecurityConfig의 denyAll()은 HTTP 응답만으로는 "미등록"과 "등록됐지만 거부"를
 * 구분하지 못하므로(결정 3), 이 사실은 HTTP가 아니라 WebEndpointsSupplier로 직접 확인한다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
class ActuatorExposureTest extends MariaDbContainerSupport {

    @Autowired
    WebEndpointsSupplier webEndpointsSupplier;

    @Test
    @DisplayName("실제로 등록된 웹 actuator 엔드포인트는 health 하나뿐이다")
    void onlyHealthEndpointRegistered() {
        Set<String> endpointIds = webEndpointsSupplier.getEndpoints().stream()
                .map(ExposableWebEndpoint::getEndpointId)
                .map(EndpointId::toString)
                .collect(Collectors.toSet());

        assertThat(endpointIds).containsExactly("health");
    }

    @Test
    @DisplayName("application-prod.yml은 공통 actuator 노출 설정을 오버라이드하지 않는다")
    void prodYamlDoesNotOverrideManagementSettings() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load("application-prod",
                new ClassPathResource("application-prod.yml"));

        for (PropertySource<?> source : sources) {
            EnumerablePropertySource<?> enumerable = (EnumerablePropertySource<?>) source;
            for (String name : enumerable.getPropertyNames()) {
                assertThat(name).doesNotStartWith("management.");
            }
        }
    }
}
