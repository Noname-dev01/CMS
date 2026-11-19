/*
 * 보안 설정 화면의 패스키 카드(PLAN-passkey-second-factor.md 결정 5·7) — 목록·추가·삭제.
 * 서버 값은 모두 textContent로만 넣는다(HTML로 해석하지 않는다). 상태 변경 요청은 CSRF 헤더를 싣는다.
 * 추가 순서: 등록 대기 발급(POST passkey-registrations) → navigator.credentials.create → 확정(POST passkeys, 이름·현재 비밀번호).
 */
(function () {
    "use strict";
    const csrfToken = document.querySelector('meta[name="_csrf"]').getAttribute("content");
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]').getAttribute("content");
    const el = function (id) { return document.getElementById(id); };
    const BASE = "/admin/api/members/me";
    let busy = false;
    let loadSeq = 0;

    function alertError(text) {
        el("passkeySuccess").classList.add("d-none");
        el("passkeyAlert").textContent = text;
        el("passkeyAlert").classList.remove("d-none");
    }

    function alertSuccess(text) {
        el("passkeyAlert").classList.add("d-none");
        el("passkeySuccess").textContent = text;
        el("passkeySuccess").classList.remove("d-none");
    }

    function clearAlerts() {
        el("passkeyAlert").classList.add("d-none");
        el("passkeySuccess").classList.add("d-none");
    }

    async function errorMessage(response, fallback) {
        try {
            const body = await response.json();
            return body.message || fallback;
        } catch (e) {
            return fallback;
        }
    }

    function formatTime(value) {
        return value ? String(value).replace("T", " ").substring(0, 16) : "";
    }

    function setBusy(value) {
        busy = value;
        el("btnPasskeyAdd").disabled = value;
        document.querySelectorAll("#passkeyList button").forEach(function (button) { button.disabled = value; });
    }

    function render(list) {
        el("passkeyCard").classList.toggle("d-none", !list.enabled);
        if (!list.enabled) {
            return;
        }
        const supported = window.CmsPasskey && window.CmsPasskey.supported();
        el("passkeyUnsupported").classList.toggle("d-none", supported);
        el("passkeyNeedTotp").classList.toggle("d-none", list.totpEnabled);
        el("passkeyAddForm").classList.toggle("d-none", !list.totpEnabled || !supported);

        const ul = el("passkeyList");
        ul.replaceChildren();
        list.items.forEach(function (item) {
            const li = document.createElement("li");
            li.className = "list-group-item d-flex justify-content-between align-items-center";
            li.setAttribute("data-passkey-id", String(item.id));
            const text = document.createElement("div");
            const name = document.createElement("strong");
            name.textContent = item.label;
            const meta = document.createElement("div");
            meta.className = "small text-muted";
            meta.textContent = "등록 " + formatTime(item.createdAt)
                + (item.lastUsedAt ? " · 마지막 사용 " + formatTime(item.lastUsedAt) : " · 사용 기록 없음");
            text.append(name, meta);
            const remove = document.createElement("button");
            remove.type = "button";
            remove.className = "btn btn-outline-danger btn-sm";
            remove.textContent = "삭제";
            remove.addEventListener("click", function () { removePasskey(item); });
            li.append(text, remove);
            ul.append(li);
        });
        el("passkeyEmpty").classList.toggle("d-none", list.items.length > 0);
    }

    async function load() {
        const seq = ++loadSeq;
        try {
            const response = await fetch(BASE + "/passkeys", { headers: { "Accept": "application/json" } });
            if (seq !== loadSeq) {
                return; // 더 늦게 시작한 조회가 있다
            }
            if (!response.ok) {
                el("passkeyCard").classList.remove("d-none");
                alertError(await errorMessage(response, "패스키 목록을 불러오지 못했습니다."));
                return;
            }
            const list = await response.json();
            if (seq === loadSeq) {
                render(list);
            }
        } catch (e) {
            el("passkeyCard").classList.remove("d-none");
            alertError("네트워크 연결을 확인해주세요.");
        }
    }

    async function removePasskey(item) {
        if (busy) {
            return;
        }
        if (!window.confirm("패스키 '" + item.label + "'을(를) 삭제할까요?")) {
            return;
        }
        clearAlerts();
        setBusy(true);
        try {
            const response = await fetch(BASE + "/passkeys/" + encodeURIComponent(item.id), {
                method: "DELETE",
                headers: { [csrfHeader]: csrfToken }
            });
            if (!response.ok && response.status !== 404) {
                alertError(await errorMessage(response, "패스키를 삭제하지 못했습니다."));
            } else {
                alertSuccess("패스키를 삭제했습니다.");
            }
        } catch (e) {
            alertError("네트워크 연결을 확인해주세요.");
        } finally {
            setBusy(false);
            await load();
        }
    }

    el("passkeyAddForm").addEventListener("submit", async function (event) {
        event.preventDefault();
        if (busy) {
            return;
        }
        clearAlerts();
        const label = el("passkeyLabel").value.trim();
        const password = el("passkeyPassword").value;
        if (!label || !password) {
            alertError("패스키 이름과 현재 비밀번호를 입력해주세요.");
            return;
        }
        setBusy(true);
        try {
            const started = await fetch(BASE + "/passkey-registrations", {
                method: "POST",
                headers: { "Accept": "application/json", [csrfHeader]: csrfToken }
            });
            if (!started.ok) {
                alertError(await errorMessage(started, "패스키 등록을 시작하지 못했습니다."));
                return;
            }
            const registration = await started.json();
            let credential;
            try {
                credential = await window.CmsPasskey.create(registration.options);
            } catch (e) {
                // 취소·시간 초과·이미 이 기기에 등록됨(excludeCredentials) — 서버로 보내지 않는다
                alertError("패스키를 만들지 못했습니다. 취소했거나 이 기기에 이미 등록된 패스키일 수 있습니다.");
                return;
            }
            const confirmed = await fetch(BASE + "/passkeys", {
                method: "POST",
                headers: { "Content-Type": "application/json", "Accept": "application/json", [csrfHeader]: csrfToken },
                body: JSON.stringify({
                    registrationId: registration.registrationId,
                    credential: credential,
                    label: label,
                    currentPassword: password
                })
            });
            if (!confirmed.ok) {
                alertError(await errorMessage(confirmed, "패스키를 등록하지 못했습니다."));
                return;
            }
            el("passkeyAddForm").reset();
            alertSuccess("패스키를 등록했습니다. 다음 로그인부터 인증 코드 대신 사용할 수 있습니다.");
        } catch (e) {
            alertError("네트워크 연결을 확인해주세요.");
        } finally {
            el("passkeyPassword").value = "";
            setBusy(false);
            await load();
        }
    });

    document.addEventListener("cms:totp-status", load);
    load();
})();
