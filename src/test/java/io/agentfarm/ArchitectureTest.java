package io.agentfarm;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.freeze.FreezingArchRule.freeze;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The direction the code must keep moving in. What already breaks a frozen rule is recorded in
 * {@code src/test/resources/archunit_store} and shrinks by itself as it gets fixed; anything new
 * fails the build. Nothing may be added to that store by hand: see docs/QUALITY.md.
 */
@AnalyzeClasses(packages = "io.agentfarm", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
  @ArchTest
  static final ArchRule theCoreDoesNotKnowHowItIsDelivered =
      noClasses()
          .that()
          .resideInAPackage("..core..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..web..", "..ui..");

  @ArchTest
  static final ArchRule theCoreIsFreeOfFrameworks =
      freeze(
          noClasses()
              .that()
              .resideInAPackage("..core..")
              .should()
              .dependOnClassesThat()
              .resideInAnyPackage("org.springframework..", "com.fasterxml.jackson.."));

  @ArchTest
  static final ArchRule theCoreDoesNotReachTheOutsideWorldDirectly =
      freeze(
          noClasses()
              .that()
              .resideInAPackage("..core..")
              .should()
              .dependOnClassesThat()
              .resideInAnyPackage("java.sql..", "java.net.http..")
              .orShould()
              .dependOnClassesThat()
              .areAssignableTo(ProcessBuilder.class));

  @ArchTest
  static final ArchRule theDeliveryLayerDoesNotTouchTheDatabase =
      freeze(
          noClasses()
              .that()
              .resideInAnyPackage("..web..", "..ui..")
              .should()
              .dependOnClassesThat()
              .resideInAPackage("java.sql..")
              .orShould()
              .dependOnClassesThat()
              .haveFullyQualifiedName("io.agentfarm.core.Store"));
}
