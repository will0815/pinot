package com.linkedin.thirdeye.anomaly.task;

import com.linkedin.thirdeye.anomaly.utils.AnomalyUtils;
import com.linkedin.thirdeye.detector.email.filter.AlertFilterFactory;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linkedin.thirdeye.anomaly.ThirdEyeAnomalyConfiguration;
import com.linkedin.thirdeye.anomaly.task.TaskConstants.TaskStatus;
import com.linkedin.thirdeye.anomaly.task.TaskConstants.TaskType;
import com.linkedin.thirdeye.client.DAORegistry;
import com.linkedin.thirdeye.datalayer.bao.TaskManager;
import com.linkedin.thirdeye.datalayer.dto.TaskDTO;
import com.linkedin.thirdeye.detector.function.AnomalyFunctionFactory;

public class TaskDriver {

  private static final Logger LOG = LoggerFactory.getLogger(TaskDriver.class);
  private static final Random RANDOM = new Random();
  private static final DAORegistry DAO_REGISTRY = DAORegistry.getInstance();

  private ExecutorService taskExecutorService;

  private final TaskManager anomalyTaskDAO;
  private TaskContext taskContext;
  private long workerId;
  private final Set<TaskStatus> allowedOldTaskStatus = new HashSet<>();
  private TaskDriverConfiguration driverConfiguration;

  private volatile boolean shutdown = false;

  public TaskDriver(ThirdEyeAnomalyConfiguration thirdEyeAnomalyConfiguration,
      AnomalyFunctionFactory anomalyFunctionFactory, AlertFilterFactory alertFilterFactory) {
    driverConfiguration = thirdEyeAnomalyConfiguration.getTaskDriverConfiguration();
    workerId = thirdEyeAnomalyConfiguration.getId();
    anomalyTaskDAO = DAO_REGISTRY.getTaskDAO();
    taskExecutorService = Executors.newFixedThreadPool(driverConfiguration.getMaxParallelTasks());
    taskContext = new TaskContext();
    taskContext.setAnomalyFunctionFactory(anomalyFunctionFactory);
    taskContext.setThirdEyeAnomalyConfiguration(thirdEyeAnomalyConfiguration);
    taskContext.setAlertFilterFactory(alertFilterFactory);
    allowedOldTaskStatus.add(TaskStatus.FAILED);
    allowedOldTaskStatus.add(TaskStatus.WAITING);
  }

  public void start() throws Exception {
    for (int i = 0; i < driverConfiguration.getMaxParallelTasks(); i++) {
      Callable callable = new Callable() {
        @Override public Object call() throws Exception {
          while (!shutdown) {
            LOG.info(Thread.currentThread().getId() + " : Finding next task to execute for threadId:{}",
                Thread.currentThread().getId());

            // select a task to execute, and update it to RUNNING
            TaskDTO anomalyTaskSpec = TaskDriver.this.acquireTask();

            if (shutdown || anomalyTaskSpec == null) continue;

            try {
              LOG.info(Thread.currentThread().getId() + " : Executing task: {} {}", anomalyTaskSpec.getId(),
                  anomalyTaskSpec.getTaskInfo());

              // execute the selected task
              TaskType taskType = anomalyTaskSpec.getTaskType();
              TaskRunner taskRunner = TaskRunnerFactory.getTaskRunnerFromTaskType(taskType);
              TaskInfo taskInfo = TaskInfoFactory.getTaskInfoFromTaskType(taskType, anomalyTaskSpec.getTaskInfo());
              LOG.info(Thread.currentThread().getId() + " : Task Info {}", taskInfo);
              List<TaskResult> taskResults = taskRunner.execute(taskInfo, taskContext);
              LOG.info(Thread.currentThread().getId() + " : DONE Executing task: {}", anomalyTaskSpec.getId());
              // update status to COMPLETED
              TaskDriver.this
                  .updateStatusAndTaskEndTime(anomalyTaskSpec.getId(), TaskStatus.RUNNING, TaskStatus.COMPLETED);
            } catch (Exception e) {
              LOG.error("Exception in electing and executing task", e);
              try {
                // update task status failed
                TaskDriver.this
                    .updateStatusAndTaskEndTime(anomalyTaskSpec.getId(), TaskStatus.RUNNING, TaskStatus.FAILED);
              } catch (Exception e1) {
                LOG.error("Error in updating failed status", e1);
              }
            }
          }
          return 0;
        }
      };
      taskExecutorService.submit(callable);
      LOG.info(Thread.currentThread().getId() + " : Started task driver");
    }
  }

