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

package com.lightbend.rp.reactivecli.runtime.kubernetes

import argonaut._
import com.lightbend.rp.reactivecli.annotations.kubernetes.{ ConfigMapEnvironmentVariable, FieldRefEnvironmentVariable, SecretKeyRefEnvironmentVariable }
import com.lightbend.rp.reactivecli.annotations._
import com.lightbend.rp.reactivecli.argparse._
import scala.collection.immutable.Seq
import scala.util.{ Failure, Success, Try }
import scalaz._

import Argonaut._
import Scalaz._

object Deployment {

  object RpEnvironmentVariables {
    /**
     * Environment variables in this set will be space-concatenated when the various environment variable
     * maps are merged.
     */
    private val ConcatLiteralEnvs = Set("RP_JAVA_OPTS")

    /**
     * Creates pod related environment variables using the Kubernetes Downward API:
     *
     * https://kubernetes.io/docs/tasks/inject-data-application/environment-variable-expose-pod-information/#use-pod-fields-as-values-for-environment-variables
     */
    private val PodEnvs = Map(
      "RP_PLATFORM" -> LiteralEnvironmentVariable("kubernetes"),
      "RP_KUBERNETES_POD_NAME" -> FieldRefEnvironmentVariable("metadata.name"),
      "RP_KUBERNETES_POD_IP" -> FieldRefEnvironmentVariable("status.podIP"))

    /**
     * Generates pod environment variables specific for RP applications.
     */
    def envs(annotations: Annotations, serviceResourceName: String, noOfReplicas: Int, externalServices: Map[String, Seq[String]], joinExistingAkkaCluster: Boolean): Map[String, EnvironmentVariable] =
      mergeEnvs(
        PodEnvs,
        namespaceEnv(annotations.namespace),
        appNameEnvs(annotations.appName),
        annotations.version.fold(Map.empty[String, EnvironmentVariable])(versionEnvs),
        appTypeEnvs(annotations.appType, annotations.modules),
        configEnvs(annotations.configResource),
        endpointEnvs(annotations.endpoints),
        akkaClusterEnvs(annotations.modules, annotations.namespace, serviceResourceName, noOfReplicas, annotations.akkaClusterBootstrapSystemName, joinExistingAkkaCluster),
        externalServicesEnvs(annotations.modules, externalServices))

    private[kubernetes] def namespaceEnv(namespace: Option[String]): Map[String, EnvironmentVariable] =
      namespace.fold(Map.empty[String, EnvironmentVariable])(v => Map("RP_NAMESPACE" -> LiteralEnvironmentVariable(v)))

    private[kubernetes] def appNameEnvs(appName: Option[String]): Map[String, EnvironmentVariable] =
      appName.fold(Map.empty[String, EnvironmentVariable])(v => Map("RP_APP_NAME" -> LiteralEnvironmentVariable(v)))

    private[kubernetes] def appTypeEnvs(appType: Option[String], modules: Set[String]): Map[String, EnvironmentVariable] = {
      appType
        .toVector
        .map("RP_APP_TYPE" -> LiteralEnvironmentVariable(_)) ++ (
          if (modules.isEmpty) Seq.empty else Seq("RP_MODULES" -> LiteralEnvironmentVariable(modules.toVector.sorted.mkString(","))))
    }.toMap

    private[kubernetes] def akkaClusterEnvs(modules: Set[String], namespace: Option[String], serviceResourceName: String, noOfReplicas: Int, akkaClusterBootstrapSystemName: Option[String], joinExistingAkkaCluster: Boolean): Map[String, EnvironmentVariable] =
      if (!modules.contains(Module.AkkaClusterBootstrapping))
        Map.empty
      else
        Map(
          "RP_JAVA_OPTS" -> LiteralEnvironmentVariable(
            Seq(
              s"-Dakka.discovery.method=kubernetes-api",
              namespace.fold("")(ns => s"-Dakka.discovery.kubernetes-api.pod-namespace=$ns"),
              s"-Dakka.management.cluster.bootstrap.contact-point-discovery.effective-name=$serviceResourceName",
              s"-Dakka.cluster.bootstrap.contact-point-discovery.required-contact-point-nr=$noOfReplicas",
              akkaClusterBootstrapSystemName.fold("-Dakka.discovery.kubernetes-api.pod-label-selector=appName=%s")(systemName => s"-Dakka.discovery.kubernetes-api.pod-label-selector=actorSystemName=$systemName"),
              s"${if (joinExistingAkkaCluster) "-Dakka.management.cluster.bootstrap.form-new-cluster=false" else ""}")
              .filter(_.nonEmpty)
              .mkString(" ")))

