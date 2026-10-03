package com.cms.admin.permission;

/** 한 회원의 허용 집합이 실제로 바뀐 저장(또는 역할 변경에 따른 삭제)이 있었다는 이벤트. 트랜잭션 완료 직후({@link PermissionChangedListener}) 캐시를 무효화한다. */
public record PermissionChangedEvent(Long memberId) {
}
