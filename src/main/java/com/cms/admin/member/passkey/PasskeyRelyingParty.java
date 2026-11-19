package com.cms.admin.member.passkey;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.UserVerificationRequirement;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.management.ImmutablePublicKeyCredentialCreationOptionsRequest;
import org.springframework.security.web.webauthn.management.ImmutablePublicKeyCredentialRequestOptionsRequest;
import org.springframework.security.web.webauthn.management.ImmutableRelyingPartyRegistrationRequest;
import org.springframework.security.web.webauthn.management.RelyingPartyAuthenticationRequest;
import org.springframework.security.web.webauthn.management.RelyingPartyPublicKey;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;

import java.time.Duration;
import java.util.List;

/**
 * WebAuthn 검증 연산(Spring Security 7.0.7 {@code Webauthn4JRelyingPartyOperations})의 얇은 감싸개 — 기능 꺼짐을 표현하고,
 * 라이브러리가 요구하는 {@code Authentication}을 회원 아이디로 만든다(이름만 쓰인다 — {@code findByUsername}).
 *
 * <p><b>모든 메서드는 그 회원의 {@code member} 행 잠금 트랜잭션 안에서 부른다</b> — 연산이 저장소 어댑터의 {@code save}를 부른다
 * (옵션 발급의 핸들 생성, 등록, 인증의 서명 횟수 갱신 — PLAN-passkey-second-factor.md 결정 3 잠금 계약).
 */
public class PasskeyRelyingParty {

    /** 라이브러리 옵션 타임아웃과 같은 5분 */
    private static final Duration LOGIN_OPTIONS_TIMEOUT = Duration.ofMinutes(5);

    private final WebAuthnRelyingPartyOperations operations;
    private final String rpId;

    PasskeyRelyingParty(WebAuthnRelyingPartyOperations operations, String rpId) {
        this.operations = operations;
        this.rpId = rpId;
    }

    static PasskeyRelyingParty enabled(WebAuthnRelyingPartyOperations operations, String rpId) {
        return new PasskeyRelyingParty(operations, rpId);
    }

    static PasskeyRelyingParty disabled() {
        return new PasskeyRelyingParty(null, null);
    }

    public boolean isEnabled() {
        return operations != null;
    }

    /** 등록 옵션 — 핸들이 없으면 만들어 저장한다(라이브러리 동작). 기존 유효 자격은 {@code excludeCredentials}. */
    public PublicKeyCredentialCreationOptions creationOptions(String userId) {
        return required().createPublicKeyCredentialCreationOptions(
                new ImmutablePublicKeyCredentialCreationOptionsRequest(asAuthentication(userId)));
    }

    /** 공개키 검증 후 저장한다. 검증 실패는 라이브러리 예외로 던진다. */
    public CredentialRecord register(PublicKeyCredentialCreationOptions options,
                                     PublicKeyCredential<org.springframework.security.web.webauthn.api.AuthenticatorAttestationResponse> credential,
                                     String label) {
        return required().registerCredential(new ImmutableRelyingPartyRegistrationRequest(options, new RelyingPartyPublicKey(credential, label)));
    }

    /** 2단계 어설션 옵션 — 그 회원의 유효한 자격만 {@code allowCredentials}에 담긴다. */
    public PublicKeyCredentialRequestOptions requestOptions(String userId) {
        return required().createCredentialRequestOptions(new ImmutablePublicKeyCredentialRequestOptionsRequest(asAuthentication(userId)));
    }

    /**
     * 패스키 단독 로그인 옵션(PLAN-passkey-login.md 결정 2) — 사용자를 미리 모르므로 {@code allowCredentials}를 비우고(브라우저가 상주 패스키를 고른다)
     * 비밀번호를 대신하므로 사용자 확인을 <b>필수</b>로 한다. 라이브러리 옵션은 UV가 {@code PREFERRED} 고정이고 바꾸는 수단이 연산 객체 전역 설정뿐이라
     * (2단계 옵션까지 바뀐다) 여기서 직접 만든다. DB에 접근하지 않는다. 검증({@link #authenticate})은 이 옵션의 {@code REQUIRED}를 보고 UV를 강제한다.
     */
    public PublicKeyCredentialRequestOptions discoverableRequestOptions() {
        required();
        return PublicKeyCredentialRequestOptions.builder()
                .challenge(Bytes.random())
                .rpId(rpId)
                .timeout(LOGIN_OPTIONS_TIMEOUT)
                .userVerification(UserVerificationRequirement.REQUIRED)
                .allowCredentials(List.of())
                .build();
    }

    /** 어설션 검증(챌린지·origin·rpId·서명·allowCredentials). 성공하면 서명 횟수를 저장하고 자격의 user entity를 돌려준다. */
    public PublicKeyCredentialUserEntity authenticate(PublicKeyCredentialRequestOptions options,
                                                      PublicKeyCredential<AuthenticatorAssertionResponse> credential) {
        return required().authenticate(new RelyingPartyAuthenticationRequest(options, credential));
    }

    private WebAuthnRelyingPartyOperations required() {
        if (operations == null) {
            throw new IllegalStateException("패스키 기능이 꺼져 있다(cms.passkey.rp-id)");
        }
        return operations;
    }

    private static UsernamePasswordAuthenticationToken asAuthentication(String userId) {
        // 라이브러리는 인증 여부와 getName()만 본다 — 권한·자격 증명은 쓰지 않는다
        return UsernamePasswordAuthenticationToken.authenticated(userId, null, AuthorityUtils.NO_AUTHORITIES);
    }
}
