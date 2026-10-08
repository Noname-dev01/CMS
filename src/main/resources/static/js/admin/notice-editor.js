/*
 * 공지 본문 HTML 편집기(Quill 2.0.3) — adversarial-review/plan/PLAN-html-editor.md 쟁점 3·13.
 *
 * - 허용 서식(formats) = 툴바 = 서버 sanitizer(HtmlContentSanitizer) 허용 목록. 편집기 화면에서부터 허용 밖 서식이 생기지 않게 한다.
 * - 붙여넣기: 제목은 2·3단계로 바꾸고(H1→2, H4~H6→3 — 서버와 같은 매핑), /content-images/{id}가 아닌 이미지와
 *   script·style 내용은 버린다(서버 sanitizer와 같은 결과).
 * - 이미지는 툴바 버튼·파일 붙여넣기·끌어놓기 모두 업로드 API를 거쳐 /content-images/{id}로 넣는다(Quill 기본값은 data: URI라
 *   서버가 지워 "보였는데 저장하면 사라짐"이 생긴다).
 * - 업로드 경합: 세대(generation)가 바뀌면(다른 공지 열기·닫기·취소) 늦게 온 응답을 버리고, 대기 중인 삽입 위치는 편집 Delta로
 *   계속 옮기며(transformPosition), 여러 파일은 순서대로 올려 선택 순서대로 넣는다. 업로드 중에는 busy 상태를 알려 저장을 막는다.
 * - 업로드 URL: options.uploadUrl(기본 = 공지 URL). 게시판 화면은 인스턴스를 하나만 만들고 게시판을 열 때마다 setUploadUrl(url)로 바꾼다 —
 *   진행 중인 업로드를 무효화한 뒤 URL만 교체하므로 이전 게시판 출처로 늦게 올라가는 이미지가 없다(PLAN-board.md 쟁점 9·R3-4).
 * - 로딩: clipboard.convert는 서식 없는 마지막 개행을 버려 끝 빈 문단이 재편집마다 줄어든다 — Quill 자신의 초기 로딩처럼 빈 문단을
 *   하나 덧붙여 변환한다(R5-1).
 */
