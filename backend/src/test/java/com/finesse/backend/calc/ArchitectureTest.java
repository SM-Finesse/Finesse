package com.finesse.backend.calc;

import com.finesse.backend.calc.calculator.AnalyticsCalculator;
import com.finesse.backend.calc.collector.CollectionStatus;
import com.finesse.backend.calc.collector.UserSummary;
import com.finesse.backend.calc.domain.DeltaStats;
import com.finesse.backend.calc.domain.FancyStats;
import com.finesse.backend.calc.domain.HighlightStats;
import com.finesse.backend.calc.domain.MatchResult;
import com.finesse.backend.calc.domain.MatchSeriesStats;
import com.finesse.backend.calc.domain.ProfileWindowDeltaStats;
import com.finesse.backend.calc.domain.RecentWinLossStats;
import com.finesse.backend.calc.domain.RivalBadge;
import com.finesse.backend.calc.domain.RivalOpponentStats;
import com.finesse.backend.calc.domain.RivalryStats;
import com.finesse.backend.calc.domain.StatResult;
import com.finesse.backend.calc.service.AnalysisMeta;
import com.finesse.backend.calc.service.AnalysisOutcome;
import com.finesse.backend.calc.service.StatCalculatorFacade;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * calc 모듈 경계·금지 규칙 (설계서 13.2절, 25장 체크리스트).
 * 패키지는 "com.finesse.backend.calc.." 로 정확히 지정한다 — "..calc.." 는 백엔드의 service.calc 까지 잡는다.
 */
class ArchitectureTest {

    private static final String CALC = "com.finesse.backend.calc..";
    private static final String BACKEND = "com.finesse.backend..";

    /** 금지 필드·메서드 이름 (설계서 6.2절) — 대소문자 무시, get 접두어 포함, 이름 전체 일치 */
    private static final String FORBIDDEN_TR_NAME = "(?i)(get)?(estTr|eTr|estimatedTr)";

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.finesse.backend");
    }

    @Test
    void 추정_TR_필드와_메서드를_두지_않는다() {
        noFields().that().areDeclaredInClassesThat().resideInAPackage(CALC)
                .should().haveNameMatching(FORBIDDEN_TR_NAME)
                .check(classes);
        noMethods().that().areDeclaredInClassesThat().resideInAPackage(CALC)
                .should().haveNameMatching(FORBIDDEN_TR_NAME)
                .check(classes);
    }

    @Test
    void Calculator는_다른_Calculator에_의존하지_않는다() {
        classes().that().implement(AnalyticsCalculator.class)
                .should(notDependOnOtherCalculators())
                .check(classes);
    }

    @Test
    void calc는_DB_접근_계층에_의존하지_않는다() {
        noClasses().that().resideInAPackage(CALC)
                .should().dependOnClassesThat().resideInAnyPackage(
                        "jakarta.persistence..", "javax.persistence..", "javax.sql..",
                        "org.springframework.data..", "org.springframework.jdbc..")
                .check(classes);
    }

    @Test
    void calc는_백엔드의_다른_패키지에_의존하지_않는다() {
        noClasses().that().resideInAPackage(CALC)
                .should().dependOnClassesThat(resideInAPackage(BACKEND).and(resideOutsideOfPackage(CALC)))
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void 백엔드는_calc의_공개_타입만_사용한다() {
        noClasses().that().resideOutsideOfPackage(CALC)
                .should().dependOnClassesThat(resideInAPackage(CALC).and(not(belongToAnyOf(
                        StatCalculatorFacade.class, AnalysisOutcome.class, AnalysisMeta.class,
                        StatResult.class, FancyStats.class, DeltaStats.class, HighlightStats.class,
                        RecentWinLossStats.class, ProfileWindowDeltaStats.class, MatchSeriesStats.class,
                        RivalryStats.class, RivalOpponentStats.class, RivalBadge.class, MatchResult.class,
                        UserSummary.class, CollectionStatus.class))))
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void calc에는_SpringBootApplication을_두지_않는다() {
        noClasses().that().resideInAPackage(CALC)
                .should().beAnnotatedWith("org.springframework.boot.autoconfigure.SpringBootApplication")
                .check(classes);
    }

    private static ArchCondition<JavaClass> notDependOnOtherCalculators() {
        return new ArchCondition<>("not depend on other AnalyticsCalculator implementations") {
            @Override
            public void check(JavaClass calculator, ConditionEvents events) {
                for (Dependency dependency : calculator.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass();
                    boolean otherCalculator = !target.isInterface()
                            && target.isAssignableTo(AnalyticsCalculator.class)
                            && !target.equals(calculator);
                    if (otherCalculator) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        };
    }
}