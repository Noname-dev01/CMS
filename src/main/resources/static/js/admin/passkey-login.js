/*
 * 패스키 제출 — 두 화면이 같은 요소 ID로 쓴다:
 *  - 2단계 인증 코드 화면의 "패스키로 인증"(PLAN-passkey-second-factor.md 결정 4·7) — 별도 폼이라 코드 폼의 required가 막지 않는다(리뷰 1-6)
 *  - 로그인 화면의 "패스키로 로그인"(PLAN-passkey-login.md 결정 7) — 비밀번호 폼과 따로 제출한다
 * 서버가 화면에 그린 옵션(data-options)으로 navigator.credentials.get을 부르고 결과를 그 폼으로 제출한다.
 * 사용자가 브라우저 창을 취소하면 제출하지 않는다.
 *
 * 로그인 화면(폼에 data-conditional="true")은 조건부 UI도 쓴다(PLAN-passkey-conditional-ui.md 결정 2·3) — 페이지를 열 때 조건부 요청을 시작해
 * 아이디 칸 자동 완성에서 패스키를 고르면 바로 제출한다. 두 요청은 화면의 챌린지 하나를 나눠 쓰고(결정 1 — 서버가 제출 때 꺼내며 지워 검증은 최대 1회),
 * 대기 요청은 언제나 많아야 하나다. 상태는 phase 하나이고, 비동기 지점(지원 판정·취소 대기·결과 도착) 뒤에는 phase를 다시 보고 진행한다.
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

    // 뒤로 가기 등으로 BFCache에서 복원된 화면은 챌린지가 이미 소비·교체됐을 수 있고 제출 뒤 상태로 굳어 있다 — 새로 받는다(결정 2-8)
    window.addEventListener("pageshow", function (event) {
        if (event.persisted) {
            window.location.reload();
        }
    });

    const conditional = form.getAttribute("data-conditional") === "true";
    /** idle | conditional(조건부 요청 대기) | modal(버튼 요청 진행) | submitted(제출함 — 되돌리지 않는다) */
    let phase = "idle";
    /** 대기 중인 조건부 요청 {controller, done} — done은 결과 처리까지 끝나면 해결되고 거부되지 않는다 */
    let pending = null;

    function options() {
        return JSON.parse(form.getAttribute("data-options"));
    }

    function submit(credential) {
        phase = "submitted";
        document.getElementById("passkeyCredential").value = JSON.stringify(credential);
        form.submit();
    }

    async function startConditional() {
        const available = await window.CmsPasskey.conditionalAvailable();
        if (!available || phase !== "idle") {
            return; // 미지원이거나 그 사이 버튼·비밀번호 제출이 시작됐다
        }
        const controller = new AbortController();
        const current = { controller: controller, done: null };
        let request;
        try {
            request = window.CmsPasskey.get(options(), { mediation: "conditional", signal: controller.signal });
        } catch (error) {
            return;
        }
        phase = "conditional";
        pending = current;
        current.done = request.then(function (credential) {
            if (pending === current) {
                pending = null;
            }
            // 버튼·비밀번호 제출이 먼저 phase를 바꿨으면 늦게 온 성공은 버린다
            if (phase === "conditional") {
                submit(credential);
            }
        }, function () {
            // 취소·자격 없음·그 밖의 실패 — 사용자가 누른 것이 아니라 알리지 않고, 즉시 실패하는 환경에서 반복하지 않도록 다시 시작하지 않는다
            if (pending === current) {
                pending = null;
            }
            if (phase === "conditional") {
                phase = "idle";
            }
        });
    }

    button.addEventListener("click", async function () {
        if (phase === "modal" || phase === "submitted") {
            return;
        }
        message.classList.add("d-none");
        button.disabled = true;
        const previous = pending;
        phase = "modal"; // 먼저 바꿔 두어 취소 직전에 도착한 조건부 성공을 버리게 한다
        if (previous) {
            previous.controller.abort();
            await previous.done; // 취소가 끝난 뒤에 모달을 시작한다(대기 요청은 하나)
        }
        if (phase !== "modal") {
            return;
        }
        let credential;
        try {
            credential = await window.CmsPasskey.get(options());
        } catch (error) {
            if (phase !== "modal") {
                return;
            }
            // 취소·시간 초과·이 기기에 패스키 없음 — 서버로 보내지 않는다(실패 횟수 아님)
            message.classList.remove("d-none");
            button.disabled = false;
            phase = "idle";
            if (conditional) {
                startConditional(); // 자동 완성이 다시 동작하도록 한 번 다시 시작
            }
            return;
        }
        if (phase === "modal") {
            submit(credential);
        }
    });

    if (conditional) {
        // 비밀번호 로그인도 같은 상태에 넣는다 — 이중 POST·다른 계정으로의 전환을 막는다(결정 2-6)
        const passwordForm = document.getElementById("loginForm");
        if (passwordForm) {
            passwordForm.addEventListener("submit", function (event) {
                if (phase === "submitted") {
                    event.preventDefault();
                    return;
                }
                phase = "submitted";
                if (pending) {
                    pending.controller.abort();
                }
            });
        }
        startConditional();
    }
})();