(function (global) {
    "use strict";

    const DEFAULT_UPLOAD_URL = "/admin/api/notices/content-images";
    const CONTENT_IMAGE_SRC = /^\/content-images\/[1-9][0-9]{0,18}$/;
    const IMAGE_MIME_TYPES = ["image/png", "image/jpeg", "image/gif"];
    const FORMATS = ["header", "bold", "italic", "underline", "strike", "list", "blockquote", "link", "image"];
    const TOOLBAR = [
        [{ header: [2, 3, false] }],
        ["bold", "italic", "underline", "strike"],
        [{ list: "ordered" }, { list: "bullet" }],
        ["blockquote", "link", "image"],
        ["clean"]
    ];

    function csrfHeaders() {
        const token = document.querySelector('meta[name="_csrf"]');
        const header = document.querySelector('meta[name="_csrf_header"]');
        const headers = {};
        if (token && header) {
            headers[header.content || "X-CSRF-TOKEN"] = token.content;
        }
        return headers;
    }

    async function errorMessageOf(response) {
        if (response.status === 403) {
            return "이미지를 올릴 권한이 없습니다. 페이지를 새로고침해 주세요.";
        }
        try {
            const body = await response.json();
            return body.message || "이미지 업로드에 실패했습니다.";
        } catch (e) {
            return "이미지 업로드에 실패했습니다.";
        }
    }

    function create(container, options) {
        const onBusyChange = options.onBusyChange || function () {};
        const onError = options.onError || function () {};
        let uploadUrl = options.uploadUrl || DEFAULT_UPLOAD_URL;
        const Delta = global.Quill.import("delta");
        // 링크 프로토콜을 서버 sanitizer(http/https/mailto)와 맞춘다 — Quill 기본값은 tel·sms도 허용해 편집기에선 링크로 보이다가 저장 후 href가 사라진다
        global.Quill.import("formats/link").PROTOCOL_WHITELIST = ["http", "https", "mailto"];

        function headerTo(level) {
            return function (node, delta) {
                return new Delta(delta.ops.map(function (op) {
                    if (op.attributes && op.attributes.header) {
                        return Object.assign({}, op, { attributes: Object.assign({}, op.attributes, { header: level }) });
                    }
                    return op;
                }));
            };
        }

        function contentImageOnly(node, delta) {
            return CONTENT_IMAGE_SRC.test(node.getAttribute("src") || "") ? delta : new Delta();
        }

        let generation = 0;
        let pending = [];       // 대기 중인 삽입: { index }
        let busyCount = 0;
        let initialHtml = "";

        const quill = new global.Quill(container, {
            theme: "snow",
            formats: FORMATS,
            modules: {
                toolbar: { container: TOOLBAR, handlers: { image: pickImages } },
                uploader: {
                    mimetypes: IMAGE_MIME_TYPES,
                    handler: function (range, files) {
                        uploadFiles(range ? range.index : quill.getLength() - 1, files);
                    }
                },
                clipboard: {
                    matchers: [
                        // script·style의 내용은 Quill 기본값이면 일반 텍스트로 들어온다 — 서버 sanitizer처럼 내용째 버린다(실기 검증 중 발견)
                        ["SCRIPT", function () { return new Delta(); }],
                        ["STYLE", function () { return new Delta(); }],
                        ["IMG", contentImageOnly],
                        ["H1", headerTo(2)],
                        ["H4", headerTo(3)],
                        ["H5", headerTo(3)],
                        ["H6", headerTo(3)]
                    ]
                }
            }
        });

        // 대기 중인 삽입 위치를 편집 내용 변화에 맞춰 옮긴다(업로드 중 앞쪽 입력·삭제에도 원래 자리에 들어가게)
        quill.on("text-change", function (delta) {
            pending.forEach(function (item) {
                item.index = delta.transformPosition(item.index);
            });
        });

        function setBusy(delta) {
            const wasBusy = busyCount > 0;
            busyCount += delta;
            if (wasBusy !== (busyCount > 0)) {
                onBusyChange(busyCount > 0);
            }
        }

        function pickImages() {
            const input = document.createElement("input");
            input.type = "file";
            input.accept = IMAGE_MIME_TYPES.join(",");
            input.multiple = true;
            input.addEventListener("change", function () {
                const range = quill.getSelection(true);
                uploadFiles(range ? range.index : quill.getLength() - 1, Array.from(input.files || []));
            });
            input.click();
        }

        async function uploadFiles(index, files) {
            if (!files || files.length === 0) {
                return;
            }
            const myGeneration = generation;
            // 모든 파일의 자리를 먼저 같은 위치로 잡는다 — 앞 파일이 들어가면 뒤 파일 위치가 transformPosition으로 밀려 순서가 지켜진다
            const items = Array.from(files).map(function () {
                const item = { index: index };
                pending.push(item);
                return item;
            });
            setBusy(1);
            try {
                for (let i = 0; i < files.length; i++) {
                    const item = items[i];
                    let url = null;
                    try {
                        const form = new FormData();
                        form.append("file", files[i]);
                        const response = await fetch(uploadUrl, { method: "POST", headers: csrfHeaders(), body: form });
                        if (myGeneration !== generation) {
                            return;     // 다른 공지로 전환·닫힘 — 이 응답은 버린다(업로드된 파일은 미참조로 남는다)
                        }
                        if (!response.ok) {
                            const message = await errorMessageOf(response);
                            if (myGeneration !== generation) {
                                return;     // 본문을 읽는 사이 전환됨 — 새 모달에 이전 공지의 오류를 표시하지 않는다
                            }
                            onError(message);
                        } else {
                            url = (await response.json()).url;
                        }
                    } catch (e) {
                        if (myGeneration !== generation) {
                            return;
                        }
                        onError("네트워크 연결을 확인해주세요.");
                    }
                    if (myGeneration !== generation) {
                        return;
                    }
                    pending = pending.filter(function (p) { return p !== item; });
                    if (url && CONTENT_IMAGE_SRC.test(url)) {
                        quill.insertEmbed(Math.min(item.index, quill.getLength() - 1), "image", url, "user");
                    }
                }
            } finally {
                if (myGeneration === generation) {
                    pending = pending.filter(function (p) { return items.indexOf(p) < 0; });
                    setBusy(-1);
                }
            }
        }

        /** 진행 중인 업로드를 모두 무효화한다(늦은 응답은 버려진다). */
        function invalidate() {
            generation++;
            pending = [];
            if (busyCount > 0) {
                busyCount = 0;
                onBusyChange(false);
            }
        }

        /** 내용을 통째로 교체한다(삽입 아님 — R1-3). 업로드 무효화·undo 이력 초기화 포함. */
        function setHtml(html) {
            invalidate();
            quill.setContents(quill.clipboard.convert({ html: (html || "") + "<p><br></p>", text: "\n" }), "silent");
            quill.history.clear();
            initialHtml = quill.getSemanticHTML();
        }

        /** 업로드 대상 URL을 바꾼다. 진행 중인 업로드(다른 게시판 출처)는 먼저 무효화한다. */
        function setUploadUrl(url) {
            invalidate();
            uploadUrl = url || DEFAULT_UPLOAD_URL;
        }

        return {
            setHtml: setHtml,
            setUploadUrl: setUploadUrl,
            invalidate: invalidate,
            getHtml: function () { return quill.getSemanticHTML(); },
            isChanged: function () { return quill.getSemanticHTML() !== initialHtml; },
            isBusy: function () { return busyCount > 0; },
            focus: function () { quill.focus(); },
            quill: quill
        };
    }

    global.NoticeEditor = { create: create };
})(window);
