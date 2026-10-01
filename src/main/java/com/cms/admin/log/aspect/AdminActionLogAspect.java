package com.cms.admin.log.aspect;

import com.cms.admin.log.annotation.AdminActionLogged;
import com.cms.admin.log.domain.AdminActionResult;
import com.cms.admin.log.service.AdminActionLogService;
import com.cms.common.web.ClientIpResolver;
import com.cms.config.auth.CustomUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

// @Order(LOWEST_PRECEDENCE - 1): @Transactional 어드바이저(기본값 LOWEST_PRECEDENCE)보다 바깥에
// 위치시켜, 대상 메서드의 커밋까지 끝난 뒤에만 @AfterReturning/@AfterThrowing이 실행되도록 한다.
// 순서가 동률이면 어느 쪽이 안쪽인지 Spring 내부 구현(등록 순서 등)에 의존하는 비결정적 상태가 되어,
// 감사 Aspect가 안쪽에 위치할 경우 원 트랜잭션 커밋 전에 SUCCESS가 먼저 커밋될 수 있다(감사 H-03·M-01).
// 메서드 보안(@PreFilter=100·@PreAuthorize=200 등 작은 순서값)보다는 안쪽이라 그 관계는 건드리지 않는다.
// 이 보장은 @AdminActionLogged가 붙은 메서드가 해당 요청의 최상위 트랜잭션 진입점일 때만 유효하다 —
// 이미 열린 다른 @Transactional 메서드 안에서 참여 호출되면 반환이 물리 커밋과 무관해진다.
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 1)
@Slf4j
@RequiredArgsConstructor
public class AdminActionLogAspect {

    /** admin_action_log.target_label 컬럼 길이(V12)와 일치해야 한다. */
    private static final int MAX_TARGET_LABEL_LENGTH = 500;

    private final AdminActionLogService adminActionLogService;

    @AfterReturning(
            value = "@annotation(adminActionLogged)",
            returning = "result"
    )
    public void logSuccess(JoinPoint joinPoint, AdminActionLogged adminActionLogged, Object result){
        // 추출 메서드는 내부에서 예외를 삼키므로 try 밖에서 계산해 저장 실패 로그에도 같은 값을 쓴다.
        Long targetId = extractTargetId(result, adminActionLogged.targetIdExpression());
        String targetLabel = extractTargetLabel(result, adminActionLogged.targetLabelExpression());

        // 감사 로그 저장 실패가 정상 완료된 본 작업을 실패로 뒤집지 않도록 예외를 격리한다.
        try {
            adminActionLogService.log(
                    getCurrentAdminId(),
                    getCurrentAdminUserId(),
                    adminActionLogged.actionType(),
                    AdminActionResult.SUCCESS,
                    adminActionLogged.targetType(),
                    targetId,
                    targetLabel,
                    ClientIpResolver.resolve(getCurrentRequest()),
                    getRequestUri(),
                    getRequestMethod(),
                    null
            );
        } catch (Exception loggingError) {
            // 하드 삭제처럼 대상이 사라지는 액션은 이 로그가 이름·URL의 유일한 단서가 된다(감사 보존은 최선 노력).
            // 라벨은 사용자 입력이라 개행 등이 가짜 로그 줄을 만들 수 없도록 이스케이프해서 남긴다.
            log.error("관리자 액션 성공 로그 저장 실패 (actionType={}, targetId={}, targetLabel={})",
                    adminActionLogged.actionType(), targetId, escapeForLog(targetLabel), loggingError);
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
                    null,
                    ClientIpResolver.resolve(getCurrentRequest()),
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

    private String extractTargetLabel(Object result, String targetLabelExpression){
        if (result == null || targetLabelExpression == null || targetLabelExpression.isBlank()){
            return null;
        }

        try {
            String getterName = "get" + Character.toUpperCase(targetLabelExpression.charAt(0))
                    + targetLabelExpression.substring(1);

            Object value = result.getClass().getMethod(getterName).invoke(result);

            if (value instanceof String label){
                return label.length() > MAX_TARGET_LABEL_LENGTH ? label.substring(0, MAX_TARGET_LABEL_LENGTH) : label;
            }

            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 서버 로그에 쓸 사용자 입력 문자열을 한 줄로 고정한다. 역슬래시와 제어문자(Cc)·줄/문단 구분자(Zl·Zp: U+2028·U+2029,
     * U+0085 포함)를 가역 이스케이프(\\, \r, \n, \t, \\uXXXX)로 치환한다 — Java 17의 {@code \p{Cntrl}}은 ASCII 제어문자만
     * 잡아 유니코드 줄바꿈을 놓치므로 문자 종류(general category)로 판정한다.
     */
    static String escapeForLog(String value){
        if (value == null){
            return null;
        }

        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++){
            char c = value.charAt(i);
            switch (c){
                case '\\' -> escaped.append("\\\\");
                case '\r' -> escaped.append("\\r");
                case '\n' -> escaped.append("\\n");
                case '\t' -> escaped.append("\\t");
                default -> {
                    int type = Character.getType(c);
                    if (type == Character.CONTROL || type == Character.LINE_SEPARATOR
                            || type == Character.PARAGRAPH_SEPARATOR){
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private String truncateErrorMessage(String errorMessage){
        if (errorMessage == null){
            return null;
        }
        return errorMessage.length() > 500 ? errorMessage.substring(0, 500) : errorMessage;
    }
}
