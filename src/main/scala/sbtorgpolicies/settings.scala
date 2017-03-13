/*
 * Copyright 2016 47 Degrees, LLC. <http://www.47deg.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sbtorgpolicies

import com.typesafe.sbt.pgp.PgpKeys
import com.typesafe.sbt.pgp.PgpKeys._
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport._
import sbt.Keys._
import sbt.ScriptedPlugin.{scriptedDependencies, scriptedLaunchOpts}
import sbt._
import sbtrelease.ReleasePlugin.autoImport.ReleaseTransformations._
import sbtrelease.ReleasePlugin.autoImport._
import sbtunidoc.Plugin.UnidocKeys._
import sbtunidoc.Plugin._
import scoverage.ScoverageKeys

trait settings extends dependencies with utils {

  /**
   * Settings common to all projects.
   *
   * Adds Sonatype release repository and "withCachedResolution" to the update options
   */
  lazy val sharedCommonSettings = Seq(
    resolvers ++= Seq(
      Resolver.sonatypeRepo("releases")
    ),
    updateOptions := updateOptions.value.withCachedResolution(true)
  )

  /**
   * Release settings common to all projects.
   *
   * Adds a Sonatype release step to the default release steps
   */
  lazy val sharedReleaseProcess = Seq(
    releaseProcess := Seq[ReleaseStep](
      checkSnapshotDependencies,
      inquireVersions,
      runClean,
      runTest,
      setReleaseVersion,
      commitReleaseVersion,
      tagRelease,
      publishArtifacts,
      setNextVersion,
      commitNextVersion,
      ReleaseStep(action = Command.process("sonatypeReleaseAll", _)),
      pushChanges
    )
  )

  /** Adds the credential settings required for sonatype releases.*/
  lazy val credentialSettings = Seq(
    // For Travis CI - see http://www.cakesolutions.net/teamblogs/publishing-artefacts-to-oss-sonatype-nexus-using-sbt-and-travis-ci
    credentials ++= (for {
      username <- Option(System.getenv().get("SONATYPE_USERNAME"))
      password <- Option(System.getenv().get("SONATYPE_PASSWORD"))
    } yield Credentials("Sonatype Nexus Repository Manager", "oss.sonatype.org", username, password)).toSeq
  )

  lazy val pgpSettings = Seq(
    pgpPassphrase := Some(sys.env.getOrElse("PGP_PASSPHRASE", "").toCharArray),
    gpgCommand := gpgFolder,
    pgpPublicRing := file(s"$gpgFolder/pubring.gpg"),
    pgpSecretRing := file(s"$gpgFolder/secring.gpg")
  )

  // Common and shared setting
  /** Settings to make the module not published*/
  lazy val noPublishSettings = Seq(
    publish := (),
    publishLocal := (),
    publishArtifact := false
  )

  /** Using the supplied Versions map, adds the dependencies for scala macros.*/
  def scalaMacroDependencies: Seq[Setting[_]] = {
    Seq(
      libraryDependencies += "org.scala-lang" % "scala-compiler" % scalaVersion.value % "provided",
      libraryDependencies += "org.scala-lang" % "scala-reflect"  % scalaVersion.value % "provided",
      libraryDependencies ++= {
        CrossVersion.partialVersion(scalaVersion.value) match {
          // if scala 2.11+ is used, quasiquotes are merged into scala-reflect
          case Some((2, scalaMajor)) if scalaMajor >= 11 => Seq()
          // in Scala 2.10, quasiquotes are provided by macro paradise
          case Some((2, 10)) =>
            Seq(
              compilerPlugin(dep("paradise") cross CrossVersion.full),
              dep("quasiquotes") cross CrossVersion.binary
            )
        }
      }
    )
  }

  /**
   * Scala JS settings shared by many projects.
   *
   * Forces the use of node.js in tests and batchmode under travis
   */
  lazy val sharedJsSettings = Seq(
    scalaJSStage in Global := FastOptStage,
    parallelExecution := false,
    requiresDOM := false,
    jsEnv := NodeJSEnv().value,
    // batch mode decreases the amount of memory needed to compile scala.js code
    scalaJSOptimizerOptions := scalaJSOptimizerOptions.value.withBatchMode(scala.sys.env.get("TRAVIS").isDefined)
  )

  /**
   * Build settings common to all projects.
   *
   * Uses the github settings and versions map to set the organisation,
   * scala version and cross versions
   */
  def sharedBuildSettings(gh: GitHubSettings) = Seq(
    organization := gh.publishOrg,
    scalaVersion := scalac.`2.12`,
    crossScalaVersions := scalac.crossScalaVersions
  )

  /**
   * Publish settings common to all projects.
   *
   * Uses the github settings and list of developers to set all publish settings
   * required to publish signed artifacts to Sonatype OSS repository
   */
  def sharedPublishSettings(gh: GitHubSettings, devs: Seq[Dev]): Seq[Setting[_]] = Seq(
    homepage := Some(url(gh.home)),
    licenses += gh.license,
    scmInfo := Some(ScmInfo(url(gh.home), "scm:git:" + gh.repo)),
    apiURL := Some(url(gh.api)),
    releaseCrossBuild := true,
    releasePublishArtifactsAction := PgpKeys.publishSigned.value,
    publishMavenStyle := true,
    publishArtifact in Test := false,
    pomIncludeRepository := Function.const(false),
    publishTo := {
      val nexus = "https://oss.sonatype.org/"
      if (isSnapshot.value)
        Some("Snapshots" at nexus + "content/repositories/snapshots")
      else
        Some("Releases" at nexus + "service/local/staging/deploy/maven2")
    },
    autoAPIMappings := true,
    pomExtra := <developers> { devs.map(_.pomExtra) } </developers>
  )

  /** Common coverage settings, with minimum coverage defaulting to 80.*/
  def sharedScoverageSettings(min: Int = 80) = Seq(
    ScoverageKeys.coverageMinimum := min,
    ScoverageKeys.coverageFailOnMinimum := false,
    ScoverageKeys.coverageHighlighting := scalaBinaryVersion.value != "2.10"
  )

  /** Common unidoc settings, adding the "-Ymacro-no-expand" scalac option.*/
  lazy val unidocCommonSettings = Seq(
    scalacOptions in (ScalaUnidoc, unidoc) += "-Ymacro-no-expand"
  )

  /** Add the "unused import" warning to scala 2.11+, but not for the console.*/
  lazy val warnUnusedImport = Seq(
    scalacOptions ++= {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, 10)) =>
          Seq()
        case Some((2, n)) if n >= 11 =>
          Seq("-Ywarn-unused-import")
      }
    },
    //use this when activator moved to 13.9
    // scalacOptions in (Compile, console) -= "-Ywarn-unused-import",
    scalacOptions in (Compile, console) ~= { _.filterNot("-Ywarn-unused-import" == _) },
    scalacOptions in (Test, console) := { scalacOptions in (Compile, console) }.value
  )

  lazy val miscSettings = Seq(
    shellPrompt := { s: State =>
      val c     = scala.Console
      val blue  = c.RESET + c.BLUE + c.BOLD
      val white = c.RESET + c.BOLD

      val projectName = Project.extract(s).currentProject.id

      s"$blue$projectName$white>${c.RESET}"
    }
  )
}