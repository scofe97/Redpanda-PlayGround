package com.study.playground.operator.arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(packages = "com.study.playground.operator", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureBoundaryTest {

    @ArchTest
    static final ArchRule ticket_should_not_depend_on_pipeline =
            noClasses().that().resideInAPackage("..ticket..")
                    .and().resideOutsideOfPackage("..ticket.event..")
                    .should().dependOnClassesThat().resideInAPackage("..pipeline..")
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule pipeline_should_not_depend_on_ticket =
            noClasses().that().resideInAPackage("..pipeline..")
                    .and().resideOutsideOfPackage("..pipeline.service..")
                    .should().dependOnClassesThat().resideInAPackage("..ticket..")
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule controller_should_only_call_service =
            classes().that().resideInAPackage("..api..")
                    .and().resideOutsideOfPackage("..operatorjob.api..")
                    .should().onlyDependOnClassesThat()
                    .resideInAnyPackage("..api..", "..dto..", "..service..", "..domain..",
                            "..sse..", "..common..", "java..", "org.springframework..",
                            "jakarta..", "lombok..", "org.slf4j..");

    @ArchTest
    static final ArchRule operatorjob_controller_should_only_use_stub_collaborators =
            classes().that().resideInAPackage("..operatorjob.api..")
                    .should().onlyDependOnClassesThat()
                    .resideInAnyPackage("..operatorjob.api..", "..operatorjob.domain..",
                            "..operatorjob.publisher..", "..operatorjob.fixture..",
                            "java..", "org.springframework..", "jakarta..", "lombok..",
                            "org.slf4j..");

    @ArchTest
    static final ArchRule repository_access_only_from_service =
            classes().that().haveSimpleNameEndingWith("Repository")
                    .and().resideInAPackage("..repository..")
                    .should().onlyBeAccessed().byClassesThat()
                    .resideInAnyPackage("..service..", "..repository..", "..event..",
                            "..engine..", "..jenkins..", "..reconciler..", "..adapter..");

    @ArchTest
    static final ArchRule mapper_access_only_from_service =
            classes().that().haveSimpleNameEndingWith("Mapper")
                    .should().onlyBeAccessed().byClassesThat()
                    .resideInAnyPackage("..service..", "..application..", "..mapper..",
                            "..event..", "..engine..", "..jenkins..", "..reconciler..",
                            "..infrastructure.persistence..")
                    .allowEmptyShould(true);

    private static DescribedPredicate<JavaClass> inPackage(String pkg) {
        return DescribedPredicate.describe("resides in " + pkg,
                clazz -> clazz.getPackageName().startsWith(pkg));
    }

    /**
     * purpose ↔ supporttool 간 의도적 양방향 의존이 있다.
     * PurposeEntry가 ToolCategory(supporttool enum)를 참조하고,
     * JenkinsInstanceResolverAdapter(supporttool)가 PurposeEntryRepository를 참조한다.
     */
    @ArchTest
    static final ArchRule no_cycles =
            slices().matching("com.study.playground.operator.(*)..")
                    .should().beFreeOfCycles()
                    .ignoreDependency(
                            inPackage("com.study.playground.operator.purpose")
                            , inPackage("com.study.playground.operator.supporttool"))
                    .ignoreDependency(
                            inPackage("com.study.playground.operator.supporttool")
                            , inPackage("com.study.playground.operator.purpose"));
}
