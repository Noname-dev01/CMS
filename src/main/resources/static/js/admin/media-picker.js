/*
 * 미디어 라이브러리 이미지 선택 모달 — adversarial-review/plan/PLAN-media-library.md D10.
 * 배너·팝업 폼과 본문 편집기가 하나를 공유한다. 호출 페이지에 jQuery·Bootstrap(모달)이 로딩돼 있어야 한다.
 *
 *   MediaPicker.open({ title: "배너 이미지 선택", maxBytes: 2 * 1024 * 1024, onPick: function (item) { ... } });
 *
 * - 목록은 GET /admin/api/media?selectable=true — 서버가 호출자의 MEDIA 읽기 권한과 "고를 수 있는 출처"만 걸러서 준다(화면은 표시만 한다).
 * - maxBytes보다 큰 이미지는 회색으로 표시하고 고를 수 없다(서버가 선택 시점에 한 번 더 검증한다).
 * - 파일명 등 사용자 입력은 항상 textContent로만 넣는다.
 */
(function (global) {
    "use strict";

    const LIST_URL = "/admin/api/media";
    const PAGE_SIZE = 12;

    let modal = null;
    let ui = null;
    let state = { page: 0, keyword: "", options: null, seq: 0, totalPages: 0 };

    function formatSize(bytes) {
        if (bytes <= 0) {
            return "0KB";
        }
        if (bytes >= 1024 * 1024) {
            return (bytes / (1024 * 1024)).toFixed(1) + "MB";
        }
        return Math.max(1, Math.round(bytes / 1024)) + "KB";
    }

    function build() {
        modal = document.createElement("div");
        modal.className = "modal fade";
        modal.tabIndex = -1;
        modal.setAttribute("role", "dialog");
        modal.setAttribute("aria-hidden", "true");
        // 폼 모달 위에 중첩해서 열리므로, 닫힐 때 Bootstrap이 body.modal-open을 지워도 아래 모달의 스크롤·포커스를 되살린다.
        global.jQuery(modal).on("hidden.bs.modal", function () {
            const underlying = document.querySelector(".modal.show");
            if (underlying !== null) {
                document.body.classList.add("modal-open");
                underlying.focus();
            }
        });

        const dialog = document.createElement("div");
        dialog.className = "modal-dialog modal-lg";
        dialog.setAttribute("role", "document");
        const content = document.createElement("div");
        content.className = "modal-content";

        const header = document.createElement("div");
        header.className = "modal-header";
        const title = document.createElement("h5");
        title.className = "modal-title";
        const closeX = document.createElement("button");
        closeX.type = "button";
        closeX.className = "close";
        closeX.setAttribute("data-dismiss", "modal");
        closeX.setAttribute("aria-label", "닫기");
        closeX.textContent = "×";
        header.appendChild(title);
        header.appendChild(closeX);

        const body = document.createElement("div");
        body.className = "modal-body";

        const searchForm = document.createElement("form");
        searchForm.className = "form-inline mb-3";
        const keyword = document.createElement("input");
        keyword.type = "search";
        keyword.className = "form-control mr-2";
        keyword.maxLength = 200;
        keyword.placeholder = "파일명 또는 번호 검색";
        keyword.setAttribute("aria-label", "이미지 검색");
        const searchButton = document.createElement("button");
        searchButton.type = "submit";
        searchButton.className = "btn btn-outline-primary";
        searchButton.textContent = "검색";
        searchForm.appendChild(keyword);
        searchForm.appendChild(searchButton);

        const message = document.createElement("div");
        message.className = "alert alert-warning d-none";
        message.setAttribute("role", "alert");

        const grid = document.createElement("div");
        grid.className = "row";

        const pager = document.createElement("div");
        pager.className = "d-flex justify-content-between align-items-center mt-3";
        const prev = document.createElement("button");
        prev.type = "button";
        prev.className = "btn btn-sm btn-outline-secondary";
        prev.textContent = "이전";
        const pageInfo = document.createElement("span");
        pageInfo.className = "text-muted small";
        const next = document.createElement("button");
        next.type = "button";
        next.className = "btn btn-sm btn-outline-secondary";
        next.textContent = "다음";
        pager.appendChild(prev);
        pager.appendChild(pageInfo);
        pager.appendChild(next);

        body.appendChild(searchForm);
        body.appendChild(message);
        body.appendChild(grid);
        body.appendChild(pager);

        const footer = document.createElement("div");
        footer.className = "modal-footer";
        const closeButton = document.createElement("button");
        closeButton.type = "button";
        closeButton.className = "btn btn-secondary";
        closeButton.setAttribute("data-dismiss", "modal");
        closeButton.textContent = "닫기";
        footer.appendChild(closeButton);

        content.appendChild(header);
        content.appendChild(body);
        content.appendChild(footer);
        dialog.appendChild(content);
        modal.appendChild(dialog);
        document.body.appendChild(modal);

        ui = { title: title, keyword: keyword, message: message, grid: grid, prev: prev, next: next, pageInfo: pageInfo };

        searchForm.addEventListener("submit", function (event) {
            event.preventDefault();
            state.keyword = keyword.value.trim();
            load(0);
        });
        prev.addEventListener("click", function () { load(state.page - 1); });
        next.addEventListener("click", function () { load(state.page + 1); });
    }

    function showMessage(text) {
        ui.message.textContent = text;
        ui.message.classList.toggle("d-none", !text);
    }

    function render(page) {
        ui.grid.textContent = "";
        const maxBytes = state.options.maxBytes || null;
        if (page.content.length === 0) {
            const empty = document.createElement("div");
            empty.className = "col-12 text-muted";
            empty.textContent = "고를 수 있는 이미지가 없습니다. 미디어 라이브러리에서 먼저 업로드하세요.";
            ui.grid.appendChild(empty);
        }
        page.content.forEach(function (item) {
            const tooBig = maxBytes !== null && item.fileSize > maxBytes;
            const col = document.createElement("div");
            col.className = "col-6 col-md-3 mb-3";
            const card = document.createElement("button");
            card.type = "button";
            card.className = "btn btn-light border w-100 p-1 text-left" + (tooBig ? " disabled" : "");
            card.setAttribute("aria-disabled", tooBig ? "true" : "false");
            const thumb = document.createElement("img");
            thumb.loading = "lazy";
            thumb.alt = "";
            thumb.src = item.previewUrl;
            thumb.style.width = "100%";
            thumb.style.height = "90px";
            thumb.style.objectFit = "cover";
            thumb.style.background = "#eaecf4";
            if (tooBig) {
                thumb.style.opacity = "0.4";
            }
            const name = document.createElement("div");
            name.className = "small text-truncate mt-1";
            name.textContent = item.originalName || ("이미지 #" + item.id);
            const meta = document.createElement("div");
            meta.className = "small text-muted";
            meta.textContent = formatSize(item.fileSize) + (tooBig ? " · 용도 초과" : "");
            card.appendChild(thumb);
            card.appendChild(name);
            card.appendChild(meta);
            card.addEventListener("click", function () {
                if (tooBig) {
                    showMessage("이 용도에는 " + formatSize(maxBytes) + " 이하의 이미지만 쓸 수 있습니다.");
                    return;
                }
                const onPick = state.options.onPick;
                global.jQuery(modal).modal("hide");
                if (onPick) {
                    onPick(item);
                }
            });
            col.appendChild(card);
            ui.grid.appendChild(col);
        });
        state.totalPages = page.totalPages;
        ui.prev.disabled = page.page <= 0;
        ui.next.disabled = page.last;
        ui.pageInfo.textContent = page.totalElements === 0 ? "" : (page.page + 1) + " / " + Math.max(page.totalPages, 1) + " 페이지 · 총 " + page.totalElements + "개";
    }

    async function load(pageNumber) {
        if (pageNumber < 0) {
            return;
        }
        const seq = ++state.seq;
        showMessage("");
        const params = new URLSearchParams({ selectable: "true", page: String(pageNumber), size: String(PAGE_SIZE) });
        if (state.keyword) {
            params.set("keyword", state.keyword);
        }
        try {
            const response = await fetch(LIST_URL + "?" + params.toString(), { headers: { "Accept": "application/json" } });
            if (seq !== state.seq) {
                return;
            }
            if (response.status === 403) {
                showMessage("미디어 라이브러리를 사용할 권한이 없습니다.");
                return;
            }
            if (!response.ok) {
                showMessage("이미지 목록을 불러오지 못했습니다.");
                return;
            }
            const page = await response.json();
            if (seq !== state.seq) {
                return;
            }
            state.page = page.page;
            render(page);
        } catch (e) {
            if (seq === state.seq) {
                showMessage("이미지 목록을 불러오지 못했습니다. 네트워크 상태를 확인해 주세요.");
            }
        }
    }

    function open(options) {
        if (!global.jQuery || !global.jQuery.fn.modal) {
            throw new Error("MediaPicker에는 jQuery와 Bootstrap 모달이 필요합니다.");
        }
        if (modal === null) {
            build();
        }
        state.options = options || {};
        state.keyword = "";
        ui.keyword.value = "";
        ui.title.textContent = state.options.title || "미디어 라이브러리에서 선택";
        showMessage("");
        ui.grid.textContent = "";
        global.jQuery(modal).modal("show");
        load(0);
    }

    global.MediaPicker = { open: open };
})(window);
