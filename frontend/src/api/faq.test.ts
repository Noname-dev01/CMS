import { describe, expect, it } from 'vitest'
import { createFaqApi } from './faq'
import type { HttpClient, RequestOptions } from './http'

function recordingClient() {
  const calls: { url: string; options?: RequestOptions }[] = []
  const http: HttpClient = {
    async request(url, options) {
      calls.push({ url, options })
      return { kind: 'http', status: 200, ok: true, body: null, bodyError: false }
    },
  }
  return { calls, api: createFaqApi(http) }
}

describe('createFaqApi', () => {
  it('트리·상세는 GET이다', async () => {
    const { calls, api } = recordingClient()
    await api.getTree()
    await api.getFaq(5)
    expect(calls).toEqual([
      { url: '/admin/api/faqs', options: undefined },
      { url: '/admin/api/faqs/5', options: undefined },
    ])
  })

  it('카테고리 등록은 version 없이, 수정은 version과 함께 보낸다', async () => {
    const { calls, api } = recordingClient()
    await api.createCategory({ name: 'a', useYn: true, siteScope: 'COMMON' })
    await api.updateCategory(3, { name: 'b', useYn: false, siteScope: '9', version: 4 })
    await api.deleteCategory(3)
    expect(calls).toEqual([
      { url: '/admin/api/faq-categories', options: { method: 'POST', json: { name: 'a', useYn: true, siteScope: 'COMMON' } } },
      { url: '/admin/api/faq-categories/3', options: { method: 'PUT', json: { name: 'b', useYn: false, siteScope: '9', version: 4 } } },
      { url: '/admin/api/faq-categories/3', options: { method: 'DELETE' } },
    ])
  })

  it('FAQ 본문은 항상 contentFormat HTML이고, 수정만 baseCategoryId를 싣는다', async () => {
    const { calls, api } = recordingClient()
    await api.createFaq({ categoryId: 1, question: 'q', answer: '<p>a</p>', useYn: true })
    await api.updateFaq(8, { categoryId: 2, baseCategoryId: 1, question: 'q', answer: '<p>a</p>', useYn: false })
    await api.deleteFaq(8)
    expect(calls).toEqual([
      { url: '/admin/api/faqs', options: { method: 'POST', json: { categoryId: 1, question: 'q', answer: '<p>a</p>', contentFormat: 'HTML', useYn: true } } },
      { url: '/admin/api/faqs/8', options: { method: 'PUT', json: { categoryId: 2, baseCategoryId: 1, question: 'q', answer: '<p>a</p>', contentFormat: 'HTML', useYn: false } } },
      { url: '/admin/api/faqs/8', options: { method: 'DELETE' } },
    ])
  })

  it('순서 반영은 PUT /order에 version과 전체 순서를 보낸다', async () => {
    const { calls, api } = recordingClient()
    await api.saveOrder({ version: 6, categories: [{ id: 1, faqIds: [3, 2] }] })
    expect(calls).toEqual([
      { url: '/admin/api/faqs/order', options: { method: 'PUT', json: { version: 6, categories: [{ id: 1, faqIds: [3, 2] }] } } },
    ])
  })
})
