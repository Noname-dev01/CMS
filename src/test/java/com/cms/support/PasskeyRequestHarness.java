package com.cms.support;

import com.microsoft.playwright.Page;

import java.util.List;

/**
 * 패스키 브라우저 요청 감싸기(PLAN-passkey-conditional-ui.md §4) — 페이지 초기화 스크립트로 {@code navigator.credentials.get}과
 * {@code PublicKeyCredential.isConditionalMediationAvailable}을 감싸 요청 조정(취소·재시작·제출 가드)을 결정적으로 시험한다.
 *
 * <p>Chromium CDP 가상 인증기는 조건부 요청에 대기 없이 응답해(자격 있으면 성공, 없으면 {@code NotAllowedError}) 실제 브라우저의
 * "사용자가 고를 때까지 대기"를 재현하지 못한다(계획 S7). 그래서 조건부 요청의 대기·늦은 성공은 이 감싸기로 만들고, 모달 요청은 필요하면 실제 인증기로 넘긴다.
 * 감싼 조건부 요청은 <b>앱이 실제로 넘긴 {@code signal}</b>의 abort로만 끝난다 — 코덱이 signal을 빠뜨리면 끝나지 않아 시험이 실패한다(리뷰 1-4).
 *
 * <p>페이지 안 상태는 {@code window.__pk}다: {@code events}(사건 순서), {@code conditionalCalls}·{@code modalCalls}(호출 수),
 * {@code resolveConditional()}(대기 중인 조건부 요청을 가짜 자격으로 성공), {@code releaseModal()}(붙잡은 모달을 실제 인증기로 넘김),
 * {@code availRelease(bool)}(붙잡은 지원 판정 해제).
 */
public final class PasskeyRequestHarness {

    /** 조건부 요청: 실제 인증기로 넘김 */
    public static final String REAL = "real";
    /** 조건부·모달·지원 판정: 시험이 풀 때까지 대기(조건부는 signal abort 때 AbortError) */
    public static final String HOLD = "hold";
    /** 조건부·모달: 즉시 NotAllowedError */
    public static final String REJECT = "reject";
    /** 조건부: 즉시 가짜 자격으로 성공 */
    public static final String RESOLVE_NOW = "resolveNow";
    /** 조건부: signal abort를 받는 순간 가짜 자격으로 성공(취소 직전 성공 경쟁) */
    public static final String RESOLVE_ON_ABORT = "resolveOnAbort";
    /** 지원 판정: 즉시 false(미지원 브라우저) */
    public static final String UNAVAILABLE = "false";

    private static final String SCRIPT = """
            (cfg => {
              const st = window.__pk = { events: [], conditionalCalls: 0, modalCalls: 0 };
              const log = e => st.events.push(e);
              if (cfg.avail !== 'real' && window.PublicKeyCredential) {
                PublicKeyCredential.isConditionalMediationAvailable = cfg.avail === 'false'
                  ? () => Promise.resolve(false)
                  : () => new Promise(resolve => { st.availRelease = resolve; });
              }
              if (!navigator.credentials) { return; }
              const realGet = navigator.credentials.get.bind(navigator.credentials);
              const fake = () => {
                const b = new Uint8Array([1, 2, 3]).buffer;
                return { id: 'AQID', rawId: b, type: 'public-key', authenticatorAttachment: 'platform',
                  response: { authenticatorData: b, clientDataJSON: b, signature: b, userHandle: b },
                  getClientExtensionResults() { return {}; } };
              };
              // 제출 기록·붙잡기 — 패스키 폼은 form.submit()(submit 이벤트 없음), 비밀번호 폼은 submit 이벤트로 나간다.
              // 붙잡으면 문서가 그대로 남아 상태를 읽을 수 있다(네트워크에서 붙잡으면 Playwright evaluate가 끝나지 않는다).
              const realSubmit = HTMLFormElement.prototype.submit;
              HTMLFormElement.prototype.submit = function () {
                log('submit:' + this.id);
                if (cfg.holdSubmit) { const f = this; st.releaseSubmit = () => realSubmit.call(f); return; }
                return realSubmit.call(this);
              };
              window.addEventListener('submit', e => {
                if (e.defaultPrevented) { log('submit-blocked:' + e.target.id); return; }
                log('submit:' + e.target.id);
                if (cfg.holdSubmit) { e.preventDefault(); const f = e.target; st.releaseSubmit = () => realSubmit.call(f); }
              });
              const track = p => { p.then(() => log('conditional-settled'), () => log('conditional-settled')); return p; };
              navigator.credentials.get = function (request) {
                if (request && request.mediation === 'conditional') {
                  st.conditionalCalls++;
                  const signal = request.signal;
                  log(signal ? 'conditional-start' : 'conditional-start-without-signal');
                  if (cfg.conditional === 'real') { return track(realGet(request)); }
                  if (cfg.conditional === 'reject') { return track(Promise.reject(new DOMException('x', 'NotAllowedError'))); }
                  if (cfg.conditional === 'resolveNow') { return track(Promise.resolve(fake())); }
                  return track(new Promise((resolve, reject) => {
                    st.resolveConditional = () => resolve(fake());
                    if (signal) {
                      signal.addEventListener('abort', () => {
                        log('conditional-signal-aborted');
                        if (cfg.conditional === 'resolveOnAbort') { resolve(fake()); } else { reject(new DOMException('x', 'AbortError')); }
                      });
                    }
                  }));
                }
                st.modalCalls++;
                log('modal-start');
                if (cfg.modal === 'reject') { return Promise.reject(new DOMException('x', 'NotAllowedError')); }
                if (cfg.modal === 'hold') {
                  return new Promise((resolve, reject) => { st.releaseModal = () => { realGet(request).then(resolve, reject); }; });
                }
                return realGet(request);
              };
            })(%s);
            """;

    private PasskeyRequestHarness() {
    }

    /** 다음 화면 이동부터 적용된다(초기화 스크립트). 제출은 그대로 나간다. */
    public static void install(Page page, String conditional, String modal, String avail) {
        install(page, conditional, modal, avail, false);
    }

    /** holdSubmit이면 폼 제출을 기록만 하고 붙잡는다 — {@link #later}로 {@code window.__pk.releaseSubmit()}을 불러 내보낸다. */
    public static void install(Page page, String conditional, String modal, String avail, boolean holdSubmit) {
        page.addInitScript(SCRIPT.formatted("{conditional: '%s', modal: '%s', avail: '%s', holdSubmit: %s}"
                .formatted(conditional, modal, avail, holdSubmit)));
    }

    /**
     * 화면 이동을 일으킬 수 있는 페이지 호출은 다음 태스크로 미뤄 부른다 — evaluate가 이동 중인 문서에서 결과를 기다리다 끝나지 않는 것을 피한다.
     * 예: {@code later(page, "window.__pk.releaseModal()")}.
     */
    public static void later(Page page, String expression) {
        page.evaluate("() => { setTimeout(() => { " + expression + "; }, 0); }");
    }

    /** 기록된 제출 수 — {@code formId}는 {@code passkeyForm}·{@code loginForm} */
    public static long submits(Page page, String formId) {
        return events(page).stream().filter(("submit:" + formId)::equals).count();
    }

    public static int conditionalCalls(Page page) {
        return ((Number) page.evaluate("() => window.__pk.conditionalCalls")).intValue();
    }

    public static int modalCalls(Page page) {
        return ((Number) page.evaluate("() => window.__pk.modalCalls")).intValue();
    }

    @SuppressWarnings("unchecked")
    public static List<String> events(Page page) {
        return (List<String>) page.evaluate("() => window.__pk.events.slice()");
    }
}
