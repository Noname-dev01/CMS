package com.cms.admin.log.aspect;

import com.cms.admin.log.annotation.AdminActionLogged;
import com.cms.admin.log.domain.AdminActionResult;
import com.cms.admin.log.service.AdminActionLogService;
import com.cms.config.auth.CustomUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
public class AdminActionLogAspect {

    private final AdminActionLogService adminActionLogService;

    @AfterReturning(
            value = "@annotation(adminActionLogged)",
            returning = "result"
    )
    public void logSuccess(JoinPoint joinPoint, AdminActionLogged adminActionLogged, Object result){
        // 감사 로그 저장 실패가 정상 완료된 본 작업을 실패로 뒤집지 않도록 예외를 격리한다.
        try {
            adminActionLogService.log(
                    getCurrentAdminId(),
                    getCurrentAdminUserId(),
                    adminActionLogged.actionType(),
                    AdminActionResult.SUCCESS,
                    adminActionLogged.targetType(),
                    extractTargetId(result, adminActionLogged.targetIdExpression()),
                    getClientIp(),
                    getRequestUri(),
                    getRequestMethod(),
                    null
            );
        } catch (Exception loggingError) {
            log.error("관리자 액션 성공 로그 저장 실패 (actionType={})", adminActionLogged.actionType(), loggingError);
        }
    }

    @AfterThrowing(
            value = "@annotation(adminActionLogged)",
            throwing = "e"
    )
    public void logFailure(AdminActionLogged adminActionLogged, Exception e){
        // 감사 로그 저장 실패가 원래의 비즈니스 예외를 가리지 않도록 예외를 격리한다.
        try {
            adminActionLogService.log(
                    getCurrentAdminId(),
                    getCurrentAdminUserId(),
                    adminActionLogged.actionType(),
                    AdminActionResult.FAIL,
                    adminActionLogged.targetType(),
                    null,
                    getClientIp(),
                    getRequestUri(),
                    getRequestMethod(),
                    truncateErrorMessage(e.getMessage())
            );
        } catch (Exception loggingError) {
            log.error("관리자 액션 실패 로그 저장 실패 (actionType={})", adminActionLogged.actionType(), loggingError);
        }
    }

    private Long getCurrentAdminId(){
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof CustomUserDetails userDetails)) {
            return null;
        }
        return userDetails.getMember().getId();
    }

    private String getCurrentAdminUserId(){
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof CustomUserDetails userDetails)) {
            return null;
        }
        return userDetails.getMember().getUserId();
    }

    private String getClientIp(){
        HttpServletRequest request = getCurrentRequest();
        if (request == null) {
            return null;
        }

        String xForwardedFor = request.getHeader("X-FORWARDED-FOR");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            String[] ips = xForwardedFor.split(",");
            return ips[ips.length - 1].trim();
        }

        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp;
        }

        return request.getRemoteAddr();
    }

    private String getRequestUri(){
        HttpServletRequest request = getCurrentRequest();
        return request != null ? request.getRequestURI() : null;
    }

    private String getRequestMethod(){
        HttpServletRequest request = getCurrentRequest();
        return request != null ? request.getMethod() : null;
    }

    private HttpServletRequest getCurrentRequest(){
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

        return attributes != null ? attributes.getRequest() : null;
    }

    private Long extractTargetId(Object result, String targetIdExpression){
        if (result == null || targetIdExpression == null || targetIdExpression.isBlank()){
            return null;
        }

        try {
            String getterName = "get" + Character.toUpperCase(targetIdExpression.charAt(0))
                    + targetIdExpression.substring(1);

            Method method = result.getClass().getMethod(getterName);
            Object value = method.invoke(result);

            if (value instanceof Long longValue){
                return longValue;
            }

            if (value instanceof Number number){
                return number.longValue();
            }

            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String truncateErrorMessage(String errorMessage){
        if (errorMessage == null){
            return null;
        }
        return errorMessage.length() > 500 ? errorMessage.substring(0, 500) : errorMessage;
    }
}
