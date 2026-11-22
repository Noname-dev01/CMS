/**
 * FAQ·FAQ 카테고리 관리 API(`/admin/api/faqs`·`/admin/api/faq-categories`)의 타입과 호출 함수.
 * 화면 무관 모듈이다 — 서버 DTO(`FaqTreeResponse`·`FaqResponse`·`FaqRequest`·`FaqCategory*Request`·`FaqOrderRequest`)와 같은 모양을 유지한다.
 */
import type { HttpClient, HttpResult } from './http'

export interface FaqTreeItem {
  id: number
  categoryId: number
  question: string
  ord: number
  useYn: boolean
}

export interface FaqTreeCategory {
  id: number
  name: string
  ord: number
  useYn: boolean
  /** null = 공통(모든 공개 사이트) */
  siteId: number | null
  version: number
  faqs: FaqTreeItem[]
}

/** 트리와 구조 version은 서버가 한 읽기 트랜잭션에서 만든다(`FaqService.getTree`). */
export interface FaqTree {
  version: number
  categories: FaqTreeCategory[]
}

export interface FaqDetail {
  id: number
  categoryId: number
  question: string
  answerHtml: string
  ord: number
  useYn: boolean
}

/** "COMMON" 또는 사이트 ID 문자열 */
export type SiteScopeValue = string

export interface CategoryCreate {
  name: string
  useYn: boolean
  siteScope: SiteScopeValue
}

export interface CategoryUpdate extends CategoryCreate {
  /** 편집을 시작할 때의 카테고리 version — 다르면 409 */
  version: number
}

export interface FaqCreate {
  categoryId: number
  question: string
  answer: string
  useYn: boolean
}

export interface FaqUpdate extends FaqCreate {
  /** 편집을 시작할 때의 소속 카테고리 — 현재 소속과 다르면 409 */
  baseCategoryId: number
}

export interface OrderRequest {
  version: number
  categories: { id: number; faqIds: number[] }[]
}

const FAQS = '/admin/api/faqs'
const CATEGORIES = '/admin/api/faq-categories'

export function createFaqApi(http: HttpClient) {
  const id = (value: number) => encodeURIComponent(String(value))
  return {
    getTree: (): Promise<HttpResult> => http.request(FAQS),
    getFaq: (faqId: number): Promise<HttpResult> => http.request(`${FAQS}/${id(faqId)}`),
    createCategory: (body: CategoryCreate) => http.request(CATEGORIES, { method: 'POST', json: body }),
    updateCategory: (categoryId: number, body: CategoryUpdate) =>
      http.request(`${CATEGORIES}/${id(categoryId)}`, { method: 'PUT', json: body }),
    deleteCategory: (categoryId: number) => http.request(`${CATEGORIES}/${id(categoryId)}`, { method: 'DELETE' }),
    createFaq: (body: FaqCreate) =>
      http.request(FAQS, { method: 'POST', json: { categoryId: body.categoryId, question: body.question, answer: body.answer, contentFormat: 'HTML', useYn: body.useYn } }),
    updateFaq: (faqId: number, body: FaqUpdate) =>
      http.request(`${FAQS}/${id(faqId)}`, {
        method: 'PUT',
        json: { categoryId: body.categoryId, baseCategoryId: body.baseCategoryId, question: body.question, answer: body.answer, contentFormat: 'HTML', useYn: body.useYn },
      }),
    deleteFaq: (faqId: number) => http.request(`${FAQS}/${id(faqId)}`, { method: 'DELETE' }),
    saveOrder: (body: OrderRequest) => http.request(`${FAQS}/order`, { method: 'PUT', json: body }),
  }
}

export type FaqApi = ReturnType<typeof createFaqApi>
