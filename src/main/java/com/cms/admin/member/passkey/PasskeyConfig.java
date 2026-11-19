package com.cms.admin.member.passkey;

import com.cms.admin.member.repository.MemberPasskeyRepository;
import com.cms.admin.member.repository.MemberPasskeyUserRepository;
import com.cms.admin.member.repository.MemberRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRpEntity;
import org.springframework.security.web.webauthn.management.Webauthn4JRelyingPartyOperations;

import java.net.URI;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 패스키 빈(PLAN-passkey-second-factor.md 결정 2·6). <b>{@code AuthenticationProvider}를 만들지 않고 {@code http.webAuthn()}도 부르지 않는다</b> —
 * Spring의 WebAuthn 필터·프로바이더는 성공 처리기 재확인·2단계 중간 상태·실패 잠금을 거치지 않는다. 검증 연산만 {@link PasskeyRelyingParty}로 감싸 쓴다.
 * 설정 검증이 빈 생성 시점에 일어나므로 켠 상태의 설정이 틀리면 기동이 실패한다.
 */
@Configuration
@EnableConfigurationProperties(PasskeyProperties.class)
public class PasskeyConfig {

    @Bean
    public PasskeyRelyingParty passkeyRelyingParty(PasskeyProperties properties, MemberRepository memberRepository,
                                                   MemberPasskeyUserRepository userRepository, MemberPasskeyRepository passkeyRepository,
                                                   Clock clock) {
        if (!properties.isEnabled()) {
            return PasskeyRelyingParty.disabled();
        }
        String rpId = properties.getRpId().trim();
        Set<String> origins = validateOrigins(rpId, properties);
        PublicKeyCredentialRpEntity rp = PublicKeyCredentialRpEntity.builder()
                .id(rpId)
                .name(properties.getRpName())
                .build();
        Webauthn4JRelyingPartyOperations operations = new Webauthn4JRelyingPartyOperations(
                new PasskeyUserEntityRepository(memberRepository, userRepository, clock),
                new PasskeyCredentialRepository(userRepository, passkeyRepository, clock),
                rp, origins);
        return PasskeyRelyingParty.enabled(operations, rpId);
    }

    static Set<String> validateOrigins(String rpId, PasskeyProperties properties) {
        if (properties.getAllowedOrigins() == null || properties.getAllowedOrigins().isEmpty()) {
            throw new IllegalStateException("cms.passkey.allowed-origins가 비어 있다 — rp-id를 설정하면 허용 origin이 1개 이상 필요하다");
        }
        String normalizedRpId = rpId.toLowerCase(Locale.ROOT);
        Set<String> origins = new LinkedHashSet<>();
        for (String raw : properties.getAllowedOrigins()) {
            String origin = raw == null ? "" : raw.trim();
            URI uri;
            try {
                uri = URI.create(origin);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("cms.passkey.allowed-origins 형식 오류: " + origin, e);
            }
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || (uri.getPath() != null && !uri.getPath().isEmpty())
                    || uri.getQuery() != null || uri.getFragment() != null || uri.getUserInfo() != null) {
                throw new IllegalStateException("cms.passkey.allowed-origins는 scheme://host[:port] 형식이어야 한다: " + origin);
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            boolean localhost = normalizedHost.equals("localhost");
            if (!scheme.equals("https") && !(localhost && scheme.equals("http"))) {
                throw new IllegalStateException("cms.passkey.allowed-origins는 https만 허용한다(localhost만 http 허용): " + origin);
            }
            if (!normalizedHost.equals(normalizedRpId) && !normalizedHost.endsWith("." + normalizedRpId)) {
                throw new IllegalStateException("cms.passkey.allowed-origins의 호스트가 rp-id(" + rpId + ")와 맞지 않는다: " + origin);
            }
            origins.add(origin);
        }
        return Set.copyOf(origins);
    }
}
