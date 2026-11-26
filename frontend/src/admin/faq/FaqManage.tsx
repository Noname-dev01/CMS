/**
 * FAQ 관리 화면(React 아일랜드). 옛 인라인 스크립트(templates/admin/faq/manage.html, 2026-11-22까지)의 동작을 그대로 옮긴 것이다 —
 * DOM 계약(id·클래스·data-*·disabled·d-none·#faqModal[data-state])은 브라우저 시험(FaqAdminOrderBrowserTest·FaqModalBrowserTest)이 고정한다.
 *
 * 상태 원칙(PLAN-react-admin-faq.md 결정 6):
 * - 화면에 그리는 상태는 view(useState)에 두고, 이벤트 핸들러가 즉시 최신 값을 읽도록 같은 값을 viewRef에 동기적으로 반영한다(update()).
 * - 같은 실행 흐름에서 즉시 읽혀야 하는 전환 정보(세대·모달 상태·미저장 기준값)는 ref에만 둔다.
 * - 편집기·모달 같은 외부 부수 효과는 핸들러가 세대를 확인한 뒤에만 실행한다(늦은 응답은 화면 상태에 닿기 전에 버린다).
 */
import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { createPortal, flushSync } from 'react-dom'
import type { FaqApi, FaqDetail, FaqTree } from '../../api/faq'
import { errorMessage, type HttpResult } from '../../api/http'
import { COMMON, preserveScope, scopeValue, type ScopeSite } from '../shared/siteScope'
import { FaqModal } from './FaqModal'
import { FaqTreeView } from './FaqTreeView'
import {
  categorySnapshot,
  classifyWrite,
  faqSnapshot,
  formLocked,
  interactionLocked,
  isDirty,
  locate,
  moveCategory,
  moveFaq,
  moveSummary,
  resolveCategorySelection,
  serverOrder,
  type CategoryForm,
  type FaqForm,
  type OrderGroup,
  type OrderItem,
} from './state'

const UNKNOWN_RESULT_MESSAGE =
  '저장 여부를 확인할 수 없습니다. 모달을 닫고 [새로고침]으로 목록을 다시 불러온 뒤 반영되었는지 직접 확인하세요. 입력은 닫으면 사라지니 필요하면 복사해 두세요.'

/** 이동 안내를 늦추는 시간. 옮긴 버튼에 포커스를 되돌리며 읽는 소리가 안내를 덮지 않게 한다(메뉴 편집 화면과 같다). */
const ANNOUNCE_DELAY = 500

export type ModalState = 'closed' | 'open' | 'closing'
export type ModalMode = 'category' | 'faq'

export interface View {
  server: FaqTree | null
  order: OrderGroup[]
  busy: boolean
  loadingDetail: boolean
  syncFailed: boolean
  writing: boolean
  initFailed: boolean
  pageAlert: string | null
  pageSuccess: string | null
  modalAlert: string | null
  modalState: ModalState
  modalMode: ModalMode
  editingCategoryId: number | null
  catTitle: string
  catForm: CategoryForm
  scopeExtras: string[]
  editingFaq: FaqDetail | null
  faqTitle: string
  faqForm: FaqForm
  moveStatus: string
}

const EMPTY_CATEGORY: CategoryForm = { name: '', useYn: true, siteScope: COMMON }

const INITIAL: View = {
  server: null,
  order: [],
  busy: false,
  loadingDetail: false,
  syncFailed: false,
  writing: false,
  initFailed: false,
  pageAlert: null,
  pageSuccess: null,
  modalAlert: null,
  modalState: 'closed',
  modalMode: 'category',
  editingCategoryId: null,
  catTitle: '새 카테고리',
  catForm: EMPTY_CATEGORY,
  scopeExtras: [],
  editingFaq: null,
  faqTitle: '새 FAQ',
  faqForm: { categoryId: '', question: '', useYn: true },
  moveStatus: '',
}

type Baseline = { kind: 'category'; value: string } | { kind: 'faq'; value: string }
type WriteResult = { ok: boolean; status?: number }

