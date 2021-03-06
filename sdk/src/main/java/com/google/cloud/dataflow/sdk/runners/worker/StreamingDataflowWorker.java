/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.dataflow.sdk.runners.worker;

import com.google.api.services.dataflow.model.MapTask;
import com.google.cloud.dataflow.sdk.options.DataflowWorkerHarnessOptions;
import com.google.cloud.dataflow.sdk.options.PipelineOptionsFactory;
import com.google.cloud.dataflow.sdk.runners.worker.logging.DataflowWorkerLoggingFormatter;
import com.google.cloud.dataflow.sdk.runners.worker.logging.DataflowWorkerLoggingInitializer;
import com.google.cloud.dataflow.sdk.runners.worker.windmill.Windmill;
import com.google.cloud.dataflow.sdk.runners.worker.windmill.WindmillServerStub;
import com.google.cloud.dataflow.sdk.util.BoundedQueueExecutor;
import com.google.cloud.dataflow.sdk.util.StateFetcher;
import com.google.cloud.dataflow.sdk.util.StreamingModeExecutionContext;
import com.google.cloud.dataflow.sdk.util.Transport;
import com.google.cloud.dataflow.sdk.util.UserCodeException;
import com.google.cloud.dataflow.sdk.util.common.Counter;
import com.google.cloud.dataflow.sdk.util.common.Counter.AggregationKind;
import com.google.cloud.dataflow.sdk.util.common.Counter.CounterMean;
import com.google.cloud.dataflow.sdk.util.common.CounterSet;
import com.google.cloud.dataflow.sdk.util.common.worker.MapTaskExecutor;
import com.google.cloud.dataflow.sdk.util.common.worker.ReadOperation;
import com.google.common.base.Preconditions;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.joda.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Implements a Streaming Dataflow worker.
 */
public class StreamingDataflowWorker {
  private static final Logger LOG = LoggerFactory.getLogger(StreamingDataflowWorker.class);
  static final int MAX_THREAD_POOL_SIZE = 100;
  static final long THREAD_EXPIRATION_TIME_SEC = 60;
  static final int MAX_THREAD_POOL_QUEUE_SIZE = 100;
  static final long MAX_COMMIT_BYTES = 32 << 20;
  static final int DEFAULT_STATUS_PORT = 8081;
  // Memory threshold over which no new work will be processed.
  // Set to a value >= 1 to disable pushback.
  static final double PUSHBACK_THRESHOLD_RATIO = 0.9;
  static final String DEFAULT_WINDMILL_SERVER_CLASS_NAME =
      "com.google.cloud.dataflow.sdk.runners.worker.windmill.WindmillServer";

  /**
   * Indicates that the key token was invalid when data was attempted to be fetched.
   */
  public static class KeyTokenInvalidException extends RuntimeException {
    private static final long serialVersionUID = 0;

    public KeyTokenInvalidException(String key) {
      super("Unable to fetch data due to token mismatch for key " + key);
    }
  }

  /**
   * Returns whether an exception was caused by a {@link KeyTokenInvalidException}.
   */
  private static boolean isKeyTokenInvalidException(Throwable t) {
    while (t != null) {
      if (t instanceof KeyTokenInvalidException) {
        return true;
      }
      t = t.getCause();
    }
    return false;
  }

  static MapTask parseMapTask(String input) throws IOException {
    return Transport.getJsonFactory()
        .fromString(input, MapTask.class);
  }

