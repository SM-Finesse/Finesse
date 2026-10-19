package com.finesse.backend.service;

import com.finesse.backend.dto.ApiErrorResponse;

/** 다른 패키지 테스트에서 CommentService의 package-private 도우미를 쓰기 위한 통로 */
public final class CommentServiceTestAccess {

    private CommentServiceTestAccess() {
    }

    public static ApiErrorResponse statsFailureBody(RuntimeException e) {
        return CommentService.statsFailureBody(e);
    }
}
