/*
 * 쪽지함 페이지 (계획서 PLAN-admin-message.md §5-I).
 * - 프레임워크 비의존(vanilla, 모달 표시만 jQuery). 제목·본문·이름은 textContent로만 그린다(innerHTML에 데이터 삽입 금지, 자동 링크화 없음).
 * - 미읽음 배지는 unread-count를 직접 호출하지 않고 window.CmsMessageBadge(상단바 스크립트가 단독 소유)로만 갱신한다 —
 *   읽음·삭제를 시작할 때 markChangeStarted(), 모두 끝나면 refreshAfterChange() (R5-3).
 * - 읽음 PATCH는 한 번에 하나씩 직렬화하고(완료 순서가 뒤집혀도 서버 값으로 확정), 상세·검색 응답은 요청 세대·AbortController로 늦은 응답을 버린다.
 * - 수신자 검색은 입력할 때마다 선택된 수신자를 즉시 해제하고, 검색어는 trim하지 않은 원문으로 보낸다(서버가 원문 아이디 정확 일치를 먼저 조회한다 — R6-1).
 * - 쪽지 시각은 서버 문자열을 시간대 변환 없이 표시한다.
 */
(function () {
    "use strict";

    var BASE = "/admin/api/members/me/messages";
    var RECIPIENTS = "/admin/api/members/me/message-recipients";
    var PAGE_SIZE = 20;
    var TITLE_MAX = 100;
    var BODY_MAX = 2000;
    var SAFE_ID = /^[1-9]\d{0,15}$/;
    var SEARCH_DEBOUNCE_MS = 250;

    var csrfToken = (document.querySelector('meta[name="_csrf"]') || {}).content;
    var csrfHeader = (document.querySelector('meta[name="_csrf_header"]') || {}).content || "X-CSRF-TOKEN";

    var boxTabs = document.getElementById("boxTabs");
    var boxList = document.getElementById("boxList");
    var btnMore = document.getElementById("btnMore");
    var btnCompose = document.getElementById("btnCompose");
    var pageAlert = document.getElementById("pageAlert");
    var pageSuccess = document.getElementById("pageSuccess");

    var detailTitle = document.getElementById("detailTitle");
    var detailMeta = document.getElementById("detailMeta");
    var detailBody = document.getElementById("detailBody");
    var detailError = document.getElementById("detailError");
    var btnReply = document.getElementById("btnReply");
    var btnDelete = document.getElementById("btnDelete");
    var deleteConfirm = document.getElementById("deleteConfirm");
    var btnDeleteConfirm = document.getElementById("btnDeleteConfirm");
    var btnDeleteCancel = document.getElementById("btnDeleteCancel");

    var composeError = document.getElementById("composeError");
    var composeRecipient = document.getElementById("composeRecipient");
    var recipientResults = document.getElementById("recipientResults");
    var recipientNote = document.getElementById("recipientNote");
    var composeTitle = document.getElementById("composeTitle");
    var composeBody = document.getElementById("composeBody");
    var titleCounter = document.getElementById("titleCounter");
    var bodyCounter = document.getElementById("bodyCounter");
    var btnSend = document.getElementById("btnSend");

    var DEFAULT_RECIPIENT_NOTE = recipientNote.textContent;

    var currentBox = "inbox";
    var shownIds = {};
    var lastId = null;
    var listSeq = 0;
    var readIds = {};             // 읽음 처리에 성공한 쪽지 id — 늦게 도착한 목록 응답이 안 읽음으로 되돌리지 못하게 병합한다
    var deletedIds = {};          // 삭제에 성공한(또는 이미 없는) 쪽지 id — 늦게 도착한 목록 응답이 행을 되살리지 못하게 병합한다
    var listLoading = false;      // 목록 요청이 진행 중인가(삭제 뒤 빈 목록 재조회 판단에 쓴다)
    var rowsById = {};            // 현재 목록에 그려진 행(id → {row, message}) — 읽음·삭제를 즉시 반영한다

    var detailSeq = 0;
    var detailAbort = null;
    var currentDetail = null;

    var searchSeq = 0;
    var searchAbort = null;
    var searchTimer = null;
    var selectedRecipient = null;
    var sending = false;

    var pendingChanges = 0;       // 진행 중인 읽음·삭제 요청 수 — 0이 되면 배지를 서버 값으로 재조회한다
    var changeQueue = Promise.resolve();

    // ── 공통 ──────────────────────────────────────────────

    function element(tag, className, text) {
        var el = document.createElement(tag);
        if (className) {
            el.className = className;
        }
        if (text !== undefined && text !== null) {
            el.textContent = text;
        }
        return el;
    }

    function jsonHeaders(withBody) {
        var headers = { "Accept": "application/json" };
        if (withBody) {
            headers["Content-Type"] = "application/json";
        }
        headers[csrfHeader] = csrfToken;
        return headers;
    }

    /** "2026-10-05T14:03:00" → "2026-10-05 14:03" (시간대 변환 없음). */
    function timeLabel(value) {
        return typeof value === "string" ? value.replace("T", " ").slice(0, 16) : "";
    }

    function showAlert(target, message) {
        target.textContent = message;
        target.classList.remove("d-none");
    }

    function clearAlert(target) {
        target.textContent = "";
        target.classList.add("d-none");
    }

    function pageError(message) {
        clearAlert(pageSuccess);
        showAlert(pageAlert, message);
    }

    function pageNotice(message) {
        clearAlert(pageAlert);
        showAlert(pageSuccess, message);
    }

    /** 응답 본문의 서버 메시지(고정 문구)를 꺼낸다. 없으면 기본 문구. */
    async function serverMessage(response, fallback) {
        try {
            var data = await response.json();
            return data && data.message ? data.message : fallback;
        } catch (error) {
            return fallback;
        }
    }

    function retryAfterText(response) {
        var seconds = parseInt(response.headers.get("Retry-After"), 10);
        return seconds > 0 ? " " + seconds + "초 후 다시 시도해주세요." : "";
    }

    /** 길이는 UTF-16 단위(value.length) — 서버 @Size·최종 길이 검사와 같은 기준이다. */
    function utf16Length(value) {
        return value.length;
    }

    /** UTF-16 예산 안에서 코드포인트 경계를 보존해 자른다(서로게이트 쌍을 반으로 자르지 않는다 — 고립 서로게이트는 서버가 400으로 거부한다). */
    function truncateByUtf16(value, max) {
        var out = "";
        var used = 0;
        for (var ch of value) {
            if (used + ch.length > max) {
                break;
            }
            out += ch;
            used += ch.length;
        }
        return out;
    }

    // ── 배지 연동(상단바가 단독 소유) ───────────────────────

    function badgeChangeStarted() {
        if (window.CmsMessageBadge) {
            window.CmsMessageBadge.markChangeStarted();
        }
    }

    function badgeRefresh() {
        if (window.CmsMessageBadge) {
            window.CmsMessageBadge.refreshAfterChange();
        }
    }

    /** 읽음·삭제를 큐로 한 번에 하나씩 실행한다. 시작 시 배지 응답을 무효화하고, 모두 끝나면 서버 값으로 재조회한다. */
    function runChange(task) {
        badgeChangeStarted();
        pendingChanges++;
        var result = changeQueue.then(task).catch(function () { return { ok: false, status: 0 }; });
        changeQueue = result.then(function () {
            pendingChanges--;
            if (pendingChanges === 0) {
                badgeRefresh();
            }
        });
        return result;
    }

    // ── 목록 ──────────────────────────────────────────────

    function setActiveTab(box) {
        Array.prototype.forEach.call(boxTabs.querySelectorAll("button[data-box]"), function (button) {
            var active = button.getAttribute("data-box") === box;
            button.classList.toggle("active", active);
            button.setAttribute("aria-selected", active ? "true" : "false");
        });
    }

    function showListStatus(message, loginLink) {
        boxList.textContent = "";
        var div = element("div", "p-4 text-center text-gray-500", message);
        if (loginLink) {
            div.appendChild(document.createTextNode(" "));
            var a = document.createElement("a");
            a.href = "/admin/login";
            a.textContent = "로그인";
            div.appendChild(a);
        }
        boxList.appendChild(div);
        btnMore.classList.add("d-none");
    }

    function buildRow(message) {
        var counterpart = message.counterpart || {};
        var row = element("div", "message-row");
        row.setAttribute("role", "listitem");
        row.tabIndex = 0;

        var top = element("div", "message-row-top");
        top.appendChild(element("span", "message-counterpart", (counterpart.userName || "") + " (" + (counterpart.userId || "") + ")"));
        var meta = timeLabel(message.createDate);
        if (currentBox === "sent") {
            // 읽음 확인(D10): 수신자가 읽으면 readAt이 나타난다(미읽음이면 키가 생략된다)
            meta += message.read && message.readAt ? " · 읽음 " + timeLabel(message.readAt) : " · 안 읽음";
        }
        top.appendChild(element("span", "message-time", meta));
        row.appendChild(top);
        row.appendChild(element("div", "message-row-title", message.title));

        if (currentBox === "inbox" && !message.read) {
            row.classList.add("unread");
        }
        var open = function () { openDetail(message.id); };
        row.addEventListener("click", open);
        row.addEventListener("keydown", function (event) {
            if (event.key === "Enter" || event.key === " ") {
                event.preventDefault();
                open();
            }
        });
        rowsById[String(message.id)] = { row: row, message: message };
        return row;
    }

    function appendRows(items) {
        items.forEach(function (message) {
            var key = String(message.id);
            if (shownIds[key]) {
                return;   // 이미 그린 항목 — 중복 제거
            }
            shownIds[key] = true;
            lastId = message.id;   // 그리지 않는 항목도 커서는 전진한다
            if (deletedIds[key]) {
                return;   // 이 화면에서 이미 삭제한 쪽지 — 지연된 목록 응답이 되살리지 않는다
            }
            if (readIds[key]) {
                message.read = true;   // 이 화면에서 이미 읽음 처리한 쪽지 — 지연된 목록 응답이 안 읽음으로 되돌리지 않는다
            }
            boxList.appendChild(buildRow(message));
        });
    }

    /**
     * 목록 조회 실패 안내. 초기 조회(reset=true)는 목록 자리를 상태 문구로 대체하지만, '더 보기'(reset=false) 실패는 이미 불러온 행과 커서를
     * 지우지 않는다 — 오류만 페이지 알림으로 알리고 같은 커서로 다시 누를 수 있게 더 보기 버튼을 복원한다.
     */
    function listFailure(reset, message, loginLink) {
        if (reset) {
            showListStatus(message, loginLink);
            return;
        }
        pageError(message + (loginLink ? " 다시 로그인해주세요." : ""));
        btnMore.classList.remove("d-none");
    }

    /** reset=true면 현재 쪽지함을 처음부터 다시 읽고, false면 lastId 커서로 이어 읽는다. */
    async function loadBox(reset) {
        var mySeq = ++listSeq;
        listLoading = true;
        if (reset) {
            shownIds = {};
            rowsById = {};
            lastId = null;
            showListStatus("불러오는 중...");
        } else {
            btnMore.classList.add("d-none");
        }
        var url = BASE + "?box=" + currentBox + "&size=" + PAGE_SIZE
            + (!reset && lastId !== null ? "&beforeId=" + encodeURIComponent(String(lastId)) : "");
        try {
            var response = await fetch(url, { headers: { "Accept": "application/json" }, credentials: "same-origin" });
            if (mySeq !== listSeq) {
                return;
            }
            if (response.status === 401) {
                listFailure(reset, "세션이 만료되었습니다.", true);
                return;
            }
            if (response.status === 403) {
                listFailure(reset, "쪽지를 사용할 수 없는 계정입니다.", false);
                return;
            }
            if (!response.ok) {
                listFailure(reset, "쪽지를 불러오지 못했습니다.", false);
                return;
            }
            var data = await response.json();
            if (mySeq !== listSeq) {
                return;
            }
            if (reset) {
                boxList.textContent = "";
            }
            appendRows(data.content);
            if (reset && Object.keys(rowsById).length === 0) {
                // 첫 페이지가 비었거나 전부 이 화면에서 지운 쪽지다 — 더 있으면 이어 읽고, 없으면 빈 상태를 보여 준다
                if (data.hasMore) {
                    listLoading = false;
                    loadBox(false);
                    return;
                }
                showListStatus(currentBox === "inbox" ? "받은 쪽지가 없습니다." : "보낸 쪽지가 없습니다.");
            }
            btnMore.classList.toggle("d-none", !data.hasMore);
        } catch (error) {
            if (mySeq === listSeq) {
                listFailure(reset, "쪽지를 불러오지 못했습니다.", false);
            }
        } finally {
            if (mySeq === listSeq) {
                listLoading = false;
            }
        }
    }

    function switchBox(box) {
        if (box === currentBox) {
            return;
        }
        currentBox = box;
        setActiveTab(box);
        clearAlert(pageAlert);
        clearAlert(pageSuccess);
        loadBox(true);
    }

    // ── 상세 ──────────────────────────────────────────────

    function resetDeleteConfirm() {
        deleteConfirm.classList.add("d-none");
        btnDelete.classList.remove("d-none");
    }

    function directionLabel(detail) {
        var who = detail.counterpart ? detail.counterpart.userName + " (" + detail.counterpart.userId + ")" : "";
        var label = detail.direction === "SENT" ? "받는 사람 " : "보낸 사람 ";
        var time = " · " + timeLabel(detail.createDate);
        if (detail.direction === "SENT") {
            time += detail.read && detail.readAt ? " · 읽음 " + timeLabel(detail.readAt) : " · 안 읽음";
        }
        return label + who + time;
    }

    /** 상세를 연다. 열 때마다 세대를 올리고 이전 요청을 취소해, 늦게 도착한 응답이 다른 쪽지를 덮어쓰지 못하게 한다. */
    async function openDetail(id) {
        if (!SAFE_ID.test(String(id))) {
            return;
        }
        var mySeq = ++detailSeq;
        if (detailAbort) {
            detailAbort.abort();
        }
        detailAbort = new AbortController();
        currentDetail = null;
        clearAlert(detailError);
        resetDeleteConfirm();
        btnReply.classList.add("d-none");
        btnDelete.classList.add("d-none");
        detailTitle.textContent = "쪽지";
        detailMeta.textContent = "";
        detailBody.textContent = "불러오는 중...";
        window.jQuery("#detailModal").modal("show");

        try {
            var response = await fetch(BASE + "/" + encodeURIComponent(String(id)), {
                headers: { "Accept": "application/json" }, credentials: "same-origin", signal: detailAbort.signal });
            if (mySeq !== detailSeq) {
                return;
            }
            if (response.status === 404) {
                detailBody.textContent = "";
                showAlert(detailError, "쪽지를 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.");
                return;
            }
            if (response.status === 401) {
                detailBody.textContent = "";
                showAlert(detailError, "세션이 만료되었습니다. 다시 로그인해주세요.");
                return;
            }
            if (!response.ok) {
                detailBody.textContent = "";
                showAlert(detailError, await serverMessage(response, "쪽지를 불러오지 못했습니다."));
                return;
            }
            var data = await response.json();
            if (mySeq !== detailSeq || String(data.id) !== String(id)) {
                return;   // 다른 쪽지를 열었거나 응답이 요청과 다르다
            }
            currentDetail = data;
            detailTitle.textContent = data.title;          // textContent — 제목은 사용자 입력이다
            detailMeta.textContent = directionLabel(data);
            detailBody.textContent = data.body;            // textContent + CSS pre-wrap (자동 링크화 없음)
            btnDelete.classList.remove("d-none");
            btnReply.classList.toggle("d-none", data.direction !== "RECEIVED");

            if (data.direction === "RECEIVED" && !data.read) {
                markRead(data);
            }
        } catch (error) {
            if (error && error.name === "AbortError") {
                return;
            }
            if (mySeq === detailSeq) {
                detailBody.textContent = "";
                showAlert(detailError, "쪽지를 불러오지 못했습니다.");
            }
        }
    }

    /** 받은 쪽지를 읽음 처리한다(큐로 직렬화). 성공하면 목록의 안 읽음 표시를 뗀다. */
    async function markRead(detail) {
        var startedSeq = detailSeq;   // 읽음 요청을 시작한 상세창 — 실패 안내는 아직 그 상세일 때만 띄운다
        var result = await runChange(async function () {
            var response = await fetch(BASE + "/" + encodeURIComponent(String(detail.id)), {
                method: "PATCH", headers: jsonHeaders(true), credentials: "same-origin", body: JSON.stringify({ read: true }) });
            return { ok: response.ok, status: response.status };
        });
        if (result && result.ok) {
            detail.read = true;
            readIds[String(detail.id)] = true;
            var entry = rowsById[String(detail.id)];
            if (entry) {
                entry.message.read = true;
                entry.row.classList.remove("unread");
            }
        } else if (!result || result.status !== 404) {
            // 네트워크·서버 오류·409 등으로 읽음 처리에 실패했다 — 본문은 보이지만 서버에는 미읽음으로 남는다(발신자에게도 "안 읽음"). 404는 그 사이 삭제된 쪽지라 안내하지 않는다.
            // 재열람이 곧 멱등 재시도다(안 읽음이면 다시 PATCH). 다른 상세로 넘어갔다면 그 화면을 건드리지 않는다.
            if (startedSeq === detailSeq && currentDetail !== null && String(currentDetail.id) === String(detail.id)) {
                showAlert(detailError, "읽음 처리에 실패했습니다. 쪽지를 다시 열면 다시 시도합니다.");
            }
        }
    }

    // ── 삭제 ──────────────────────────────────────────────

    async function deleteCurrent() {
        if (!currentDetail) {
            return;
        }
        // 삭제를 시작한 상세창(세대·id)을 캡처한다 — 응답이 늦게 와 그 사이 창을 닫고 다른 쪽지(B)를 열었다면, 이 응답의 성공·실패 처리가
        // B의 상세창을 닫거나 오류를 띄우거나 삭제 확인 상태를 초기화하면 안 된다(목록 제거는 캡처한 id에 계속 적용하고 결과는 페이지 알림으로 전달한다)
        var id = currentDetail.id;
        var startedSeq = detailSeq;
        var stillCurrent = function () {
            return startedSeq === detailSeq && currentDetail !== null && String(currentDetail.id) === String(id);
        };
        btnDeleteConfirm.disabled = true;
        try {
            var result = await runChange(async function () {
                var response = await fetch(BASE + "/" + encodeURIComponent(String(id)), {
                    method: "DELETE", headers: jsonHeaders(false), credentials: "same-origin" });
                return { ok: response.ok, status: response.status, response: response };
            });
            var current = stillCurrent();   // 상세창을 닫기 전에 판정한다(닫으면 세대가 바뀐다)
            if (result.ok || result.status === 404) {
                // 204 삭제됨 / 404 이미 없음 — 둘 다 목록에서 뺀다(삭제는 HTTP 의미상 멱등)
                deletedIds[String(id)] = true;
                var entry = rowsById[String(id)];
                if (entry) {
                    entry.row.remove();
                    delete rowsById[String(id)];
                }
                if (current) {
                    btnDeleteConfirm.blur();   // 포커스가 있는 요소가 든 모달을 aria-hidden 처리하지 않도록 먼저 포커스를 푼다
                    window.jQuery("#detailModal").modal("hide");
                }
                pageNotice(result.ok ? "내 보관함에서 삭제했습니다." : "이미 삭제된 쪽지입니다.");
                // 로딩 안내 같은 요소가 섞인 children이 아니라 실제 행 수로 판단하고, 목록이 진행 중이면 그 응답이 병합해 처리한다
                if (Object.keys(rowsById).length === 0 && !listLoading) {
                    loadBox(true);
                }
            } else {
                var message = result.status === 409 ? "동시 변경과 충돌했습니다. 다시 시도해주세요."
                    : result.status === 401 ? "세션이 만료되었습니다. 다시 로그인해주세요."
                    : "삭제하지 못했습니다.";
                if (current) {
                    showAlert(detailError, message);
                } else {
                    pageError("쪽지 삭제에 실패했습니다. " + message);   // 이미 다른 화면으로 넘어갔다 — 현재 상세를 건드리지 않고 페이지 알림으로 알린다
                }
            }
        } finally {
            btnDeleteConfirm.disabled = false;
            if (stillCurrent()) {
                resetDeleteConfirm();   // 현재 상세가 이 삭제를 시작한 창일 때만 확인 상태를 되돌린다
            }
        }
    }

    // ── 쓰기 ──────────────────────────────────────────────

    function recipientLabel(recipient) {
        return recipient.userName + " (" + recipient.userId + ")";
    }

    function updateCounters() {
        var titleLength = utf16Length(composeTitle.value);
        var bodyLength = utf16Length(composeBody.value);
        titleCounter.textContent = titleLength + " / " + TITLE_MAX;
        titleCounter.classList.toggle("over", titleLength > TITLE_MAX);
        bodyCounter.textContent = bodyLength + " / " + BODY_MAX;
        bodyCounter.classList.toggle("over", bodyLength > BODY_MAX);
    }

    function clearRecipientResults() {
        recipientResults.textContent = "";
        recipientResults.classList.add("d-none");
    }

    function setRecipientNote(message) {
        recipientNote.textContent = message;
    }

    function openCompose(prefill) {
        // 발송 요청이 진행 중이면 작성창을 다시 열지 않는다 — 열면 진행 중인 요청의 잠금이 풀려 같은 쪽지가 한 번 더 전송되고,
        // 먼저 보낸 요청의 응답이 새 작성창을 닫거나 오류·버튼 상태를 바꿀 수 있다. 잠금(sending)은 요청이 끝날 때까지 유지한다.
        if (sending) {
            pageError("쪽지를 보내는 중입니다. 잠시 후 다시 시도해주세요.");
            return;
        }
        clearAlert(composeError);
        invalidateRecipientSearch();   // 이전 작성창의 지연된 검색 응답이 새 작성창에 나타나지 않게 한다
        selectedRecipient = prefill && prefill.recipient ? prefill.recipient : null;
        composeRecipient.value = selectedRecipient ? recipientLabel(selectedRecipient) : "";
        composeTitle.value = prefill && prefill.title ? prefill.title : "";
        composeBody.value = "";
        clearRecipientResults();
        setRecipientNote(DEFAULT_RECIPIENT_NOTE);
        updateCounters();
        btnSend.disabled = false;
        window.jQuery("#composeModal").modal("show");
        window.jQuery("#composeModal").one("shown.bs.modal", function () {
            (selectedRecipient ? composeTitle : composeRecipient).focus();
        });
    }

    function renderRecipientResults(data) {
        recipientResults.textContent = "";
        if (!data.content || data.content.length === 0) {
            clearRecipientResults();
            setRecipientNote("일치하는 받는 사람이 없습니다.");
            return;
        }
        data.content.forEach(function (recipient) {
            var option = element("button", "message-recipient-option", recipientLabel(recipient));
            option.type = "button";
            option.setAttribute("role", "option");
            option.addEventListener("click", function () {
                selectedRecipient = { id: recipient.id, userId: recipient.userId, userName: recipient.userName };
                composeRecipient.value = recipientLabel(selectedRecipient);
                clearRecipientResults();
                setRecipientNote("받는 사람을 선택했습니다.");
                composeTitle.focus();
            });
            recipientResults.appendChild(option);
        });
        recipientResults.classList.remove("d-none");
        setRecipientNote(data.truncated ? "결과가 더 있습니다. 검색어를 좁혀주세요." : DEFAULT_RECIPIENT_NOTE);
    }

    /** 진행 중인 수신자 검색을 즉시 무효화한다 — 세대를 올리고 요청을 취소하고 예약된 검색을 지우며 이전 결과를 비운다. */
    function invalidateRecipientSearch() {
        searchSeq++;
        clearTimeout(searchTimer);
        if (searchAbort) {
            searchAbort.abort();
            searchAbort = null;
        }
        clearRecipientResults();
    }

    /** 수신자 검색 — 원문(trim 없음)을 그대로 보낸다. 요청 세대·AbortController로 늦은 응답을 버린다. */
    async function searchRecipients() {
        var raw = composeRecipient.value;
        var mySeq = ++searchSeq;
        if (searchAbort) {
            searchAbort.abort();
        }
        if (raw.trim() === "") {
            clearRecipientResults();
            setRecipientNote(DEFAULT_RECIPIENT_NOTE);
            return;
        }
        searchAbort = new AbortController();
        try {
            var response = await fetch(RECIPIENTS + "?keyword=" + encodeURIComponent(raw), {
                headers: { "Accept": "application/json" }, credentials: "same-origin", signal: searchAbort.signal });
            if (mySeq !== searchSeq || composeRecipient.value !== raw) {
                return;   // 세대가 바뀌었거나 입력이 그 사이 달라졌다 — 이전 검색어의 응답을 버린다
            }
            if (response.status === 429) {
                clearRecipientResults();
                setRecipientNote("검색 요청이 너무 많습니다." + retryAfterText(response));
                return;
            }
            if (response.status === 400) {
                clearRecipientResults();
                setRecipientNote(await serverMessage(response, "검색어를 확인해주세요."));
                return;
            }
            if (response.status === 401) {
                setRecipientNote("세션이 만료되었습니다. 다시 로그인해주세요.");
                return;
            }
            if (!response.ok) {
                clearRecipientResults();
                setRecipientNote("검색하지 못했습니다.");
                return;
            }
            var data = await response.json();
            if (mySeq !== searchSeq || composeRecipient.value !== raw) {
                return;   // 세대가 바뀌었거나 입력이 그 사이 달라졌다 — 이전 검색어의 응답을 버린다
            }
            renderRecipientResults(data);
        } catch (error) {
            if (!error || error.name !== "AbortError") {
                if (mySeq === searchSeq) {
                    setRecipientNote("검색하지 못했습니다.");
                }
            }
        }
    }

    function validateCompose() {
        // 입력창의 이름과 선택된 회원이 어긋난 상태로 보내지 않는다 — 입력을 바꾸면 선택이 이미 해제되므로 보통 selectedRecipient가 null이다
        if (!selectedRecipient || composeRecipient.value !== recipientLabel(selectedRecipient)) {
            return "받는 사람을 검색 결과에서 선택해주세요.";
        }
        var title = composeTitle.value.trim();
        if (title === "") {
            return "제목을 입력해주세요.";
        }
        if (utf16Length(title) > TITLE_MAX) {
            return "제목은 " + TITLE_MAX + "자 이하여야 합니다.";
        }
        if (composeBody.value.trim() === "") {
            return "본문을 입력해주세요.";
        }
        if (utf16Length(composeBody.value) > BODY_MAX) {
            return "본문은 " + BODY_MAX + "자 이하여야 합니다.";
        }
        return null;
    }

    /** 발송 실패 안내 — 작성창이 아직 열려 있으면 창 안에, 이미 닫혔으면(응답이 늦은 경우) 페이지 알림에 표시한다(보이지 않는 모달에 쓰지 않는다). */
    function sendFailure(message) {
        if (document.getElementById("composeModal").classList.contains("show")) {
            showAlert(composeError, message);
        } else {
            pageError(message);
        }
    }

    async function sendMessage() {
        if (sending) {
            return;
        }
        clearAlert(composeError);
        var problem = validateCompose();
        if (problem) {
            showAlert(composeError, problem);
            return;
        }
        sending = true;
        btnSend.disabled = true;   // 보내는 중에는 다시 누를 수 없다(이중 발송 방지)
        try {
            var response = await fetch(BASE, {
                method: "POST", headers: jsonHeaders(true), credentials: "same-origin",
                body: JSON.stringify({ recipientId: selectedRecipient.id, title: composeTitle.value.trim(), body: composeBody.value }) });
            if (response.status === 201) {
                window.jQuery("#composeModal").modal("hide");
                pageNotice("쪽지를 보냈습니다.");
                currentBox = "sent";
                setActiveTab("sent");
                loadBox(true);
                return;
            }
            if (response.status === 429) {
                sendFailure((await serverMessage(response, "쪽지 발송 한도를 초과했습니다.")) + retryAfterText(response));
            } else if (response.status === 409) {
                sendFailure("동시 변경과 충돌해 보내지 못했습니다. 쪽지는 저장되지 않았으니 다시 시도해주세요.");
            } else if (response.status === 403) {
                sendFailure(await serverMessage(response, "쪽지를 사용할 수 없는 계정입니다."));
            } else if (response.status === 401) {
                sendFailure("세션이 만료되었습니다. 다시 로그인해주세요.");
            } else {
                sendFailure(await serverMessage(response, "쪽지를 보내지 못했습니다."));
            }
        } catch (error) {
            sendFailure("쪽지를 보내지 못했습니다. 네트워크를 확인해주세요.");
        } finally {
            sending = false;
            btnSend.disabled = false;
        }
    }

    // ── 이벤트 ────────────────────────────────────────────

    boxTabs.addEventListener("click", function (event) {
        var button = event.target.closest("button[data-box]");
        if (button) {
            switchBox(button.getAttribute("data-box"));
        }
    });

    btnMore.addEventListener("click", function () {
        loadBox(false);
    });

    btnCompose.addEventListener("click", function () {
        openCompose(null);
    });

    composeRecipient.addEventListener("input", function () {
        selectedRecipient = null;   // 입력을 바꾸면 선택을 즉시 해제한다 — 결과를 눌러야만 recipientId가 설정된다
        // 세대 증가·요청 취소·결과 비움을 디바운스 뒤가 아니라 입력 즉시 한다 — 늦게 도착한 이전 검색어의 응답이 새 입력 아래에 나타나
        // 선택되면(선택 핸들러가 입력을 후보 라벨로 덮어써 검증을 통과한다) 잘못된 상대에게 보내게 된다
        invalidateRecipientSearch();
        setRecipientNote(DEFAULT_RECIPIENT_NOTE);
        searchTimer = setTimeout(searchRecipients, SEARCH_DEBOUNCE_MS);   // debounce는 UX용이다(서버 보호는 요청 횟수 제한)
    });

    composeTitle.addEventListener("input", updateCounters);
    composeBody.addEventListener("input", updateCounters);
    btnSend.addEventListener("click", sendMessage);

    btnReply.addEventListener("click", function () {
        if (!currentDetail || !currentDetail.counterpart) {
            return;
        }
        var counterpart = currentDetail.counterpart;
        var replyTitle = truncateByUtf16("Re: " + currentDetail.title, TITLE_MAX);   // 코드포인트 경계를 보존해 자른다
        btnReply.blur();   // 포커스가 있는 요소가 든 모달을 aria-hidden 처리하지 않도록 먼저 포커스를 푼다
        window.jQuery("#detailModal").modal("hide");
        window.jQuery("#detailModal").one("hidden.bs.modal", function () {
            openCompose({
                recipient: { id: counterpart.id, userId: counterpart.userId, userName: counterpart.userName },
                title: replyTitle
            });
        });
    });

    btnDelete.addEventListener("click", function () {
        btnDelete.classList.add("d-none");
        deleteConfirm.classList.remove("d-none");
    });
    btnDeleteCancel.addEventListener("click", resetDeleteConfirm);
    btnDeleteConfirm.addEventListener("click", deleteCurrent);

    window.jQuery("#composeModal").on("hidden.bs.modal", function () {
        invalidateRecipientSearch();   // 닫으면 진행 중인 수신자 검색 응답을 버린다
    });

    // 닫기가 시작되는 즉시 진행 중인 상세 응답을 무효화한다 — hidden.bs.modal은 전환·배경 페이드가 끝나야 발생해 그 사이 도착한 본문이
    // 세대 검사를 통과해 보이지 않는 본문을 넣고 읽음 PATCH를 만들어 버린다(사용자가 보지 않은 쪽지가 읽음으로 전달되고 취소는 불가능하다)
    window.jQuery("#detailModal").on("hide.bs.modal", function () {
        detailSeq++;
        if (detailAbort) {
            detailAbort.abort();
        }
    });
    window.jQuery("#detailModal").on("hidden.bs.modal", function () {
        currentDetail = null;
        resetDeleteConfirm();
    });

    // ── 시작 ──────────────────────────────────────────────

    setActiveTab(currentBox);
    loadBox(true);

    // 상단바·검색 결과 링크 ?id= 진입: 상세를 바로 열고 쿼리에서 id를 지운다(새로고침·뒤로 가기 때 다시 열리지 않게)
    var requestedId = new URLSearchParams(window.location.search).get("id");
    if (requestedId !== null && SAFE_ID.test(requestedId)) {
        openDetail(requestedId);
        window.history.replaceState(null, "", window.location.pathname);
    }
})();
