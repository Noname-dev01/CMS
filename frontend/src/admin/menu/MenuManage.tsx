/**
 * 메뉴 구조 편집 화면(React 아일랜드). 옛 인라인 스크립트(templates/admin/menu/fragments/menu-tab.html, 2026-11까지)의 동작을 옮긴 것이다 —
 * DOM 계약(id·문구·disabled·d-none)은 MenuStructureScreenBrowserTest·SiteMenuScreenBrowserTest가 고정한다.
 *
 * 상태 원칙(PLAN-react-admin-menu.md 결정 2): 화면에 그리는 상태는 view에 두고 같은 값을 viewRef에 동기적으로 반영해(update()) 핸들러가 즉시
 * 최신 값을 읽는다. 세대(loadSeq·selectSeq)를 확인하기 전에는 늦은 응답이 상태를 바꾸지 못한다.
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import { createPortal, flushSync } from 'react-dom'
import { errorMessage, type HttpResult } from '../../api/http'
import type { MenuApi, MenuDetail, MenuFields, MenuTreeNode } from '../../api/menu'
import type { Site, SiteApi } from '../../api/site'
import { escapeForConfirm } from './confirmText'
import { IconPickerModal, PermanentDeleteModal } from './MenuModals'
import { MenuForm } from './MenuForm'
import { MenuTree } from './MenuTree'
import { allowsExternal, menuUrlError } from './menuUrl'
import { buildStructureRequest, countDraftChanges, findNode, indexBase, MAX_DEPTH, moveNode, toNodes, type BaseStructure, type DropPosition, type MenuNode } from './tree'

/** 관리자 사이트 ID(시드 id=1) — 기억한 사이트가 사라졌을 때 돌아갈 곳. 서버 Site.ADMIN_SITE_ID와 같다. */
const ADMIN_SITE_ID = 1
const SITE_KEY_STORAGE = 'menuSiteId'
/** 반영 성공 뒤 페이지를 다시 읽는 동안 성공 메시지를 잃지 않도록 남긴다. */
const APPLIED_KEY = 'menuStructureApplied'

const IDLE_LABEL = '메뉴를 선택하거나 추가 버튼을 눌러주세요.'
const BUSY_MESSAGE = '다른 작업이 진행 중입니다. 잠시 후 다시 시도해주세요.'

// 신규 메뉴는 기본 활성으로 생성한다(API의 생성 기본값 useYn=true와 일치)
const EMPTY_FORM: MenuFields = { menuName: '', menuUrl: '', menuIcon: '', menuDesc: '', useYn: true }

type Notice = { kind: 'error' | 'warning' | 'success'; text: string }
/** 구조 편집(드래그)은 비활성까지 포함한 'all'에서만 한다 — 반영 요청이 전체 메뉴 집합이어야 한다. 조회 중에는 null. */
type TreeScope = 'all' | 'active' | null

interface View {
  sites: Site[]
  /** 셀렉트가 보여 주는 사이트 = 편집 대상 */
  site: Site | null
  /** 사이트를 바꾼 뒤 새 트리가 적용되기 전 — 저장·삭제·추가·반영·선택을 모두 막는다 */
  switching: boolean
  includeInactive: boolean
  treeScope: TreeScope
  /** 화면의 트리(초안 포함) */
  nodes: MenuNode[]
  base: BaseStructure
  /** 트리가 교체될 때마다 오른다(MenuTree의 key) */
  treeVersion: number
  /** 트리에서 강조된 메뉴 — 확정된 편집 대상(target)과 별개다. [하위 추가]는 이것을 부모로 쓴다 */
  treeSelection: number | null
  mode: 'edit' | 'create' | null
  /** 상세 응답이 확정한 편집 대상. 비활성화·영구삭제는 폼이 아니라 이 스냅샷을 쓴다 */
  target: { menuNo: number; menuName: string; useYn: boolean } | null
  pendingParentNo: number | null
  form: MenuFields
  exposure: string
  modeLabel: string
  busy: boolean
  treeError: string | null
  treeWarning: string | null
  treeSuccess: string | null
  formError: string | null
  reloading: boolean
  iconKeyword: string
  /** 영구삭제 모달을 연 순간 복사해 고정한 대상 — 이름 확인과 DELETE는 이 복사본만 쓴다 */
  deleteTarget: { menuNo: number; menuName: string } | null
  deleteInput: string
  deleteError: string | null
}

