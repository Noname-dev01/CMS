/*
 * 민감 작업 재인증 모달(PLAN-sensitive-reauthentication.md 쟁점 4).
 *
 * CmsReauth.fetch(url, init)는 fetch와 같게 쓰되, 응답이 403 REAUTHENTICATION_REQUIRED(로그인 상태 유지로 접속한 세션의 민감 작업)이면
 * 그 자리에서 비밀번호를 물어 POST /admin/api/members/me/reauthentication으로 세션을 승격한 뒤 같은 요청을 한 번 다시 보낸다.
 *  - 취소하면 원래 403 응답을 돌려준다(화면의 기존 오류 처리가 서버 문구를 보인다).
 *  - 오답(400 REAUTHENTICATION_FAILED)은 모달 안에 문구를 보이고 다시 입력받는다. 401(잠금·세션 종료)이면 로그인 화면으로 간다.
 *  - 진행 중 재인증은 문서에 하나만 둔다(동시에 403을 받은 호출은 같은 결과를 기다린다). 확인 중에는 다시 제출·취소할 수 없다.
 *  - 재인증 성공 세대를 기억해, 성공 뒤 늦게 도착한 403은 모달 없이 한 번만 다시 보낸다. 재시도는 원 요청마다 1회뿐이다.
 *  - Bootstrap 4 모달은 전환 중의 show()/hide()를 무시한다 — 완전히 표시되기 전(shown 전)에는 입력·확인·취소를 잠그고,
 *    다음 표시는 이전 모달이 완전히 닫힌 뒤(hidden 뒤)에 시작한다(코드 리뷰 1라운드).
 * 재시도는 같은 init을 다시 쓰므로 본문은 문자열(또는 없음)이어야 한다. CSRF 토큰은 승격 뒤에도 같다.
 * 모달은 처음 필요할 때 만든다(jQuery·Bootstrap 4 모달 — 이 스크립트를 쓰는 화면은 둘 다 이미 불러온다).
 */
