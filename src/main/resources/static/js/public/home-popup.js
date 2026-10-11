/*
 * 공개 메인 레이어 팝업(PLAN-popup.md 쟁점 9).
 * - 서버가 hidden으로 그린 팝업 중 "오늘 하루 보지 않기" 상태가 아닌 것만 연다. 이미지는 data-src → src로 이때 처음 내려받는다.
 * - 숨김 상태는 브라우저 localStorage에만 둔다(서버는 모른다): cms.popup.hide.{id} = {v: 팝업 revision, until: 다음 자정(로컬, epoch ms)}.
 *   v가 현재 팝업의 revision과 다르면(관리자가 수정함) 무시하고 다시 보인다.
 * - localStorage 접근은 전부 try/catch — 막혀 있으면 저장 없이 매번 보인다.
 */
(function () {
    "use strict";

    var KEY_PREFIX = "cms.popup.hide.";

    function readState(id) {
        try {
            var raw = window.localStorage.getItem(KEY_PREFIX + id);
            return raw ? JSON.parse(raw) : null;
        } catch (e) {
            return null;
        }
    }

    function writeState(id, state) {
        try {
            window.localStorage.setItem(KEY_PREFIX + id, JSON.stringify(state));
        } catch (e) {
            // 저장할 수 없으면 이번 방문에서만 닫는다
        }
    }

    function nextLocalMidnight() {
        var date = new Date();
        date.setHours(24, 0, 0, 0);
        return date.getTime();
    }

    function isHiddenToday(popup) {
        var state = readState(popup.getAttribute("data-popup-id"));
        return !!state
            && String(state.v) === popup.getAttribute("data-version")
            && Number(state.until) > Date.now();
    }

    function open(popup) {
        var image = popup.querySelector("img[data-src]");
        if (image) {
            image.src = image.getAttribute("data-src");
            image.removeAttribute("data-src");
        }
        popup.hidden = false;
    }

    function close(popup) {
        popup.hidden = true;
    }

    function visiblePopups() {
        return Array.prototype.filter.call(document.querySelectorAll(".home-popup"), function (popup) {
            return !popup.hidden;
        });
    }

    Array.prototype.forEach.call(document.querySelectorAll(".home-popup"), function (popup) {
        if (!isHiddenToday(popup)) {
            open(popup);
        }
    });

    document.addEventListener("click", function (event) {
        var target = event.target instanceof Element ? event.target : null;
        var popup = target ? target.closest(".home-popup") : null;
        if (!popup) {
            return;
        }
        if (target.closest("[data-popup-hide-today]")) {
            writeState(popup.getAttribute("data-popup-id"), {
                v: popup.getAttribute("data-version"),
                until: nextLocalMidnight()
            });
            close(popup);
        } else if (target.closest("[data-popup-close]")) {
            close(popup);
        }
    });

    document.addEventListener("keydown", function (event) {
        if (event.key !== "Escape") {
            return;
        }
        var visible = visiblePopups();
        if (visible.length > 0) {
            close(visible[visible.length - 1]);
        }
    });
})();
