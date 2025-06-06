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
package org.apache.hadoop.hbase.quotas;

import org.apache.yetus.audience.InterfaceAudience;
import org.apache.yetus.audience.InterfaceStability;

/**
 * Internal interface used to interact with the user/table quota.
 */
@InterfaceAudience.Private
@InterfaceStability.Evolving
public interface QuotaLimiter {
  /**
   * Checks if it is possible to execute the specified operation.
   * @param writeReqs                    the write requests that will be checked against the
   *                                     available quota
   * @param estimateWriteSize            the write size that will be checked against the available
   *                                     quota
   * @param readReqs                     the read requests that will be checked against the
   *                                     available quota
   * @param estimateReadSize             the read size that will be checked against the available
   *                                     quota
   * @param estimateWriteCapacityUnit    the write capacity unit that will be checked against the
   *                                     available quota
   * @param estimateReadCapacityUnit     the read capacity unit that will be checked against the
   *                                     available quota
   * @param isAtomic                     if the request performs an atomic operation
   * @param estimateHandlerThreadUsageMs the estimated handler usage time in ms that will be checked
   *                                     against the available quota
   * @throws RpcThrottlingException thrown if not enough available resources to perform operation.
   */
  void checkQuota(long writeReqs, long estimateWriteSize, long readReqs, long estimateReadSize,
    long estimateWriteCapacityUnit, long estimateReadCapacityUnit, boolean isAtomic,
    long estimateHandlerThreadUsageMs) throws RpcThrottlingException;

  /**
   * Removes the specified write and read amount from the quota. At this point the write and read
   * amount will be an estimate, that will be later adjusted with a consumeWrite()/consumeRead()
   * call.
   * @param writeReqs                    the write requests that will be removed from the current
   *                                     quota
   * @param writeSize                    the write size that will be removed from the current quota
   * @param readReqs                     the read requests that will be removed from the current
   *                                     quota
   * @param readSize                     the read size that will be removed from the current quota
   * @param writeCapacityUnit            the write capacity unit that will be removed from the
   *                                     current quota
   * @param readCapacityUnit             the read capacity unit num that will be removed from the
   *                                     current quota
   * @param isAtomic                     if the request performs an atomic operation
   * @param estimateHandlerThreadUsageMs the estimated handler usage time in ms that will be removed
   *                                     from the available quota
   */
  void grabQuota(long writeReqs, long writeSize, long readReqs, long readSize,
    long writeCapacityUnit, long readCapacityUnit, boolean isAtomic,
    long estimateHandlerThreadUsageMs);

  /**
   * Removes or add back some write amount to the quota. (called at the end of an operation in case
   * the estimate quota was off)
   */
  void consumeWrite(long size, long capacityUnit, boolean isAtomic);

  /**
   * Removes or add back some read amount to the quota. (called at the end of an operation in case
   * the estimate quota was off)
   */
  void consumeRead(long size, long capacityUnit, boolean isAtomic);

  /**
   * Removes or add back some handler thread usage milliseconds to the quota. (called at the end of
   * an operation in case the estimate quota was off)
   * @param handlerMillisUsed the actual elapsed time used processing the request
   */
  void consumeTime(long handlerMillisUsed);

  /** Returns true if the limiter is a noop */
  boolean isBypass();

  /** Returns the number of bytes available to read to avoid exceeding the quota */
  long getReadAvailable();

  /** Returns the maximum number of bytes ever available to read */
  long getReadLimit();

  /** Returns the maximum number of bytes ever available to write */
  long getWriteLimit();

  /** Returns the number of bytes available to write to avoid exceeding the quota */
  long getWriteAvailable();

  /** Returns the maximum number of requests to allow per TimeUnit */
  long getRequestNumLimit();

  /** Returns the maximum number of reads to allow per TimeUnit */
  long getReadNumLimit();

  /** Returns the maximum number of writes to allow per TimeUnit */
  long getWriteNumLimit();

}