    private[kubernetes] def configEnvs(config: Option[String]): Map[String, EnvironmentVariable] =
      config
        .map(c => Map("RP_JAVA_OPTS" -> LiteralEnvironmentVariable(s"-Dconfig.resource=$c")))
        .getOrElse(Map.empty)

    private[kubernetes] def externalServicesEnvs(modules: Set[String], externalServices: Map[String, Seq[String]]): Map[String, EnvironmentVariable] =
      if (!modules.contains(Module.ServiceDiscovery))
        Map.empty
      else
        Map(
          "RP_JAVA_OPTS" -> LiteralEnvironmentVariable(
            externalServices
              .flatMap {
                case (name, addresses) =>
                  // We allow '/' as that's the convention used: $serviceName/$endpoint
                  // We allow '_' as it's currently used for Lagom defaults, i.e. "cas_native"

                  val arguments =
                    for {
                      (address, i) <- addresses.zipWithIndex
                    } yield s"-Dcom.lightbend.platform-tooling.service-discovery.external-service-addresses.${serviceName(name, Set('/', '_'))}.$i=$address"

                  arguments
              }
              .mkString(" ")))

    private[kubernetes] def versionEnvs(version: String): Map[String, EnvironmentVariable] =
      Map(
        "RP_APP_VERSION" -> LiteralEnvironmentVariable(version))

    private[kubernetes] def endpointEnvs(endpoints: Map[String, Endpoint]): Map[String, EnvironmentVariable] =
      if (endpoints.isEmpty)
        Map(
          "RP_ENDPOINTS_COUNT" -> LiteralEnvironmentVariable("0"))
      else
        Map(
          "RP_ENDPOINTS_COUNT" -> LiteralEnvironmentVariable(endpoints.size.toString),
          "RP_ENDPOINTS" -> LiteralEnvironmentVariable(
            endpoints.values.toList
              .sortBy(_.index)
              .map(v => envVarName(v.name))
              .mkString(","))) ++
          endpointPortEnvs(endpoints)

    private[kubernetes] def endpointPortEnvs(endpoints: Map[String, Endpoint]): Map[String, EnvironmentVariable] =
      AssignedPort.assignPorts(endpoints)
        .flatMap { assigned =>
          val assignedPortEnv = LiteralEnvironmentVariable(assigned.port.toString)
          val hostEnv = FieldRefEnvironmentVariable("status.podIP")
          Seq(
            s"RP_ENDPOINT_${envVarName(assigned.endpoint.name)}_HOST" -> hostEnv,
            s"RP_ENDPOINT_${envVarName(assigned.endpoint.name)}_BIND_HOST" -> hostEnv,
            s"RP_ENDPOINT_${envVarName(assigned.endpoint.name)}_PORT" -> assignedPortEnv,
            s"RP_ENDPOINT_${envVarName(assigned.endpoint.name)}_BIND_PORT" -> assignedPortEnv,
            s"RP_ENDPOINT_${assigned.endpoint.index}_HOST" -> hostEnv,
            s"RP_ENDPOINT_${assigned.endpoint.index}_BIND_HOST" -> hostEnv,
            s"RP_ENDPOINT_${assigned.endpoint.index}_PORT" -> assignedPortEnv,
            s"RP_ENDPOINT_${assigned.endpoint.index}_BIND_PORT" -> assignedPortEnv)
        }
        .toMap

    private[kubernetes] def mergeEnvs(envs: Map[String, EnvironmentVariable]*): Map[String, EnvironmentVariable] = {
      envs.foldLeft(Map.empty[String, EnvironmentVariable]) {
        case (a1, n) =>
          n.foldLeft(a1) {
            case (a2, (key, LiteralEnvironmentVariable(v))) if ConcatLiteralEnvs.contains(key) =>
              a2.updated(key, a2.get(key) match {
                case Some(LiteralEnvironmentVariable(ov)) => LiteralEnvironmentVariable(s"$ov $v".trim)
                case _ => LiteralEnvironmentVariable(v)
              })

            case (a2, (key, value)) =>
              a2.updated(key, value)
          }
      }
    }
  }