export interface FaqManageProps {
  api: FaqApi
  permissions: ReadonlySet<string>
  sites: ScopeSite[]
  /** 모달을 그릴 곳 — 옛 화면처럼 #wrapper 밖(body 직속)에 둔다 */
  modalContainer: HTMLElement
}

function idLabel(saved: unknown): string {
  if (saved !== null && typeof saved === 'object' && 'id' in saved && saved.id) {
    return ': #' + String(saved.id)
  }
  return ''
}

export function FaqManage({ api, permissions, sites, modalContainer }: FaqManageProps) {
  const canCreate = permissions.has('FAQ:CREATE')
  const canUpdate = permissions.has('FAQ:UPDATE')
  const canDelete = permissions.has('FAQ:DELETE')

  const [view, setView] = useState<View>(INITIAL)
  const viewRef = useRef<View>(INITIAL)
  const update = useCallback((patch: Partial<View>) => {
    viewRef.current = { ...viewRef.current, ...patch }
    setView(viewRef.current)
  }, [])

  const loadSeq = useRef(0)
  const detailSeq = useRef(0)
  const modalState = useRef<ModalState>('closed')
  const baseline = useRef<Baseline | null>(null)
  const categoryBase = useRef<{ id: number; version: number } | null>(null)
  const faqBase = useRef<{ id: number; categoryId: number } | null>(null)
  const announceTimer = useRef(0)
  const modalEl = useRef<HTMLDialogElement>(null)
  const editorHost = useRef<HTMLDivElement>(null)
  const editor = useRef<NoticeEditorInstance | null>(null)

  // ===================== 공통 =====================

  const dirty = () => isDirty(viewRef.current.server, viewRef.current.order)
  const locked = () => interactionLocked(viewRef.current)
  const formIsLocked = () => formLocked(viewRef.current, dirty())
  const modalIsShowing = () => modalState.current === 'open'
  const hasCategory = (id: number) => viewRef.current.server?.categories.some((c) => c.id === id) ?? false
  const findFaq = (id: number) => {
    for (const c of viewRef.current.server?.categories ?? []) {
      const faq = c.faqs.find((f) => f.id === id)
      if (faq) return faq
    }
    return undefined
  }

  // 페이지 상단 알림(모달 밖) — 모달을 닫으면서 안내할 때 쓴다.
  const showPageError = (message: string) => update({ pageSuccess: null, pageAlert: message })
  // 모달이 떠 있는 동안의 오류는 모달 안에 보인다(페이지 상단 알림은 배경 뒤에 가려진다). 닫히는 중·닫힌 뒤는 페이지 상단.
  const showError = (message: string) => {
    if (modalIsShowing()) {
      update({ modalAlert: message })
    } else {
      showPageError(message)
    }
  }
  const showSuccess = (message: string) => update({ pageAlert: null, pageSuccess: message })

  const setModalState = (state: ModalState) => {
    modalState.current = state
    if (modalEl.current) {
      modalEl.current.dataset.state = state   // 브라우저 시험이 읽는다 — React prop이 아니라 직접 둔다
    }
    update({ modalState: state })
  }

  // ===================== 서버 상태 읽기 =====================

  const setServer = (tree: FaqTree) => {
    announceMove('')
    const v = viewRef.current
    const preserve = modalIsShowing() && v.modalMode === 'faq'
    update({
      server: tree,
      order: serverOrder(tree),
      faqForm: { ...v.faqForm, categoryId: resolveCategorySelection(tree, v.faqForm.categoryId, preserve) },
    })
    // 편집 중이던 항목이 새 트리에 없다(다른 관리자가 지웠다) — 폼을 생성 모드로 비우지 않고 안내만 한다(입력은 둔다).
    if (modalIsShowing()
        && ((v.modalMode === 'category' && v.editingCategoryId !== null && !tree.categories.some((c) => c.id === v.editingCategoryId))
          || (v.modalMode === 'faq' && v.editingFaq !== null && !tree.categories.some((c) => c.faqs.some((f) => f.id === v.editingFaq?.id))))) {
      showError('이 항목이 삭제되었거나 목록에 없습니다. 저장하면 실패할 수 있으니 닫고 목록을 확인해 주세요.')
    }
  }

  /** 목록을 읽어 서버 상태와 초안을 새로 만든다. busy 상태는 호출자가 관리한다. 성공 여부를 돌려준다. */
  const fetchTree = async (): Promise<boolean> => {
    const seq = ++loadSeq.current
    const result = await api.getTree()
    if (result.kind === 'network' || (result.ok && result.bodyError)) {
      showError('목록을 불러오지 못했습니다. 네트워크 상태를 확인해 주세요.')
      return false
    }
    if (!result.ok) {
      showError(errorMessage(result, '목록을 불러오지 못했습니다.'))
      return false
    }
    if (seq !== loadSeq.current) {
      return true   // 더 새로운 요청이 있거나 초안이 시작됐다 — 이 응답은 버린다
    }
    setServer(result.body as FaqTree)
    return true
  }

  const reload = async () => {
    if (viewRef.current.busy || viewRef.current.loadingDetail || dirty()) {
      return
    }
    update({ busy: true })
    const ok = await fetchTree()
    update({ busy: false })
    if (ok) {
      update({ syncFailed: false, initFailed: false })
      if (viewRef.current.pageAlert !== null && viewRef.current.server) {
        update({ pageAlert: null })
      }
    } else {
      update({ syncFailed: true })
    }
  }

  /**
   * 쓰기 요청 → 성공하면 목록 재조회. 쓰기 결과와 목록 동기화를 분리한다: 요청이 실패하면 폼을 그대로 두고, 쓰기는 성공했는데 재조회가
   * 실패하면 syncFailed로 [새로고침]만 허용하며, 응답을 잃으면(헤더 전 실패) 결과를 모르는 상태로 보고 자동 재전송하지 않는다.
   */
  const write = async (
    request: () => Promise<HttpResult>,
    successMessage: string | ((saved: unknown) => string),
    onSuccess?: (saved: unknown) => void,
  ): Promise<WriteResult> => {
    update({ busy: true, writing: true })
    const result = await request()
    const outcome = classifyWrite(result)
    if (result.kind === 'network' || outcome === 'unknown') {
      update({ busy: false, writing: false, syncFailed: true })
      showError(UNKNOWN_RESULT_MESSAGE)
      return { ok: false }
    }
    update({ writing: false })   // 응답이 왔다 — 이후 재조회가 지연돼도 모달을 닫을 수 있다
    if (outcome !== 'ok') {
      const message = errorMessage(result, '저장에 실패했습니다.')
      update({ busy: false })
      showError(message)
      if (outcome === 'conflict') {
        // 낡은 화면·상한·자식 있는 카테고리 등 — 서버 상태가 바뀌었을 수 있어 목록을 다시 읽는다(초안은 버려진다).
        // 모달이 열려 있으면 닫지 않고 입력을 보존한다. busy는 재조회가 끝날 때까지 이 호출이 소유한다.
        update({ busy: true })
        const loaded = await fetchTree()
        update({ busy: false, syncFailed: !loaded })
        showError(message + (loaded ? ' (서버의 최신 목록으로 새로고침했습니다.)' : '')
          + (modalIsShowing() ? ' 입력한 내용은 그대로 두었으니 복사해 두고, 닫은 뒤 다시 선택해 최신 내용을 불러오세요.' : ''))
      }
      return { ok: false, status: result.status }
    }
    const body = result.body   // 204·본문 유실이면 null — 쓰기는 성공한 것이다
    try {
      showSuccess(typeof successMessage === 'function' ? successMessage(body) : successMessage)
      onSuccess?.(body)
    } catch {
      // 성공 처리 중 예외가 나도 busy가 풀리지 않는 영구 잠금이 되면 안 된다 — 목록 재조회로 계속한다
    }
    const loaded = await fetchTree()
    update({ busy: false })
    if (!loaded) {
      update({ syncFailed: true })
      showError('저장은 완료됐지만 목록을 불러오지 못했습니다. [새로고침]을 눌러 목록을 다시 불러오세요.')
    }
    return { ok: true }
  }

  // ===================== 순서 초안 =====================

  const setOrder = (next: OrderGroup[]) => {
    if (next === viewRef.current.order) return
    loadSeq.current++   // 진행 중인 이전 목록 응답이 초안을 덮지 못하게 폐기한다
    update({ order: next })
  }

  /** 비운 뒤 늦게 채운다. 빈 문구는 대기 중인 안내까지 지운다. */
  const announceMove = (text: string) => {
    window.clearTimeout(announceTimer.current)
    update({ moveStatus: '' })
    if (text !== '') announceTimer.current = window.setTimeout(() => update({ moveStatus: text }), ANNOUNCE_DELAY)
  }

  const canMove = () => canUpdate && !locked()

  /** 초안에서 item을 형제 중 to번째로 옮긴다(▲▼·드래그 공용). 막힌 이동이면 아무것도 하지 않는다. */
  const onMove = (item: OrderItem, to: number) => {
    const { server, order } = viewRef.current
    const at = locate(order, item)
    if (!canMove() || !server || !at) return
    const next = item.kind === 'category' ? moveCategory(order, at.index, to) : moveFaq(order, at.cat, at.index, to)
    if (next === order) return
    setOrder(next)
    announceMove(moveSummary(server, next, item))
  }

  const onCancelOrder = () => {
    const v = viewRef.current
    if (v.busy || !v.server) return
    loadSeq.current++
    update({ order: serverOrder(v.server) })
    announceMove('')
  }

  const onApplyOrder = async () => {
    const v = viewRef.current
    if (v.busy || v.syncFailed || !dirty() || !v.server) return
    const body = { version: v.server.version, categories: v.order }
    await write(() => api.saveOrder(body), '순서를 반영했습니다.')
    // 400(구조 위반)이면 초안은 남겨 고치거나 취소할 수 있게 한다(409는 write()가 최신 목록으로 되돌렸다)
  }

  // ===================== 모달 =====================

  /** 모드·폼을 DOM에 반영한 뒤(flushSync) 모달을 연다. 새로 여는 동작은 모달이 완전히 닫혀 있을 때만 호출한다(호출자가 가드한다). */
  const openModal = (mode: ModalMode, patch: Partial<View>) => {
    flushSync(() => update({ ...patch, modalMode: mode }))
    const el = modalEl.current
    if (!el) return
    // 배경 스크롤을 잠그면(admin-manage.css) 스크롤바가 사라져 본문 폭이 변한다 — 그 폭만큼 채운다(scrollbar-gutter는 dialog와 배경막에도 빈 자리를 남긴다).
    const scrollbar = window.innerWidth - document.documentElement.clientWidth
    document.body.style.paddingRight = scrollbar > 0 ? scrollbar + 'px' : ''
    el.showModal()   // 동기로 열린다 — 이 직후의 closeModal()도 무시되지 않는다
    el.scrollTop = 0   // 초기 포커스가 화면보다 긴 모달을 아래로 스크롤한다
    setModalState('open')
  }

  /** 확인 없이 닫는다. close 이벤트는 나중에 오므로 그때까지 closing이다(다시 열기는 closed에서만). */
  const closeModal = () => {
    if (modalState.current !== 'open') return
    setModalState('closing')
    modalEl.current?.close()
  }

  const isFormDirty = () => {
    const base = baseline.current
    if (base === null) return false
    const v = viewRef.current
    return base.kind === 'category'
      ? categorySnapshot(v.catForm) !== base.value
      : faqSnapshot(v.faqForm) !== base.value || (editor.current?.isChanged() ?? false)
  }

  const resetCategoryForm = (): Partial<View> => {
    categoryBase.current = null
    return { editingCategoryId: null, catTitle: '새 카테고리', catForm: EMPTY_CATEGORY }
  }

  const resetFaqForm = (): Partial<View> => {
    faqBase.current = null
    editor.current?.setHtml('')
    return { editingFaq: null, faqTitle: '새 FAQ', faqForm: { ...viewRef.current.faqForm, question: '', useYn: true } }
  }

  // ×·[닫기]로 닫을 때 미저장 입력이 있으면 확인한다. 취소하면 닫지 않는다.
  const requestClose = () => {
    if (isFormDirty() && !window.confirm('저장하지 않은 변경이 있습니다. 버리고 닫으시겠습니까?')) return
    closeModal()
  }

  const onModalClosed = () => {
    document.body.style.paddingRight = ''
    setModalState('closed')
    baseline.current = null
    // 상세 조회 중에 닫아도 세대를 올리고 loadingDetail을 풀어, 늦게 온 응답이 새 폼을 덮지 못하고 다음 수정 진입이 막히지 않는다.
    detailSeq.current++
    update({ modalAlert: null, loadingDetail: false, ...resetCategoryForm(), ...resetFaqForm() })
  }

  // closedby를 모르는 브라우저에서 ESC를 연속으로 누르면 cancel 취소만으로는 닫힌다 — keydown을 막으면 닫기 요청이 처리되지 않는다.
  // 저장 중에는 포커스를 쥔 입력이 비활성화돼 키 이벤트 대상이 body가 되므로 document에서 듣는다.
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && modalState.current !== 'closed') {
        event.preventDefault()
      }
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [])

  // 편집기는 한 번만 만든다(파괴 API가 없고 페이지 수명과 같다). 호스트는 React가 자식을 관리하지 않는 빈 요소다 —
  // Quill이 툴바를 편집기 요소 앞에 직접 끼워 넣기 때문이다.
  useEffect(() => {
    const host = editorHost.current
    if (!host || editor.current) return
    const container = document.createElement('div')
    container.id = 'faqAnswerEditor'
    host.appendChild(container)
    // 이미지 없는 편집기 — 서버도 저장·출력 시 img를 제거한다
    editor.current = window.NoticeEditor.create(container, { allowImages: false, onError: (message) => showError(message) })
  }, [])

  // ===================== 카테고리 폼 =====================

  const startCategoryCreate = () => {
    if (modalState.current !== 'closed' || formIsLocked()) return
    const patch = { modalAlert: null, ...resetCategoryForm() }
    openModal('category', patch)
    baseline.current = { kind: 'category', value: categorySnapshot(EMPTY_CATEGORY) }
  }

  // 진입(행 클릭)은 수정 권한을 요구하지 않는다 — 저장 버튼이 권한이 없으면 숨는다.
  const startCategoryEdit = (id: number) => {
    const category = viewRef.current.server?.categories.find((c) => c.id === id)
    if (modalState.current !== 'closed' || formIsLocked() || !category) return
    const siteScope = scopeValue(category.siteId)
    const form: CategoryForm = { name: category.name, useYn: category.useYn, siteScope }
    categoryBase.current = { id: category.id, version: category.version }
    baseline.current = { kind: 'category', value: categorySnapshot(form) }
    openModal('category', {
      modalAlert: null,
      editingCategoryId: id,
      catTitle: '카테고리 수정 #' + id,
      catForm: form,
      scopeExtras: preserveScope(sites, viewRef.current.scopeExtras, siteScope),
    })
  }

  const onCategorySubmit = async (event: FormEvent) => {
    event.preventDefault()
    if (formIsLocked()) return
    const v = viewRef.current
    const editingId = v.editingCategoryId
    const form = v.catForm
    const base = categoryBase.current
    // 성공하면 응답 직후 모달을 닫는다(재조회를 기다리지 않는다) — 닫힘 처리가 폼을 초기화한다
    await write(
      () => editingId === null || base === null
        ? api.createCategory({ name: form.name, useYn: form.useYn, siteScope: form.siteScope })
        : api.updateCategory(editingId, { name: form.name, useYn: form.useYn, siteScope: form.siteScope, version: base.version }),
      (saved) => (editingId === null ? '카테고리를 등록했습니다' : '카테고리를 저장했습니다') + idLabel(saved),
      () => closeModal(),
    )
  }

  const onCategoryDelete = async () => {
    const id = viewRef.current.editingCategoryId
    if (formIsLocked() || id === null) return
    if (!window.confirm('카테고리 #' + id + '을(를) 삭제하시겠습니까?\nFAQ가 남아 있으면 삭제되지 않습니다.')) return
    await write(() => api.deleteCategory(id), '카테고리를 삭제했습니다: #' + id, () => closeModal())
  }

  // ===================== FAQ 폼 =====================

  const startFaqCreate = () => {
    const tree = viewRef.current.server
    if (modalState.current !== 'closed' || formIsLocked() || !tree) return
    const reset = resetFaqForm()
    const form: FaqForm = { question: '', useYn: true, categoryId: resolveCategorySelection(tree, viewRef.current.faqForm.categoryId, false) }
    openModal('faq', { modalAlert: null, ...reset, faqForm: form })
    baseline.current = { kind: 'faq', value: faqSnapshot(form) }
  }

  // 진입(행 클릭)은 수정 권한을 요구하지 않는다. 수정 모달은 연 직후 상세를 읽는다 — 그동안 loadingDetail이 폼과 편집기를 통째로 잠근다.
  const startFaqEdit = async (id: number) => {
    if (modalState.current !== 'closed' || formIsLocked() || !findFaq(id)) return
    const seq = ++detailSeq.current
    baseline.current = null   // 상세가 도착해 폼을 채울 때까지는 변경 없음 — 확인 없이 닫을 수 있다
    openModal('faq', { modalAlert: null, loadingDetail: true, faqTitle: 'FAQ를 불러오는 중…' })
    try {
      const result = await api.getFaq(id)
      if (seq !== detailSeq.current) return
      if (result.kind === 'network' || (result.ok && result.bodyError)) {
        showPageError('FAQ를 불러오지 못했습니다. 네트워크 상태를 확인해 주세요.')
        closeModal()
        return
      }
      if (!result.ok) {
        // 불러오지 못한 FAQ는 보여 줄 것이 없다 — 모달을 닫는다. 404면 목록을 다시 읽고, 재조회가 성공하면 알림을 지우므로 안내는 재조회 뒤에 띄운다.
        const message = errorMessage(result, 'FAQ를 불러오지 못했습니다.')
        update({ loadingDetail: false })
        closeModal()
        if (result.status === 404) {
          await reload()
        }
        showPageError(message)
        return
      }
      const detail = result.body as FaqDetail
      if (!hasCategory(detail.categoryId)) {
        // 상세(최신)와 트리(옛 조회)가 어긋났다 — 선택 박스가 소속 카테고리를 복원하지 못하면 다른 카테고리로 조용히 이동 저장될 수 있으므로
        // 저장하지 않고 모달을 닫고 목록을 다시 읽는다. 다시 항목을 눌러 열면 된다.
        update({ loadingDetail: false })
        closeModal()
        await reload()
        showPageError('FAQ의 카테고리가 화면 목록에 없습니다. 최신 목록으로 새로고침했습니다. 다시 선택해 주세요.')
        return
      }
      const form: FaqForm = { categoryId: String(detail.categoryId), question: detail.question, useYn: detail.useYn }
      faqBase.current = { id: detail.id, categoryId: detail.categoryId }
      editor.current?.setHtml(detail.answerHtml)   // 세대를 확인한 뒤에만 편집기를 바꾼다
      update({ editingFaq: detail, faqTitle: 'FAQ 수정 #' + detail.id, faqForm: form })
      baseline.current = { kind: 'faq', value: faqSnapshot(form) }   // 서버 값으로 채운 직후가 기준이다(편집기는 setHtml이 자체 기준을 잡는다)
    } finally {
      if (seq === detailSeq.current) {
        update({ loadingDetail: false })
      }
    }
  }

  const onFaqSubmit = async (event: FormEvent) => {
    event.preventDefault()
    if (formIsLocked()) return
    if (editor.current?.isBusy()) {
      showError('편집기가 아직 처리 중입니다. 잠시 후 다시 시도해 주세요.')
      return
    }
    const v = viewRef.current
    // 선택한 카테고리가 사라졌다(임시 옵션) — 요청 없이 막는다. 복구 수단은 모드마다 다르다.
    if (!hasCategory(Number(v.faqForm.categoryId))) {
      showError(v.editingFaq === null
        ? '선택한 카테고리가 삭제되었습니다. 존재하는 다른 카테고리를 선택해 주세요.'
        : '선택한 카테고리가 삭제되었습니다. 닫은 뒤 이 FAQ를 다시 선택해 최신 내용을 불러오세요.')
      return
    }
    const target = v.editingFaq   // 요청 시작 시점의 대상과 낡은 화면 검사 기준을 고정한다
    const base = faqBase.current
    const body = {
      categoryId: Number(v.faqForm.categoryId),
      question: v.faqForm.question,
      answer: editor.current?.getHtml() ?? '',
      useYn: v.faqForm.useYn,
    }
    await write(
      () => target === null || base === null ? api.createFaq(body) : api.updateFaq(target.id, { ...body, baseCategoryId: base.categoryId }),
      (saved) => (target === null ? 'FAQ를 등록했습니다' : 'FAQ를 저장했습니다') + idLabel(saved),
      () => closeModal(),
    )
  }

  const onFaqDelete = async () => {
    const target = viewRef.current.editingFaq
    if (formIsLocked() || target === null) return
    const id = target.id
    if (!window.confirm('FAQ #' + id + '을(를) 삭제하시겠습니까?\n복구할 수 없습니다.')) return
    await write(() => api.deleteFaq(id), 'FAQ를 삭제했습니다: #' + id, () => closeModal())
  }

  // ===================== 수명 =====================

  useEffect(() => {
    const onBeforeUnload = (event: BeforeUnloadEvent) => {
      if (isDirty(viewRef.current.server, viewRef.current.order)) {
        event.preventDefault()
        event.returnValue = ''
      }
    }
    window.addEventListener('beforeunload', onBeforeUnload)
    return () => window.removeEventListener('beforeunload', onBeforeUnload)
  }, [])

  useEffect(() => () => window.clearTimeout(announceTimer.current), [])

  const initialized = useRef(false)
  useEffect(() => {
    if (initialized.current) return
    initialized.current = true
    void (async () => {
      update({ busy: true })
      const ok = await fetchTree()
      update({ busy: false, syncFailed: !ok, initFailed: !ok && viewRef.current.server === null })
    })()
  }, [])

  // ===================== 렌더링 =====================

  const viewDirty = isDirty(view.server, view.order)
  const viewInteractionLocked = interactionLocked(view)
  const viewFormLocked = formLocked(view, viewDirty)

  // 편집기는 fieldset으로 잠기지 않는다 — 폼 잠금을 따라 직접 켜고 끈다.
  useEffect(() => {
    editor.current?.quill.enable(!viewFormLocked)
  }, [viewFormLocked])

  const faqCount = view.server
    ? '(카테고리 ' + view.server.categories.length + '개 · FAQ ' + view.server.categories.reduce((n, c) => n + c.faqs.length, 0) + '개)'
    : ''

  return (
    <>
      <div id="pageAlert" className={'alert alert-danger' + (view.pageAlert === null ? ' d-none' : '')} role="alert">{view.pageAlert ?? ''}</div>
      <div id="pageSuccess" className={'alert alert-success' + (view.pageSuccess === null ? ' d-none' : '')} role="alert">{view.pageSuccess ?? ''}</div>

      <p className="text-muted small">
        공개 <code>/faq</code>에 카테고리별 아코디언으로 노출할 질문과 답변을 관리합니다. 카테고리는 <strong>30개</strong>, FAQ는 <strong>200개</strong>까지 등록할 수 있습니다.{' '}
        <strong>카테고리를 숨기면 그 안의 FAQ가 모두 공개되지 않습니다.</strong> 순서는 왼쪽 손잡이(⠿)를 끌거나 ▲▼ 버튼으로 바꾼 뒤 <strong>[순서 반영]</strong>을 눌러야 저장됩니다
        (같은 카테고리 안에서만 끌 수 있고, 다른 카테고리로 옮기려면 FAQ를 수정해 카테고리를 바꾸세요). 답변에는 이미지를 넣을 수 없습니다.
      </p>

      <div className="row">
        <div className="col-12 mb-4">
          <div className="faq-card">
            <div className="card-header py-3 d-flex justify-content-between align-items-center">
              <h6 className="m-0 font-weight-bold text-primary">카테고리·FAQ <small className="text-muted" id="faqCount">{faqCount}</small></h6>
              <div>
                <button type="button" className={'btn btn-sm btn-primary' + (canCreate ? '' : ' d-none')} id="btnCatNew" disabled={viewFormLocked} onClick={startCategoryCreate}>새 카테고리</button>{' '}
                <button type="button" className={'btn btn-sm btn-primary' + (canCreate ? '' : ' d-none')} id="btnFaqNew" disabled={viewFormLocked} onClick={startFaqCreate}>새 FAQ</button>{' '}
                <button type="button" className="btn btn-sm btn-outline-secondary" id="btnReload" disabled={view.busy || view.loadingDetail || viewDirty} onClick={() => void reload()}>새로고침</button>
              </div>
            </div>
            <div className="card-body">
              <div id="draftBar" className={'alert alert-warning align-items-center justify-content-between ' + (viewDirty ? 'd-flex' : 'd-none')} role="status">
                <span>순서가 변경되었습니다. 아직 저장되지 않았습니다.</span>
                <span>
                  <button type="button" className="btn btn-sm btn-primary" id="btnApplyOrder" disabled={view.busy || view.syncFailed} onClick={() => void onApplyOrder()}>순서 반영</button>{' '}
                  <button type="button" className="btn btn-sm btn-outline-secondary" id="btnCancelOrder" disabled={view.busy} onClick={onCancelOrder}>취소</button>
                </span>
              </div>
              {/* 목록 밖에 항상 그려 둔다 — 다시 만들어지면 안내가 읽히지 않는다 */}
              <div id="orderMoveStatus" className="sr-only" role="status" aria-live="polite">{view.moveStatus}</div>
              <FaqTreeView
                view={view}
                sites={sites}
                canUpdate={canUpdate}
                interactionLocked={viewInteractionLocked}
                onCategoryClick={startCategoryEdit}
                onFaqClick={(id) => void startFaqEdit(id)}
                canMove={canMove}
                onMove={onMove}
              />
            </div>
          </div>
        </div>
      </div>

      {createPortal(
        <FaqModal
          view={view}
          sites={sites}
          modalRef={modalEl}
          editorHostRef={editorHost}
          formLocked={viewFormLocked}
          canCreate={canCreate}
          canUpdate={canUpdate}
          canDelete={canDelete}
          onCategoryForm={(patch) => update({ catForm: { ...viewRef.current.catForm, ...patch } })}
          onFaqForm={(patch) => update({ faqForm: { ...viewRef.current.faqForm, ...patch } })}
          onCategorySubmit={(event) => void onCategorySubmit(event)}
          onFaqSubmit={(event) => void onFaqSubmit(event)}
          onCategoryDelete={() => void onCategoryDelete()}
          onFaqDelete={() => void onFaqDelete()}
          onRequestClose={requestClose}
          onClosed={onModalClosed}
        />,
        modalContainer,
      )}
    </>
  )
}