const INITIAL: View = {
  sites: [],
  site: null,
  switching: false,
  includeInactive: true,
  treeScope: null,
  nodes: [],
  base: new Map(),
  treeVersion: 0,
  treeSelection: null,
  mode: null,
  target: null,
  pendingParentNo: null,
  form: EMPTY_FORM,
  exposure: '-',
  modeLabel: IDLE_LABEL,
  busy: false,
  treeError: null,
  treeWarning: null,
  treeSuccess: null,
  formError: null,
  reloading: false,
  iconKeyword: '',
  deleteTarget: null,
  deleteInput: '',
  deleteError: null,
}

const NO_TREE_ALERTS = { treeError: null, treeWarning: null, treeSuccess: null }

/** 저장소를 쓸 수 없으면(비공개 모드 등) 기억 없이 진행한다. */
function storage<T>(use: (session: Storage) => T): T | null {
  try {
    return use(window.sessionStorage)
  } catch {
    return null
  }
}

/** 읽기 응답이면 본문, 아니면 사용자 문구. */
function readBody<T>(result: HttpResult, failMessage: string, errorFallback: string): { body: T } | { error: string } {
  if (result.kind === 'network' || (result.ok && result.bodyError)) return { error: errorFallback }
  return result.ok ? { body: result.body as T } : { error: errorMessage(result, failMessage) }
}

/** 쓰기 실패 문구. 성공이면 null. */
function writeError(result: HttpResult, failMessage: string, errorFallback: string): string | null {
  if (result.kind === 'network') return errorFallback
  return result.ok ? null : errorMessage(result, failMessage)
}

interface Props {
  menuApi: MenuApi
  siteApi: SiteApi
  /** 모달을 그릴 곳 — #wrapper 밖 */
  modalContainer: HTMLElement
}