  /**
   * Represents possible values for imagePullPolicy field within the Kubernetes deployment resource.
   */
  object ImagePullPolicy extends Enumeration {
    val Never, IfNotPresent, Always = Value
  }

  case class ResourceLimits(cpu: Option[Double], memory: Option[Long])

  private[kubernetes] val VersionSeparator = "-v"

  implicit def imagePullPolicyEncode = EncodeJson[ImagePullPolicy.Value] {
    case ImagePullPolicy.Never => "Never".asJson
    case ImagePullPolicy.IfNotPresent => "IfNotPresent".asJson
    case ImagePullPolicy.Always => "Always".asJson
  }

  implicit def literalEnvironmentVariableEncode = EncodeJson[LiteralEnvironmentVariable] { env =>
    Json("value" -> env.value.asJson)
  }

  implicit def fieldRefEnvironmentVariableEncode = EncodeJson[FieldRefEnvironmentVariable] { env =>
    Json(
      "valueFrom" -> Json(
        "fieldRef" -> Json(
          "fieldPath" -> env.fieldPath.asJson)))
  }

  implicit def secretKeyRefEnvironmentVariableEncode = EncodeJson[SecretKeyRefEnvironmentVariable] { env =>
    Json(
      "valueFrom" -> Json(
        "secretKeyRef" -> Json(
          "name" -> env.name.asJson,
          "key" -> env.key.asJson)))
  }

  implicit def configMapEnvironmentVariableEncode = EncodeJson[ConfigMapEnvironmentVariable] { env =>
    Json(
      "valueFrom" -> Json(
        "configMapKeyRef" -> Json(
          "name" -> env.mapName.asJson,
          "key" -> env.key.asJson)))
  }

  implicit def environmentVariableEncode = EncodeJson[EnvironmentVariable] {
    case v: LiteralEnvironmentVariable => v.asJson
    case v: FieldRefEnvironmentVariable => v.asJson
    case v: ConfigMapEnvironmentVariable => v.asJson
    case v: SecretKeyRefEnvironmentVariable => v.asJson
  }

  implicit def environmentVariablesEncode = EncodeJson[Map[String, EnvironmentVariable]] { envs =>
    envs
      .toList
      .sortBy(_._1)
      .map {
        case (envName, env) =>
          Json("name" -> envName.asJson).deepmerge(env.asJson)
      }
      .asJson
  }

  implicit def assignedEncode = EncodeJson[AssignedPort] { assigned =>
    Json(
      "containerPort" -> assigned.port.asJson,
      "name" -> serviceName(assigned.endpoint.name).asJson)
  }

  implicit def endpointsEncode = EncodeJson[Map[String, Endpoint]] { endpoints =>
    AssignedPort.assignPorts(endpoints)
      .toList
      .sortBy(_.endpoint.index)
      .map(_.asJson)
      .asJson
  }

  implicit def resourceLimitsEncode = EncodeJson[ResourceLimits] {
    case ResourceLimits(cpu, memory) =>
      val memoryJson = memory.map({ v => Json("memory" -> v.asJson) }).getOrElse(jEmptyObject)
      val cpuJson = cpu.map({ v => Json("cpu" -> v.asJson) }).getOrElse(jEmptyObject)
      if (cpu.isEmpty && memory.isEmpty) {
        jEmptyObject
      } else {
        Json(
          "resources" -> Json(
            "limits" -> cpuJson.deepmerge(memoryJson),
            "request" -> cpuJson.deepmerge(memoryJson)))
      }
  }

  /**
   * Builds [[Deployment]] resource.
   */
  def generate(
    annotations: Annotations,
    apiVersion: String,
    imageName: String,
    imagePullPolicy: ImagePullPolicy.Value,
    noOfReplicas: Int,
    externalServices: Map[String, Seq[String]],
    deploymentType: DeploymentType,
    jqExpression: Option[String],
    joinExistingAkkaCluster: Boolean): ValidationNel[String, Deployment] =

