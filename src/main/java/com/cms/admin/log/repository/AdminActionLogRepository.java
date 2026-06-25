package com.cms.admin.log.repository;

import com.cms.admin.log.domain.AdminActionLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminActionLogRepository extends JpaRepository<AdminActionLog, Long>, AdminActionLogRepositoryCustom {
}
