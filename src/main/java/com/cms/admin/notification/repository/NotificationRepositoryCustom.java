package com.cms.admin.notification.repository;

import com.cms.admin.notification.domain.Notification;

import java.util.List;

public interface NotificationRepositoryCustom {

    /**
     * 본인 알림을 id 내림차순으로 최대 {@code limit}건 읽는다. {@code beforeId}가 있으면 {@code id < beforeId}(커서).
     * {@code includeAdminOnly=false}이면 ADMIN에게만 보이는 종류({@code ADMIN_ACCOUNT_LOCKED})를 <b>쿼리 조건으로</b> 제외한다(D11).
     */
    List<Notification> findPage(Long memberId, Long beforeId, boolean includeAdminOnly, int limit);

    /** 본인 미읽음 수. {@code includeAdminOnly=false}이면 ADMIN 전용 종류를 제외한다(D11). */
    long countUnread(Long memberId, boolean includeAdminOnly);
}