  public static void main(String[] args) throws Exception {
    Thread.setDefaultUncaughtExceptionHandler(
        DataflowWorkerHarness.WorkerUncaughtExceptionHandler.INSTANCE);

    DataflowWorkerLoggingInitializer.initialize();
    DataflowWorkerHarnessOptions options =
        PipelineOptionsFactory.createFromSystemPropertiesInternal();
    // TODO: Remove setting these options once we have migrated to passing
    // through the pipeline options.
    options.setAppName("StreamingWorkerHarness");
    options.setStreaming(true);

    DataflowWorkerLoggingInitializer.configure(options);
    String hostport = System.getProperty("windmill.hostport");
    if (hostport == null) {
      throw new Exception("-Dwindmill.hostport must be set to the location of the windmill server");
    }

    int statusPort = DEFAULT_STATUS_PORT;
    if (System.getProperties().containsKey("status_port")) {
      statusPort = Integer.parseInt(System.getProperty("status_port"));
    }

    String windmillServerClassName = DEFAULT_WINDMILL_SERVER_CLASS_NAME;
    if (System.getProperties().containsKey("windmill.serverclassname")) {
      windmillServerClassName = System.getProperty("windmill.serverclassname");
    }

    ArrayList<MapTask> mapTasks = new ArrayList<>();
    for (String arg : args) {
      mapTasks.add(parseMapTask(arg));
    }

    WindmillServerStub windmillServer =
        (WindmillServerStub) Class.forName(windmillServerClassName)
        .getDeclaredConstructor(String.class).newInstance(hostport);

    StreamingDataflowWorker worker =
        new StreamingDataflowWorker(mapTasks, windmillServer, options);
    worker.start();

    worker.runStatusServer(statusPort);
  }

  private ConcurrentMap<String, MapTask> instructionMap;
  private ConcurrentMap<String, ConcurrentLinkedQueue<Windmill.WorkItemCommitRequest>> outputMap;
  private ConcurrentMap<String, ConcurrentLinkedQueue<WorkerAndContext>> mapTaskExecutors;
  private ThreadFactory threadFactory;
  private BoundedQueueExecutor executor;
  private WindmillServerStub windmillServer;
  private Thread dispatchThread;
  private Thread commitThread;
  private AtomicBoolean running;
  private StateFetcher stateFetcher;
  private DataflowWorkerHarnessOptions options;
  private long clientId;
  private Server statusServer;
  private AtomicReference<Throwable> lastException;

