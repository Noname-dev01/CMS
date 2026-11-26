/** 스크롤 영역의 보이는 위·아래 가장자리에서 이 거리 안이면 자동 스크롤한다. */
const EDGE = 40
const SCROLL_STEP = 8

/**
 * 포인터가 가장자리에 있는 스크롤 영역을 안쪽부터 한 걸음 움직인다. 움직였으면 true. 관리자 화면에서 실제로 스크롤되는 것은 목록 상자가 아니라
 * #content-wrapper와 문서다(메뉴 트리 상자는 부모 높이가 auto라 내용만큼 커진다).
 */
export function scrollAt(list: HTMLElement | null, y: number): boolean {
  const areas: Element[] = []
  for (let el = list?.parentElement ?? null; el; el = el.parentElement) {
    if (el === document.scrollingElement || /auto|scroll/.test(getComputedStyle(el).overflowY)) areas.push(el)
  }
  return areas.some((area) => {
    const rect = area.getBoundingClientRect()
    const isDocument = area === document.scrollingElement
    const top = isDocument ? 0 : Math.max(0, rect.top)
    const bottom = isDocument ? window.innerHeight : Math.min(window.innerHeight, rect.bottom)
    const step = y < top + EDGE ? -SCROLL_STEP : y > bottom - EDGE ? SCROLL_STEP : 0
    const before = area.scrollTop
    area.scrollTop += step
    return area.scrollTop !== before
  })
}
