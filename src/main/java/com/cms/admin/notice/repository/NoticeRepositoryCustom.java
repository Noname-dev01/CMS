package com.cms.admin.notice.repository;

import com.cms.admin.notice.domain.Notice;
import com.cms.admin.notice.dto.request.NoticeSearchRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface NoticeRepositoryCustom {

    Page<Notice> searchNotices(NoticeSearchRequest request, Pageable pageable);
}
