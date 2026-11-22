/**
 * FAQ 관리 화면 진입점 — templates/admin/faq/manage.html이 #faqManageRoot(권한 키)·#faqScopeSites(표시 대상 사이트)·#faqModalRoot를 렌더링한다.
 * 서버 데이터는 Thymeleaf 기본 이스케이프를 거친 data-*·텍스트로만 받는다(인라인 스크립트·JSON 직렬화 없음, PLAN-react-admin-faq.md 결정 8).
 */
import { createRoot } from 'react-dom/client'
import { createFaqApi } from '../../api/faq'
import { createHttpClient } from '../../api/http'
import { metaCsrf } from '../shared/csrf'
import type { ScopeSite } from '../shared/siteScope'
import { FaqManage } from './FaqManage'

const root = document.getElementById('faqManageRoot')
const modalContainer = document.getElementById('faqModalRoot')
if (root && modalContainer) {
  // 버튼 표시용일 뿐 서버 판정(403)이 최종이다.
  const permissions = new Set((root.dataset.permissions ?? '').split(',').map((key) => key.trim()).filter((key) => key !== ''))
  const sites: ScopeSite[] = Array.from(document.querySelectorAll<HTMLElement>('#faqScopeSites > li')).map((item) => ({
    id: item.dataset.id ?? '',
    name: item.textContent ?? '',
    active: item.dataset.active === 'true',
  }))
  const api = createFaqApi(createHttpClient(metaCsrf))
  createRoot(root).render(<FaqManage api={api} permissions={permissions} sites={sites} modalContainer={modalContainer} />)
}
