/**
 * 콘텐츠 관리 화면(배너·팝업·FAQ 카테고리·정적 페이지)의 "표시 대상" 선택 박스 헬퍼(PLAN-site-menu.md PR ③ S7·S8).
 *
 * 선택 박스는 서버가 렌더링한다(첫 옵션 value="COMMON" = 모든 공개 사이트 공통, 이어서 value=사이트 ID). 이름은 관리자가 입력한 값이라
 * 항상 textContent로만 다룬다.
 *
 * 낡은 목록 방어: 문서를 연 뒤 다른 관리자가 만든 사이트의 전용 콘텐츠를 편집 폼에 불러오면 그 ID가 옵션에 없다. 그때 select.value를 그대로
 * 대입하면 선택이 비워져 "공통"으로 저장될 수 있으므로, 없는 값은 "사이트 #ID (목록에 없음)" 옵션을 추가해 보존한다.
 */
(function () {
    "use strict";

    var COMMON = "COMMON";

    function create(select) {
        var names = new Map();
        var keys = new Map();      // 사이트 ID → 사이트 키(공개 경로 접두어). 선택 박스가 data-key로 싣는 화면에서만 채워진다.
        var actives = new Map();   // 사이트 ID → 활성 여부(data-active)
        Array.prototype.forEach.call(select.options, function (option) {
            if (option.value !== COMMON) {
                names.set(option.value, option.textContent);
                if (option.dataset.key) {
                    keys.set(option.value, option.dataset.key);
                }
                actives.set(option.value, option.dataset.active === "true");
            }
        });

        function keyOf(siteId) {
            return siteId === null || siteId === undefined ? COMMON : String(siteId);
        }

        return {
            /** 목록 배지에 쓸 이름. 공통이면 "공통". 옵션에 없는 사이트는 "사이트 #ID". */
            label: function (siteId) {
                if (siteId === null || siteId === undefined) {
                    return "공통";
                }
                var name = names.get(String(siteId));
                return name !== undefined ? name : "사이트 #" + siteId;
            },
            /** 사이트 키. 알 수 없으면 null(공통·옵션에 없는 사이트). */
            key: function (siteId) {
                return siteId === null || siteId === undefined ? null : (keys.get(String(siteId)) || null);
            },
            /** 활성 공개 사이트인가. 알 수 없으면 false. */
            isActive: function (siteId) {
                return siteId !== null && siteId !== undefined && actives.get(String(siteId)) === true;
            },
            /** 목록 행용 배지 요소(textContent만 사용). */
            badge: function (siteId) {
                var span = document.createElement("span");
                var common = siteId === null || siteId === undefined;
                span.className = "badge " + (common ? "badge-light border" : "badge-info");
                span.textContent = this.label(siteId);
                return span;
            },
            /** 서버가 준 siteId(null = 공통)를 선택 박스에 반영한다. 옵션에 없으면 추가해서 값을 보존한다. */
            set: function (siteId) {
                var value = keyOf(siteId);
                var exists = Array.prototype.some.call(select.options, function (option) { return option.value === value; });
                if (!exists) {
                    var option = document.createElement("option");
                    option.value = value;
                    option.textContent = "사이트 #" + value + " (목록에 없음)";
                    select.appendChild(option);
                    names.set(value, option.textContent);
                }
                select.value = value;
            },
            /** 요청에 실을 값: "COMMON" 또는 사이트 ID 문자열. */
            value: function () {
                return select.value;
            },
            reset: function () {
                select.value = COMMON;
            }
        };
    }

    window.ContentScope = { create: create, COMMON: COMMON };
})();
