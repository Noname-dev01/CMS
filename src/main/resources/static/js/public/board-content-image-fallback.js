/*
 * 공개 게시글 상세의 본문 이미지 실패 대체(PLAN-content-image-rate-limit.md D1·D2).
 * - 본문 이미지(/content-images/{id})가 실패하면(429 한도 초과·404·500) 깨진 이미지 아이콘 대신 안내 문구 상자로 바꾼다.
 *   본문 이미지는 내용의 일부라 고정 안내 문구와 함께 원래 alt(공백이 아니면)를 글자 그대로 보여준다(textContent만 사용, innerHTML 금지).
 * - 원인은 그대로이고 다시 요청하지 않는다(재시도는 한도를 더 쓴다). 새로고침하면 회복된다.
 * - error 이벤트는 버블링되지 않으므로 document의 캡처 단계에서 받는다. <head>에서 defer 없이 읽혀 이미지 요청보다 먼저 리스너가 붙고,
 *   그래도 붙기 전에 실패한 이미지는 DOMContentLoaded에서 한 번 더 정리한다.
 */
(function () {
    "use strict";

    var CONTENT_IMAGE = ".board-detail-content img";

    function replaceWithNotice(img) {
        var box = document.createElement("span");
        box.className = "board-content-image-failed";
        // role="img"를 쓰지 않는다 — 이 역할은 자식 텍스트를 접근성 이름으로 쓰지 않아 안내 문구와 alt가 스크린리더에 전달되지 않는다. 일반 텍스트로 읽히게 한다.
        box.textContent = "이미지를 불러오지 못했습니다";
        var alt = (img.getAttribute("alt") || "").trim();
        if (alt !== "") {
            var altNode = document.createElement("span");
            altNode.className = "board-content-image-alt";
            altNode.textContent = alt;
            box.appendChild(altNode);
        }
        img.replaceWith(box);
    }

    document.addEventListener("error", function (event) {
        var target = event.target;
        if (target instanceof HTMLImageElement && target.matches(CONTENT_IMAGE)) {
            replaceWithNotice(target);
        }
    }, true);

    document.addEventListener("DOMContentLoaded", function () {
        Array.prototype.forEach.call(document.querySelectorAll(CONTENT_IMAGE), function (img) {
            // complete ∧ naturalWidth 0 = 이미 실패한 이미지(아직 요청 전인 이미지는 complete가 false라 건드리지 않는다)
            if (img.complete && img.naturalWidth === 0) {
                replaceWithNotice(img);
            }
        });
    });
})();
