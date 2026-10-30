/*
 * 공개 게시판 갤러리형·웹진형 목록의 썸네일 실패 대체(PLAN-board-definition.md PR 6 후속).
 * - 썸네일(/content-images/{id}?size=thumb)이 실패하면(429 한도 초과·404 비공개 전환/파일 유실·500) 깨진 이미지 아이콘 대신
 *   대표 없는 글과 같은 자리표시(.board-thumb-empty)로 바꾼다. 원인은 그대로이고 다시 요청하지 않는다(재시도는 한도를 더 쓴다).
 * - error 이벤트는 버블링되지 않으므로 document의 캡처 단계에서 받는다 — 지연 로딩으로 스크롤할 때 나중에 실패하는 칸도 잡힌다.
 * - <head>에서 defer 없이 읽혀 이미지 요청보다 먼저 리스너가 붙는다. 그래도 붙기 전에 실패한 칸은 DOMContentLoaded에서 한 번 더 정리한다.
 */
(function () {
    "use strict";

    var THUMB_IMAGE = ".board-thumb img";

    function replaceWithPlaceholder(img) {
        var holder = document.createElement("span");
        holder.className = "board-thumb-empty";
        holder.setAttribute("aria-hidden", "true");
        img.replaceWith(holder);
    }

    document.addEventListener("error", function (event) {
        var target = event.target;
        if (target instanceof HTMLImageElement && target.matches(THUMB_IMAGE)) {
            replaceWithPlaceholder(target);
        }
    }, true);

    document.addEventListener("DOMContentLoaded", function () {
        Array.prototype.forEach.call(document.querySelectorAll(THUMB_IMAGE), function (img) {
            // complete ∧ naturalWidth 0 = 이미 실패한 이미지(아직 요청 전인 지연 로딩 이미지는 complete가 false라 건드리지 않는다)
            if (img.complete && img.naturalWidth === 0) {
                replaceWithPlaceholder(img);
            }
        });
    });
})();
