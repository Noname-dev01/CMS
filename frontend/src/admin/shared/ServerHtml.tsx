/**
 * 서버가 <b>출력 시</b> sanitizer(HtmlContentSanitizer)를 거쳐 내려준 본문 HTML을 그린다. 이 저장소의 React 코드에서 HTML을 해석하는 유일한 곳이다 —
 * 다른 값(제목·이름·입력)에 쓰지 않는다. 파일·횟수·쓰는 곳은 FrontendSourceConventionTest가 고정한다(PLAN-react-admin-posts.md 결정 1).
 */
export function ServerHtml({ id, className, html }: { id: string; className: string; html: string }) {
  return <div id={id} className={className} dangerouslySetInnerHTML={{ __html: html }} />
}
