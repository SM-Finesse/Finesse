package com.finesse.backend.calc.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 실제 TETR.IO로 전체 분석 확인용. -Dtetrio.live=true -Dtetrio.username=<닉네임> 을 줄 때만 실행된다.
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "tetrio.live", matches = "true")
class AnalyzeLiveTest {

    @Autowired
    StatCalculatorFacade facade;

    @Test
    void 실제_유저_분석() {
        AnalysisOutcome outcome = facade.analyze(System.getProperty("tetrio.username"));
        System.out.println(outcome);
    }
}