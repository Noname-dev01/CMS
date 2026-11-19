package com.cms.admin.member.passkey;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** 결정 6: 패스키 설정 검증 — 켠 상태의 잘못된 설정은 기동 실패, rp-id가 비면 꺼짐. */
class PasskeyConfigTest {

    private static PasskeyProperties properties(String rpId, String... origins) {
        PasskeyProperties properties = new PasskeyProperties();
        properties.setRpId(rpId);
        properties.setAllowedOrigins(List.of(origins));
        return properties;
    }

    @Test
    @DisplayName("rp-id가 비어 있으면 꺼진 감싸개를 만든다(origin 검사 없음)")
    void disabledWhenRpIdBlank() {
        PasskeyRelyingParty party = new PasskeyConfig().passkeyRelyingParty(properties(" "), null, null, null, Clock.systemUTC());
        assertThat(party.isEnabled()).isFalse();
        assertThatThrownBy(() -> party.requestOptions("x")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("올바른 설정이면 켜진다 — https 하위 도메인, localhost http")
    void enabled() {
        assertThat(PasskeyConfig.validateOrigins("example.com", properties("example.com", "https://admin.example.com", "https://example.com:8443")))
                .containsExactlyInAnyOrder("https://admin.example.com", "https://example.com:8443");
        assertThat(PasskeyConfig.validateOrigins("localhost", properties("localhost", "http://localhost:8080")))
                .containsExactly("http://localhost:8080");
        assertThat(new PasskeyConfig().passkeyRelyingParty(properties("localhost", "http://localhost:8080"),
                mock(com.cms.admin.member.repository.MemberRepository.class),
                mock(com.cms.admin.member.repository.MemberPasskeyUserRepository.class),
                mock(com.cms.admin.member.repository.MemberPasskeyRepository.class), Clock.systemUTC()).isEnabled()).isTrue();
    }

    @Test
    @DisplayName("켰는데 허용 origin이 없으면 기동 실패")
    void noOrigins() {
        assertThatThrownBy(() -> PasskeyConfig.validateOrigins("example.com", properties("example.com")))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://example.com",            // https 아님(localhost 아님)
            "https://evil.com",              // rp-id와 다른 호스트
            "https://notexample.com",        // 접미사이지만 점 경계 아님
            "https://example.com/admin",     // 경로
            "https://example.com?x=1",       // 쿼리
            "https://user@example.com",      // 사용자 정보
            "example.com",                   // scheme 없음
            "https://exa mple.com"           // 형식 오류
    })
    @DisplayName("잘못된 origin은 기동 실패")
    void invalidOrigin(String origin) {
        assertThatThrownBy(() -> PasskeyConfig.validateOrigins("example.com", properties("example.com", origin)))
                .isInstanceOf(IllegalStateException.class);
    }
}
