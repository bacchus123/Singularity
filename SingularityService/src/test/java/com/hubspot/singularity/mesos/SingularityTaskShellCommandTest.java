package com.hubspot.singularity.mesos;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.ws.rs.WebApplicationException;

import org.apache.mesos.Protos.TaskState;
import org.junit.Test;

import com.google.common.base.Optional;
import com.google.inject.Inject;
import com.hubspot.singularity.SingularitySchedulerTestBase;
import com.hubspot.singularity.SingularityShellCommand;
import com.hubspot.singularity.SingularityTask;
import com.hubspot.singularity.SingularityTaskId;
import com.hubspot.singularity.SingularityTaskShellCommandRequest;
import com.hubspot.singularity.SingularityTaskShellCommandRequestId;
import com.hubspot.singularity.SingularityTaskShellCommandUpdate;
import com.hubspot.singularity.SingularityTaskShellCommandUpdate.UpdateType;
import com.hubspot.singularity.config.UIConfiguration;
import com.hubspot.singularity.config.shell.ShellCommandDescriptor;
import com.hubspot.singularity.config.shell.ShellCommandOptionDescriptor;
import com.hubspot.singularity.data.transcoders.Transcoder;
import com.hubspot.singularity.resources.TaskResource;
import com.hubspot.singularity.scheduler.SingularityTaskShellCommandDispatchPoller;

public class SingularityTaskShellCommandTest extends SingularitySchedulerTestBase {

  @Inject
  private TaskResource taskResource;
  @Inject
  private SingularityTaskShellCommandDispatchPoller dispatchPoller;
  @Inject
  private SingularityMesosScheduler mesosScheduler;
  @Inject
  private Transcoder<SingularityTaskShellCommandUpdate> updateTranscoder;
  @Inject
  private UIConfiguration uiConfiguration;

  public SingularityTaskShellCommandTest() {
    super(false);
  }

  @Test
  public void testTaskShellCommandPersistence() {
    initRequest();
    initFirstDeploy();

    SingularityTask task = launchTask(request, firstDeploy, 1, TaskState.TASK_RUNNING);

    // test bad command first:

    List<ShellCommandDescriptor> descriptors = new ArrayList<>();

    ShellCommandDescriptor descriptor1 = new ShellCommandDescriptor();
    descriptor1.setName("d1");
    descriptor1.setOptions(Arrays.asList(new ShellCommandOptionDescriptor().setName("o1"), new ShellCommandOptionDescriptor().setName("o2")));
    ShellCommandDescriptor descriptor2 = new ShellCommandDescriptor();
    descriptor2.setName("d2");
    descriptor2.setOptions(Collections.<ShellCommandOptionDescriptor> emptyList());

    descriptors.add(descriptor1);
    descriptors.add(descriptor2);

    uiConfiguration.setShellCommands(descriptors);

    // bad shell cmd
    try {
      taskResource.runShellCommand(task.getTaskId().getId(), new SingularityShellCommand("test-cmd", Optional.of(Arrays.asList("one", "two")), user));
    } catch (WebApplicationException exception) {
      assertEquals(403, exception.getResponse().getStatus());
    }

    // bad option
    try {
      taskResource.runShellCommand(task.getTaskId().getId(), new SingularityShellCommand("d1", Optional.of(Arrays.asList("one", "two")), user));
    } catch (WebApplicationException exception) {
      assertEquals(400, exception.getResponse().getStatus());
    }

    SingularityTaskShellCommandRequest firstShellRequest = taskResource.runShellCommand(task.getTaskId().getId(), new SingularityShellCommand("d1", Optional.of(Arrays.asList("o1", "o2")), user));

    try {
      Thread.sleep(3);
    } catch (Exception e) {

    }

    SingularityTaskShellCommandRequest secondShellRequest = taskResource.runShellCommand(task.getTaskId().getId(), new SingularityShellCommand("d2", Optional.<List<String>> absent(), user));

    assertEquals(2, taskManager.getAllQueuedTaskShellCommandRequests().size());

    dispatchPoller.runActionOnPoll();

    assertEquals(0, taskManager.getAllQueuedTaskShellCommandRequests().size());

    assertEquals(2, taskManager.getTaskShellCommandRequestsForTask(task.getTaskId()).size());

    mesosScheduler.frameworkMessage(driver, task.getMesosTask().getExecutor().getExecutorId(), task.getMesosTask().getSlaveId(),
        updateTranscoder.toBytes(
            new SingularityTaskShellCommandUpdate(firstShellRequest.getId(), System.currentTimeMillis(), Optional.<String> of("hi"), Optional.<String>absent(), UpdateType.STARTED)));

    mesosScheduler.frameworkMessage(driver, task.getMesosTask().getExecutor().getExecutorId(), task.getMesosTask().getSlaveId(),
        updateTranscoder.toBytes(
            new SingularityTaskShellCommandUpdate(new SingularityTaskShellCommandRequestId(task.getTaskId(), "wat", System.currentTimeMillis()), System.currentTimeMillis(), Optional.<String> of("hi"), Optional.<String>absent(), UpdateType.STARTED)));

    mesosScheduler.frameworkMessage(driver, task.getMesosTask().getExecutor().getExecutorId(), task.getMesosTask().getSlaveId(),
        updateTranscoder.toBytes(
            new SingularityTaskShellCommandUpdate(new SingularityTaskShellCommandRequestId(new SingularityTaskId("makingitup", "did", System.currentTimeMillis(), 1, "host", "rack"), "wat", System.currentTimeMillis()), System.currentTimeMillis(), Optional.<String> of("hi"), Optional.<String>absent(), UpdateType.STARTED)));

    assertEquals(true, taskManager.getTaskHistory(task.getTaskId()).get().getShellCommandHistory().get(1).getShellUpdates().get(0).getUpdateType() == UpdateType.STARTED);

    assertEquals(1, taskManager.getTaskShellCommandUpdates(firstShellRequest.getId()).size());

    assertEquals(0, taskManager.getTaskShellCommandUpdates(secondShellRequest.getId()).size());
  }


}