(function () {
    "use strict";

    const REAUTH_URL = "/admin/api/members/me/reauthentication";
    const REQUIRED = "REAUTHENTICATION_REQUIRED";
    const VERIFY_TIMEOUT_MS = 10000;   // 검증 요청 제한 시간 — 응답이 없으면 모달을 다시 조작할 수 있게 한다(코드 리뷰 4라운드)

    let generation = 0;     // 재인증 성공 세대
    let pending = null;     // 진행 중 재인증 Promise<boolean>
    let modal = null;       // { root, form, input, error, submit, cancel }
    let resolver = null;    // 현재 모달의 결과 전달 함수
    let busy = false;       // 표시 전환 중이거나 검증 요청 중 — 입력·확인·취소 잠금
    let hiding = null;      // 닫힘 전환이 끝나면(hidden) 해결되는 Promise
    let returnFocus = null; // 모달을 열기 직전의 포커스 요소 — 닫힌 뒤 아래 모달 안이면 그리로 돌려보낸다

    function csrfHeaders() {
        const token = document.querySelector('meta[name="_csrf"]');
        const header = document.querySelector('meta[name="_csrf_header"]');
        const headers = { "Content-Type": "application/json" };
        if (token && header) {
            headers[header.getAttribute("content")] = token.getAttribute("content");
        }
        return headers;
    }

    async function isReauthRequired(response) {
        if (response.status !== 403) {
            return false;
        }
        try {
            const body = await response.clone().json();
            return !!body && body.code === REQUIRED;
        } catch (e) {
            return false;
        }
    }

    function el(tag, attrs, text) {
        const node = document.createElement(tag);
        Object.keys(attrs || {}).forEach(function (key) { node.setAttribute(key, attrs[key]); });
        if (text) {
            node.textContent = text;
        }
        return node;
    }

    function buildModal() {
        const root = el("div", { "class": "modal fade", id: "reauthModal", tabindex: "-1", role: "dialog",
            "aria-labelledby": "reauthModalTitle", "aria-hidden": "true", "data-backdrop": "static", "data-keyboard": "false" });
        const dialog = el("div", { "class": "modal-dialog modal-dialog-centered", role: "document" });
        const content = el("div", { "class": "modal-content" });
        const form = el("form", { id: "reauthForm", novalidate: "novalidate" });

        const header = el("div", { "class": "modal-header" });
        header.appendChild(el("h5", { "class": "modal-title", id: "reauthModalTitle" }, "비밀번호 확인"));

        const body = el("div", { "class": "modal-body" });
        body.appendChild(el("p", { "class": "mb-3" }, "로그인 상태 유지로 접속 중이라, 이 작업 전에 비밀번호를 한 번 더 확인합니다."));
        const error = el("div", { id: "reauthError", "class": "alert alert-danger d-none", role: "alert" });
        body.appendChild(error);
        body.appendChild(el("label", { "for": "reauthPassword" }, "현재 비밀번호"));
        const input = el("input", { type: "password", id: "reauthPassword", "class": "form-control", autocomplete: "current-password" });
        body.appendChild(input);

        const footer = el("div", { "class": "modal-footer" });
        const cancel = el("button", { type: "button", "class": "btn btn-secondary", id: "reauthCancel" }, "취소");
        const submit = el("button", { type: "submit", "class": "btn btn-primary", id: "reauthSubmit" }, "확인");
        footer.appendChild(cancel);
        footer.appendChild(submit);

        form.appendChild(header);
        form.appendChild(body);
        form.appendChild(footer);
        content.appendChild(form);
        dialog.appendChild(content);
        root.appendChild(dialog);
        document.body.appendChild(root);

        form.addEventListener("submit", function (event) {
            event.preventDefault();
            verify();
        });
        cancel.addEventListener("click", function () {
            if (!busy) {
                finish(false);
            }
        });
        window.jQuery(root).on("shown.bs.modal", function () {
            // 완전히 표시된 뒤에만 입력·확인·취소를 연다(표시 전환 중 취소는 Bootstrap이 무시해 모달이 남는다)
            if (resolver) {
                setBusy(false);
                input.focus();
            }
        });
        window.jQuery(root).on("hidden.bs.modal", function () {
            // 다른 모달 위에 떴다가 닫혔으면 Bootstrap이 지운 body 상태를 되돌린다(겹친 모달의 스크롤 유지)
            const open = document.querySelectorAll(".modal.show");
            if (open.length) {
                document.body.classList.add("modal-open");
                restoreUnderlyingFocus(open[open.length - 1]);
            }
            returnFocus = null;
        });
        return { root: root, form: form, input: input, error: error, submit: submit, cancel: cancel };
    }

    /*
     * 겹친 모달의 포커스 복원(PLAN-content-delete-reauthentication.md 쟁점 6). Bootstrap 4는 문서에 focusin.bs.modal 하나만 두고
     * 이 모달의 hide()가 그것을 해제하므로, 아래 모달은 열려 있는데 Tab이 배경 화면으로 빠진다. Bootstrap의 _enforceFocus와 같은 규칙을
     * 같은 이벤트 이름으로 다시 걸어(아래 모달이 나중에 닫히면 Bootstrap의 off가 그대로 해제한다) 포커스를 그 모달 안에 둔다.
     * 열기 전 포커스 요소가 아래 모달 안에서 아직 포커스 가능하면 그리로, 아니면 모달 루트(tabindex=-1)로 돌린다 — 요청 전에 버튼을
     * 비활성화하는 화면은 그 요소를 잃었을 수 있어 "버튼으로 복귀"가 아니라 "모달 안에 머문다"가 계약이다.
     */
    function restoreUnderlyingFocus(top) {
        window.jQuery(document).off("focusin.bs.modal").on("focusin.bs.modal", function (event) {
            if (document !== event.target && top !== event.target && !top.contains(event.target)) {
                top.focus();
            }
        });
        const target = returnFocus;
        if (target && target !== document.body && top.contains(target) && !target.disabled) {
            target.focus();
            if (document.activeElement === target) {
                return;
            }
        }
        top.focus();
    }

    function setBusy(value) {
        busy = value;
        modal.submit.disabled = value;
        modal.cancel.disabled = value;
        modal.input.disabled = value;
        modal.submit.textContent = value ? "확인 중..." : "확인";
    }

    function showError(message) {
        modal.error.textContent = message;
        modal.error.classList.remove("d-none");
    }

    function finish(result) {
        const resolve = resolver;
        resolver = null;
        // 포커스가 모달 안에 남은 채로 aria-hidden이 걸리지 않게 먼저 놓는다(접근성 경고 방지)
        if (modal.root.contains(document.activeElement)) {
            document.activeElement.blur();
        }
        // 다음 표시는 닫힘 전환이 끝난 뒤에 시작한다(전환 중 show()는 무시된다)
        hiding = new Promise(function (done) {
            window.jQuery(modal.root).one("hidden.bs.modal", function () {
                hiding = null;
                done();
            });
        });
        window.jQuery(modal.root).modal("hide");
        if (resolve) {
            resolve(result);
        }
    }

    async function verify() {
        if (busy || !resolver) {
            return;
        }
        const password = modal.input.value;
        if (!password) {
            showError("비밀번호를 입력해 주세요.");
            modal.input.focus();
            return;
        }
        setBusy(true);
        const controller = new AbortController();
        const timer = setTimeout(function () { controller.abort(); }, VERIFY_TIMEOUT_MS);
        try {
            const response = await fetch(REAUTH_URL, {
                method: "POST",
                headers: csrfHeaders(),
                body: JSON.stringify({ password: password }),
                signal: controller.signal
            });
            clearTimeout(timer);
            if (response.status === 204) {
                generation++;
                setBusy(false);
                finish(true);
                return;
            }
            if (response.status === 401) {
                window.location.href = "/admin/login";
                return;     // 이동하는 동안 모달은 확인 중 상태로 둔다
            }
            let message = "비밀번호를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.";
            if (response.status === 400) {
                try {
                    const body = await response.json();
                    if (body && body.code === "REAUTHENTICATION_FAILED" && body.message) {
                        message = body.message;
                    }
                } catch (e) {
                    // 기본 문구
                }
            }
            setBusy(false);
            showError(message);
            modal.input.value = "";
            modal.input.focus();
        } catch (e) {
            clearTimeout(timer);
            setBusy(false);
            // 시간 초과여도 서버가 늦게 승격했을 수 있다 — 다시 확인하면 "이미 완전 인증" 경로로 끝난다
            showError(e && e.name === "AbortError"
                    ? "응답이 없습니다. 다시 시도하거나 취소해 주세요."
                    : "네트워크 연결을 확인해 주세요.");
        }
    }

    async function promptPassword() {
        if (hiding) {
            await hiding;
        }
        if (!modal) {
            modal = buildModal();
        }
        returnFocus = document.activeElement;
        modal.input.value = "";
        modal.error.textContent = "";
        modal.error.classList.add("d-none");
        setBusy(true);     // shown.bs.modal에서 연다
        return new Promise(function (resolve) {
            resolver = resolve;
            window.jQuery(modal.root).modal("show");
        });
    }

    async function cmsFetch(url, init) {
        const sentAt = generation;
        const response = await fetch(url, init);
        if (!(await isReauthRequired(response))) {
            return response;
        }
        if (generation !== sentAt) {
            return fetch(url, init);    // 이 요청이 나간 뒤 재인증이 이미 끝났다 — 모달 없이 한 번만 다시 보낸다
        }
        if (!pending) {
            pending = promptPassword().finally(function () { pending = null; });
        }
        const ok = await pending;
        return ok ? fetch(url, init) : response;
    }

    window.CmsReauth = { fetch: cmsFetch };
})();
