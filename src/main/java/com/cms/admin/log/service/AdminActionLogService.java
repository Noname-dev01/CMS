package com.cms.admin.log.service;

import com.cms.admin.log.domain.AdminActionLog;
import com.cms.admin.log.domain.AdminActionResult;
import com.cms.admin.log.repository.AdminActionLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AdminActionLogService {

    private final AdminActionLogRepository adminActionLogRepository;

    public void log(Long actionId, String actionUserId, String actionType, AdminActionResult actionResult, String targetType,
                    Long targetId, String requestIp, String requestUri, String requestMethod, String errorMessage){
        AdminActionLog Log = AdminActionLog.builder()
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

        adminActionLogRepository.save(Log);
    }
}
