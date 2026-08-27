package com.cms.error;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

@Controller
public class CustomErrorController implements ErrorController {

    /**
     * 관리자 페이지 판정용 패턴. "/admin/**"는 /admin(루트)·/admin/·/admin/하위경로·
     * 세미콜론 매트릭스 파라미터가 섞인 경로(/admin;v=1/missing)까지 전부 매칭하고,
     * /administrator/missing·/admin-api/missing 같은 비-admin 경로는 매칭하지 않는다
     * (실측 확인) — 과거의 requestURI.startsWith("/admin") raw 문자열 비교가 두 가지를
     * 놓쳤다: 컨텍스트 경로가 붙으면 오분류되고(별도로 getContextPath() 제거로 처리),
     * 매트릭스 파라미터가 섞이면 startsWith("/admin/")가 false가 되어 관리자 404로
     * 분류되지 않았다.
     */
    private static final PathPattern ADMIN_PATTERN = PathPatternParser.defaultInstance.parse("/admin/**");

    @RequestMapping("/error")
    public ModelAndView handleError(HttpServletRequest request) {
        Integer statusCode = (Integer) request.getAttribute("jakarta.servlet.error.status_code");
        String requestURI = (String) request.getAttribute("jakarta.servlet.error.request_uri");

        ModelAndView modelAndView = new ModelAndView();

        if (statusCode != null) {
            if (statusCode == 404) {
                if (isAdminPath(requestURI, request.getContextPath())) {
                    modelAndView.setViewName("error/admin/404");
                } else {
                    // 일반 사용자 페이지인 경우
                    modelAndView.setViewName("error/404");
                }
                modelAndView.addObject("timestamp", new java.util.Date());
                modelAndView.addObject("path", requestURI);
            } else if (statusCode == 429) {
                // 레이트리밋 초과(RateLimitFilter의 sendError(429)) — 429가 걸리는 경로는 전부
                // 무인증 공개 경로(/notices/**)라 admin 전용 429는 두지 않는다.
                modelAndView.setViewName("error/429");
                modelAndView.addObject("timestamp", new java.util.Date());
                modelAndView.addObject("path", requestURI);
            }
        }

        return modelAndView;
    }

    /**
     * requestURI(컨텍스트 경로 포함 raw 값)에서 컨텍스트 경로를 제거한 뒤 관리자 경로 패턴과
     * 대조한다. contextPath가 빈 문자열(현재 전 프로파일 기본값)이면 원래 requestURI 그대로
     * 비교하는 것과 동일하게 동작해 기존 동작에 회귀가 없다.
     */
    private boolean isAdminPath(String requestURI, String contextPath) {
        if (requestURI == null) {
            return false;
        }
        String path = (contextPath != null && !contextPath.isEmpty() && requestURI.startsWith(contextPath))
                ? requestURI.substring(contextPath.length())
                : requestURI;
        return ADMIN_PATTERN.matches(PathContainer.parsePath(path));
    }
} 