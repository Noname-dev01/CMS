package com.cms.admin.page.domain;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** {@link SitePageRevision} 복합 키(pageId, revisionNo). */
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class SitePageRevisionId implements Serializable {

    private Long pageId;
    private Integer revisionNo;
}
