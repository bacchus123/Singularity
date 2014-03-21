package com.hubspot.singularity.mesos;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.Inject;
import com.hubspot.mesos.json.MesosExecutorObject;
import com.hubspot.mesos.json.MesosSlaveFrameworkObject;
import com.hubspot.mesos.json.MesosSlaveStateObject;
import com.hubspot.mesos.json.MesosTaskObject;
import com.hubspot.singularity.SingularityCloseable;
import com.hubspot.singularity.SingularityCloser;
import com.hubspot.singularity.SingularityTask;
import com.hubspot.singularity.SingularityTaskId;
import com.hubspot.singularity.config.SingularityConfiguration;
import com.hubspot.singularity.data.TaskManager;

public class SingularityLogSupport implements SingularityCloseable {

  private final static Logger LOG = LoggerFactory.getLogger(SingularityLogSupport.class);

  private final MesosClient mesosClient;
  private final TaskManager taskManager;
  
  private final ThreadPoolExecutor logLookupExecutorService;
  
  private final SingularityCloser closer;

  @Inject
  public SingularityLogSupport(SingularityConfiguration configuration, MesosClient mesosClient, TaskManager taskManager, SingularityCloser closer) {
    this.mesosClient = mesosClient;
    this.taskManager = taskManager;
    this.closer = closer;

    this.logLookupExecutorService = new ThreadPoolExecutor(configuration.getLogFetchCoreThreads(), configuration.getLogFetchMaxThreads(), 250L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(), new ThreadFactoryBuilder().setNameFormat("SingularityDirectoryFetcher-%d").build());
  }

  @Override
  public void close() {
    closer.shutdown(getClass().getName(), logLookupExecutorService);
  }
  
  private Optional<String> findDirectory(SingularityTaskId taskId, List<MesosExecutorObject> executors) {
    for (MesosExecutorObject executor : executors) {
      for (MesosTaskObject executorTask : executor.getTasks()) {
        if (taskId.getId().equals(executorTask.getId())) {
          return Optional.of(executor.getDirectory());
        }
      }
      for (MesosTaskObject executorTask : executor.getCompletedTasks()) {
        if (taskId.getId().equals(executorTask.getId())) {
          return Optional.of(executor.getDirectory());
        }
      }
    }
  
    return Optional.absent();
  }

  private void loadDirectory(SingularityTask task) {
    final long now = System.currentTimeMillis();
    
    final String slaveUri = mesosClient.getSlaveUri(task.getOffer().getHostname());

    LOG.info(String.format("Fetching slave data to find log directory for task %s from uri %s", task.getTaskId(), slaveUri));

    MesosSlaveStateObject slaveState = mesosClient.getSlaveState(slaveUri);

    Optional<String> directory = null;

    for (MesosSlaveFrameworkObject slaveFramework : slaveState.getFrameworks()) {
      directory = findDirectory(task.getTaskId(), slaveFramework.getExecutors());
      if (!directory.isPresent()) {
        directory = findDirectory(task.getTaskId(), slaveFramework.getCompletedExecutors());
      }
    }
   
    if (!directory.isPresent()) {
      LOG.warn(String.format("Couldn't find matching executor for task %s", task.getTaskId()));
      return;
    }

    LOG.debug(String.format("Found a directory %s for task %s", directory.get(), task.getTaskId()));

    taskManager.updateTaskDirectory(task.getTaskId().getId(), directory.get());

    LOG.trace(String.format("Updated task %s directory in %sms", task.getTaskId(), System.currentTimeMillis() - now));
  }

  public void checkDirectory(final SingularityTaskId taskId) {
    final Optional<String> maybeDirectory = taskManager.getDirectory(taskId.getId());
    
    if (maybeDirectory.isPresent()) {
      LOG.debug(String.format("Already had a directory for task %s, skipping lookup", taskId));
      return;
    }
    
    final Optional<SingularityTask> task = taskManager.getTask(taskId.getId());
    
    if (!task.isPresent()) {
      LOG.warn(String.format("No task found available for task %s, can't locate directory", taskId));
      return;
    }
    
    Runnable cmd = generateLookupCommand(task.get());

    LOG.trace(String.format("Enqueing a request to fetch directory for task: %s, current queue size: %s", taskId, logLookupExecutorService.getQueue().size()));
  
    logLookupExecutorService.submit(cmd);
  }
  
  private Runnable generateLookupCommand(final SingularityTask task) {
    return new Runnable() {

      @Override
      public void run() {
        try {
          loadDirectory(task);
        } catch (Throwable t) {
          LOG.error(String.format("While fetching directory for task: %s", task.getTaskId()), t);
        }
      }
    };
  }

}