    (annotations.appNameValidation |@| annotations.versionValidation) { (rawAppName, version) =>
      val appName = serviceName(rawAppName)
      val appNameVersion = serviceName(s"$appName$VersionSeparator$version")

      val deploymentLabels =
        Json("appName" -> appName.asJson, "appNameVersion" -> appNameVersion.asJson)
          .deepmerge(annotations.akkaClusterBootstrapSystemName.fold(jEmptyObject)(systemName => Json("actorSystemName" -> systemName.asJson)))

      val (deploymentName, deploymentMatchLabels, serviceResourceName) =
        deploymentType match {
          case CanaryDeploymentType =>
            (appNameVersion, Json("appNameVersion" -> appNameVersion.asJson), appName)

          case BlueGreenDeploymentType =>
            (appNameVersion, Json("appNameVersion" -> appNameVersion.asJson), appNameVersion)

          case RollingDeploymentType =>
            (appName, Json("appName" -> appName.asJson), appName)
        }

      val secretNames =
        annotations
          .secrets
          .map(_.name)
          .distinct
          .map(ns => (ns, serviceName(ns), s"secret-${serviceName(ns)}"))
          .toList

      val resourceLimits = ResourceLimits(annotations.cpu, annotations.memory)

      val enableChecks =
        annotations.modules.contains(Module.Status) && annotations.modules.contains(Module.AkkaManagement)

      val livenessProbe =
        if (enableChecks)
          Json("livenessProbe" ->
            Json(
              "httpGet" -> Json(
                "path" -> jString("/platform-tooling/healthy"),
                "port" -> jString(AkkaManagementPortName)),
              "periodSeconds" -> jNumber(StatusPeriodSeconds)))
        else
          jEmptyObject

      val readinessProbe =
        if (enableChecks)
          Json("readinessProbe" ->
            Json(
              "httpGet" -> Json(
                "path" -> jString("/platform-tooling/ready"),
                "port" -> jString(AkkaManagementPortName)),
              "periodSeconds" -> jNumber(StatusPeriodSeconds)))
        else
          jEmptyObject

      Deployment(
        deploymentName,
        Json(
          "apiVersion" -> apiVersion.asJson,
          "kind" -> "Deployment".asJson,
          "metadata" -> Json(
            "name" -> deploymentName.asJson,
            "labels" -> deploymentLabels)
            .deepmerge(
              annotations.namespace.fold(jEmptyObject)(ns => Json("namespace" -> serviceName(ns).asJson))),
          "spec" -> Json(
            "replicas" -> noOfReplicas.asJson,
            "selector" -> Json("matchLabels" -> deploymentMatchLabels),
            "template" -> Json(
              "metadata" -> Json("labels" -> deploymentLabels),
              "spec" -> Json(
                "containers" -> List(
                  Json(
                    "name" -> appName.asJson,
                    "image" -> imageName.asJson,
                    "imagePullPolicy" -> imagePullPolicy.asJson,
                    "env" -> RpEnvironmentVariables.mergeEnvs(
                      annotations.environmentVariables ++
                        RpEnvironmentVariables.envs(annotations, serviceResourceName, noOfReplicas, externalServices, joinExistingAkkaCluster)).asJson,
                    "ports" -> annotations.endpoints.asJson,
                    "volumeMounts" -> secretNames
                      .map {
                        case (_, secretServiceName, volumeName) =>
                          Json(
                            "mountPath" -> s"/rp/secrets/$secretServiceName".asJson,
                            "name" -> volumeName.asJson)
                      }
                      .asJson)
                    .deepmerge(readinessProbe)
                    .deepmerge(livenessProbe)
                    .deepmerge(resourceLimits.asJson)).asJson,
                "volumes" -> secretNames
                  .map {
                    case (secretName, _, volumeName) =>
                      Json(
                        "name" -> volumeName.asJson,
                        "secret" -> Json("secretName" -> secretName.asJson))
                  }.asJson)))),
        jqExpression)
    }
}

/**
 * Represents the generated Kubernetes deployment resource.
 */
case class Deployment(name: String, json: Json, jqExpression: Option[String]) extends GeneratedKubernetesResource {
  val resourceType = "deployment"
}
