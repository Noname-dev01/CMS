/** 사이트 목록 API(`/admin/api/sites`)의 타입과 호출 함수 — 서버 `SiteResponse` 중 화면이 쓰는 필드만 적는다. */
import type { HttpClient, HttpResult } from './http'

export interface Site {
  id: number
  siteKey: string
  siteName: string
  siteType: 'ADMIN' | 'PUBLIC'
  allowExternalLink: boolean
  useYn: boolean
}

export function createSiteApi(http: HttpClient) {
  return {
    list: (): Promise<HttpResult> => http.request('/admin/api/sites'),
  }
}

export type SiteApi = ReturnType<typeof createSiteApi>
