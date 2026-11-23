/**
 * 메뉴 관리 API(`/admin/api/menus`)의 타입과 호출 함수. 화면 무관 모듈이다 — 서버 DTO(`MenuTreeResponse`·`MenuResponse`·
 * `MenuCreateRequest`·`MenuUpdateRequest`·`MenuStructureRequest`)와 같은 모양을 유지한다.
 * 쓰기는 본문이 필요 없어 2xx 헤더로 성공을 판정한다(skipSuccessBody).
 */
import type { HttpClient, HttpResult } from './http'

/** 서버 JSON은 null 필드를 생략한다(non_null) — 최상위의 upMenuNo, 레거시 행의 ord가 없을 수 있다. */
export interface MenuTreeNode {
  id: string
  text: string
  data: {
    menuNo: number
    useYn?: boolean
    ord?: number
    upMenuNo?: number
    exposureLabel?: string
  }
  children?: MenuTreeNode[]
}

export interface MenuDetail {
  menuNo: number
  menuName: string
  menuUrl?: string
  menuIcon?: string
  menuDesc?: string
  useYn?: boolean
}

/** 순서(ord)는 없다 — 구조·순서는 트리 드래그 + [반영]으로만 바꾼다(낡은 폼이 확정된 순서를 되돌리는 것을 막는다). */
export interface MenuFields {
  menuName: string
  menuUrl: string
  menuIcon: string
  menuDesc: string
  useYn: boolean
}

export interface MenuCreate extends MenuFields {
  upMenuNo: number | null
  siteId: number
}

/** 네 필드 모두 필수다(누락은 400) — null도 명시해서 보낸다. 같은 upMenuNo 안의 배열 순서가 새 순서다. */
export interface MenuStructureItem {
  menuNo: number
  baseUpMenuNo: number | null
  baseOrd: number | null
  upMenuNo: number | null
}

export interface MenuStructure {
  siteId: number
  menus: MenuStructureItem[]
}

const MENUS = '/admin/api/menus'

export function createMenuApi(http: HttpClient) {
  const one = (menuNo: number) => `${MENUS}/${encodeURIComponent(String(menuNo))}`
  return {
    getTree: (siteId: number, includeInactive: boolean): Promise<HttpResult> =>
      http.request(`${MENUS}/tree?siteId=${encodeURIComponent(String(siteId))}&useYn=${includeInactive ? 'all' : 'true'}`),
    getMenu: (menuNo: number): Promise<HttpResult> => http.request(one(menuNo)),
    create: (body: MenuCreate) => http.request(MENUS, { method: 'POST', json: body, skipSuccessBody: true }),
    update: (menuNo: number, body: MenuFields) => http.request(one(menuNo), { method: 'PATCH', json: body, skipSuccessBody: true }),
    /** useYn만 보낸다 — 저장하지 않은 폼 값을 싣지 않는다. */
    deactivate: (menuNo: number) => http.request(one(menuNo), { method: 'PATCH', json: { useYn: false }, skipSuccessBody: true }),
    applyStructure: (body: MenuStructure) => http.request(`${MENUS}/structure`, { method: 'PUT', json: body, skipSuccessBody: true }),
    remove: (menuNo: number) => http.request(one(menuNo), { method: 'DELETE', skipSuccessBody: true }),
  }
}

export type MenuApi = ReturnType<typeof createMenuApi>
