package com.cms.admin.member.passkey;

import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.AuthenticatorAttestationResponse;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.jackson.WebauthnJacksonModule;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * WebAuthn JSON 변환 전용 매퍼(PLAN-passkey-second-factor.md 결정 2). 앱 전역 매퍼에는 WebAuthn 모듈을 넣지 않는다
 * (실측: 전역 매퍼에 자동 등록되지 않음 — {@code PasskeyModuleWiringIntegrationTest}).
 * 컨트롤러 경계는 {@link JsonNode}다 — 응답 옵션은 이 매퍼로 쓴 뒤 노드로 싣고, 요청 자격은 노드를 문자열로 쓴 뒤 이 매퍼로 읽는다.
 */
@Component
public class PasskeyJson {

    private static final TypeReference<PublicKeyCredential<AuthenticatorAssertionResponse>> ASSERTION = new TypeReference<>() {
    };
    private static final TypeReference<PublicKeyCredential<AuthenticatorAttestationResponse>> ATTESTATION = new TypeReference<>() {
    };

    private final JsonMapper mapper = JsonMapper.builder().addModule(new WebauthnJacksonModule()).build();

    /** 옵션 객체를 브라우저가 받는 JSON 문자열로 쓴다. */
    public String write(Object value) {
        return mapper.writeValueAsString(value);
    }

    /** 옵션 객체를 응답에 실을 노드로 만든다. */
    public JsonNode toNode(Object value) {
        return mapper.readTree(write(value));
    }

    /** 브라우저 어설션 JSON을 읽는다. 형식이 틀리면 {@link IllegalArgumentException}. */
    public PublicKeyCredential<AuthenticatorAssertionResponse> readAssertion(String json) {
        return read(json, ASSERTION);
    }

    /** 브라우저 등록(attestation) JSON을 읽는다. 형식이 틀리면 {@link IllegalArgumentException}. */
    public PublicKeyCredential<AuthenticatorAttestationResponse> readAttestation(String json) {
        return read(json, ATTESTATION);
    }

    private <T> T read(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("패스키 응답이 비어 있다");
        }
        try {
            T value = mapper.readValue(json, type);
            if (value == null) {
                throw new IllegalArgumentException("패스키 응답이 비어 있다");
            }
            return value;
        } catch (JacksonException e) {
            throw new IllegalArgumentException("패스키 응답 형식 오류", e);
        }
    }
}
