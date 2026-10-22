package com.cms.admin.faq.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * FAQ·카테고리의 모든 쓰기를 직렬화하는 단일 가드 행(id=1, V2 시드)이자 구조 버전 보관소(PLAN-faq.md 쟁점 3). 가드 행이 있으면 테이블이 비어
 * 있어도 잠글 행이 있다. {@link #version}은 구조(등록·삭제·카테고리 이동·순서)가 바뀔 때마다 오르고, 순서 초안을 만든 화면이 낡았는지 가린다.
 */
@Entity
@Table(name = "faq_lock")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FaqLock {

    public static final Long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(nullable = false)
    private Long version;

    /** 구조가 바뀌었음을 기록한다. 반드시 가드 행을 잠근 트랜잭션 안에서 호출한다. */
    public void bumpVersion() {
        this.version = Math.incrementExact(this.version);
    }
}
