/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.yarn

import java.io.{FileWriter, BufferedWriter, PrintWriter}
import java.security.PrivilegedAction

import akka.actor.ActorSystem
import org.apache.flink.client.CliFrontend
import org.apache.flink.configuration.{GlobalConfiguration, Configuration, ConfigConstants}
import org.apache.flink.runtime.StreamingMode
import org.apache.flink.runtime.akka.AkkaUtils
import org.apache.flink.runtime.jobmanager.{MemoryArchivist, JobManagerMode, JobManager}
import org.apache.flink.runtime.util.EnvironmentInformation
import org.apache.flink.runtime.webmonitor.WebMonitor
import org.apache.flink.yarn.YarnMessages.StartYarnSession
import org.apache.hadoop.security.UserGroupInformation
import org.apache.hadoop.yarn.api.ApplicationConstants.Environment
import org.apache.hadoop.yarn.conf.YarnConfiguration
import org.slf4j.LoggerFactory

import scala.io.Source

/** Base class for all application masters. This base class provides functionality to start a
  * [[JobManager]] implementation in a Yarn container.
  *
  * The only functions which have to be overwritten are the getJobManagerClass and
  * getArchivistClass, which define the actors to be started.
  *
  */
abstract class ApplicationMasterBase {
  import scala.collection.JavaConverters._

  val log = LoggerFactory.getLogger(getClass)

  val CONF_FILE = "flink-conf.yaml"
  val MODIFIED_CONF_FILE = "flink-conf-modified.yaml"
  val MAX_REGISTRATION_DURATION = "5 minutes"

  def getJobManagerClass: Class[_ <: JobManager]
  def getArchivistClass: Class[_ <: MemoryArchivist]

  def run(args: Array[String]): Unit = {
    val yarnClientUsername = System.getenv(FlinkYarnClientBase.ENV_CLIENT_USERNAME)
    log.info(s"YARN daemon runs as ${UserGroupInformation.getCurrentUser.getShortUserName} " +
      s"setting user to execute Flink ApplicationMaster/JobManager to ${yarnClientUsername}")

    EnvironmentInformation.logEnvironmentInfo(log, "YARN ApplicationMaster/JobManager", args)
    EnvironmentInformation.checkJavaVersion()
    org.apache.flink.runtime.util.SignalHandler.register(log)

    val ugi = UserGroupInformation.createRemoteUser(yarnClientUsername)

    for(token <- UserGroupInformation.getCurrentUser.getTokens.asScala){
      ugi.addToken(token)
    }

    ugi.doAs(new PrivilegedAction[Object] {
      override def run(): Object = {
        runAction()
        null
      }
    })
  }

  def runAction(): Unit = {
    var webMonitorOption: Option[WebMonitor] = None
    var actorSystemOption: Option[ActorSystem] = None

    try {
      val env = System.getenv()

      if (log.isDebugEnabled) {
        log.debug("All environment variables: " + env.toString)
      }

      val currDir = env.get(Environment.PWD.key())
      require(currDir != null, "Current directory unknown.")

      val logDirs = env.get(Environment.LOG_DIRS.key())

      val streamingMode = if(ApplicationMasterBase.hasStreamingMode(env)) {
        log.info("Starting ApplicationMaster/JobManager in streaming mode")
        StreamingMode.STREAMING
      } else {
        log.info("Starting ApplicationMaster/JobManager in batch only mode")
        StreamingMode.BATCH_ONLY
      }

      // Note that we use the "ownHostname" given by YARN here, to make sure
      // we use the hostnames given by YARN consistently throughout akka.
      // for akka "localhost" and "localhost.localdomain" are different actors.
      val ownHostname = env.get(Environment.NM_HOST.key())
      require(ownHostname != null, "Own hostname in YARN not set.")

      log.debug("Yarn assigned hostname for application master {}.", ownHostname)

      val taskManagerCount = env.get(FlinkYarnClientBase.ENV_TM_COUNT).toInt
      val slots = env.get(FlinkYarnClientBase.ENV_SLOTS).toInt
      val dynamicPropertiesEncodedString = env.get(FlinkYarnClientBase.ENV_DYNAMIC_PROPERTIES)

      val config = createConfiguration(currDir, dynamicPropertiesEncodedString)

      // if a web monitor shall be started, set the port to random binding
      if (config.getInteger(ConfigConstants.JOB_MANAGER_WEB_PORT_KEY, 0) >= 0) {
        config.setString(ConfigConstants.JOB_MANAGER_WEB_LOG_PATH_KEY, logDirs)
        config.setInteger(ConfigConstants.JOB_MANAGER_WEB_PORT_KEY, 0); // set port to 0.
      }

      val (actorSystem, jmActor, archivActor, webMonitor) =
        JobManager.startActorSystemAndJobManagerActors(
          config,
          JobManagerMode.CLUSTER,
          streamingMode,
          ownHostname,
          0,
          getJobManagerClass,
          getArchivistClass
        )

      actorSystemOption = Option(actorSystem)
      webMonitorOption = webMonitor

      val address = AkkaUtils.getAddress(actorSystem)
      val jobManagerPort = address.port.get
      val akkaHostname = address.host.get

      log.debug("Actor system bound hostname {}.", akkaHostname)

      val webServerPort = webMonitor.map(_.getServerPort()).getOrElse(-1)

      // generate configuration file for TaskManagers
      generateConfigurationFile(s"$currDir/$MODIFIED_CONF_FILE", currDir, akkaHostname,
        jobManagerPort, webServerPort, logDirs, slots, taskManagerCount,
        dynamicPropertiesEncodedString)

      val hadoopConfig = new YarnConfiguration();

      // send "start yarn session" message to YarnJobManager.
      log.info("Starting YARN session on Job Manager.")
      jmActor ! StartYarnSession(hadoopConfig, webServerPort)

      log.info("Application Master properly initiated. Awaiting termination of actor system.")
      actorSystem.awaitTermination()
    }
    catch {
      case t: Throwable =>
        log.error("Error while running the application master.", t)

        actorSystemOption.foreach {
          actorSystem =>
            actorSystem.shutdown()
            actorSystem.awaitTermination()
        }
    }
    finally {
      webMonitorOption.foreach {
        webMonitor =>
          log.debug("Stopping Job Manager web frontend.")
          webMonitor.stop()
      }
    }
  }

