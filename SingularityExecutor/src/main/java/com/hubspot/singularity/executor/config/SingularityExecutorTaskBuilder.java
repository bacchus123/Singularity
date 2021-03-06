package com.hubspot.singularity.executor.config;

import java.nio.file.Path;

import org.apache.mesos.ExecutorDriver;
import org.apache.mesos.Protos;
import org.apache.mesos.Protos.TaskInfo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.io.Files;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import com.hubspot.deploy.ExecutorData;
import com.hubspot.mesos.MesosUtils;
import com.hubspot.singularity.executor.TemplateManager;
import com.hubspot.singularity.executor.task.SingularityExecutorArtifactFetcher;
import com.hubspot.singularity.executor.task.SingularityExecutorTask;
import com.hubspot.singularity.executor.task.SingularityExecutorTaskDefinition;
import com.hubspot.singularity.executor.utils.ExecutorUtils;
import com.hubspot.singularity.runner.base.config.SingularityRunnerBaseModule;
import com.hubspot.singularity.runner.base.configuration.SingularityRunnerBaseConfiguration;
import com.hubspot.singularity.runner.base.shared.JsonObjectFileHelper;
import com.hubspot.singularity.s3.base.config.SingularityS3Configuration;
import com.spotify.docker.client.DockerClient;

import ch.qos.logback.classic.Logger;

@Singleton
public class SingularityExecutorTaskBuilder {

  private final ObjectMapper jsonObjectMapper;

  private final TemplateManager templateManager;

  private final SingularityRunnerBaseConfiguration baseConfiguration;
  private final SingularityExecutorConfiguration executorConfiguration;
  private final SingularityS3Configuration s3Configuration;
  private final SingularityExecutorArtifactFetcher artifactFetcher;
  private final DockerClient dockerClient;

  private final SingularityExecutorLogging executorLogging;
  private final ExecutorUtils executorUtils;

  private final String executorPid;

  private final JsonObjectFileHelper jsonObjectFileHelper;

  @Inject
  public SingularityExecutorTaskBuilder(ObjectMapper jsonObjectMapper, JsonObjectFileHelper jsonObjectFileHelper, TemplateManager templateManager,
      SingularityExecutorLogging executorLogging, SingularityRunnerBaseConfiguration baseConfiguration, SingularityExecutorConfiguration executorConfiguration, @Named(SingularityRunnerBaseModule.PROCESS_NAME) String executorPid,
      ExecutorUtils executorUtils, SingularityExecutorArtifactFetcher artifactFetcher, DockerClient dockerClient, SingularityS3Configuration s3Configuration) {
    this.jsonObjectFileHelper = jsonObjectFileHelper;
    this.jsonObjectMapper = jsonObjectMapper;
    this.templateManager = templateManager;
    this.executorLogging = executorLogging;
    this.baseConfiguration = baseConfiguration;
    this.executorConfiguration = executorConfiguration;
    this.artifactFetcher = artifactFetcher;
    this.dockerClient = dockerClient;
    this.executorPid = executorPid;
    this.executorUtils = executorUtils;
    this.s3Configuration = s3Configuration;
  }

  public Logger buildTaskLogger(String taskId, String executorId) {
    Path javaExecutorLogPath = MesosUtils.getTaskDirectoryPath(taskId).resolve(executorConfiguration.getExecutorJavaLog());

    return executorLogging.buildTaskLogger(taskId, executorId, executorPid, javaExecutorLogPath.toString());
  }

  public SingularityExecutorTask buildTask(String taskId, ExecutorDriver driver, TaskInfo taskInfo, Logger log) {
    ExecutorData executorData = readExecutorData(jsonObjectMapper, taskInfo);

    SingularityExecutorTaskDefinition taskDefinition = new SingularityExecutorTaskDefinition(taskId, executorData, MesosUtils.getTaskDirectoryPath(taskId).toString(), executorPid,
        executorConfiguration.getServiceLog(), Files.getFileExtension(executorConfiguration.getServiceLog()), executorConfiguration.getTaskAppDirectory(), executorConfiguration.getExecutorBashLog(), executorConfiguration.getLogrotateStateFile(), executorConfiguration.getSignatureVerifyOut());

    jsonObjectFileHelper.writeObject(taskDefinition, executorConfiguration.getTaskDefinitionPath(taskId), log);

    return new SingularityExecutorTask(driver, executorUtils, baseConfiguration, executorConfiguration, taskDefinition, executorPid, artifactFetcher, taskInfo, templateManager, jsonObjectMapper, log, jsonObjectFileHelper, dockerClient, s3Configuration);
  }

  private ExecutorData readExecutorData(ObjectMapper objectMapper, Protos.TaskInfo taskInfo) {
    try {
      Preconditions.checkState(taskInfo.hasData(), "TaskInfo was missing executor data");

      return objectMapper.readValue(taskInfo.getData().toByteArray(), ExecutorData.class);
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }

}
