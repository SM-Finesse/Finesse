package com.finesse.backend.calc.preprocessing;

import org.springframework.stereotype.Component;

/**
 * 분석 요청마다 새 PseudonymScope를 발급한다 (설계서 5.6절).
 * 빈 자체는 상태를 갖지 않으며, 상태는 매번 새로 만들어지는 PseudonymScope에만 존재한다.
 */
@Component
public class MatchScopedPseudonymizer {

    public PseudonymScope newScope() {
        return new PseudonymScope();
    }
}