  def generateConfigurationFile(
    fileName: String,
    currDir: String,
    ownHostname: String,
    jobManagerPort: Int,
    jobManagerWebPort: Int,
    logDirs: String,
    slots: Int,
    taskManagerCount: Int,
    dynamicPropertiesEncodedString: String)
  : Unit = {
    log.info("Generate configuration file for application master.")
    val output = new PrintWriter(new BufferedWriter(
      new FileWriter(fileName))
    )

    for (line <- Source.fromFile(s"$currDir/$CONF_FILE").getLines() if !(line.contains
      (ConfigConstants.JOB_MANAGER_IPC_ADDRESS_KEY))) {
      output.println(line)
    }

    output.println(s"${ConfigConstants.JOB_MANAGER_IPC_ADDRESS_KEY}: $ownHostname")
    output.println(s"${ConfigConstants.JOB_MANAGER_IPC_PORT_KEY}: $jobManagerPort")

    output.println(s"${ConfigConstants.JOB_MANAGER_WEB_LOG_PATH_KEY}: $logDirs")
    output.println(s"${ConfigConstants.JOB_MANAGER_WEB_PORT_KEY}: $jobManagerWebPort")


    if(slots != -1){
      output.println(s"${ConfigConstants.TASK_MANAGER_NUM_TASK_SLOTS}: $slots")
      output.println(
        s"${ConfigConstants.DEFAULT_PARALLELISM_KEY}: ${slots*taskManagerCount}")
    }

    output.println(s"${ConfigConstants.TASK_MANAGER_MAX_REGISTRATION_DURATION}: " +
      s"$MAX_REGISTRATION_DURATION")

    // add dynamic properties
    val dynamicProperties = CliFrontend.getDynamicProperties(dynamicPropertiesEncodedString)

    import scala.collection.JavaConverters._

    for(property <- dynamicProperties.asScala){
      output.println(s"${property.f0}: ${property.f1}")
    }

    output.close()
  }

  def createConfiguration(curDir: String, dynamicPropertiesEncodedString: String): Configuration = {
    log.info(s"Loading config from: $curDir.")

    GlobalConfiguration.loadConfiguration(curDir)
    val configuration = GlobalConfiguration.getConfiguration()

    configuration.setString(ConfigConstants.FLINK_BASE_DIR_PATH_KEY, curDir)

    // add dynamic properties to JobManager configuration.
    val dynamicProperties = CliFrontend.getDynamicProperties(dynamicPropertiesEncodedString)
    import scala.collection.JavaConverters._
    for(property <- dynamicProperties.asScala){
      configuration.setString(property.f0, property.f1)
    }

    configuration
  }
}

object ApplicationMasterBase {
  def hasStreamingMode(env: java.util.Map[String, String]): Boolean = {
    val sModeString = env.get(FlinkYarnClientBase.ENV_STREAMING_MODE)
    if(sModeString != null) {
      return sModeString.toBoolean
    }
    false
  }
}
