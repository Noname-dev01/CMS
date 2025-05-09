package com.cms.error;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

@Controller
public class CustomErrorController implements ErrorController {

    @RequestMapping("/error")
    public ModelAndView handleError(HttpServletRequest request) {
        Integer statusCode = (Integer) request.getAttribute("jakarta.servlet.error.status_code");
        String requestURI = (String) request.getAttribute("jakarta.servlet.error.request_uri");

        ModelAndView modelAndView = new ModelAndView();

        if (statusCode != null) {
            if (statusCode == 404) {
                // 관리자 페이지인 경우
                if (requestURI != null && requestURI.startsWith("/admin")) {
                    modelAndView.setViewName("error/admin/404");
                } else {
                    // 일반 사용자 페이지인 경우
                    modelAndView.setViewName("error/404");
                }
                modelAndView.addObject("timestamp", new java.util.Date());
                modelAndView.addObject("path", requestURI);
            }
        }

        return modelAndView;
    }
} 