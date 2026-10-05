/*
 * 상단바 알림 벨·드롭다운 (계획서 PLAN-admin-notification.md §5-E).
 * - 프레임워크 비의존(vanilla). 데이터는 textContent·createElement로만 그린다(innerHTML에 데이터 삽입 금지).
 * - 배지는 페이지 로드 시 1회만 조회한다(폴링 없음 — 폴링은 세션 유휴 타임아웃을 연장하는 부작용이 있다).
 * - 읽음 요청은 한 번에 하나씩 직렬화하고, 큐가 비면 unread-count를 재조회해 배지를 서버 값으로 확정한다
 *   (읽음 PATCH 두 건의 완료 순서가 뒤집혀도 배지가 서버와 어긋나지 않게 — 세대 무효화만으로는 부족하다, v4 R-15).
 * - 목록은 beforeId 커서로 이어 읽고 이미 표시한 id는 중복 제거한다.
 */
(function () {
    "use strict";

    var BASE = "/admin/api/members/me/notifications";
    var SAFE_PATH = /^\/(?![\/\\])[^\s\\\x00-\x1f\x7f]*$/;   // 같은 출처 경로만(검색 드롭다운과 같은 규칙)
    var SAFE_ID = /^[1-9]\d{0,15}$/;
    var PAGE_SIZE = 10;

    var toggle = document.getElementById("alertsDropdown");
    var badge = document.getElementById("notificationBadge");
    var list = document.getElementById("notificationList");
    var readAllButton = document.getElementById("notificationReadAll");
    var moreButton = document.getElementById("notificationMore");
    if (!toggle || !badge || !list || !readAllButton || !moreButton) {
        return;
    }

    var csrfToken = (document.querySelector('meta[name="_csrf"]') || {}).content;
    var csrfHeader = (document.querySelector('meta[name="_csrf_header"]') || {}).content || "X-CSRF-TOKEN";

    var badgeSeq = 0;          // unread-count 요청 세대 — 읽음 요청이 시작되면 진행 중인 조회를 무효화한다
    var listSeq = 0;           // 목록 요청 세대 — 새로 열거나 다시 읽을 때 늦은 응답을 버린다
    var shownIds = {};         // 이미 그린 알림 id(중복 제거)
    var lastId = null;         // 다음 페이지 커서(마지막으로 그린 항목의 id)
    var readQueue = Promise.resolve();
    var pendingReads = 0;

    // ── 배지 ──────────────────────────────────────────────

    function setBadge(count) {
        if (!count || count < 1) {
            badge.classList.add("d-none");
            badge.textContent = "";
            return;
        }
        badge.textContent = count > 99 ? "99+" : String(count);
        badge.classList.remove("d-none");
    }

    async function refreshBadge() {
        var mySeq = ++badgeSeq;
        try {
            var response = await fetch(BASE + "/unread-count", { headers: { "Accept": "application/json" }, credentials: "same-origin" });
            if (!response.ok) {
                return;   // 401(세션 만료) 등 — 배지는 조용히 둔다
            }
            var data = await response.json();
            if (mySeq !== badgeSeq) {
                return;   // 그 사이 읽음 요청이 시작됐다 — 큐가 끝난 뒤 재조회가 확정한다
            }
            setBadge(data.unreadCount);
        } catch (error) {
            // 네트워크 오류는 배지를 건드리지 않는다
        }
    }

    // ── 읽음 요청(직렬화) ──────────────────────────────────

    function patchRead(path) {
        var headers = { "Content-Type": "application/json", "Accept": "application/json" };
        headers[csrfHeader] = csrfToken;
        return fetch(path, { method: "PATCH", headers: headers, credentials: "same-origin", body: JSON.stringify({ read: true }) });
    }

    /** 읽음 요청을 큐에 넣는다. 시작 시 진행 중인 count 조회를 무효화하고, 큐가 비면 배지를 서버 값으로 재조회한다. */
    function enqueueRead(task) {
        badgeSeq++;
        pendingReads++;
        var result = readQueue.then(task).catch(function () { return false; });
        readQueue = result.then(function () {
            pendingReads--;
            if (pendingReads === 0) {
                refreshBadge();
            }
        });
        return result;
    }

    async function markOneRead(id) {
        if (!SAFE_ID.test(String(id))) {
            return false;
        }
        var response = await patchRead(BASE + "/" + encodeURIComponent(String(id)));
        return response.ok;
    }

    async function markAllRead() {
        var response = await patchRead(BASE);
        return response.ok;
    }

    // ── 목록 ──────────────────────────────────────────────

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

    function showStatus(message, loginLink) {
        list.textContent = "";
        var div = element("div", "notification-status", message);
        if (loginLink) {
            div.appendChild(document.createTextNode(" "));
            var a = document.createElement("a");
            a.href = "/admin/login";
            a.textContent = "로그인";
            div.appendChild(a);
        }
        list.appendChild(div);
        readAllButton.classList.add("d-none");
        moreButton.classList.add("d-none");
    }

    /** 서버가 주는 "2026-10-05T14:03:00" 문자열을 시간대 변환 없이 "2026-10-05 14:03"으로 보여 준다(브라우저 시간대에 의존하지 않는다). */
    function timeLabel(createDate) {
        return typeof createDate === "string" ? createDate.replace("T", " ").slice(0, 16) : "";
    }

    function buildItem(notification) {
        var safeLink = typeof notification.linkUrl === "string" && SAFE_PATH.test(notification.linkUrl);
        var item = safeLink ? element("a", "dropdown-item notification-item") : element("div", "dropdown-item notification-item");
        if (safeLink) {
            item.href = notification.linkUrl;
        } else {
            item.setAttribute("role", "button");
            item.tabIndex = 0;
        }
        if (!notification.read) {
            item.classList.add("unread");
        }
        item.appendChild(element("span", "notification-time", timeLabel(notification.createDate)));
        item.appendChild(element("span", "notification-message", notification.message));

        var activate = async function (event) {
            event.preventDefault();
            if (!notification.read) {
                var ok = await enqueueRead(function () { return markOneRead(notification.id); });
                if (ok) {
                    notification.read = true;
                    item.classList.remove("unread");
                }
            }
            if (safeLink) {
                window.location.href = notification.linkUrl;
            }
        };
        item.addEventListener("click", activate);
        item.addEventListener("keydown", function (event) {
            if (event.key === "Enter" || event.key === " ") {
                activate(event);
            }
        });
        return item;
    }

    function appendItems(items) {
        items.forEach(function (notification) {
            var idKey = String(notification.id);
            if (shownIds[idKey]) {
                return;   // 이미 그린 항목 — 중복 제거
            }
            shownIds[idKey] = true;
            list.appendChild(buildItem(notification));
            lastId = notification.id;
        });
    }

    async function fetchPage(beforeId) {
        var url = BASE + "?size=" + PAGE_SIZE + (beforeId !== null ? "&beforeId=" + encodeURIComponent(String(beforeId)) : "");
        return fetch(url, { headers: { "Accept": "application/json" }, credentials: "same-origin" });
    }

    /** reset=true면 처음부터 다시 읽고, false면 lastId 커서로 이어 읽는다. */
    async function loadList(reset) {
        var mySeq = ++listSeq;
        if (reset) {
            shownIds = {};
            lastId = null;
            showStatus("불러오는 중...");
        } else {
            moreButton.classList.add("d-none");
        }
        try {
            var response = await fetchPage(reset ? null : lastId);
            if (mySeq !== listSeq) {
                return;
            }
            if (response.status === 401) {
                showStatus("세션이 만료되었습니다.", true);
                return;
            }
            if (!response.ok) {
                showStatus("알림을 불러오지 못했습니다.");
                return;
            }
            var data = await response.json();
            if (mySeq !== listSeq) {
                return;   // 본문 해석 사이에 다시 열었거나 새로 읽었다
            }
            if (reset) {
                list.textContent = "";
            }
            appendItems(data.content);
            if (reset && data.content.length === 0) {
                showStatus("새 알림이 없습니다.");
            }
            readAllButton.classList.toggle("d-none", !(data.unreadCount > 0));
            moreButton.classList.toggle("d-none", !data.hasMore);
            if (pendingReads === 0) {
                setBadge(data.unreadCount);   // 읽음 큐가 진행 중이면 큐 종료 뒤 재조회가 배지를 확정한다
            }
        } catch (error) {
            if (mySeq === listSeq) {
                showStatus("알림을 불러오지 못했습니다.");
            }
        }
    }

    // ── 이벤트 ────────────────────────────────────────────

    toggle.addEventListener("click", function () {
        // Bootstrap이 aria-expanded를 바꾸기 전에 호출된다 — "false"였다면 지금 열리는 중이다.
        if (toggle.getAttribute("aria-expanded") !== "true") {
            loadList(true);
        }
    });

    moreButton.addEventListener("click", function (event) {
        event.stopPropagation();   // 드롭다운이 닫히지 않게 한다
        loadList(false);
    });
    moreButton.addEventListener("keydown", function (event) {
        if (event.key === "Enter" || event.key === " ") {
            event.preventDefault();
            event.stopPropagation();
            loadList(false);
        }
    });

    readAllButton.addEventListener("click", async function (event) {
        event.preventDefault();
        event.stopPropagation();
        await enqueueRead(markAllRead);
        loadList(true);
    });

    refreshBadge();
})();
