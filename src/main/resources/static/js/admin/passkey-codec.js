/*
 * 패스키(WebAuthn) 브라우저 변환 — 서버 옵션 JSON(base64url) ↔ navigator.credentials의 ArrayBuffer (PLAN-passkey-second-factor.md 결정 7).
 * 형식은 Spring Security 7.0.7의 spring-security-webauthn.js와 같다(서버 역직렬화가 그 형식을 받는다).
 * 브라우저의 PublicKeyCredential.parse*FromJSON은 지원 범위가 고르지 않아 쓰지 않는다.
 */
(function (global) {
    "use strict";

    function encode(buffer) {
        const bytes = new Uint8Array(buffer);
        let binary = "";
        for (let i = 0; i < bytes.length; i++) {
            binary += String.fromCharCode(bytes[i]);
        }
        return global.btoa(binary).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
    }

    function decode(base64url) {
        const base64 = base64url.replace(/-/g, "+").replace(/_/g, "/");
        const binary = global.atob(base64);
        const bytes = new Uint8Array(binary.length);
        for (let i = 0; i < binary.length; i++) {
            bytes[i] = binary.charCodeAt(i);
        }
        return bytes.buffer;
    }

    function decodeDescriptors(list) {
        return (list || []).map(function (descriptor) {
            return Object.assign({}, descriptor, { id: decode(descriptor.id) });
        });
    }

    /** 브라우저가 패스키를 지원하는가 */
    function supported() {
        return !!(global.PublicKeyCredential && global.navigator && global.navigator.credentials);
    }

    /** 등록: 서버 옵션 → navigator.credentials.create → 서버가 받는 JSON 객체 */
    async function create(options) {
        const publicKey = Object.assign({}, options, {
            challenge: decode(options.challenge),
            user: Object.assign({}, options.user, { id: decode(options.user.id) }),
            excludeCredentials: decodeDescriptors(options.excludeCredentials)
        });
        const credential = await global.navigator.credentials.create({ publicKey: publicKey });
        const response = credential.response;
        return {
            id: credential.id,
            rawId: encode(credential.rawId),
            response: {
                attestationObject: encode(response.attestationObject),
                clientDataJSON: encode(response.clientDataJSON),
                transports: response.getTransports ? response.getTransports() : []
            },
            type: credential.type,
            clientExtensionResults: credential.getClientExtensionResults(),
            authenticatorAttachment: credential.authenticatorAttachment
        };
    }

    /**
     * 조건부 UI(자동 완성)를 쓸 수 있는가 — 판정 함수가 없거나 예외면 false (PLAN-passkey-conditional-ui.md 결정 4)
     */
    async function conditionalAvailable() {
        const credentialType = global.PublicKeyCredential;
        if (!credentialType || typeof credentialType.isConditionalMediationAvailable !== "function") {
            return false;
        }
        try {
            return (await credentialType.isConditionalMediationAvailable()) === true;
        } catch (error) {
            return false;
        }
    }

    /**
     * 인증: 서버 옵션 → navigator.credentials.get → 서버가 받는 JSON 객체.
     * request(선택)는 {mediation, signal} — 조건부 요청과 그 취소에 쓴다(결정 4). 생략하면 기존과 같은 모달 요청이다.
     */
    async function get(options, request) {
        const publicKey = Object.assign({}, options, {
            challenge: decode(options.challenge),
            allowCredentials: decodeDescriptors(options.allowCredentials)
        });
        const credential = await global.navigator.credentials.get(Object.assign({}, request || {}, { publicKey: publicKey }));
        const response = credential.response;
        return {
            id: credential.id,
            rawId: encode(credential.rawId),
            response: {
                authenticatorData: encode(response.authenticatorData),
                clientDataJSON: encode(response.clientDataJSON),
                signature: encode(response.signature),
                userHandle: response.userHandle ? encode(response.userHandle) : undefined
            },
            type: credential.type,
            clientExtensionResults: credential.getClientExtensionResults(),
            authenticatorAttachment: credential.authenticatorAttachment
        };
    }

    global.CmsPasskey = { supported: supported, conditionalAvailable: conditionalAvailable, create: create, get: get };
})(window);