  public void shutdown() {
    shutdown = true;
    AnomalyUtils.safelyShutdownExecutionService(taskExecutorService, this.getClass());
  }

  private TaskDTO acquireTask() {
    LOG.info(Thread.currentThread().getId() + " : Starting selectAndUpdate {}",
        Thread.currentThread().getId());
    TaskDTO acquiredTask = null;
    LOG.info(Thread.currentThread().getId() + " : Trying to find a task to execute");
    do {
      List<TaskDTO> anomalyTasks = new ArrayList<>();
      try {
        boolean orderAscending = System.currentTimeMillis() % 2 == 0;
        anomalyTasks = anomalyTaskDAO
            .findByStatusOrderByCreateTime(TaskStatus.WAITING, driverConfiguration.getTaskFetchSizeCap(),
                orderAscending);
      } catch (Exception e) {
        LOG.error("Exception found in fetching new tasks, sleeping for few seconds", e);
        try {
          // TODO : Add better wait / clear call
          Thread.sleep(driverConfiguration.getTaskFailureDelayInMillis());
        } catch (InterruptedException e1) {
          LOG.error(e1.getMessage(), e1);
          return acquiredTask;
        }
      }
      if (anomalyTasks.size() > 0) {
        LOG.info(Thread.currentThread().getId() + " : Found {} tasks in waiting state",
            anomalyTasks.size());
      } else {
        // sleep for few seconds if not tasks found - avoid cpu thrashing
        // also add some extra random number of milli seconds to allow threads to start at different times
        // TODO : Add better wait / clear call
        int delay = driverConfiguration.getNoTaskDelayInMillis() + RANDOM
            .nextInt(driverConfiguration.getRandomDelayCapInMillis());
        LOG.debug("No tasks found to execute, sleeping for {} MS", delay);
        try {
          Thread.sleep(delay);
        } catch (InterruptedException e) {
          LOG.error(e.getMessage(), e);
          return acquiredTask;
        }
      }

      for (TaskDTO anomalyTaskSpec : anomalyTasks) {
        LOG.info(Thread.currentThread().getId() + " : Trying to acquire task : {}",
            anomalyTaskSpec.getId());

        boolean success = false;
        try {
          success = anomalyTaskDAO
              .updateStatusAndWorkerId(workerId, anomalyTaskSpec.getId(), allowedOldTaskStatus,
                  TaskStatus.RUNNING, anomalyTaskSpec.getVersion());
          LOG.info("Thread - [{}] : trying to acquire task id [{}], success status: [{}] with version [{}]",
              Thread.currentThread().getId(), anomalyTaskSpec.getId(), success, anomalyTaskSpec.getVersion());
        } catch (Exception e) {
          LOG.warn("exception : [{}] in acquiring task by threadId {} and workerId {}",
              e.getClass().getSimpleName(), Thread.currentThread().getId(), workerId);
        }
        if (success) {
          acquiredTask = anomalyTaskSpec;
          break;
        }
      }
    } while (acquiredTask == null);
    LOG.info(Thread.currentThread().getId() + " : Acquired task ======" + acquiredTask);
    return acquiredTask;
  }

  private void updateStatusAndTaskEndTime(long taskId, TaskStatus oldStatus, TaskStatus newStatus)
      throws Exception {
    LOG.info("{} : Starting updateStatus {}", Thread.currentThread().getId(),
        Thread.currentThread().getId());
    try {
      anomalyTaskDAO
          .updateStatusAndTaskEndTime(taskId, oldStatus, newStatus, System.currentTimeMillis());
      LOG.info("{} : updated status {}", Thread.currentThread().getId(), newStatus);
    } catch (Exception e) {
      LOG.error("Exception in updating status and task end time", e);
    }
  }
}
