package com.cms.admin.banner.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 배너 생성·삭제·순서 저장을 직렬화하는 단일 가드 행(id=1, V34 시드). 배너 행이 하나도 없어도 잠글 행이 있어 갭 잠금·격리 수준에
 * 기대지 않고 개수 상한과 {@code ord} 계산이 직렬화된다(PLAN-public-home-banner.md 쟁점 5, 리뷰 R1-1).
 */
@Entity
@Table(name = "banner_lock")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BannerLock {

    public static final Long SINGLETON_ID = 1L;

    @Id
    private Long id;
}
