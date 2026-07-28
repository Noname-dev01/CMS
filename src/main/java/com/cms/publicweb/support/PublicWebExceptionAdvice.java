package com.cms.publicweb.support;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * {@code com.cms.publicweb} 패키지 전용 예외 처리. {@code GlobalApiExceptionHandler}는
 * 전역 {@code @RestControllerAdvice}라 페이지 컨트롤러의 예외까지 JSON으로 응답해버린다
 * (PLAN-public-notice.md 결정 3 참조) — 그 전역 advice를 수정하면 admin API·페이지
 * 전체의 예외 처리가 흔들릴 위험이 있으므로 건드리지 않고, {@code basePackages}로 범위를
 * 좁힌 이 advice를 별도로 둔다. {@code @Order(HIGHEST_PRECEDENCE)}는 이 advice가 적용
 * 후보가 되는 {@code publicweb} 요청에서 전역 advice보다 먼저 매칭되도록 하기 위함이다
 * — {@code basePackages}가 다르므로 admin/API 요청에는 애초에 적용 후보조차 되지 않는다.
 *
 * <p><b>보장 범위는 컨트롤러·Service 실행 중 예외로 한정된다.</b> Thymeleaf 렌더링(뷰 반환
 * 이후) 단계의 예외는 {@code DispatcherServlet}의 핸들러 실행 try/catch 바깥에서 발생해
 * 이 advice가 잡지 못하고 컨테이너 오류 처리(`/error`)로 전파된다 — 이 공백은 이 기능이
 * 새로 만든 것이 아니라 앱 전체에 이미 있던 기존 한계라 이번 범위에서 닫지 않는다(결정 3-2).
 */
@Slf4j
@ControllerAdvice(basePackages = "com.cms.publicweb")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PublicWebExceptionAdvice {

    @ExceptionHandler(Exception.class)
    public String handleUnexpected(Exception e, HttpServletResponse response) {
        log.error("공개 페이지 처리 중 오류", e);
        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        return "public/notice/error";
    }
}
