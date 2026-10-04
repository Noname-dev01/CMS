/*
 * 상단바 통합 검색 드롭다운 (계획서 PLAN-admin-unified-search.md §5-E).
 * - 프레임워크 비의존(vanilla). 결과는 textContent·createElement로만 그린다(innerHTML에 데이터 삽입 금지).
 * - 비동기 응답은 "요청 세대(generation)"로 보호한다: 입력 변경·닫기·새 요청이 세대를 올리고, 늦은 응답은 버려진다.
 */
(function () {
    "use strict";

    var DEBOUNCE_MS = 300;
    var MIN_LENGTH = 2;                       // 서버 최소 검색어 길이와 같다(코드포인트 기준)
    var SAFE_PATH = /^\/(?![\/\\])[^\s\\\x00-\x1f\x7f]*$/;   // 서버(AdminSearchService)와 같은 같은 출처 경로 규칙
    var SAFE_ICON = /^[a-z0-9 -]+$/;
    var SAFE_ID = /^[1-9]\d{0,15}$/;          // JS 숫자 정밀도 안전 범위(16자리 이하)

    var form = document.querySelector(".topbar-search");
    var input = document.getElementById("topbarSearchInput");
    var results = document.getElementById("topbarSearchResults");
    var toggleButton = document.getElementById("topbarSearchToggle");
    var box = document.getElementById("topbarSearchBox");
    if (!form || !input || !results || !box) {
        return;
    }

    var generation = 0;        // 요청 세대 — 입력 변경·닫기·새 요청마다 증가
    var debounceTimer = null;
    var controller = null;     // 진행 중 요청의 AbortController
    var composing = false;     // 한글 등 IME 조합 중
    var activeIndex = -1;

    function codePointLength(text) {
        return Array.from(text).length;
    }

    function currentKeyword() {
        return input.value.trim();
    }

    function items() {
        return Array.prototype.slice.call(results.querySelectorAll(".search-item"));
    }

    /** 타이머·진행 중 요청을 취소하고 세대를 올려 늦은 응답이 화면을 바꾸지 못하게 한다. */
    function invalidatePending() {
        generation++;
        if (debounceTimer !== null) {
            clearTimeout(debounceTimer);
            debounceTimer = null;
        }
        if (controller) {
            controller.abort();
            controller = null;
        }
    }

    function open() {
        results.classList.remove("d-none");
        input.setAttribute("aria-expanded", "true");
    }

    function close() {
        invalidatePending();
        results.classList.add("d-none");
        results.textContent = "";
        input.setAttribute("aria-expanded", "false");
        input.removeAttribute("aria-activedescendant");
        activeIndex = -1;
    }

    function clearActive() {
        items().forEach(function (el) {
            el.classList.remove("active");
            el.setAttribute("aria-selected", "false");
        });
        activeIndex = -1;
        input.removeAttribute("aria-activedescendant");
    }

    function setActive(index) {
        var list = items();
        if (list.length === 0) {
            return;
        }
        clearActive();
        activeIndex = (index + list.length) % list.length;
        var el = list[activeIndex];
        el.classList.add("active");
        el.setAttribute("aria-selected", "true");
        input.setAttribute("aria-activedescendant", el.id);
        el.scrollIntoView({ block: "nearest" });
    }

    function showStatus(message, loginLink) {
        results.textContent = "";
        var div = document.createElement("div");
        div.className = "search-status";
        div.textContent = message;
        if (loginLink) {
            div.appendChild(document.createTextNode(" "));
            var a = document.createElement("a");
            a.href = "/admin/login";
            a.textContent = "로그인";
            div.appendChild(a);
        }
        results.appendChild(div);
        activeIndex = -1;
        open();
    }

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

    var itemSeq = 0;

    function buildItem(href, iconClass, primary, secondary, badge) {
        var a = element("a", "search-item");
        a.id = "topbarSearchItem" + (++itemSeq);
        a.setAttribute("role", "option");
        a.setAttribute("aria-selected", "false");
        a.href = href;

        var icon = element("span", "search-icon");
        var i = element("i", SAFE_ICON.test(iconClass) ? iconClass : "fas fa-fw fa-circle");
        icon.appendChild(i);
        a.appendChild(icon);

        var main = element("span", "search-main");
        main.appendChild(element("div", "search-primary", primary));
        if (secondary) {
            main.appendChild(element("div", "search-secondary", secondary));
        }
        a.appendChild(main);

        if (badge) {
            a.appendChild(element("span", "search-badge", badge));
        }
        return a;
    }

    function appendSection(title, section, makeItem) {
        results.appendChild(element("div", "search-section-title", title));
        section.items.forEach(function (item) {
            var node = makeItem(item);
            if (node) {
                results.appendChild(node);
            }
        });
        if (section.total > section.items.length) {
            results.appendChild(element("div", "search-section-note",
                "전체 " + section.total + "건 중 " + section.items.length + "건 — 검색어를 구체적으로 입력하세요"));
        }
    }

    function render(data) {
        results.textContent = "";
        itemSeq = 0;
        var any = false;

        if (data.menus && data.menus.items.length > 0) {
            any = true;
            appendSection("메뉴", data.menus, function (m) {
                if (typeof m.url !== "string" || !SAFE_PATH.test(m.url)) {
                    return null;   // 서버가 걸러도 화면에서 한 번 더 확인한다
                }
                return buildItem(m.url, String(m.icon || ""), m.name, m.path !== m.name ? m.path : "", "");
            });
        }
        if (data.notices && data.notices.items.length > 0) {
            any = true;
            appendSection("공지사항", data.notices, function (n) {
                var id = String(n.id);
                if (!SAFE_ID.test(id)) {
                    return null;
                }
                return buildItem("/admin/notice/manage?id=" + id, "fas fa-fw fa-bullhorn", n.title, "",
                    n.useYn === false ? "미사용" : "");
            });
        }
        if (data.members && data.members.items.length > 0) {
            any = true;
            appendSection("관리자", data.members, function (m) {
                var id = String(m.id);
                if (!SAFE_ID.test(id)) {
                    return null;
                }
                return buildItem("/admin/member/manage?id=" + id, "fas fa-fw fa-user", m.userName,
                    m.userId, m.userType === "ROLE_ADMIN" ? "ADMIN" : "MANAGER");
            });
        }

        if (!any || items().length === 0) {
            showStatus("검색 결과가 없습니다.");
            return;
        }
        activeIndex = -1;
        open();
    }

    async function search(keyword, myGeneration) {
        controller = new AbortController();
        var myController = controller;
        showStatus("검색 중...");
        try {
            var response = await fetch("/admin/api/search-results?keyword=" + encodeURIComponent(keyword), {
                headers: { "Accept": "application/json" },
                credentials: "same-origin",
                signal: myController.signal
            });
            if (myGeneration !== generation) {
                return;   // 성공·실패 모두 같은 세대 검사를 통과해야 화면을 바꾼다
            }
            if (response.status === 401) {
                showStatus("세션이 만료되었습니다.", true);
                return;
            }
            if (!response.ok) {
                showStatus("검색 중 오류가 발생했습니다.");
                return;
            }
            var data = await response.json();
            // 본문 해석 뒤에 다시 검사한다 — 해석하는 사이 입력이 바뀌었거나 닫혔을 수 있다.
            if (myGeneration !== generation || data.keyword !== currentKeyword()) {
                return;
            }
            render(data);
        } catch (error) {
            if (error && error.name === "AbortError") {
                return;
            }
            if (myGeneration !== generation) {
                return;
            }
            showStatus("검색 중 오류가 발생했습니다.");
        } finally {
            if (controller === myController) {
                controller = null;
            }
        }
    }

    /** 입력값에 맞춰 디바운스 후 검색한다. 이 함수가 호출될 때마다 이전 대기·요청은 무효화된다. */
    function schedule() {
        invalidatePending();
        clearActive();
        var keyword = currentKeyword();
        if (keyword === "") {
            close();
            return;
        }
        if (codePointLength(keyword) < MIN_LENGTH) {
            showStatus(MIN_LENGTH + "자 이상 입력하세요.");
            return;
        }
        var myGeneration = generation;
        debounceTimer = setTimeout(function () {
            debounceTimer = null;
            if (myGeneration !== generation) {
                return;
            }
            search(keyword, myGeneration);
        }, DEBOUNCE_MS);
    }

    input.addEventListener("input", function () {
        // 조합 중에는 compositionend에서 예약한다.
        if (composing) {
            return;
        }
        schedule();
    });

    input.addEventListener("compositionstart", function () {
        composing = true;
        invalidatePending();
        clearActive();
    });

    input.addEventListener("compositionend", function () {
        composing = false;
        schedule();
    });

    input.addEventListener("keydown", function (event) {
        // IME 조합 중(한글 확정 Enter 등)에는 이동 키를 소비하지 않는다.
        if (composing || event.isComposing || event.keyCode === 229) {
            return;
        }
        switch (event.key) {
            case "ArrowDown":
                if (items().length > 0) {
                    event.preventDefault();
                    setActive(activeIndex + 1);
                }
                break;
            case "ArrowUp":
                if (items().length > 0) {
                    event.preventDefault();
                    setActive(activeIndex < 0 ? items().length - 1 : activeIndex - 1);
                }
                break;
            case "Enter":
                // 폼 제출은 항상 막는다. 활성 항목이 있을 때만 그 항목으로 이동한다.
                event.preventDefault();
                if (activeIndex >= 0 && items()[activeIndex]) {
                    items()[activeIndex].click();
                }
                break;
            case "Escape":
                if (!results.classList.contains("d-none")) {
                    event.preventDefault();
                    close();   // 입력값은 유지, 포커스도 입력창에 남는다
                }
                break;
            default:
                break;
        }
    });

    input.addEventListener("focus", function () {
        // 이미 결과가 그려져 있으면 다시 보여 준다(새 요청은 하지 않는다).
        if (results.children.length > 0 && currentKeyword() !== "") {
            open();
        }
    });

    form.addEventListener("submit", function (event) {
        event.preventDefault();
    });

    document.addEventListener("click", function (event) {
        if (!form.contains(event.target)) {
            close();
        }
    });

    if (toggleButton) {
        toggleButton.addEventListener("click", function () {
            var shown = box.classList.toggle("d-block");
            box.classList.toggle("d-none", !shown);
            toggleButton.setAttribute("aria-expanded", shown ? "true" : "false");
            if (shown) {
                input.focus();
            } else {
                close();
            }
        });
    }
})();
