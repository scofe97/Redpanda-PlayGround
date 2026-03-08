package com.study.playground.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(packages = "com.study.playground", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureBoundaryTest {

    @ArchTest
    static final ArchRule ticket_should_not_depend_on_pipeline =
            noClasses().that().resideInAPackage("..ticket..")
                    .and().resideOutsideOfPackage("..ticket.event..")
                    .should().dependOnClassesThat().resideInAPackage("..pipeline..");

    @ArchTest
    static final ArchRule pipeline_should_not_depend_on_ticket =
            noClasses().that().resideInAPackage("..pipeline..")
                    .and().resideOutsideOfPackage("..pipeline.service..")
                    .should().dependOnClassesThat().resideInAPackage("..ticket..");

    @ArchTest
    static final ArchRule controller_should_only_call_service =
            classes().that().resideInAPackage("..api..")
                    .should().onlyDependOnClassesThat()
                    .resideInAnyPackage("..api..", "..dto..", "..service..", "..domain..",
                            "..sse..", "..common..", "java..", "org.springframework..",
                            "jakarta..", "lombok..");

    @ArchTest
    static final ArchRule mapper_access_only_from_service =
            classes().that().haveSimpleNameEndingWith("Mapper")
                    .and().resideInAPackage("..mapper..")
                    .should().onlyBeAccessed().byClassesThat()
                    .resideInAnyPackage("..service..", "..mapper..", "..event..", "..engine..");

    @ArchTest
    static final ArchRule no_cycles =
            slices().matching("com.study.playground.(*)..")
                    .should().beFreeOfCycles();
}
