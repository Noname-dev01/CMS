package com.cms.admin.permission;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** {@link MemberBoardPermission} 복합 키(memberId, boardId, action). */
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class MemberBoardPermissionId implements Serializable {

    private Long memberId;
    private Long boardId;
    private String action;
}
