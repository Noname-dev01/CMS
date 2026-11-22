/**
 * 템플릿이 <script>로 먼저 싣는 전역(관리자 공용 스크립트)의 타입 — 번들에 넣지 않고 그대로 재사용한다(PLAN-react-admin-faq.md 결정 7).
 * - NoticeEditor: static/js/admin/notice-editor.js(Quill 래퍼, sanitizer 허용 목록과 1:1)
 * - $: jQuery + Bootstrap 4 모달 플러그인
 */
export {}

declare global {
  interface NoticeEditorInstance {
    setHtml(html: string): void
    getHtml(): string
    isChanged(): boolean
    isBusy(): boolean
    quill: { enable(enabled: boolean): void }
  }

  interface NoticeEditorOptions {
    allowImages?: boolean
    onError?: (message: string) => void
  }

  interface Window {
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
