/** 선택 가능한 Font Awesome 5 Free(solid) 아이콘. 저장 값은 사이드바가 그대로 class로 쓰는 "fas fa-fw fa-<이름>"이다. */
const ICON_NAMES = ('home tachometer-alt chart-line chart-bar chart-pie table list list-alt clipboard-list clipboard-check ' +
  'file file-alt folder folder-open book bookmark bullhorn bell envelope comment comments inbox ' +
  'user users user-plus user-shield user-lock user-cog id-badge id-card address-book lock unlock key shield-alt ' +
  'cog cogs wrench tools sliders-h bars sitemap network-wired database server cloud hdd ' +
  'calendar calendar-alt clock history search filter tag tags flag star heart image images camera video music ' +
  'map-marker-alt globe link paperclip download upload print edit pen trash plus minus check times ' +
  'info-circle question-circle exclamation-triangle shopping-cart credit-card wallet building briefcase ' +
  'laptop mobile-alt desktop code terminal bug rocket gift lightbulb palette layer-group th-large circle').split(' ')

/** 값에서 아이콘 이름(fa-xxx 중 fa-fw 제외)을 뽑는다. 목록 밖의 기존 값도 처리한다. */
export function iconNameOf(value: string): string {
  const token = value.split(/\s+/).find((t) => t.startsWith('fa-') && t !== 'fa-fw')
  return token ? token.substring(3) : ''
}

export function filterIcons(keyword: string): string[] {
  const query = keyword.trim().toLowerCase()
  return ICON_NAMES.filter((name) => name.includes(query))
}
