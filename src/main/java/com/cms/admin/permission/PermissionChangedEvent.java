package com.cms.admin.permission;

/** 한 역할의 허용 집합이 실제로 바뀐 저장이 있었다는 이벤트. 트랜잭션 완료 직후({@link PermissionChangedListener}) 캐시를 무효화한다. */
public record PermissionChangedEvent(String role) {
}
