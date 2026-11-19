/*
 * 패스키 제출 — 두 화면이 같은 요소 ID로 쓴다:
 *  - 2단계 인증 코드 화면의 "패스키로 인증"(PLAN-passkey-second-factor.md 결정 4·7) — 별도 폼이라 코드 폼의 required가 막지 않는다(리뷰 1-6)
 *  - 로그인 화면의 "패스키로 로그인"(PLAN-passkey-login.md 결정 7) — 비밀번호 폼과 따로 제출한다
 * 서버가 화면에 그린 옵션(data-options)으로 navigator.credentials.get을 부르고 결과를 그 폼으로 제출한다.
 * 사용자가 브라우저 창을 취소하면 제출하지 않는다.
 */
(function () {
    "use strict";
    const button = document.getElementById("btnPasskeyLogin");
    const form = document.getElementById("passkeyForm");
    if (!button || !form) {
        return;
    }
    const block = document.getElementById("passkeyBlock");
    const message = document.getElementById("passkeyClientError");
    if (!window.CmsPasskey || !window.CmsPasskey.supported()) {
        block.classList.add("d-none");
        return;
    }

    button.addEventListener("click", async function () {
        message.classList.add("d-none");
        button.disabled = true;
        let credential;
        try {
            const options = JSON.parse(form.getAttribute("data-options"));
            credential = await window.CmsPasskey.get(options);
        } catch (error) {
            // 취소·시간 초과·이 기기에 패스키 없음 — 서버로 보내지 않는다(실패 횟수 아님)
            message.classList.remove("d-none");
            button.disabled = false;
            return;
        }
        document.getElementById("passkeyCredential").value = JSON.stringify(credential);
        form.submit();
    });
})();
