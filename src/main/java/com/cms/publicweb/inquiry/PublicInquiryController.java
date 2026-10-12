package com.cms.publicweb.inquiry;

import com.cms.admin.inquiry.service.InquiryClosedException;
import com.cms.admin.inquiry.service.InquiryInputPolicy;
import com.cms.admin.inquiry.service.InquiryNotifier;
import com.cms.admin.inquiry.service.InquiryService;
import com.cms.common.web.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 공개(비로그인) 문의 폼 {@code /inquiry}(PLAN-inquiry.md). {@code @AdminPage}를 붙이지 않는다. {@code SecurityConfig}가 {@code /inquiry} 정확 경로의
 * GET·HEAD·POST만 무인증 공개하고(2026-10-12 승인) 하위 경로·그 밖의 메서드는 거부한다 — 라우트는 {@code GET}·{@code POST} 둘뿐이다.
 *
 * <p><b>POST 순서(고정)</b>: 닫힘 확인 → {@link InquiryInputPolicy} 전체(DB 없음) → 캡차 토큰 형식 → 캡차 서버 검증(트랜잭션 밖 외부 호출) → 접수
 * ({@link InquiryService#submit}, 트랜잭션 하나) → 알림({@link InquiryNotifier}, 트랜잭션 밖·최선 노력) → PRG 303. 어떤 형식 오류도 외부 호출을 일으키지 않는다.
 *
 * <p>{@code publicweb}의 예외 advice는 모든 예외를 HTML 500으로 바꾸고 원인 체인까지 로그에 남기므로, 검증 실패는 예외 없이 폼을 다시 그리고(400, 입력 보존)
 * 접수 중 예기치 않은 오류는 이 컨트롤러가 직접 잡아 <b>예외 클래스명만</b> 로그에 남긴 뒤 폼을 500으로 다시 그린다(입력이 로그로 복제되지 않게, R1-2).
 * 레이트리밋(429)은 필터가 끝내므로 입력을 보존하지 못한다.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class PublicInquiryController {

    /** Turnstile 위젯이 폼에 넣는 hidden 필드 이름. */
    static final String TOKEN_PARAM = "cf-turnstile-response";

    /** Cloudflare가 정의한 토큰 최대 길이 — 넘으면 외부에 보내기 전에 로컬에서 거른다(R3-2). */
    static final int TOKEN_MAX_LENGTH = 2048;

    static final String CAPTCHA_MESSAGE = "자동 입력 방지 확인에 실패했습니다. 다시 시도해 주세요.";
    static final String UNAVAILABLE_MESSAGE = "현재 문의를 받을 수 없습니다. 잠시 후 다시 시도해 주세요.";
    static final String ERROR_MESSAGE = "일시적인 오류로 문의를 접수하지 못했습니다. 잠시 후 다시 시도해 주세요.";

    private final InquiryTurnstileProperties turnstile;
    private final CaptchaVerifier captchaVerifier;
    private final InquiryService inquiryService;
    private final InquiryNotifier inquiryNotifier;

    @GetMapping("/inquiry")
    public String form(@RequestParam(name = "sent", required = false) String sent, Model model, HttpServletResponse response) {
        // 접수 직후의 완료 안내(?sent)는 미처리 상한과 무관하다 — 방금 저장된 문의가 상한을 채웠어도 성공을 실패처럼 안내하지 않는다.
        // 기능 자체가 닫혀 있으면(enabled=false) 그대로 503이다.
        if (!turnstile.isEnabled() || (sent == null && inquiryService.isClosed())) {
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            return "public/inquiry/closed";
        }
        model.addAttribute("form", new InquiryForm());
        model.addAttribute("siteKey", turnstile.getSiteKey());
        model.addAttribute("sent", sent != null);
        model.addAttribute("errors", Map.of());
        return "public/inquiry/form";
    }

    @PostMapping("/inquiry")
    public Object submit(@ModelAttribute("form") InquiryForm form, BindingResult bindingResult,
                         @RequestParam(name = TOKEN_PARAM, required = false) String captchaToken,
                         HttpServletRequest request, HttpServletResponse response, Model model) {
        // bindingResult는 쓰지 않지만 파라미터로 있어야 한다 — 없으면 변환 불가 값(consent=invalid 등)이 바인딩 예외가 되어 공개 예외 advice의
        // 500 페이지로 흐르고 거부된 값이 로그에 남는다. 변환에 실패한 필드는 기본값(consent=false)으로 남아 아래 정책이 고정 문구로 거른다.
        if (!turnstile.isEnabled()) {
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            return "public/inquiry/closed";
        }
        // 상한에 이미 도달했으면 캡차(외부 호출)를 소비하기 전에 입력을 보존한 채 안내한다. 접수 직전의 경합은 submit()의 상한 검사가 다시 막는다.
        boolean capped;
        try {
            capped = inquiryService.isClosed();
        } catch (RuntimeException e) {
            // DB 장애·풀 획득 타임아웃 — advice의 오류 페이지로 흘리면 작성한 입력을 잃고 원인 체인이 로그에 남는다. 클래스명만 남기고 입력을 보존한 500 폼으로 답한다.
            log.error("문의 상한 확인 실패 ({})", e.getClass().getName());
            return rerender(model, response, HttpStatus.INTERNAL_SERVER_ERROR, Map.of(), ERROR_MESSAGE);
        }
        if (capped) {
            return rerender(model, response, HttpStatus.SERVICE_UNAVAILABLE, Map.of(), UNAVAILABLE_MESSAGE);
        }

        // 1) 최종 입력 정책(DB 없음) + 토큰 형식 — 어떤 형식 오류도 외부 호출을 일으키지 않는다.
        InquiryInputPolicy.Result policy = InquiryInputPolicy.check(form.getName(), form.getEmail(), form.getTitle(), form.getBody(), form.isConsent());
        Map<String, String> errors = new LinkedHashMap<>(policy.fieldErrors());
        if (captchaToken == null || captchaToken.isBlank() || captchaToken.length() > TOKEN_MAX_LENGTH) {
            errors.put("captcha", CAPTCHA_MESSAGE);
        }
        if (!errors.isEmpty()) {
            return rerender(model, response, HttpStatus.BAD_REQUEST, errors, null);
        }

        // 2) 캡차 서버 검증 — 트랜잭션 밖 외부 호출(fail-closed).
        if (!captchaVerifier.verify(captchaToken, ClientIpResolver.resolve(request))) {
            return rerender(model, response, HttpStatus.BAD_REQUEST, Map.of("captcha", CAPTCHA_MESSAGE), null);
        }

        // 3) 접수. 예기치 않은 예외는 클래스명만 로그에 남긴다(입력이 로그로 복제되지 않게).
        try {
            inquiryService.submit(policy.value());
        } catch (InquiryClosedException e) {
            return rerender(model, response, HttpStatus.SERVICE_UNAVAILABLE, Map.of(), UNAVAILABLE_MESSAGE);
        } catch (RuntimeException e) {
            log.error("문의 접수 실패 ({})", e.getClass().getName());
            return rerender(model, response, HttpStatus.INTERNAL_SERVER_ERROR, Map.of(), ERROR_MESSAGE);
        }

        // 4) 알림 — 접수 트랜잭션이 끝난 뒤 트랜잭션 밖에서(요청당 커넥션 2개 금지). 실패해도 접수는 유지된다.
        inquiryNotifier.notifyReceived();

        // 5) PRG — 새로고침이 문의를 중복 접수하지 않게 303으로 보낸다.
        RedirectView redirect = new RedirectView("/inquiry?sent", true);
        redirect.setStatusCode(HttpStatus.SEE_OTHER);
        return new ModelAndView(redirect);
    }

    /** 입력을 보존한 채 폼을 다시 그린다. 값은 뷰에서 {@code th:value}/{@code th:text}로만 출력된다(자동 이스케이프). */
    private String rerender(Model model, HttpServletResponse response, HttpStatus status, Map<String, String> errors, String formError) {
        response.setStatus(status.value());
        model.addAttribute("siteKey", turnstile.getSiteKey());
        model.addAttribute("sent", false);
        model.addAttribute("errors", errors);
        model.addAttribute("formError", formError);
        return "public/inquiry/form";
    }
}
