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
        Exception exception = (Exception) request.getAttribute("jakarta.servlet.error.exception");
        
        ModelAndView modelAndView = new ModelAndView();
        
        if (statusCode != null) {
            if (statusCode == 404) {
                modelAndView.setViewName("error/404");
                modelAndView.addObject("timestamp", new java.util.Date());
                modelAndView.addObject("path", request.getAttribute("jakarta.servlet.error.request_uri"));
            }
        }
        
        return modelAndView;
    }
} 