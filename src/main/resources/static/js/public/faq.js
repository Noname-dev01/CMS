/*
 * 공개 FAQ 즉시 필터(PLAN-faq.md 쟁점 9). 서버 검색은 없다 — 화면에 이미 있는 항목을 입력에 따라 숨기고 보인다.
 * - 스크립트가 없어도 모든 항목이 열고 닫히며 보인다(점진적 향상). 검색 입력은 서버가 hidden으로 내보내고 여기서 보이게 한다.
 * - 검색용 텍스트는 로드 시 항목마다 한 번 만든다: 블록 경계(p·li·ul·ol·h2·h3·blockquote)와 br에서 공백 하나를 넣고(인라인 요소는 구분자 없음),
 *   질문·답변 모두 NFC 정규화·소문자화·연속 공백 한 칸 압축. 입력도 같은 정규화를 한다.
 * - 일치 항목을 자동으로 펼치지 않는다(details의 사용자 상태를 건드리지 않는다).
 */
(function () {
    "use strict";

    var searchBox = document.getElementById("faq-search-box");
    var input = document.getElementById("faq-search");
    var noMatch = document.getElementById("faq-no-match");
    if (!searchBox || !input) {
        return;
    }

    var BLOCK_TAGS = {P: 1, LI: 1, UL: 1, OL: 1, H2: 1, H3: 1, BLOCKQUOTE: 1, BR: 1};

    function normalize(text) {
        var value = text;
        if (typeof value.normalize === "function") {
            value = value.normalize("NFC");
        }
        return value.toLowerCase().replace(/\s+/g, " ").trim();
    }

    function collectText(node) {
        var out = "";
        for (var child = node.firstChild; child; child = child.nextSibling) {
            if (child.nodeType === 3) {
                out += child.nodeValue;
            } else if (child.nodeType === 1) {
                var block = BLOCK_TAGS[child.tagName] === 1;
                out += (block ? " " : "") + collectText(child) + (block ? " " : "");
            }
        }
        return out;
    }

    var categories = Array.prototype.map.call(document.querySelectorAll("[data-faq-category]"), function (section) {
        var items = Array.prototype.map.call(section.querySelectorAll("[data-faq-item]"), function (item) {
            var question = item.querySelector(".faq-question");
            var answer = item.querySelector(".faq-answer");
            return {
                element: item,
                text: normalize((question ? collectText(question) : "") + " " + (answer ? collectText(answer) : ""))
            };
        });
        return {element: section, items: items};
    });

    function apply() {
        var query = normalize(input.value);
        var anyVisible = false;
        categories.forEach(function (category) {
            var visibleInCategory = false;
            category.items.forEach(function (item) {
                var match = query === "" || item.text.indexOf(query) !== -1;
                item.element.hidden = !match;
                visibleInCategory = visibleInCategory || match;
            });
            category.element.hidden = !visibleInCategory;
            anyVisible = anyVisible || visibleInCategory;
        });
        if (noMatch) {
            noMatch.hidden = anyVisible || categories.length === 0;
        }
    }

    input.addEventListener("input", apply);
    searchBox.hidden = false;
})();
