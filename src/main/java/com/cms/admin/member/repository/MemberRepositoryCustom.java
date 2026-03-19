package com.cms.admin.member.repository;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.dto.request.AdminMemberSearchRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface MemberRepositoryCustom {

    Page<Member> searchAdminMembers(AdminMemberSearchRequest request, Pageable pageable);
}
