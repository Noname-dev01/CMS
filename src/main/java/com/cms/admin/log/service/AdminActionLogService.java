package com.cms.admin.log.service;

import com.cms.admin.log.domain.AdminActionLog;
import com.cms.admin.log.domain.AdminActionResult;
import com.cms.admin.log.repository.AdminActionLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AdminActionLogService {

    private final AdminActionLogRepository adminActionLogRepository;

    /**
     * 감사 로그는 원 비즈니스 트랜잭션과 분리된 독립 트랜잭션(REQUIRES_NEW)으로 저장한다.
     * - 원 작업이 롤백되어도 실패(FAIL) 로그는 남아야 한다.
     * - 로그 저장이 원 작업 트랜잭션에 묶여 함께 롤백되지 않도록 한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(Long actionId, String actionUserId, String actionType, AdminActionResult actionResult, String targetType,
                    Long targetId, String requestIp, String requestUri, String requestMethod, String errorMessage){
        AdminActionLog actionLog = AdminActionLog.builder()
                .actionId(actionId)
                .actionUserId(actionUserId)
                .actionType(actionType)
                .actionResult(actionResult)
                .targetType(targetType)
                .targetId(targetId)
                .requestIp(requestIp)
                .requestUri(requestUri)
                .requestMethod(requestMethod)
                .errorMessage(errorMessage)
                .createAt(LocalDateTime.now())
                .build();

        adminActionLogRepository.save(actionLog);
    }
}
