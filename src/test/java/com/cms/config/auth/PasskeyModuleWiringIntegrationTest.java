package com.cms.config.auth;

import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.http.converter.HttpMessageConverter;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F1(PLAN-passkey-second-factor.md 결정 2): {@code spring-security-webauthn}을 클래스패스에 넣어도 WebAuthn 필터·엔드포인트·
 * 두 번째 프로바이더가 생기지 않는다 — 생기면 패스키 로그인이 성공 처리기 재확인·2단계 중간 상태·실패 잠금을 우회한다.
 * 응답 코드만 보면 인가 단계 차단과 구분되지 않으므로 실제 필터 체인·인증 관리자·핸들러 매핑을 직접 본다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
class PasskeyModuleWiringIntegrationTest extends MariaDbContainerSupport {

    private static final String WEBAUTHN_PACKAGE = "org.springframework.security.web.webauthn";

    @Autowired FilterChainProxy filterChainProxy;
    @Autowired ApplicationContext context;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping;
    @Autowired RequestMappingHandlerAdapter handlerAdapter;
    @Autowired JsonMapper jsonMapper;

    @Test
    @DisplayName("보안 필터 체인에 WebAuthn 패키지의 필터가 없다")
    void noWebAuthnFilters() {
        List<String> filters = new ArrayList<>();
        for (SecurityFilterChain chain : filterChainProxy.getFilterChains()) {
            for (Filter filter : chain.getFilters()) {
                filters.add(filter.getClass().getName());
            }
        }
        assertThat(filters).isNotEmpty();
        assertThat(filters).noneMatch(name -> name.startsWith(WEBAUTHN_PACKAGE));
    }

    @Test
    @DisplayName("폼 로그인 인증 관리자(부모 포함)의 프로바이더는 TotpAwareAuthenticationProvider 하나뿐이고 그 타입 빈도 하나다")
    void onlyTotpAwareProvider() {
        UsernamePasswordAuthenticationFilter formLogin = filterChainProxy.getFilterChains().stream()
                .flatMap(chain -> chain.getFilters().stream())
                .filter(UsernamePasswordAuthenticationFilter.class::isInstance)
                .map(UsernamePasswordAuthenticationFilter.class::cast)
                .findFirst()
                .orElseThrow();
        AuthenticationManager manager = ReflectionTestUtils.invokeMethod(formLogin, "getAuthenticationManager");

        List<Class<?>> providers = new ArrayList<>();
        List<String> managers = new ArrayList<>();
        while (manager != null) {
            managers.add(manager.getClass().getName());
            if (manager instanceof ProviderManager providerManager) {
                providerManager.getProviders().forEach(provider -> providers.add(provider.getClass()));
                manager = (AuthenticationManager) ReflectionTestUtils.getField(providerManager, "parent");
            } else {
                // 전역 관리자는 지연 위임 래퍼(AuthenticationConfiguration.AuthenticationManagerDelegator)로 온다 — 위임 대상을 따라간다
                manager = (AuthenticationManager) ReflectionTestUtils.getField(manager, "delegate");
            }
        }
        assertThat(managers).as("인증 관리자 사슬").isNotEmpty();
        // 익명 인증 프로바이더는 익명 필터용 기본 구성이다(비밀번호·패스키를 판정하지 않는다)
        assertThat(providers)
                .filteredOn(type -> type != org.springframework.security.authentication.AnonymousAuthenticationProvider.class)
                .containsExactly(TotpAwareAuthenticationProvider.class);
        assertThat(providers).noneMatch(type -> type.getName().startsWith(WEBAUTHN_PACKAGE));
        assertThat(context.getBeansOfType(AuthenticationProvider.class)).hasSize(1);
    }

    @Test
    @DisplayName("MVC 핸들러 매핑에 /webauthn/** · /login/webauthn 경로가 없다")
    void noWebAuthnHandlers() {
        List<String> patterns = handlerMapping.getHandlerMethods().keySet().stream()
                .map(RequestMappingInfo::getPatternValues)
                .flatMap(java.util.Set::stream)
                .toList();
        assertThat(patterns).noneMatch(pattern -> pattern.startsWith("/webauthn") || pattern.startsWith("/login/webauthn"));
    }

    @Test
    @DisplayName("전이 의존성(jackson-dataformat-cbor)이 MVC에 CBOR 변환기를 더하지 않는다 — JSON 입력 계약 밖의 본문 형식을 받지 않는다")
    void noCborMessageConverter() {
        List<String> converters = handlerAdapter.getMessageConverters().stream()
                .map(HttpMessageConverter::getClass)
                .map(Class::getName)
                .toList();
        assertThat(converters).isNotEmpty();
        assertThat(converters).noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("cbor"));
    }

    @Test
    @DisplayName("전역 JsonMapper에 WebAuthn Jackson 모듈이 등록되지 않는다(전용 매퍼만 쓴다)")
    void globalMapperHasNoWebAuthnModule() {
        assertThat(jsonMapper.registeredModules())
                .noneMatch(module -> module.getClass().getName().startsWith(WEBAUTHN_PACKAGE));
    }
}
