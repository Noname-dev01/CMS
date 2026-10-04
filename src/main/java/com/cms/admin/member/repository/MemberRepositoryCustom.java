package com.cms.admin.member.repository;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.dto.request.AdminMemberSearchRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface MemberRepositoryCustom {

    Page<Member> searchAdminMembers(AdminMemberSearchRequest request, Pageable pageable);

    /** 통합 검색용 — 관리자(ADMIN·MANAGER)·비삭제 계정 중 아이디 <b>또는</b> 이름에 검색어가 포함된 계정. */
    Page<Member> searchByKeyword(String keyword, Pageable pageable);
}
