package com.cms.admin.log.repository;

import com.cms.admin.log.domain.AdminActionLog;
import com.cms.admin.log.dto.request.AdminActionLogSearchRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AdminActionLogRepositoryCustom {

    Page<AdminActionLog> searchActionLogs(AdminActionLogSearchRequest req, Pageable pageable);
}
