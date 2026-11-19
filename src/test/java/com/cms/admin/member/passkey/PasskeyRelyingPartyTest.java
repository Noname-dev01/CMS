package com.cms.admin.member.passkey;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.api.UserVerificationRequirement;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** 패스키 단독 로그인 옵션(PLAN-passkey-login.md 결정 2) — 연산 객체 전역 설정을 건드리지 않고 직접 만든다. */
class PasskeyRelyingPartyTest {

    @Test
    @DisplayName("F4: 로그인 옵션은 UV REQUIRED·allowCredentials 비움(discoverable)·rp-id·5분·매번 다른 챌린지이고 연산 객체를 부르지 않는다")
    void discoverableRequestOptions() {
        WebAuthnRelyingPartyOperations operations = mock(WebAuthnRelyingPartyOperations.class);
        PasskeyRelyingParty relyingParty = PasskeyRelyingParty.enabled(operations, "cms.example.com");

        PublicKeyCredentialRequestOptions first = relyingParty.discoverableRequestOptions();
        PublicKeyCredentialRequestOptions second = relyingParty.discoverableRequestOptions();

        assertThat(first.getUserVerification()).isEqualTo(UserVerificationRequirement.REQUIRED);
        assertThat(first.getAllowCredentials()).isEmpty();
        assertThat(first.getRpId()).isEqualTo("cms.example.com");
        assertThat(first.getTimeout()).isEqualTo(Duration.ofMinutes(5));
        assertThat(first.getChallenge().getBytes()).hasSizeGreaterThanOrEqualTo(16);
        assertThat(first.getChallenge()).isNotEqualTo(second.getChallenge());
        verifyNoInteractions(operations);
    }

    @Test
    @DisplayName("기능 꺼짐이면 옵션을 만들지 않는다")
    void disabledRejects() {
        assertThatThrownBy(() -> PasskeyRelyingParty.disabled().discoverableRequestOptions()).isInstanceOf(IllegalStateException.class);
    }
}
