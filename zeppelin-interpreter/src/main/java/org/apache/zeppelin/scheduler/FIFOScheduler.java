/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zeppelin.scheduler;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.zeppelin.scheduler.Job.Status;

/**
 * TODO(moon) : add description.
 *
 * @author Leemoonsoo
 *
 */
public class FIFOScheduler implements Scheduler {
  Logger logger = LoggerFactory.getLogger(FIFOScheduler.class);
  List<Job> queue = new LinkedList<Job>();
  private ExecutorService executor;
  private SchedulerListener listener;
  boolean terminate = false;
  Job runningJob = null;
  private String name;

  public FIFOScheduler(String name, ExecutorService executor, SchedulerListener listener) {
    this.name = name;
    this.executor = executor;
    this.listener = listener;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public Collection<Job> getJobsWaiting() {
    List<Job> ret = new LinkedList<Job>();
    synchronized (queue) {
      for (Job job : queue) {
        ret.add(job);
      }
    }
    return ret;
  }

  @Override
  public Collection<Job> getJobsRunning() {
    List<Job> ret = new LinkedList<Job>();
    Job job = runningJob;

    if (job != null) {
      ret.add(job);
    }

    return ret;
  }



  @Override
  public void submit(Job job) {
    synchronized (queue) {
      if (job.findSimilarJob(queue) != null || job.isSimilarJob(runningJob)) {
        logger.info("Trying to submit job that already exists in queue: " + job.getId());
      } else {
        job.setStatus(Status.PENDING);
        queue.add(job);
        logger.info("Success to submit job : " + job.getId());
      }            
      queue.notify();
    }
  }

  @Override
  public void run() {

    synchronized (queue) {
      while (terminate == false) {
        if (runningJob != null || queue.isEmpty() == true) {
          try {
            queue.wait(500);
          } catch (InterruptedException e) {
          }
          continue;
        }

        runningJob = queue.remove(0);

        final Scheduler scheduler = this;
        this.executor.execute(new Runnable() {
          @Override
          public void run() {
            if (runningJob.isAborted()) {
              runningJob.setStatus(Status.ABORT);
              runningJob.aborted = false;
              synchronized (queue) {
                queue.notify();
              }
              return;
            }

            runningJob.setStatus(Status.RUNNING);
            if (listener != null) {
              listener.jobStarted(scheduler, runningJob);
            }
            runningJob.run();
            if (runningJob.isAborted()) {
              runningJob.setStatus(Status.ABORT);
            } else {
              if (runningJob.getException() != null) {
                runningJob.setStatus(Status.ERROR);
              } else {
                runningJob.setStatus(Status.FINISHED);
              }
            }
            if (listener != null) {
              listener.jobFinished(scheduler, runningJob);
            }
            // reset aborted flag to allow retry
            runningJob.aborted = false;
            runningJob = null;
            synchronized (queue) {
              queue.notify();
            }
          }
        });
      }
    }
  }

  @Override
  public void stop() {
    terminate = true;
    synchronized (queue) {
      queue.notify();
    }
  }

}
