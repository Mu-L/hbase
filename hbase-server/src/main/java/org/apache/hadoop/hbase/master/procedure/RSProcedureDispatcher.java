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
package org.apache.hadoop.hbase.master.procedure;

import java.io.IOException;
import java.lang.Thread.UncaughtExceptionHandler;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.security.sasl.SaslException;
import org.apache.hadoop.hbase.CallQueueTooBigException;
import org.apache.hadoop.hbase.DoNotRetryIOException;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.client.AsyncRegionServerAdmin;
import org.apache.hadoop.hbase.client.RegionInfo;
import org.apache.hadoop.hbase.exceptions.ConnectionClosedException;
import org.apache.hadoop.hbase.ipc.RpcConnectionConstants;
import org.apache.hadoop.hbase.ipc.ServerNotRunningYetException;
import org.apache.hadoop.hbase.master.MasterServices;
import org.apache.hadoop.hbase.master.ServerListener;
import org.apache.hadoop.hbase.master.ServerManager;
import org.apache.hadoop.hbase.procedure2.ProcedureExecutor;
import org.apache.hadoop.hbase.procedure2.RemoteProcedureDispatcher;
import org.apache.hadoop.hbase.regionserver.RegionServerStoppedException;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.hadoop.hbase.util.FutureUtils;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hbase.thirdparty.com.google.common.collect.ArrayListMultimap;
import org.apache.hbase.thirdparty.com.google.protobuf.ByteString;

import org.apache.hadoop.hbase.shaded.protobuf.ProtobufUtil;
import org.apache.hadoop.hbase.shaded.protobuf.RequestConverter;
import org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.CloseRegionRequest;
import org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.ExecuteProceduresRequest;
import org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.ExecuteProceduresResponse;
import org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.OpenRegionRequest;
import org.apache.hadoop.hbase.shaded.protobuf.generated.AdminProtos.RemoteProcedureRequest;

/**
 * A remote procecdure dispatcher for regionservers.
 */
