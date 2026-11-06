package com.example.backend.shortform;

/**
 * 제작의 세부 단계. {@link RenderStatus}는 기존 앱 호환을 위해 그대로 두고, 검토 단계는 이 값으로 구분한다.
 * 검토 중에도 status는 PROCESSING이다.
 */
public enum RenderPhase {
    /** AI가 사진·영상을 분석해 스토리보드와 대본 초안을 만드는 중. */
    DRAFTING,
    /** 사용자의 검토·수정·승인을 기다리는 중. 영상 생성은 시작하지 않았다. */
    AWAITING_REVIEW,
    /** 승인된 스토리보드로 영상을 만드는 중. */
    RENDERING,
    /** 완성본을 받아 보관했다. */
    DONE
}
