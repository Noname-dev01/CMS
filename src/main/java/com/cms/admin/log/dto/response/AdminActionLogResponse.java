package com.cms.admin.log.dto.response;

import com.cms.admin.log.domain.AdminActionResult;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AdminActionLogResponse {

    private Long id;
    /** 행위자 member PK. 시스템 로그처럼 보안 컨텍스트가 없으면 null (Jackson non_null 설정으로 키 생략됨). */
    private Long actionId;
    private String actionUserId;
    private String actionType;
    private AdminActionResult actionResult;
    private String targetType;
    private Long targetId;
    private String requestIp;
    private String requestUri;
    private String requestMethod;
    private String errorMessage;
    private LocalDateTime createAt;
}
