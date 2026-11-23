/**
 * 템플릿이 <script>로 먼저 싣는 전역(관리자 공용 스크립트)의 타입 — 번들에 넣지 않고 그대로 재사용한다(PLAN-react-admin-faq.md 결정 7).
 * - NoticeEditor: static/js/admin/notice-editor.js(Quill 래퍼, sanitizer 허용 목록과 1:1)
 * - MediaPicker: static/js/admin/media-picker.js(미디어 라이브러리 선택 모달)
 * - $: jQuery + Bootstrap 4 모달 플러그인
 */
export {}

declare global {
  interface NoticeEditorInstance {
    setHtml(html: string): void
    getHtml(): string
    isChanged(): boolean
    isBusy(): boolean
    /** 본문에 들어 있는 이미지 번호(등장 순서, 중복 제거) */
    getImageIds(): number[]
    /** 진행 중인 업로드를 무효화한 뒤 업로드 주소를 바꾼다 */
    setUploadUrl(url: string | null): void
    /** 새 이미지 입력만 켜고 끈다(이미 든 이미지는 유지) */
    setImageUploadEnabled(enabled: boolean): void
    /** 진행 중인 업로드를 무효화한다(늦은 응답을 버린다) */
    invalidate(): void
    quill: { enable(enabled: boolean): void }
  }

  interface NoticeEditorOptions {
    allowImages?: boolean
    onError?: (message: string) => void
    /** 본문이 바뀔 때마다(setHtml 교체는 제외) */
    onChange?: () => void
    onBusyChange?: (busy: boolean) => void
    /** 있으면 "라이브러리" 버튼이 생긴다 */
    pickLibraryImage?: ((insert: (item: { id: number }) => void) => void) | null
  }

  interface Window {
    MediaPicker: { open(options: { title: string; onPick: (item: { id: number }) => void }): void }
    NoticeEditor: { create(container: HTMLElement, options: NoticeEditorOptions): NoticeEditorInstance }
  }

  interface BootstrapModalEvent {
    preventDefault(): void
  }

  interface JQueryModalTarget {
    modal(command: 'show' | 'hide'): void
    on(events: string, handler: (event: BootstrapModalEvent) => void): void
    off(events: string): void
  }

  const $: (element: Element) => JQueryModalTarget
}
