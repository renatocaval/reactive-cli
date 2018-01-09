/*
 * Copyright 2017 Lightbend, Inc.
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

package com.lightbend.rp.reactivecli

import com.lightbend.rp.reactivecli.argparse.kubernetes.KubernetesArgs
import com.lightbend.rp.reactivecli.argparse.{ GenerateDeploymentArgs, InputArgs, VersionArgs }
import com.lightbend.rp.reactivecli.docker.{ Config, DockerCredentials, DockerEngine, DockerRegistry }
import com.lightbend.rp.reactivecli.process.jq
import com.lightbend.rp.reactivecli.runtime.kubernetes
import com.lightbend.rp.reactivecli.http.{ Http, HttpRequest }
import com.lightbend.rp.reactivecli.http.Http.HttpExchange
import java.nio.file.{ Files, Path, Paths }
import scala.annotation.tailrec
import scala.util.Try
import scalaz._
import slogging._

/**
 * This is the main entry of the Reactive CLI.
 */
object Main extends LazyLogging {
  val CliName = "reactive-cli"

  val parser = InputArgs.parser(CliName, ProgramVersion.current)

  private def credentialsFile: Option[Path] =
    for {
      home <- sys.props.get("user.home")
      path = Paths.get(home, ".lightbend", "docker.credentials")
      if Files.exists(path)
    } yield path

  @tailrec
  private def run(args: Array[String]): Unit = {
    if (args.nonEmpty) {
      parser.parse(args, InputArgs.default).foreach { inputArgs =>
        val inputArgsMerged = InputArgs.Envs.mergeWithEnvs(inputArgs, sys.env)

        LoggerConfig.level = inputArgsMerged.logLevel

        inputArgsMerged.commandArgs
          .collect {
            case VersionArgs =>
              System.out.println(s"rp (Reactive CLI) ${ProgramVersion.current}")
              System.out.println(s"jq support: ${if (jq.available) "Available" else "Unavailable"}")

            case generateDeploymentArgs @ GenerateDeploymentArgs(_, _, _, _, _, _, _, Some(kubernetesArgs: KubernetesArgs), _, _, _, _, _) =>
              implicit val httpSettings: Http.Settings =
                inputArgs.tlsCacertsPath.fold(Http.defaultSettings)(v => Http.defaultSettings.copy(tlsCacertsPath = Some(v)))

              val http: HttpExchange = Http.http

              val dockerRegistryArgsAuth =
                for {
                  username <- generateDeploymentArgs.registryUsername
                  password <- generateDeploymentArgs.registryPassword
                } yield HttpRequest.BasicAuth(username, password)

              val dockerRegistryFileAuth =
                for {
                  imageName <- generateDeploymentArgs.dockerImage
                  registry <- DockerRegistry.getRegistry(imageName)
                  credsFile <- credentialsFile
                  auth = DockerCredentials.parse(credsFile)
                  entry <- auth.find(_.registry == registry)
                } yield HttpRequest.BasicAuth(entry.username, entry.password)

              val dockerRegistryAuth = dockerRegistryArgsAuth.orElse(dockerRegistryFileAuth)

              dockerRegistryAuth match {
                case None =>
                  logger.debug("Attempting to pull manifest while unauthenticated")
                case Some(HttpRequest.BasicAuth(username, _)) =>
                  logger.debug(s"Attempting to pull manifest as $username")
              }

              def getDockerHostConfig(imageName: String): Option[Config] = {
                implicit val httpSettingsWithDockerCredentials: Http.Settings = DockerEngine.applyDockerHostSettings(httpSettings, sys.env)
                val http = Http.http(httpSettingsWithDockerCredentials)
                DockerEngine
                  .getConfigFromDockerHost(http, sys.env)(imageName)(httpSettingsWithDockerCredentials)
                  .map(_.registryConfig)
              }

              def getDockerRegistryConfig(imageName: String): Try[Config] =
                DockerRegistry.getConfig(
                  http,
                  dockerRegistryAuth,
                  generateDeploymentArgs.registryUseHttps,
                  generateDeploymentArgs.registryValidateTls)(imageName, token = None).map(_._1)

              def getDockerConfig(imageName: String): Try[Config] = {
                Try(getDockerHostConfig(imageName).get)
                  .orElse(getDockerRegistryConfig(imageName))
              }

              def tryToEither[T](t: Try[T]): Either[Throwable, T] =
                t
                  .map(Right.apply)
                  .recover { case failed => Left(failed) }
                  .get

              val outputHandler = kubernetes.handleGeneratedResources(kubernetesArgs.output)

              val output = {
                import Validation.FlatMap._

                for {
                  config <- Validation.fromEither(tryToEither(getDockerConfig(generateDeploymentArgs.dockerImage.get)))
                    .leftMap(t => NonEmptyList(s"Failed to obtain Docker config for ${generateDeploymentArgs.dockerImage.get}, ${t.getMessage}"))
                  resources <- kubernetes.generateResources(config, generateDeploymentArgs, kubernetesArgs)
                } yield resources
              }

              output.fold(
                errors =>
                  errors
                    .stream
                    .toVector
                    .distinct
                    .foreach(logger.error(_)),
                resources =>
                  outputHandler(resources))
          }
      }
    } else {
      run(Array("--help"))
    }
  }

  def main(args: Array[String]): Unit = {
    Http.globalInit()
    LoggerConfig.factory = TerminalLoggerFactory
    try {
      run(args)
    } finally {
      Http.globalCleanup()
    }
  }
}
