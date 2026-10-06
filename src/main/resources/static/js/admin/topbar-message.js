/*
 * 상단바 쪽지 봉투·드롭다운 (계획서 PLAN-admin-message.md §5-I, R5-3).
 * - 프레임워크 비의존(vanilla). 데이터는 textContent·createElement로만 그린다(innerHTML에 데이터 삽입 금지).
 * - 미읽음 배지의 값·요청 세대·재조회는 이 스크립트가 <b>단독으로 소유</b>한다. 쪽지함 페이지(message-box.js)는 unread-count API를 직접 호출하지 않고
 *   window.CmsMessageBadge로만 갱신한다 — 두 스크립트가 각자 count를 조회하면 늦게 도착한 최초 응답이 읽음 처리 뒤의 배지를 이전 값으로 되돌린다
 *   (폴링이 없어 복구 수단도 없다):
 *     markChangeStarted()   읽음·삭제를 시작할 때 — 진행 중인 count 요청의 응답을 무효화한다(세대 증가)
 *     refreshAfterChange()  변경이 끝난 뒤 — count를 재조회하고, 이 이후에 시작한 요청의 응답만 반영한다
 * - 배지는 페이지 로드 시 1회만 조회한다(폴링 없음 — 폴링은 세션 유휴 타임아웃을 연장하는 부작용이 있다).
 * - 드롭다운은 열 때 최근 받은 쪽지 5건을 읽고, 항목을 누르면 쪽지함 페이지가 ?id= 로 상세를 연다.
 */
(function () {
    "use strict";

    var BASE = "/admin/api/members/me/messages";
    var BOX_URL = "/admin/member/messages";
    var SAFE_ID = /^[1-9]\d{0,15}$/;
    var PAGE_SIZE = 5;

    var toggle = document.getElementById("messagesDropdown");
    var badge = document.getElementById("messageBadge");
    var list = document.getElementById("messageList");
    if (!toggle || !badge || !list) {
        return;
    }

    var badgeSeq = 0;   // unread-count 요청 세대 — 변경이 시작되면 진행 중인 조회를 무효화한다
    var listSeq = 0;    // 드롭다운 목록 요청 세대

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
                return;   // 401(세션 만료)·403(쪽지 사용 불가) 등 — 배지는 조용히 둔다
            }
            var data = await response.json();
            if (mySeq !== badgeSeq) {
                return;   // 그 사이 변경이 시작됐다 — 변경 뒤 재조회가 확정한다
            }
            setBadge(data.unreadCount);
        } catch (error) {
            // 네트워크 오류는 배지를 건드리지 않는다
        }
    }

    /** 쪽지함 페이지가 쓰는 좁은 인터페이스 — 배지의 단일 소유자는 이 스크립트다. */
    window.CmsMessageBadge = {
        markChangeStarted: function () {
            badgeSeq++;
        },
        refreshAfterChange: function () {
            return refreshBadge();
        }
    };

    // ── 드롭다운 목록 ──────────────────────────────────────

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

    function showStatus(message) {
        list.textContent = "";
        list.appendChild(element("div", "message-status", message));
    }

    /** 서버가 주는 "2026-10-05T14:03:00" 문자열을 시간대 변환 없이 "2026-10-05 14:03"으로 보여 준다. */
    function timeLabel(createDate) {
        return typeof createDate === "string" ? createDate.replace("T", " ").slice(0, 16) : "";
    }

    function buildItem(message) {
        var isSafe = SAFE_ID.test(String(message.id));
        var item = element(isSafe ? "a" : "div", "dropdown-item message-item");
        if (isSafe) {
            item.href = BOX_URL + "?id=" + encodeURIComponent(String(message.id));
        }
        if (!message.read) {
            item.classList.add("unread");
        }
        var counterpart = message.counterpart || {};
        item.appendChild(element("span", "message-meta", (counterpart.userName || "") + " · " + timeLabel(message.createDate)));
        item.appendChild(element("span", "message-title", message.title));
        return item;
    }

    async function loadList() {
        var mySeq = ++listSeq;
        showStatus("불러오는 중...");
        try {
            var response = await fetch(BASE + "?box=inbox&size=" + PAGE_SIZE, { headers: { "Accept": "application/json" }, credentials: "same-origin" });
            if (mySeq !== listSeq) {
                return;
            }
            if (response.status === 401) {
                showStatus("세션이 만료되었습니다. 다시 로그인해주세요.");
                return;
            }
            if (!response.ok) {
                showStatus("쪽지를 불러오지 못했습니다.");
                return;
            }
            var data = await response.json();
            if (mySeq !== listSeq) {
                return;
            }
            list.textContent = "";
            if (!data.content || data.content.length === 0) {
                showStatus("받은 쪽지가 없습니다.");
                return;
            }
            data.content.forEach(function (message) {
                list.appendChild(buildItem(message));
            });
        } catch (error) {
            if (mySeq === listSeq) {
                showStatus("쪽지를 불러오지 못했습니다.");
            }
        }
    }

    if (window.jQuery) {
        // 키보드(방향키·Space)로 열 때는 Bootstrap이 jQuery trigger('click')을 쓰고 기본 동작을 취소하므로
        // 네이티브 click 리스너가 호출되지 않는다 — 열림 이벤트(show.bs.dropdown)에서 조회한다.
        window.jQuery(toggle.parentNode).on("show.bs.dropdown", loadList);
    } else {
        toggle.addEventListener("click", function () {
            // Bootstrap이 aria-expanded를 바꾸기 전에 호출된다 — "false"였다면 지금 열리는 중이다.
            if (toggle.getAttribute("aria-expanded") !== "true") {
                loadList();
            }
        });
    }

    refreshBadge();
})();
