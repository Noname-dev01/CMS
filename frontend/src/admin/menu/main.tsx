/**
 * 메뉴 구조 편집 화면 진입점 — templates/admin/menu/fragments/menu-tab.html이 #menuManageRoot·#menuModalRoot를 렌더링한다.
 * 서버 모델 데이터는 없다(사이트·메뉴는 모두 API로 읽는다).
 */
import { flushSync } from 'react-dom'
import { createRoot } from 'react-dom/client'
import { createHttpClient } from '../../api/http'
import { createMenuApi } from '../../api/menu'
import { createSiteApi } from '../../api/site'
import { metaCsrf } from '../shared/csrf'
import { MenuManage } from './MenuManage'

const root = document.getElementById('menuManageRoot')
const modalContainer = document.getElementById('menuModalRoot')
if (root && modalContainer) {
  const http = createHttpClient(metaCsrf)
  // 서버가 그리던 마크업처럼 load 이벤트 시점에 화면이 있도록 첫 렌더링을 동기로 끝낸다
  flushSync(() => {
    createRoot(root).render(<MenuManage menuApi={createMenuApi(http)} siteApi={createSiteApi(http)} modalContainer={modalContainer} />)
  })
}
