package com.cms.admin.member.dto.response;

/**
 * 회원 상세 응답에 프로필 이미지 URL을 어떤 라우트 형태로 넣을지 구분한다(PLAN-profile-image-storage.md 쟁점 10).
 * 회원 관리({@code AdminMemberService})와 프로필 이미지({@code MemberProfileImageService}) 두 서비스가 함께 쓴다.
 */
public enum ProfileImageVisibility { HIDDEN, SELF, OTHER }