export function MenuManage({ menuApi, siteApi, modalContainer }: Props) {
  const [view, setView] = useState<View>(INITIAL)
  const viewRef = useRef<View>(INITIAL)
  const update = useCallback((patch: Partial<View>) => {
    viewRef.current = { ...viewRef.current, ...patch }
    setView(viewRef.current)
  }, [])

  const loadSeq = useRef(0)
  /** 폼·모드를 바꾸는 모든 전이에서 올린다 — 늦게 온 상세 응답이 낡은 값으로 폼을 되살리는 것을 막는다 */
  const selectSeq = useRef(0)
  /** 트리가 실제로 그려진 마지막 사이트 */
  const lastAppliedSite = useRef<Site | null>(null)
  const leavingAfterApply = useRef(false)
  /** 재조회가 적용(또는 최신 조회가 실패)된 시점에 보일 결과 메시지 */
  const queuedNotice = useRef<Notice | null>(null)
  const urlInput = useRef<HTMLInputElement>(null)
  const deleteModal = useRef<HTMLDivElement>(null)
  const deleteInput = useRef<HTMLInputElement>(null)
  const iconModal = useRef<HTMLDivElement>(null)

  const draftCount = () => countDraftChanges(viewRef.current.base, viewRef.current.nodes)
  const canDrag = () => viewRef.current.treeScope === 'all' && !viewRef.current.busy
  const modal = (el: HTMLDivElement | null, command: 'show' | 'hide') => {
    if (el) $(el).modal(command)
  }

  const resetSelection = (): Partial<View> => {
    selectSeq.current++
    return { mode: null, target: null, pendingParentNo: null, form: EMPTY_FORM, exposure: '-', formError: null, modeLabel: IDLE_LABEL }
  }

  // ===================== 트리 조회 =====================

  const loadTree = async () => {
    const site = viewRef.current.site
    if (site === null) return
    const seq = ++loadSeq.current
    selectSeq.current++
    update({ treeScope: null, ...NO_TREE_ALERTS })
    const includeInactive = viewRef.current.includeInactive
    const read = readBody<MenuTreeNode[]>(await menuApi.getTree(site.id, includeInactive),
      '메뉴 트리 조회에 실패했습니다.', '메뉴 트리 조회 중 오류가 발생했습니다.')
    if (seq !== loadSeq.current) return   // 더 최근의 조회가 시작됐다(응답 순서 역전 방지)

    const notice = queuedNotice.current
    queuedNotice.current = null
    if ('body' in read) {
      selectSeq.current++   // 교체 전 옛 트리에서 시작한 상세 응답이 폼을 채우지 못하게 한다
      lastAppliedSite.current = site
      update({
        nodes: toNodes(read.body),
        base: indexBase(read.body),
        treeVersion: viewRef.current.treeVersion + 1,
        treeSelection: null,
        treeScope: includeInactive ? 'all' : 'active',
        switching: false,
        treeError: notice?.kind === 'error' ? notice.text : null,
        treeWarning: notice?.kind === 'warning' ? notice.text : null,
        treeSuccess: notice?.kind === 'success' ? notice.text : null,
      })
      return
    }
    const previous = lastAppliedSite.current
    if (viewRef.current.switching && previous !== null) {
      // 사이트 전환 조회가 실패했다 — 화면에는 이전 사이트의 트리가 그대로 있으므로 셀렉트도 되돌려 "셀렉트 = 편집 대상 = 트리"를 지킨다
      rememberSite(previous)
      update({ site: previous, switching: false, treeSelection: null, treeError: read.error + ' 이전 사이트로 되돌렸습니다.', treeWarning: notice?.text ?? null })
    } else {
      // 최신 조회가 실패했어도 대기 중인 결과 메시지는 잃지 않는다
      update({ treeError: read.error, treeWarning: notice?.text ?? null })
    }
  }

  // ===================== 사이트 =====================

  const rememberSite = (site: Site) => storage((s) => s.setItem(SITE_KEY_STORAGE, String(site.id)))

  // 선택·폼·삭제 대상을 즉시 비우고 새 트리가 적용될 때까지 쓰기를 잠근다. 초안이 있거나 요청 중이면 거부한다(셀렉트는 값이 그대로다).
  const onSiteChange = (siteId: number) => {
    const v = viewRef.current
    const next = v.sites.find((s) => s.id === siteId)
    if (!next || v.busy || draftCount() > 0) return
    rememberSite(next)
    update({ ...resetSelection(), site: next, switching: true, deleteTarget: null, treeSelection: null })
    void loadTree()
  }

  // ===================== 선택·모드 =====================

  const onSelect = async (menuNo: number) => {
    update({ treeSelection: menuNo })
    // 저장·비활성화·반영 중이면 상세를 읽지 않는다(완료 뒤 어차피 초기화된다)
    if (viewRef.current.busy || viewRef.current.switching) return
    const seq = ++selectSeq.current
    update({ formError: null })
    const exposure = findNode(viewRef.current.nodes, menuNo)?.exposureLabel ?? '-'
    const read = readBody<MenuDetail>(await menuApi.getMenu(menuNo), '메뉴 상세 조회에 실패했습니다.', '메뉴 상세 조회 중 오류가 발생했습니다.')
    // 그 사이 다른 선택·초기화·트리 교체·생성 모드 진입이 있었다면 낡은 응답이다
    if (seq !== selectSeq.current) return
    if ('error' in read) {
      update({ formError: read.error })
      return
    }
    const menu = read.body
    update({
      mode: 'edit',
      target: { menuNo: menu.menuNo, menuName: menu.menuName, useYn: menu.useYn === true },
      pendingParentNo: null,
      form: { menuName: menu.menuName ?? '', menuUrl: menu.menuUrl ?? '', menuIcon: menu.menuIcon ?? '', menuDesc: menu.menuDesc ?? '', useYn: menu.useYn === true },
      exposure,
      modeLabel: '메뉴 수정: ' + menu.menuName,
    })
  }

  const startCreate = (parentNo: number | null, modeLabel: string) =>
    update({ ...resetSelection(), mode: 'create', pendingParentNo: parentNo, modeLabel })

  const onNewTop = () => {
    if (!viewRef.current.switching) startCreate(null, '새 메뉴 추가 (최상위)')
  }

  const onNewChild = () => {
    const v = viewRef.current
    if (v.switching) return
    const parent = v.treeSelection === null ? null : findNode(v.nodes, v.treeSelection)
    if (parent === null) {
      update({ formError: '먼저 상위로 사용할 메뉴를 트리에서 선택해주세요.' })
      return
    }
    startCreate(parent.menuNo, '새 메뉴 추가 (상위: ' + parent.name + ')')
  }

  const onReset = () => update({ ...resetSelection(), treeSelection: null })

  // ===================== 구조 초안 =====================

  const onMove = (menuNo: number, targetNo: number, position: DropPosition) => {
    const nodes = moveNode(viewRef.current.nodes, menuNo, targetNo, position)
    if (nodes !== viewRef.current.nodes) update({ nodes, ...NO_TREE_ALERTS })
  }

  const onApply = async () => {
    const v = viewRef.current
    const changed = draftCount()
    if (v.busy || v.switching || changed === 0 || v.treeScope !== 'all' || v.site === null) return
    update({ busy: true, ...NO_TREE_ALERTS })
    const result = await menuApi.applyStructure(buildStructureRequest(v.site.id, v.base, v.nodes))
    const error = writeError(result, '구조 반영에 실패했습니다.', '구조 반영 중 오류가 발생했습니다.')
    if (error === null) {
      // 사이드바까지 새 구조로 갱신하려고 페이지를 다시 읽는다. 잠금은 풀지 않는다 — 풀면 문서가 교체되기 전의 입력이 사라진다.
      // 로딩이 멈추면 안내의 [지금 새로고침]으로 복구한다.
      storage((s) => s.setItem(APPLIED_KEY, String(changed)))
      leavingAfterApply.current = true
      flushSync(() => update({ reloading: true }))
      window.location.reload()
      return
    }
    if (result.kind === 'http' && result.status === 409) {
      // 다른 관리자가 먼저 바꿨거나 동시 변경과 충돌했다 — 낡은 초안은 버리고 서버 상태로 다시 그린다
      queuedNotice.current = { kind: 'error', text: error + ' 서버의 최신 구조로 새로고침했습니다.' }
      update(resetSelection())
      await loadTree()
      update({ busy: false })
    } else {
      // 구조 위반(400) 등: 초안은 그대로 두어 고치거나 취소할 수 있게 한다
      update({ busy: false, treeError: error })
    }
  }

  const onCancel = () => {
    if (viewRef.current.busy || draftCount() === 0) return
    queuedNotice.current = null
    update(resetSelection())
    void loadTree()
  }

  // ===================== 저장·비활성화 =====================

  /** 쓰기 → 성공하면 선택을 비우고 트리를 다시 읽는다. 동시에 하나만 진행한다. */
  const write = async (request: () => Promise<HttpResult>, failMessage: string, errorFallback: string) => {
    if (viewRef.current.busy) {
      update({ formError: BUSY_MESSAGE })
      return
    }
    update({ busy: true })
    const error = writeError(await request(), failMessage, errorFallback)
    if (error === null) {
      update(resetSelection())
      await loadTree()
    } else {
      update({ formError: error })
    }
    update({ busy: false })
  }

  const onSave = async () => {
    update({ formError: null })
    const { mode, target, switching, site, form, pendingParentNo } = viewRef.current
    if (mode === null) {
      update({ formError: '메뉴를 선택하거나 추가 버튼을 눌러 새 메뉴를 작성해주세요.' })
      return
    }
    if (switching || site === null) {
      update({ formError: '사이트 메뉴를 불러오는 중입니다. 잠시 후 다시 시도해주세요.' })
      return
    }
    const fields: MenuFields = {
      menuName: form.menuName.trim(),
      menuUrl: form.menuUrl.trim(),
      menuIcon: form.menuIcon.trim(),
      menuDesc: form.menuDesc.trim(),
      useYn: form.useYn,
    }
    const urlError = menuUrlError(fields.menuUrl, site)
    if (urlError !== null) {
      update({ formError: urlError })
      urlInput.current?.focus()
      return
    }
    await write(
      () => target === null
        ? menuApi.create({ ...fields, upMenuNo: pendingParentNo, siteId: site.id })
        : menuApi.update(target.menuNo, fields),
      '저장에 실패했습니다.', '저장 중 오류가 발생했습니다.',
    )
  }

  const onDeactivate = async () => {
    update({ formError: null })
    const { mode, target } = viewRef.current
    if (mode !== 'edit' || target === null) {
      update({ formError: '비활성화할 메뉴를 선택해주세요.' })
      return
    }
    if (!window.confirm('선택한 메뉴를 비활성화하시겠습니까?')) return
    await write(() => menuApi.deactivate(target.menuNo), '메뉴 비활성화에 실패했습니다.', '메뉴 비활성화 중 오류가 발생했습니다.')
  }

  // ===================== 영구삭제 =====================

  // 입력은 trim 없이 확인 문자열과 정확히 비교한다. 확인 문자열이 비어 있으면(있을 수 없지만 방어적으로) 허용하지 않는다.
  const isDeleteConfirmed = (v: View) => {
    const expected = v.deleteTarget === null ? '' : escapeForConfirm(v.deleteTarget.menuName)
    return expected !== '' && v.deleteInput === expected
  }

  const onPermanentDelete = () => {
    const { mode, target } = viewRef.current
    if (mode !== 'edit' || target === null || target.useYn) {
      update({ formError: '영구삭제는 비활성 상태의 메뉴를 선택했을 때만 할 수 있습니다.' })
      return
    }
    flushSync(() => update({
      formError: null, deleteTarget: { menuNo: target.menuNo, menuName: target.menuName }, deleteInput: '', deleteError: null,
    }))
    modal(deleteModal.current, 'show')
  }

  const closeDeleteModal = () => {
    update({ deleteTarget: null })
    modal(deleteModal.current, 'hide')
  }

  const onDeleteCancel = () => {
    if (!viewRef.current.busy) closeDeleteModal()
  }

  const onDeleteConfirm = async () => {
    const v = viewRef.current
    const target = v.deleteTarget
    if (v.busy || target === null || !isDeleteConfirmed(v)) return
    update({ busy: true, deleteError: null })
    const result = await menuApi.remove(target.menuNo)
    const error = writeError(result, '메뉴 영구삭제에 실패했습니다.', '메뉴 영구삭제 중 오류가 발생했습니다.')
    const gone = result.kind === 'http' && result.status === 404
    if (error !== null && !gone) {
      // 409(활성 메뉴·하위 메뉴 존재) 등 — 모달을 유지한 채 서버 메시지를 보여 준다
      update({ busy: false, deleteError: error })
      return
    }
    queuedNotice.current = gone
      ? { kind: 'warning', text: '이미 삭제되었거나 존재하지 않는 메뉴입니다. 최신 구조로 새로고침했습니다.' }
      : { kind: 'success', text: '메뉴를 영구삭제했습니다.' }
    update({ busy: false, ...resetSelection() })
    closeDeleteModal()
    await loadTree()
  }

  useEffect(() => {
    const el = deleteModal.current
    if (!el) return
    const target = $(el)
    target.on('shown.bs.modal.menuReact', () => deleteInput.current?.focus())
    // 모달이 aria-hidden 처리되기 전에 포커스를 빼 둔다 — 포커스를 쥔 채 숨기면 접근성 경고가 난다
    target.on('hide.bs.modal.menuReact', () => {
      if (document.activeElement instanceof HTMLElement && el.contains(document.activeElement)) {
        document.activeElement.blur()
      }
    })
    return () => target.off('.menuReact')
  }, [])

  // ===================== 아이콘 =====================

  const changeForm = (patch: Partial<MenuFields>) => update({ form: { ...viewRef.current.form, ...patch } })

  const onIconPick = () => {
    flushSync(() => update({ iconKeyword: '' }))
    modal(iconModal.current, 'show')
  }

  // ===================== 수명 =====================

  useEffect(() => {
    const onBeforeUnload = (event: BeforeUnloadEvent) => {
      if (draftCount() > 0 && !leavingAfterApply.current) {
        event.preventDefault()
        event.returnValue = ''
      }
    }
    window.addEventListener('beforeunload', onBeforeUnload)
    return () => window.removeEventListener('beforeunload', onBeforeUnload)
  }, [])

  // 시작: 사이트 목록을 읽고(기억한 사이트가 아직 있으면 그 사이트, 아니면 관리자 사이트) 그 사이트의 트리를 그린다
  const initialized = useRef(false)
  useEffect(() => {
    if (initialized.current) return
    initialized.current = true
    // 반영 성공 뒤 다시 읽은 페이지라면 첫 트리 조회가 적용될 때 성공 메시지를 보인다
    const applied = storage((s) => {
      const value = s.getItem(APPLIED_KEY)
      s.removeItem(APPLIED_KEY)
      return value
    })
    if (applied !== null) {
      queuedNotice.current = { kind: 'success', text: '메뉴 구조를 반영했습니다(' + applied + '건 이동).' }
    }
    void (async () => {
      const read = readBody<Site[]>(await siteApi.list(), '사이트 목록 조회에 실패했습니다.', '사이트 목록 조회 중 오류가 발생했습니다.')
      if ('error' in read) {
        update({ treeError: read.error })
        return
      }
      const sites = read.body
      const remembered = Number(storage((s) => s.getItem(SITE_KEY_STORAGE)))
      const site = sites.find((s) => s.id === remembered) ?? sites.find((s) => s.id === ADMIN_SITE_ID) ?? null
      if (site !== null) rememberSite(site)
      update({ sites, site, switching: true })   // 첫 트리가 그려지기 전에는 편집하지 않는다
      await loadTree()
    })()
  }, [])

  // ===================== 렌더링 =====================

  const changed = countDraftChanges(view.base, view.nodes)
  const dirty = changed > 0
  // 초안이 있거나 사이트 전환 중이면 서버 상태를 바꾸는 작업을 막는다(이 작업들은 트리를 다시 불러와 초안이 사라진다)
  const locked = dirty || view.switching
  const isAdminSite = view.site?.siteType === 'ADMIN'
  const modeLabel = view.switching
    ? '사이트 메뉴를 불러오는 중입니다.'
    : dirty
      ? '반영 대기 중인 구조 변경이 있습니다. [반영] 또는 [취소]한 뒤 메뉴 정보를 수정할 수 있습니다.'
      : view.modeLabel
  const hint = view.treeScope === 'all'
    ? '메뉴를 드래그해 순서를 바꾸거나 다른 상위 메뉴 안으로 옮긴 뒤 [반영]을 눌러야 저장됩니다(최대 ' + MAX_DEPTH + '단, 하위 메뉴는 함께 이동합니다).'
    : view.treeScope === 'active'
      ? '구조 편집(드래그)은 비활성 메뉴까지 보여야 하므로 "비활성 포함"을 켜면 사용할 수 있습니다.'
      : ''
  const siteHint = view.site === null
    ? ''
    : isAdminSite
      ? '관리자 사이드바에 나오는 메뉴입니다.'
      : '공개 사이트 메뉴입니다. 공개 화면 렌더링은 이후 단계에서 연결되며, 지금은 편집만 됩니다.' + (view.site.useYn ? '' : ' (비활성 사이트)')
  const urlHelp = allowsExternal(view.site)
    ? '/로 시작하는 내부 경로 또는 http(s):// 주소를 입력할 수 있습니다. 비우면 하위 메뉴를 묶는 그룹 메뉴가 됩니다.'
    : '/로 시작하는 사이트 내부 경로만 입력할 수 있습니다(외부 주소 불가). 비우면 하위 메뉴를 묶는 그룹 메뉴가 됩니다.'
  const alert = (id: string, kind: string, text: string | null) =>
    <div id={id} className={'alert alert-' + kind + (text === null ? ' d-none' : '')} role="alert">{text ?? ''}</div>

  return (
    <>
      {/* 구조 반영이 성공하면 문서를 새로 읽는다. 로딩이 멈춘 경우의 수동 복구(잠금은 문서가 교체될 때까지 유지된다). */}
      <div id="contentReloadNotice" className={'alert alert-info' + (view.reloading ? '' : ' d-none')} role="alert">
        메뉴 구조를 반영했습니다. 화면을 새로 고치는 중입니다. 멈춘 경우{' '}
        <button type="button" className="btn btn-sm btn-primary ml-1" id="contentReloadNow" onClick={() => window.location.reload()}>지금 새로고침</button>
      </div>

      <div className="menu-container">
        <div className="row">
          <div className="col-md-5">
            <div className="tree-container">
              <div className="card-header"><h6>메뉴 구조</h6></div>
              <div className="card-body">
                <div className="form-group mb-3">
                  <label htmlFor="siteSelect" className="small font-weight-bold mb-1">편집할 사이트</label>
                  <select id="siteSelect" className="custom-select custom-select-sm" aria-describedby="siteSelectHint"
                    disabled={dirty || view.busy} value={view.site === null ? '' : String(view.site.id)}
                    onChange={(e) => onSiteChange(Number(e.target.value))}>
                    {view.sites.map((s) => (
                      <option key={s.id} value={String(s.id)}>{s.siteName + ' (' + s.siteKey + ')' + (s.useYn ? '' : ' [비활성]')}</option>
                    ))}
                  </select>
                  <small id="siteSelectHint" className="form-text text-muted">{siteHint}</small>
                </div>
                {alert('treeAlert', 'danger', view.treeError)}
                {alert('treeWarning', 'warning', view.treeWarning)}
                {alert('treeSuccess', 'success', view.treeSuccess)}
                <div className="tree-toolbar">
                  <div className="custom-control custom-switch">
                    <input type="checkbox" className="custom-control-input" id="includeInactiveToggle" disabled={locked}
                      checked={view.includeInactive} onChange={(e) => { update({ includeInactive: e.target.checked }); void loadTree() }} />
                    <label className="custom-control-label" htmlFor="includeInactiveToggle">비활성 포함</label>
                  </div>
                  <div className="btn-group-actions">
                    <button type="button" className="btn btn-primary btn-sm" id="btnNewTop" disabled={locked} onClick={onNewTop}>새 최상위</button>
                    <button type="button" className="btn btn-primary btn-sm" id="btnNewChild" disabled={locked} onClick={onNewChild}>선택 아래 하위 추가</button>
                  </div>
                </div>
                <small id="treeHint" className={'form-text text-muted mb-2' + (hint === '' ? ' d-none' : '')}>{hint}</small>
                <MenuTree
                  key={view.treeVersion}
                  nodes={view.nodes}
                  selected={view.treeSelection}
                  draggable={view.treeScope === 'all' && !view.busy}
                  canDrag={canDrag}
                  onSelect={(menuNo) => void onSelect(menuNo)}
                  onMove={onMove}
                />
                <div id="draftBar" className={'draft-bar' + (dirty ? ' dirty' : '')}>
                  <span id="draftStatus" className="draft-status text-muted">
                    {dirty ? '반영 대기 중 ' + changed + '건(이동한 메뉴) — [반영]을 눌러야 저장됩니다' : '변경 없음'}
                  </span>
                  <div className="btn-group-actions">
                    <button type="button" className="btn btn-primary btn-sm" id="btnApply" disabled={!dirty || view.busy} onClick={() => void onApply()}>반영</button>
                    <button type="button" className="btn btn-secondary btn-sm" id="btnCancel" disabled={!dirty || view.busy} onClick={onCancel}>취소</button>
                  </div>
                </div>
              </div>
            </div>
          </div>
          <div className="col-md-7">
            <MenuForm
              form={view.form}
              error={view.formError}
              modeLabel={modeLabel}
              urlHelp={urlHelp}
              exposure={isAdminSite ? view.exposure : null}
              locked={locked}
              showDeactivate={view.mode === 'edit' && view.target?.useYn === true}
              showPermanentDelete={view.mode === 'edit' && view.target?.useYn === false}
              urlRef={urlInput}
              onChange={changeForm}
              onIconPick={onIconPick}
              onSave={() => void onSave()}
              onDeactivate={() => void onDeactivate()}
              onPermanentDelete={onPermanentDelete}
              onReset={onReset}
            />
          </div>
        </div>
      </div>

      {createPortal(
        <>
          <PermanentDeleteModal
            modalRef={deleteModal}
            inputRef={deleteInput}
            error={view.deleteError}
            confirmText={view.deleteTarget === null ? '' : escapeForConfirm(view.deleteTarget.menuName)}
            input={view.deleteInput}
            confirmEnabled={!view.busy && isDeleteConfirmed(view)}
            onInput={(value) => update({ deleteInput: value })}
            onCancel={onDeleteCancel}
            onConfirm={() => void onDeleteConfirm()}
          />
          <IconPickerModal
            modalRef={iconModal}
            keyword={view.iconKeyword}
            value={view.form.menuIcon}
            onKeyword={(value) => update({ iconKeyword: value })}
            onPick={(name) => { changeForm({ menuIcon: 'fas fa-fw fa-' + name }); modal(iconModal.current, 'hide') }}
          />
        </>,
        modalContainer,
      )}
    </>
  )
}
