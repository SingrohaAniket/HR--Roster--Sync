package com.moveinsync.roster.web;

import org.springframework.http.ProblemDetail;

final class Problems {

    private Problems() {
    }

    static ProblemDetail of(ApiErrorCode code) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), code.detail());
        problem.setProperty("code", code.name());
        return problem;
    }
}