  public StreamingDataflowWorker(
      List<MapTask> mapTasks, WindmillServerStub server, DataflowWorkerHarnessOptions options) {
    this.options = options;
    this.instructionMap = new ConcurrentHashMap<>();
    this.outputMap = new ConcurrentHashMap<>();
    this.mapTaskExecutors = new ConcurrentHashMap<>();
    for (MapTask mapTask : mapTasks) {
      addComputation(mapTask);
    }
    this.threadFactory = new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
          Thread t = new Thread(r);
          t.setDaemon(true);
          return t;
        }
      };
    this.executor = new BoundedQueueExecutor(
        MAX_THREAD_POOL_SIZE, THREAD_EXPIRATION_TIME_SEC, TimeUnit.SECONDS,
        MAX_THREAD_POOL_QUEUE_SIZE, threadFactory);
    this.windmillServer = server;
    this.running = new AtomicBoolean();
    this.stateFetcher = new StateFetcher(server);
    this.clientId = new Random().nextLong();
    this.lastException = new AtomicReference<>();

    DataflowWorkerLoggingFormatter.setJobId(options.getJobId());
    DataflowWorkerLoggingFormatter.setWorkerId(options.getWorkerId());
  }

  public void start() {
    running.set(true);
    dispatchThread = threadFactory.newThread(new Runnable() {
        @Override
        public void run() {
          dispatchLoop();
        }
      });
    dispatchThread.setPriority(Thread.MIN_PRIORITY);
    dispatchThread.setName("DispatchThread");
    dispatchThread.start();

    commitThread = threadFactory.newThread(new Runnable() {
        @Override
        public void run() {
          commitLoop();
        }
      });
    commitThread.setPriority(Thread.MAX_PRIORITY);
    commitThread.setName("CommitThread");
    commitThread.start();
  }

  public void stop() {
    try {
      if (statusServer != null) {
        statusServer.stop();
      }
      running.set(false);
      dispatchThread.join();
      executor.shutdown();
      if (!executor.awaitTermination(5, TimeUnit.MINUTES)) {
        throw new RuntimeException("Process did not terminate within 5 minutes");
      }
      for (ConcurrentLinkedQueue<WorkerAndContext> queue : mapTaskExecutors.values()) {
        WorkerAndContext workerAndContext;
        while ((workerAndContext = queue.poll()) != null) {
          workerAndContext.getWorker().close();
        }
      }
      commitThread.join();
    } catch (Exception e) {
      LOG.warn("Exception while shutting down: ", e);
    }
  }

  public void runStatusServer(int statusPort) {
    statusServer = new Server(statusPort);
    statusServer.setHandler(new StatusHandler());
    try {
      statusServer.start();
      LOG.info("Status server started on port {}", statusPort);
      statusServer.join();
    } catch (Exception e) {
      LOG.warn("Status server failed to start: ", e);
    }
  }

  private void addComputation(MapTask mapTask) {
    String computation = mapTask.getSystemName();
    if (!instructionMap.containsKey(computation)) {
      LOG.info("Adding config for {}: {}", computation, mapTask);
      outputMap.put(computation, new ConcurrentLinkedQueue<Windmill.WorkItemCommitRequest>());
      instructionMap.put(computation, mapTask);
      mapTaskExecutors.put(
          computation,
          new ConcurrentLinkedQueue<WorkerAndContext>());
    }
  }

  private static void sleep(int millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      // NOLINT
    }
  }

  private void dispatchLoop() {
    LOG.info("Dispatch starting");
    Runtime rt = Runtime.getRuntime();
    long lastPushbackLog = 0;
    while (running.get()) {

      // If free memory is less than a percentage of total memory, block
      // until current work drains and memory is released.
      // Also force a GC to try to get under the memory threshold if possible.
      long currentMemorySize = rt.totalMemory();
      long memoryUsed = currentMemorySize - rt.freeMemory();
      long maxMemory = rt.maxMemory();

      while (memoryUsed > maxMemory * PUSHBACK_THRESHOLD_RATIO) {
        if (lastPushbackLog < (lastPushbackLog = System.currentTimeMillis()) - 60 * 1000) {
          LOG.warn(
              "In pushback, not accepting new work. Using {}MB / {}MB ({}MB currently used by JVM)",
              memoryUsed >> 20, maxMemory >> 20, currentMemorySize >> 20);
          System.gc();
        }
        sleep(10);
      }

      int backoff = 1;
      Windmill.GetWorkResponse workResponse;
      do {
        workResponse = getWork();
        if (workResponse.getWorkCount() > 0) {
          break;
        }
        sleep(backoff);
        backoff = Math.min(1000, backoff * 2);
      } while (running.get());
      for (final Windmill.ComputationWorkItems computationWork : workResponse.getWorkList()) {
        final String computation = computationWork.getComputationId();
        if (!instructionMap.containsKey(computation)) {
          getConfig(computation);
        }

        long watermarkMicros = computationWork.getInputDataWatermark();
        final Instant inputDataWatermark = new Instant(watermarkMicros / 1000);

        for (final Windmill.WorkItem work : computationWork.getWorkList()) {
          executor.execute(new Runnable() {
              @Override
              public void run() {
                process(computation, inputDataWatermark, work);
              }
            });
        }
      }
    }
    LOG.info("Dispatch done");
  }

  private void process(
      final String computation,
      final Instant inputDataWatermark,
      final Windmill.WorkItem work) {
    LOG.debug("Starting processing for {}:\n{}", computation, work);

    MapTask mapTask = instructionMap.get(computation);
    if (mapTask == null) {
      LOG.info("Received work for unknown computation: {}. Known computations are {}",
          computation, instructionMap.keySet());
      return;
    }

    Windmill.WorkItemCommitRequest.Builder outputBuilder =
        Windmill.WorkItemCommitRequest.newBuilder()
        .setKey(work.getKey())
        .setWorkToken(work.getWorkToken());

    StreamingModeExecutionContext context = null;
    MapTaskExecutor worker = null;

    try {
      DataflowWorkerLoggingFormatter.setWorkId(
          work.getKey().toStringUtf8() + "-" + Long.toString(work.getWorkToken()));
      WorkerAndContext workerAndContext = mapTaskExecutors.get(computation).poll();
      if (workerAndContext == null) {
        context = new StreamingModeExecutionContext(computation, stateFetcher);
        worker = MapTaskExecutorFactory.create(options, mapTask, context);
        ReadOperation readOperation = worker.getReadOperation();
        // Disable progress updates since its results are unused for streaming
        // and involves starting a thread.
        readOperation.setProgressUpdatePeriodMs(0);
        Preconditions.checkState(
            worker.supportsRestart(), "Streaming runner requires all operations support restart.");
      } else {
        worker = workerAndContext.getWorker();
        context = workerAndContext.getContext();
      }

      context.start(work, inputDataWatermark, outputBuilder);

      // Blocks while executing work.
      worker.execute();

      buildCounters(worker.getOutputCounters(), outputBuilder);

      context.flushState();

      mapTaskExecutors.get(computation).offer(new WorkerAndContext(worker, context));
      worker = null;
      context = null;

      Windmill.WorkItemCommitRequest output = outputBuilder.build();
      outputMap.get(computation).add(output);
      LOG.debug("Processing done for work token: {}", work.getWorkToken());
    } catch (Throwable t) {
      if (worker != null) {
        try {
          worker.close();
        } catch (Exception e) {
          LOG.warn("Failed to close worker: ", e);
        }
      }

      t = t instanceof UserCodeException ? t.getCause() : t;

      if (isKeyTokenInvalidException(t)) {
        LOG.debug(
            "Execution of work for "
                + computation
                + " for key "
                + work.getKey().toStringUtf8()
                + " failed due to token expiration, will not retry locally.");
      } else {
        LOG.error(
            "Execution of work for {} for key {} failed, retrying.",
            computation,
            work.getKey().toStringUtf8());
        LOG.error("\nError: ", t);
        lastException.set(t);
        LOG.debug("Failed work: {}", work);
        if (reportFailure(computation, work, t)) {
          // Try again, after some delay and at the end of the queue to avoid a tight loop.
          sleep(10000);
          executor.forceExecute(
              new Runnable() {
                @Override
                public void run() {
                  process(computation, inputDataWatermark, work);
                }
              });
        } else {
          // If we failed to report the error, the item is invalid and should
          // not be retried internally.  It will be retried at the higher level.
          LOG.debug("Aborting processing due to exception reporting failure");
        }
      }
    } finally {
      DataflowWorkerLoggingFormatter.setWorkId(null);
    }
  }

  private void commitLoop() {
    while (running.get()) {
      Windmill.CommitWorkRequest.Builder commitRequestBuilder =
          Windmill.CommitWorkRequest.newBuilder();
      long remainingCommitBytes = MAX_COMMIT_BYTES;
      for (Map.Entry<String, ConcurrentLinkedQueue<Windmill.WorkItemCommitRequest>> entry :
               outputMap.entrySet()) {
        Windmill.ComputationCommitWorkRequest.Builder computationRequestBuilder =
            Windmill.ComputationCommitWorkRequest.newBuilder();
        ConcurrentLinkedQueue<Windmill.WorkItemCommitRequest> queue = entry.getValue();
        while (remainingCommitBytes > 0) {
          Windmill.WorkItemCommitRequest request = queue.poll();
          if (request == null) {
            break;
          }
          remainingCommitBytes -= request.getSerializedSize();
          computationRequestBuilder.addRequests(request);
        }
        if (computationRequestBuilder.getRequestsCount() > 0) {
          computationRequestBuilder.setComputationId(entry.getKey());
          commitRequestBuilder.addRequests(computationRequestBuilder);
        }
      }
      if (commitRequestBuilder.getRequestsCount() > 0) {
        Windmill.CommitWorkRequest commitRequest = commitRequestBuilder.build();
        LOG.trace("Commit: {}", commitRequest);
        commitWork(commitRequest);
      }
      if (remainingCommitBytes > 0) {
        sleep(100);
      }
    }
  }

  private Windmill.GetWorkResponse getWork() {
    return windmillServer.getWork(
        Windmill.GetWorkRequest.newBuilder()
        .setClientId(clientId)
        .setMaxItems(100)
        .build());
  }

  private void commitWork(Windmill.CommitWorkRequest request) {
    windmillServer.commitWork(request);
  }

  private void getConfig(String computation) {
    Windmill.GetConfigRequest request =
        Windmill.GetConfigRequest.newBuilder().addComputations(computation).build();
    for (String serializedMapTask : windmillServer.getConfig(request).getCloudWorksList()) {
      try {
        addComputation(parseMapTask(serializedMapTask));
      } catch (IOException e) {
        LOG.warn("Parsing MapTask failed: {}", serializedMapTask);
        LOG.warn("Error: ", e);
      }
    }
  }

  private void buildCounters(CounterSet counterSet,
                             Windmill.WorkItemCommitRequest.Builder builder) {
    for (Counter<?> counter : counterSet) {
      Windmill.Counter.Builder counterBuilder = Windmill.Counter.newBuilder();
      Windmill.Counter.Kind kind;
      Object aggregateObj = null;
      switch (counter.getKind()) {
        case SUM: kind = Windmill.Counter.Kind.SUM; break;
        case MAX: kind = Windmill.Counter.Kind.MAX; break;
        case MIN: kind = Windmill.Counter.Kind.MIN; break;
        case MEAN:
          kind = Windmill.Counter.Kind.MEAN;
          CounterMean<?> mean = counter.getAndResetMeanDelta();
          long count = mean.getCount();
          aggregateObj = mean.getAggregate();
          if (count <= 0) {
            continue;
          }
          counterBuilder.setMeanCount(count);
          break;
        default:
          LOG.debug("Unhandled counter type: {}", counter.getKind());
          continue;
      }
      if (counter.getKind() != AggregationKind.MEAN) {
        aggregateObj = counter.getAndResetDelta();
      }
      if (addKnownTypeToCounterBuilder(aggregateObj, counterBuilder)) {
        counterBuilder.setName(counter.getName()).setKind(kind);
        builder.addCounterUpdates(counterBuilder);
      }
    }
  }

  private boolean addKnownTypeToCounterBuilder(Object aggregateObj,
      Windmill.Counter.Builder counterBuilder) {
    if (aggregateObj instanceof Double) {
      double aggregate = (Double) aggregateObj;
      if (aggregate != 0) {
        counterBuilder.setDoubleScalar(aggregate);
      }
    } else if (aggregateObj instanceof Long) {
      long aggregate = (Long) aggregateObj;
      if (aggregate != 0) {
        counterBuilder.setIntScalar(aggregate);
      }
    } else if (aggregateObj instanceof Integer) {
      long aggregate = ((Integer) aggregateObj).longValue();
      if (aggregate != 0) {
        counterBuilder.setIntScalar(aggregate);
      }
    } else {
      LOG.debug("Unhandled aggregate class: {}", aggregateObj.getClass());
      return false;
    }
    return true;
  }

  private Windmill.Exception buildExceptionReport(Throwable t) {
    Windmill.Exception.Builder builder = Windmill.Exception.newBuilder();

    builder.addStackFrames(t.toString());
    for (StackTraceElement frame : t.getStackTrace()) {
      builder.addStackFrames(frame.toString());
    }
    if (t.getCause() != null) {
      builder.setCause(buildExceptionReport(t.getCause()));
    }

    return builder.build();
  }

  // Returns true if reporting the exception is successful and the work should be retried.
  private boolean reportFailure(String computation, Windmill.WorkItem work, Throwable t) {
    Windmill.ReportStatsResponse response =
        windmillServer.reportStats(Windmill.ReportStatsRequest.newBuilder()
            .setComputationId(computation)
            .setKey(work.getKey())
            .setWorkToken(work.getWorkToken())
            .addExceptions(buildExceptionReport(t))
            .build());
    return !response.getFailed();
  }

  private static class WorkerAndContext {
    public MapTaskExecutor worker;
    public StreamingModeExecutionContext context;

    public WorkerAndContext(MapTaskExecutor worker, StreamingModeExecutionContext context) {
      this.worker = worker;
      this.context = context;
    }

    public MapTaskExecutor getWorker() {
      return worker;
    }

    public StreamingModeExecutionContext getContext() {
      return context;
    }
  }

  private class StatusHandler extends AbstractHandler {
    @Override
    public void handle(
        String target, Request baseRequest,
        HttpServletRequest request, HttpServletResponse response)
        throws IOException, ServletException {

      response.setContentType("text/html;charset=utf-8");
      response.setStatus(HttpServletResponse.SC_OK);
      baseRequest.setHandled(true);

      PrintWriter responseWriter = response.getWriter();

      responseWriter.println("<html><body>");

      if (target.equals("/healthz")) {
        responseWriter.println("ok");
      } else if (target.equals("/threadz")) {
        printThreads(responseWriter);
      } else {
        printHeader(responseWriter);
        printMetrics(responseWriter);
        printResources(responseWriter);
        printLastException(responseWriter);
        printSpecs(responseWriter);
      }

      responseWriter.println("</body></html>");
    }
  }

  private void printHeader(PrintWriter response) {
    response.println("<h1>Streaming Worker Harness</h1>");
    response.println("Running: " + running.get() + "<br>");
    response.println("ID: " + clientId + "<br>");
  }

  private void printMetrics(PrintWriter response) {
    response.println("<h2>Metrics</h2>");
    response.println("Worker Threads: " + executor.getPoolSize()
        + "/" + MAX_THREAD_POOL_QUEUE_SIZE + "<br>");
    response.println("Active Threads: " + executor.getActiveCount() + "<br>");
    response.println("Work Queue Size: " + executor.getQueue().size() + "<br>");
    response.println("Commit Queues: <ul>");
    for (Map.Entry<String, ConcurrentLinkedQueue<Windmill.WorkItemCommitRequest>> entry
             : outputMap.entrySet()) {
      response.print("<li>");
      response.print(entry.getKey());
      response.print(": ");
      response.print(entry.getValue().size());
      response.println("</li>");
    }
    response.println("</ul>");
  }

  private void printResources(PrintWriter response) {
    Runtime rt = Runtime.getRuntime();
    response.append("<h2>Resources</h2>\n");
    response.append("Total Memory: " + (rt.totalMemory() >> 20) + "MB<br>\n");
    response.append("Used Memory: " + ((rt.totalMemory() - rt.freeMemory()) >> 20) + "MB<br>\n");
    response.append("Max Memory: " + (rt.maxMemory() >> 20) + "MB<br>\n");
  }

  private void printSpecs(PrintWriter response) {
    response.append("<h2>Specs</h2>\n");
    for (Map.Entry<String, MapTask> entry : instructionMap.entrySet()) {
      response.println("<h3>" + entry.getKey() + "</h3>");
      response.print("<script>document.write(JSON.stringify(");
      response.print(entry.getValue().toString());
      response.println(", null, \"&nbsp&nbsp\").replace(/\\n/g, \"<br>\"))</script>");
    }
  }

  private void printLastException(PrintWriter response) {
    Throwable t = lastException.get();
    if (t != null) {
      response.println("<h2>Last Exception</h2>");
      StringWriter writer = new StringWriter();
      t.printStackTrace(new PrintWriter(writer));
      response.println(writer.toString().replace("\t", "&nbsp&nbsp").replace("\n", "<br>"));
    }
  }

  private void printThreads(PrintWriter response) {
    Map<Thread, StackTraceElement[]> stacks = Thread.getAllStackTraces();
    for (Map.Entry<Thread,  StackTraceElement[]> entry : stacks.entrySet()) {
      Thread thread = entry.getKey();
      response.println("Thread: " + thread + " State: " + thread.getState() + "<br>");
      for (StackTraceElement element : entry.getValue()) {
        response.println("&nbsp&nbsp" + element + "<br>");
      }
      response.println("<br>");
    }
  }
}