@InterfaceAudience.Private
public class RSProcedureDispatcher extends RemoteProcedureDispatcher<MasterProcedureEnv, ServerName>
  implements ServerListener {
  private static final Logger LOG = LoggerFactory.getLogger(RSProcedureDispatcher.class);

  public static final String RS_RPC_STARTUP_WAIT_TIME_CONF_KEY =
    "hbase.regionserver.rpc.startup.waittime";
  private static final int DEFAULT_RS_RPC_STARTUP_WAIT_TIME = 60000;

  protected final MasterServices master;
  private final long rsStartupWaitTime;
  private MasterProcedureEnv procedureEnv;

  public RSProcedureDispatcher(final MasterServices master) {
    super(master.getConfiguration());

    this.master = master;
    this.rsStartupWaitTime = master.getConfiguration().getLong(RS_RPC_STARTUP_WAIT_TIME_CONF_KEY,
      DEFAULT_RS_RPC_STARTUP_WAIT_TIME);
  }

  @Override
  protected UncaughtExceptionHandler getUncaughtExceptionHandler() {
    return new UncaughtExceptionHandler() {

      @Override
      public void uncaughtException(Thread t, Throwable e) {
        LOG.error("Unexpected error caught, this may cause the procedure to hang forever", e);
      }
    };
  }

  @Override
  public boolean start() {
    if (!super.start()) {
      return false;
    }
    setTimeoutExecutorUncaughtExceptionHandler(this::abort);
    if (master.isStopped()) {
      LOG.debug("Stopped");
      return false;
    }
    // Around startup, if failed, some of the below may be set back to null so NPE is possible.
    ServerManager sm = master.getServerManager();
    if (sm == null) {
      LOG.debug("ServerManager is null");
      return false;
    }
    sm.registerListener(this);
    ProcedureExecutor<MasterProcedureEnv> pe = master.getMasterProcedureExecutor();
    if (pe == null) {
      LOG.debug("ProcedureExecutor is null");
      return false;
    }
    this.procedureEnv = pe.getEnvironment();
    if (this.procedureEnv == null) {
      LOG.debug("ProcedureEnv is null; stopping={}", master.isStopping());
      return false;
    }
    try {
      for (ServerName serverName : sm.getOnlineServersList()) {
        addNode(serverName);
      }
    } catch (Exception e) {
      LOG.info("Failed start", e);
      return false;
    }
    return true;
  }

  private void abort(Thread t, Throwable e) {
    LOG.error("Caught error", e);
    if (!master.isStopped() && !master.isStopping() && !master.isAborted()) {
      master.abort("Aborting master", e);
    }
  }

  @Override
  public boolean stop() {
    if (!super.stop()) {
      return false;
    }

    master.getServerManager().unregisterListener(this);
    return true;
  }

  @Override
  protected void remoteDispatch(final ServerName serverName,
    final Set<RemoteProcedure> remoteProcedures) {
    if (!master.getServerManager().isServerOnline(serverName)) {
      // fail fast
      submitTask(new DeadRSRemoteCall(serverName, remoteProcedures));
    } else {
      submitTask(new ExecuteProceduresRemoteCall(serverName, remoteProcedures));
    }
  }

  @Override
  protected void abortPendingOperations(final ServerName serverName,
    final Set<RemoteProcedure> operations) {
    // TODO: Replace with a ServerNotOnlineException()
    final IOException e = new DoNotRetryIOException("server not online " + serverName);
    for (RemoteProcedure proc : operations) {
      proc.remoteCallFailed(procedureEnv, serverName, e);
    }
  }

  @Override
  public void serverAdded(final ServerName serverName) {
    addNode(serverName);
  }

  @Override
  public void serverRemoved(final ServerName serverName) {
    removeNode(serverName);
  }

  private interface RemoteProcedureResolver {
    void dispatchOpenRequests(MasterProcedureEnv env, List<RegionOpenOperation> operations);

    void dispatchCloseRequests(MasterProcedureEnv env, List<RegionCloseOperation> operations);

    void dispatchServerOperations(MasterProcedureEnv env, List<ServerOperation> operations);
  }

  /**
   * Fetches {@link org.apache.hadoop.hbase.procedure2.RemoteProcedureDispatcher.RemoteOperation}s
   * from the given {@code remoteProcedures} and groups them by class of the returned operation.
   * Then {@code resolver} is used to dispatch {@link RegionOpenOperation}s and
   * {@link RegionCloseOperation}s.
   * @param serverName RegionServer to which the remote operations are sent
   * @param operations Remote procedures which are dispatched to the given server
   * @param resolver   Used to dispatch remote procedures to given server.
   */
  public void splitAndResolveOperation(ServerName serverName, Set<RemoteProcedure> operations,
    RemoteProcedureResolver resolver) {
    MasterProcedureEnv env = master.getMasterProcedureExecutor().getEnvironment();
    ArrayListMultimap<Class<?>, RemoteOperation> reqsByType =
      buildAndGroupRequestByType(env, serverName, operations);

    List<RegionOpenOperation> openOps = fetchType(reqsByType, RegionOpenOperation.class);
    if (!openOps.isEmpty()) {
      resolver.dispatchOpenRequests(env, openOps);
    }

    List<RegionCloseOperation> closeOps = fetchType(reqsByType, RegionCloseOperation.class);
    if (!closeOps.isEmpty()) {
      resolver.dispatchCloseRequests(env, closeOps);
    }

    List<ServerOperation> refreshOps = fetchType(reqsByType, ServerOperation.class);
    if (!refreshOps.isEmpty()) {
      resolver.dispatchServerOperations(env, refreshOps);
    }

    if (!reqsByType.isEmpty()) {
      LOG.warn("unknown request type in the queue: " + reqsByType);
    }
  }

  private class DeadRSRemoteCall extends ExecuteProceduresRemoteCall {

    public DeadRSRemoteCall(ServerName serverName, Set<RemoteProcedure> remoteProcedures) {
      super(serverName, remoteProcedures);
    }

    @Override
    public void run() {
      remoteCallFailed(procedureEnv,
        new RegionServerStoppedException("Server " + getServerName() + " is not online"));
    }
  }

  // ==========================================================================
  // Compatibility calls
  // ==========================================================================
  protected class ExecuteProceduresRemoteCall implements RemoteProcedureResolver, Runnable {

    private final ServerName serverName;

    private final Set<RemoteProcedure> remoteProcedures;

    private int numberOfAttemptsSoFar = 0;
    private long maxWaitTime = -1;

    private final long rsRpcRetryInterval;
    private static final String RS_RPC_RETRY_INTERVAL_CONF_KEY =
      "hbase.regionserver.rpc.retry.interval";
    private static final int DEFAULT_RS_RPC_RETRY_INTERVAL = 100;

    /**
     * Config to determine the retry limit while executing remote regionserver procedure. This retry
     * limit applies to only specific errors. These errors could potentially get the remote
     * procedure stuck for several minutes unless the retry limit is applied.
     */
    private static final String RS_REMOTE_PROC_FAIL_FAST_LIMIT =
      "hbase.master.rs.remote.proc.fail.fast.limit";
    /**
     * The default retry limit. Waiting for more than {@value} attempts is not going to help much
     * for genuine connectivity errors. Therefore, consider fail-fast after {@value} retries. Value
     * = {@value}
     */
    private static final int DEFAULT_RS_REMOTE_PROC_RETRY_LIMIT = 5;

    private final int failFastRetryLimit;

    private ExecuteProceduresRequest.Builder request = null;

    public ExecuteProceduresRemoteCall(final ServerName serverName,
      final Set<RemoteProcedure> remoteProcedures) {
      this.serverName = serverName;
      this.remoteProcedures = remoteProcedures;
      this.rsRpcRetryInterval = master.getConfiguration().getLong(RS_RPC_RETRY_INTERVAL_CONF_KEY,
        DEFAULT_RS_RPC_RETRY_INTERVAL);
      this.failFastRetryLimit = master.getConfiguration().getInt(RS_REMOTE_PROC_FAIL_FAST_LIMIT,
        DEFAULT_RS_REMOTE_PROC_RETRY_LIMIT);
    }

    private AsyncRegionServerAdmin getRsAdmin() throws IOException {
      return master.getAsyncClusterConnection().getRegionServerAdmin(serverName);
    }

    protected final ServerName getServerName() {
      return serverName;
    }

    private boolean scheduleForRetry(IOException e) {
      LOG.debug("Request to {} failed, try={}", serverName, numberOfAttemptsSoFar, e);
      // Should we wait a little before retrying? If the server is starting it's yes.
      if (e instanceof ServerNotRunningYetException) {
        long remainingTime = getMaxWaitTime() - EnvironmentEdgeManager.currentTime();
        if (remainingTime > 0) {
          LOG.warn("Waiting a little before retrying {}, try={}, can wait up to {}ms", serverName,
            numberOfAttemptsSoFar, remainingTime);
          numberOfAttemptsSoFar++;
          // Retry every rsRpcRetryInterval millis up to maximum wait time.
          submitTask(this, rsRpcRetryInterval, TimeUnit.MILLISECONDS);
          return true;
        }
        LOG.warn("{} is throwing ServerNotRunningYetException for {}ms; trying another server",
          serverName, getMaxWaitTime());
        return false;
      }
      if (e instanceof DoNotRetryIOException) {
        LOG.warn("{} tells us DoNotRetry due to {}, try={}, give up", serverName, e.toString(),
          numberOfAttemptsSoFar);
        return false;
      }
      // This category of exceptions is thrown in the rpc framework, where we can make sure
      // that the call has not been executed yet, so it is safe to mark it as fail.
      // Especially for open a region, we'd better choose another region server.
      // Notice that, it is safe to quit only if this is the first time we send request to region
      // server. Maybe the region server has accepted our request the first time, and then there is
      // a network error which prevents we receive the response, and the second time we hit
      // this category of exceptions, obviously it is not safe to quit here, otherwise it may lead
      // to a double assign...
      if (numberOfAttemptsSoFar == 0 && unableToConnectToServer(e)) {
        return false;
      }

      // Check if the num of attempts have crossed the retry limit, and if the error type can
      // fail-fast.
      if (numberOfAttemptsSoFar >= failFastRetryLimit - 1 && isErrorTypeFailFast(e)) {
        LOG
          .warn("Number of retries {} exceeded limit {} for the given error type. Scheduling server"
            + " crash for {}", numberOfAttemptsSoFar + 1, failFastRetryLimit, serverName, e);
        // Expiring the server will schedule SCP and also reject the regionserver report from the
        // regionserver if regionserver is somehow able to send the regionserver report to master.
        // The master rejects the report by throwing YouAreDeadException, which would eventually
        // result in the regionserver abort.
        // This will also remove "serverName" from the ServerManager's onlineServers map.
        master.getServerManager().expireServer(serverName);
        return false;
      }
      // Always retry for other exception types if the region server is not dead yet.
      if (!master.getServerManager().isServerOnline(serverName)) {
        LOG.warn("Request to {} failed due to {}, try={} and the server is not online, give up",
          serverName, e.toString(), numberOfAttemptsSoFar);
        return false;
      }
      if (e instanceof RegionServerStoppedException) {
        // A better way is to return true here to let the upper layer quit, and then schedule a
        // background task to check whether the region server is dead. And if it is dead, call
        // remoteCallFailed to tell the upper layer. Keep retrying here does not lead to incorrect
        // result, but waste some resources.
        LOG.warn("{} is aborted or stopped, for safety we still need to"
          + " wait until it is fully dead, try={}", serverName, numberOfAttemptsSoFar);
      } else {
        LOG.warn("request to {} failed due to {}, try={}, retrying... , request params: {}",
          serverName, e.toString(), numberOfAttemptsSoFar, request.build());
      }
      numberOfAttemptsSoFar++;
      // Add some backoff here as the attempts rise otherwise if a stuck condition, will fill logs
      // with failed attempts. None of our backoff classes -- RetryCounter or ClientBackoffPolicy
      // -- fit here nicely so just do something simple; increment by rsRpcRetryInterval millis *
      // retry^2 on each try
      // up to max of 10 seconds (don't want to back off too much in case of situation change).
      submitTask(this,
        Math.min(
          rsRpcRetryInterval * ((long) this.numberOfAttemptsSoFar * this.numberOfAttemptsSoFar),
          10 * 1000),
        TimeUnit.MILLISECONDS);
      return true;
    }

    /**
     * The category of exceptions where we can ensure that the request has not yet been received
     * and/or processed by the target regionserver yet and hence we can determine whether it is safe
     * to choose different regionserver as the target.
     * @param e IOException thrown by the underlying rpc framework.
     * @return true if the exception belongs to the category where the regionserver has not yet
     *         received the request yet.
     */
    private boolean unableToConnectToServer(IOException e) {
      if (e instanceof CallQueueTooBigException) {
        LOG.warn("request to {} failed due to {}, try={}, this usually because"
          + " server is overloaded, give up", serverName, e, numberOfAttemptsSoFar);
        return true;
      }
      if (isSaslError(e)) {
        LOG.warn("{} is not reachable; give up after first attempt", serverName, e);
        return true;
      }
      return false;
    }

    private boolean isSaslError(IOException e) {
      Throwable cause = e;
      while (true) {
        if (cause instanceof IOException) {
          IOException unwrappedCause = unwrapException((IOException) cause);
          if (
            unwrappedCause instanceof SaslException
              || (unwrappedCause.getMessage() != null && unwrappedCause.getMessage()
                .contains(RpcConnectionConstants.RELOGIN_IS_IN_PROGRESS))
          ) {
            return true;
          }
        }
        cause = cause.getCause();
        if (cause == null) {
          return false;
        }
      }
    }

    /**
     * Returns true if the error or its cause indicates a network connection issue.
     * @param e IOException thrown by the underlying rpc framework.
     * @return True if the error or its cause indicates a network connection issue.
     */
    private boolean isNetworkError(IOException e) {
      if (
        e instanceof ConnectionClosedException || e instanceof UnknownHostException
          || e instanceof ConnectException
      ) {
        return true;
      }
      Throwable cause = e;
      while (true) {
        if (cause instanceof IOException) {
          IOException unwrappedCause = unwrapException((IOException) cause);
          if (
            unwrappedCause instanceof ConnectionClosedException
              || unwrappedCause instanceof UnknownHostException
              || unwrappedCause instanceof ConnectException
          ) {
            return true;
          }
        }
        cause = cause.getCause();
        if (cause == null) {
          return false;
        }
      }
    }

    /**
     * Returns true if the error type can allow fail-fast.
     * @param e IOException thrown by the underlying rpc framework.
     * @return True if the error type can allow fail-fast.
     */
    private boolean isErrorTypeFailFast(IOException e) {
      return e instanceof CallQueueTooBigException || isSaslError(e) || isNetworkError(e);
    }

    private long getMaxWaitTime() {
      if (this.maxWaitTime < 0) {
        // This is the max attempts, not retries, so it should be at least 1.
        this.maxWaitTime = EnvironmentEdgeManager.currentTime() + rsStartupWaitTime;
      }
      return this.maxWaitTime;
    }

    private IOException unwrapException(IOException e) {
      if (e instanceof RemoteException) {
        e = ((RemoteException) e).unwrapRemoteException();
      }
      return e;
    }

    @Override
    public void run() {
      request = ExecuteProceduresRequest.newBuilder();
      if (LOG.isTraceEnabled()) {
        LOG.trace("Building request with operations count=" + remoteProcedures.size());
      }
      splitAndResolveOperation(getServerName(), remoteProcedures, this);

      try {
        sendRequest(getServerName(), request.build());
      } catch (IOException e) {
        e = unwrapException(e);
        // TODO: In the future some operation may want to bail out early.
        // TODO: How many times should we retry (use numberOfAttemptsSoFar)
        if (!scheduleForRetry(e)) {
          remoteCallFailed(procedureEnv, e);
        }
      }
    }

    @Override
    public void dispatchOpenRequests(final MasterProcedureEnv env,
      final List<RegionOpenOperation> operations) {
      request.addOpenRegion(buildOpenRegionRequest(env, getServerName(), operations));
    }

    @Override
    public void dispatchCloseRequests(final MasterProcedureEnv env,
      final List<RegionCloseOperation> operations) {
      for (RegionCloseOperation op : operations) {
        request.addCloseRegion(op.buildCloseRegionRequest(getServerName()));
      }
    }

    @Override
    public void dispatchServerOperations(MasterProcedureEnv env, List<ServerOperation> operations) {
      operations.stream().map(ServerOperation::buildRequest).forEachOrdered(request::addProc);
    }

    // will be overridden in test.
    protected ExecuteProceduresResponse sendRequest(final ServerName serverName,
      final ExecuteProceduresRequest request) throws IOException {
      return FutureUtils.get(getRsAdmin().executeProcedures(request));
    }

    protected final void remoteCallFailed(final MasterProcedureEnv env, final IOException e) {
      for (RemoteProcedure proc : remoteProcedures) {
        proc.remoteCallFailed(env, getServerName(), e);
      }
    }
  }

  private static OpenRegionRequest buildOpenRegionRequest(final MasterProcedureEnv env,
    final ServerName serverName, final List<RegionOpenOperation> operations) {
    final OpenRegionRequest.Builder builder = OpenRegionRequest.newBuilder();
    builder.setServerStartCode(serverName.getStartCode());
    operations.stream().map(RemoteOperation::getInitiatingMasterActiveTime).findAny()
      .ifPresent(builder::setInitiatingMasterActiveTime);
    builder.setMasterSystemTime(EnvironmentEdgeManager.currentTime());
    for (RegionOpenOperation op : operations) {
      builder.addOpenInfo(op.buildRegionOpenInfoRequest(env));
    }
    return builder.build();
  }

  // ==========================================================================
  // RPC Messages
  // - ServerOperation: refreshConfig, grant, revoke, ... (TODO)
  // - RegionOperation: open, close, flush, snapshot, ...
  // ==========================================================================

  public static final class ServerOperation extends RemoteOperation {

    private final long procId;

    private final Class<?> rsProcClass;

    private final byte[] rsProcData;

    public ServerOperation(RemoteProcedure remoteProcedure, long procId, Class<?> rsProcClass,
      byte[] rsProcData, long initiatingMasterActiveTime) {
      super(remoteProcedure, initiatingMasterActiveTime);
      this.procId = procId;
      this.rsProcClass = rsProcClass;
      this.rsProcData = rsProcData;
    }

    public RemoteProcedureRequest buildRequest() {
      return RemoteProcedureRequest.newBuilder().setProcId(procId)
        .setProcClass(rsProcClass.getName()).setProcData(ByteString.copyFrom(rsProcData))
        .setInitiatingMasterActiveTime(getInitiatingMasterActiveTime()).build();
    }
  }

  public static abstract class RegionOperation extends RemoteOperation {
    protected final RegionInfo regionInfo;
    protected final long procId;

    protected RegionOperation(RemoteProcedure remoteProcedure, RegionInfo regionInfo, long procId,
      long initiatingMasterActiveTime) {
      super(remoteProcedure, initiatingMasterActiveTime);
      this.regionInfo = regionInfo;
      this.procId = procId;
    }
  }

  public static class RegionOpenOperation extends RegionOperation {

    public RegionOpenOperation(RemoteProcedure remoteProcedure, RegionInfo regionInfo, long procId,
      long initiatingMasterActiveTime) {
      super(remoteProcedure, regionInfo, procId, initiatingMasterActiveTime);
    }

    public OpenRegionRequest.RegionOpenInfo
      buildRegionOpenInfoRequest(final MasterProcedureEnv env) {
      return RequestConverter.buildRegionOpenInfo(regionInfo,
        env.getAssignmentManager().getFavoredNodes(regionInfo), procId);
    }
  }

  public static class RegionCloseOperation extends RegionOperation {
    private final ServerName destinationServer;
    private boolean evictCache;

    public RegionCloseOperation(RemoteProcedure remoteProcedure, RegionInfo regionInfo, long procId,
      ServerName destinationServer, boolean evictCache, long initiatingMasterActiveTime) {
      super(remoteProcedure, regionInfo, procId, initiatingMasterActiveTime);
      this.destinationServer = destinationServer;
      this.evictCache = evictCache;
    }

    public ServerName getDestinationServer() {
      return destinationServer;
    }

    public CloseRegionRequest buildCloseRegionRequest(final ServerName serverName) {
      return ProtobufUtil.buildCloseRegionRequest(serverName, regionInfo.getRegionName(),
        getDestinationServer(), procId, evictCache, getInitiatingMasterActiveTime());
    }
  }
}
