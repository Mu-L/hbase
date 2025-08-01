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
package org.apache.hadoop.hbase.snapshot;

import java.io.BufferedInputStream;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileChecksum;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.RegionInfo;
import org.apache.hadoop.hbase.io.FileLink;
import org.apache.hadoop.hbase.io.HFileLink;
import org.apache.hadoop.hbase.io.WALLink;
import org.apache.hadoop.hbase.io.hadoopbackport.ThrottledInputStream;
import org.apache.hadoop.hbase.mapreduce.TableMapReduceUtil;
import org.apache.hadoop.hbase.mob.MobUtils;
import org.apache.hadoop.hbase.regionserver.StoreFileInfo;
import org.apache.hadoop.hbase.util.AbstractHBaseTool;
import org.apache.hadoop.hbase.util.CommonFSUtils;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.hadoop.hbase.util.FSUtils;
import org.apache.hadoop.hbase.util.HFileArchiveUtil;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.hbase.util.Strings;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.output.NullOutputFormat;
import org.apache.hadoop.mapreduce.security.TokenCache;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.util.Tool;
import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hbase.thirdparty.com.google.common.collect.ImmutableList;
import org.apache.hbase.thirdparty.com.google.common.collect.ImmutableSet;
import org.apache.hbase.thirdparty.org.apache.commons.cli.CommandLine;
import org.apache.hbase.thirdparty.org.apache.commons.cli.Option;

import org.apache.hadoop.hbase.shaded.protobuf.ProtobufUtil;
import org.apache.hadoop.hbase.shaded.protobuf.generated.SnapshotProtos.SnapshotDescription;
import org.apache.hadoop.hbase.shaded.protobuf.generated.SnapshotProtos.SnapshotFileInfo;
import org.apache.hadoop.hbase.shaded.protobuf.generated.SnapshotProtos.SnapshotRegionManifest;

/**
 * Export the specified snapshot to a given FileSystem. The .snapshot/name folder is copied to the
 * destination cluster and then all the hfiles/wals are copied using a Map-Reduce Job in the
 * .archive/ location. When everything is done, the second cluster can restore the snapshot.
 */
@InterfaceAudience.Public
public class ExportSnapshot extends AbstractHBaseTool implements Tool {
  public static final String NAME = "exportsnapshot";
  /** Configuration prefix for overrides for the source filesystem */
  public static final String CONF_SOURCE_PREFIX = NAME + ".from.";
  /** Configuration prefix for overrides for the destination filesystem */
  public static final String CONF_DEST_PREFIX = NAME + ".to.";

  private static final Logger LOG = LoggerFactory.getLogger(ExportSnapshot.class);

  private static final String MR_NUM_MAPS = "mapreduce.job.maps";
  private static final String CONF_NUM_SPLITS = "snapshot.export.format.splits";
  private static final String CONF_SNAPSHOT_NAME = "snapshot.export.format.snapshot.name";
  private static final String CONF_SNAPSHOT_DIR = "snapshot.export.format.snapshot.dir";
  private static final String CONF_FILES_USER = "snapshot.export.files.attributes.user";
  private static final String CONF_FILES_GROUP = "snapshot.export.files.attributes.group";
  private static final String CONF_FILES_MODE = "snapshot.export.files.attributes.mode";
  private static final String CONF_CHECKSUM_VERIFY = "snapshot.export.checksum.verify";
  private static final String CONF_OUTPUT_ROOT = "snapshot.export.output.root";
  private static final String CONF_INPUT_ROOT = "snapshot.export.input.root";
  private static final String CONF_BUFFER_SIZE = "snapshot.export.buffer.size";
  private static final String CONF_REPORT_SIZE = "snapshot.export.report.size";
  private static final String CONF_MAP_GROUP = "snapshot.export.default.map.group";
  private static final String CONF_BANDWIDTH_MB = "snapshot.export.map.bandwidth.mb";
  private static final String CONF_MR_JOB_NAME = "mapreduce.job.name";
  private static final String CONF_INPUT_FILE_GROUPER_CLASS =
    "snapshot.export.input.file.grouper.class";
  private static final String CONF_INPUT_FILE_LOCATION_RESOLVER_CLASS =
    "snapshot.export.input.file.location.resolver.class";
  protected static final String CONF_SKIP_TMP = "snapshot.export.skip.tmp";
  private static final String CONF_COPY_MANIFEST_THREADS =
    "snapshot.export.copy.references.threads";
  private static final int DEFAULT_COPY_MANIFEST_THREADS =
    Runtime.getRuntime().availableProcessors();
  private static final String CONF_STORAGE_POLICY = "snapshot.export.storage.policy.family";

  static class Testing {
    static final String CONF_TEST_FAILURE = "test.snapshot.export.failure";
    static final String CONF_TEST_FAILURE_COUNT = "test.snapshot.export.failure.count";
    int failuresCountToInject = 0;
    int injectedFailureCount = 0;
  }

  // Command line options and defaults.
  static final class Options {
    static final Option SNAPSHOT = new Option(null, "snapshot", true, "Snapshot to restore.");
    static final Option TARGET_NAME =
      new Option(null, "target", true, "Target name for the snapshot.");
    static final Option COPY_TO =
      new Option(null, "copy-to", true, "Remote " + "destination hdfs://");
    static final Option COPY_FROM =
      new Option(null, "copy-from", true, "Input folder hdfs:// (default hbase.rootdir)");
    static final Option NO_CHECKSUM_VERIFY = new Option(null, "no-checksum-verify", false,
      "Do not verify checksum, use name+length only.");
    static final Option NO_TARGET_VERIFY = new Option(null, "no-target-verify", false,
      "Do not verify the exported snapshot's expiration status and integrity.");
    static final Option NO_SOURCE_VERIFY = new Option(null, "no-source-verify", false,
      "Do not verify the source snapshot's expiration status and integrity.");
    static final Option OVERWRITE =
      new Option(null, "overwrite", false, "Rewrite the snapshot manifest if already exists.");
    static final Option CHUSER =
      new Option(null, "chuser", true, "Change the owner of the files to the specified one.");
    static final Option CHGROUP =
      new Option(null, "chgroup", true, "Change the group of the files to the specified one.");
    static final Option CHMOD =
      new Option(null, "chmod", true, "Change the permission of the files to the specified one.");
    static final Option MAPPERS = new Option(null, "mappers", true,
      "Number of mappers to use during the copy (mapreduce.job.maps). "
        + "If you provide a --custom-file-grouper, "
        + "then --mappers is interpreted as the number of mappers per group.");
    static final Option BANDWIDTH =
      new Option(null, "bandwidth", true, "Limit bandwidth to this value in MB/second.");
    static final Option RESET_TTL =
      new Option(null, "reset-ttl", false, "Do not copy TTL for the snapshot");
    static final Option STORAGE_POLICY = new Option(null, "storage-policy", true,
      "Storage policy for export snapshot output directory, with format like: f=HOT&g=ALL_SDD");
    static final Option CUSTOM_FILE_GROUPER = new Option(null, "custom-file-grouper", true,
      "Fully qualified class name of an implementation of ExportSnapshot.CustomFileGrouper. "
        + "See JavaDoc on that class for more information.");
    static final Option FILE_LOCATION_RESOLVER = new Option(null, "file-location-resolver", true,
      "Fully qualified class name of an implementation of ExportSnapshot.FileLocationResolver. "
        + "See JavaDoc on that class for more information.");
  }

  // Export Map-Reduce Counters, to keep track of the progress
  public enum Counter {
    MISSING_FILES,
    FILES_COPIED,
    FILES_SKIPPED,
    COPY_FAILED,
    BYTES_EXPECTED,
    BYTES_SKIPPED,
    BYTES_COPIED
  }

  /**
   * Indicates the checksum comparison result.
   */
  public enum ChecksumComparison {
    TRUE, // checksum comparison is compatible and true.
    FALSE, // checksum comparison is compatible and false.
    INCOMPATIBLE, // checksum comparison is not compatible.
  }

  /**
   * If desired, you may implement a CustomFileGrouper in order to influence how ExportSnapshot
   * chooses which input files go into the MapReduce job's {@link InputSplit}s. Your implementation
   * must return a data structure that contains each input file exactly once. Files that appear in
   * separate entries in the top-level returned Collection are guaranteed to not be placed in the
   * same InputSplit. This can be used to segregate your input files by the rack or host on which
   * they are available, which, used in conjunction with {@link FileLocationResolver}, can improve
   * the performance of your ExportSnapshot runs. To use this, pass the --custom-file-grouper
   * argument with the fully qualified class name of an implementation of CustomFileGrouper that's
   * on the classpath. If this argument is not used, no particular grouping logic will be applied.
   */
  @InterfaceAudience.Public
  public interface CustomFileGrouper {
    Collection<Collection<Pair<SnapshotFileInfo, Long>>>
      getGroupedInputFiles(final Collection<Pair<SnapshotFileInfo, Long>> snapshotFiles);
  }

  private static class NoopCustomFileGrouper implements CustomFileGrouper {
    @Override
    public Collection<Collection<Pair<SnapshotFileInfo, Long>>>
      getGroupedInputFiles(final Collection<Pair<SnapshotFileInfo, Long>> snapshotFiles) {
      return ImmutableList.of(snapshotFiles);
    }
  }

  /**
   * If desired, you may implement a FileLocationResolver in order to influence the _location_
   * metadata attached to each {@link InputSplit} that ExportSnapshot will submit to YARN. The
   * method {@link #getLocationsForInputFiles(Collection)} method is called once for each InputSplit
   * being constructed. Whatever is returned will ultimately be reported by that split's
   * {@link InputSplit#getLocations()} method. This can be used to encourage YARN to schedule the
   * ExportSnapshot's mappers on rack-local or host-local NodeManagers. To use this, pass the
   * --file-location-resolver argument with the fully qualified class name of an implementation of
   * FileLocationResolver that's on the classpath. If this argument is not used, no locations will
   * be attached to the InputSplits.
   */
  @InterfaceAudience.Public
  public interface FileLocationResolver {
    Set<String> getLocationsForInputFiles(final Collection<Pair<SnapshotFileInfo, Long>> files);
  }

  static class NoopFileLocationResolver implements FileLocationResolver {
    @Override
    public Set<String> getLocationsForInputFiles(Collection<Pair<SnapshotFileInfo, Long>> files) {
      return ImmutableSet.of();
    }
  }

  private static class ExportMapper
    extends Mapper<BytesWritable, NullWritable, NullWritable, NullWritable> {
    private static final Logger LOG = LoggerFactory.getLogger(ExportMapper.class);
    final static int REPORT_SIZE = 1 * 1024 * 1024;
    final static int BUFFER_SIZE = 64 * 1024;

    private boolean verifyChecksum;
    private String filesGroup;
    private String filesUser;
    private short filesMode;
    private int bufferSize;
    private int reportSize;

    private FileSystem outputFs;
    private Path outputArchive;
    private Path outputRoot;

    private FileSystem inputFs;
    private Path inputArchive;
    private Path inputRoot;

    private static Testing testing = new Testing();

    @Override
    public void setup(Context context) throws IOException {
      Configuration conf = context.getConfiguration();

      Configuration srcConf = HBaseConfiguration.createClusterConf(conf, null, CONF_SOURCE_PREFIX);
      Configuration destConf = HBaseConfiguration.createClusterConf(conf, null, CONF_DEST_PREFIX);

      verifyChecksum = conf.getBoolean(CONF_CHECKSUM_VERIFY, true);

      filesGroup = conf.get(CONF_FILES_GROUP);
      filesUser = conf.get(CONF_FILES_USER);
      filesMode = (short) conf.getInt(CONF_FILES_MODE, 0);
      outputRoot = new Path(conf.get(CONF_OUTPUT_ROOT));
      inputRoot = new Path(conf.get(CONF_INPUT_ROOT));

      inputArchive = new Path(inputRoot, HConstants.HFILE_ARCHIVE_DIRECTORY);
      outputArchive = new Path(outputRoot, HConstants.HFILE_ARCHIVE_DIRECTORY);

      try {
        inputFs = FileSystem.get(inputRoot.toUri(), srcConf);
      } catch (IOException e) {
        throw new IOException("Could not get the input FileSystem with root=" + inputRoot, e);
      }

      try {
        outputFs = FileSystem.get(outputRoot.toUri(), destConf);
      } catch (IOException e) {
        throw new IOException("Could not get the output FileSystem with root=" + outputRoot, e);
      }

      // Use the default block size of the outputFs if bigger
      int defaultBlockSize = Math.max((int) outputFs.getDefaultBlockSize(outputRoot), BUFFER_SIZE);
      bufferSize = conf.getInt(CONF_BUFFER_SIZE, defaultBlockSize);
      LOG.info("Using bufferSize=" + Strings.humanReadableInt(bufferSize));
      reportSize = conf.getInt(CONF_REPORT_SIZE, REPORT_SIZE);

      for (Counter c : Counter.values()) {
        context.getCounter(c).increment(0);
      }
      if (context.getConfiguration().getBoolean(Testing.CONF_TEST_FAILURE, false)) {
        testing.failuresCountToInject = conf.getInt(Testing.CONF_TEST_FAILURE_COUNT, 0);
        // Get number of times we have already injected failure based on attempt number of this
        // task.
        testing.injectedFailureCount = context.getTaskAttemptID().getId();
      }
    }

    @Override
    public void map(BytesWritable key, NullWritable value, Context context)
      throws InterruptedException, IOException {
      SnapshotFileInfo inputInfo = SnapshotFileInfo.parseFrom(key.copyBytes());
      Path outputPath = getOutputPath(inputInfo);

      copyFile(context, inputInfo, outputPath);
    }

    /**
     * Returns the location where the inputPath will be copied.
     */
    private Path getOutputPath(final SnapshotFileInfo inputInfo) throws IOException {
      Path path = null;
      switch (inputInfo.getType()) {
        case HFILE:
          Path inputPath = new Path(inputInfo.getHfile());
          String family = inputPath.getParent().getName();
          TableName table = HFileLink.getReferencedTableName(inputPath.getName());
          String region = HFileLink.getReferencedRegionName(inputPath.getName());
          String hfile = HFileLink.getReferencedHFileName(inputPath.getName());
          path = new Path(CommonFSUtils.getTableDir(new Path("./"), table),
            new Path(region, new Path(family, hfile)));
          break;
        case WAL:
          LOG.warn("snapshot does not keeps WALs: " + inputInfo);
          break;
        default:
          throw new IOException("Invalid File Type: " + inputInfo.getType().toString());
      }
      return new Path(outputArchive, path);
    }

    @SuppressWarnings("checkstyle:linelength")
    /**
     * Used by TestExportSnapshot to test for retries when failures happen. Failure is injected in
     * {@link #copyFile(Mapper.Context, org.apache.hadoop.hbase.shaded.protobuf.generated.SnapshotProtos.SnapshotFileInfo, Path)}.
     */
    private void injectTestFailure(final Context context, final SnapshotFileInfo inputInfo)
      throws IOException {
      if (!context.getConfiguration().getBoolean(Testing.CONF_TEST_FAILURE, false)) return;
      if (testing.injectedFailureCount >= testing.failuresCountToInject) return;
      testing.injectedFailureCount++;
      context.getCounter(Counter.COPY_FAILED).increment(1);
      LOG.debug("Injecting failure. Count: " + testing.injectedFailureCount);
      throw new IOException(String.format("TEST FAILURE (%d of max %d): Unable to copy input=%s",
        testing.injectedFailureCount, testing.failuresCountToInject, inputInfo));
    }

    private void copyFile(final Context context, final SnapshotFileInfo inputInfo,
      final Path outputPath) throws IOException {
      // Get the file information
      FileStatus inputStat = getSourceFileStatus(context, inputInfo);

      // Verify if the output file exists and is the same that we want to copy
      if (outputFs.exists(outputPath)) {
        FileStatus outputStat = outputFs.getFileStatus(outputPath);
        if (outputStat != null && sameFile(inputStat, outputStat)) {
          LOG.info("Skip copy " + inputStat.getPath() + " to " + outputPath + ", same file.");
          context.getCounter(Counter.FILES_SKIPPED).increment(1);
          context.getCounter(Counter.BYTES_SKIPPED).increment(inputStat.getLen());
          return;
        }
      }

      InputStream in = openSourceFile(context, inputInfo);
      int bandwidthMB = context.getConfiguration().getInt(CONF_BANDWIDTH_MB, 100);
      if (Integer.MAX_VALUE != bandwidthMB) {
        in = new ThrottledInputStream(new BufferedInputStream(in), bandwidthMB * 1024 * 1024L);
      }

      Path inputPath = inputStat.getPath();
      try {
        context.getCounter(Counter.BYTES_EXPECTED).increment(inputStat.getLen());

        // Ensure that the output folder is there and copy the file
        createOutputPath(outputPath.getParent());
        String family = new Path(inputInfo.getHfile()).getParent().getName();
        String familyStoragePolicy = generateFamilyStoragePolicyKey(family);
        if (stringIsNotEmpty(context.getConfiguration().get(familyStoragePolicy))) {
          String key = context.getConfiguration().get(familyStoragePolicy);
          LOG.info("Setting storage policy {} for {}", key, outputPath.getParent());
          outputFs.setStoragePolicy(outputPath.getParent(), key);
        }
        FSDataOutputStream out = outputFs.create(outputPath, true);

        long stime = EnvironmentEdgeManager.currentTime();
        long totalBytesWritten =
          copyData(context, inputPath, in, outputPath, out, inputStat.getLen());

        // Verify the file length and checksum
        verifyCopyResult(inputStat, outputFs.getFileStatus(outputPath));

        long etime = EnvironmentEdgeManager.currentTime();
        LOG.info("copy completed for input=" + inputPath + " output=" + outputPath);
        LOG.info("size=" + totalBytesWritten + " (" + Strings.humanReadableInt(totalBytesWritten)
          + ")" + " time=" + StringUtils.formatTimeDiff(etime, stime) + String.format(" %.3fM/sec",
            (totalBytesWritten / ((etime - stime) / 1000.0)) / 1048576.0));
        context.getCounter(Counter.FILES_COPIED).increment(1);

        // Try to Preserve attributes
        if (!preserveAttributes(outputPath, inputStat)) {
          LOG.warn("You may have to run manually chown on: " + outputPath);
        }
      } catch (IOException e) {
        LOG.error("Error copying " + inputPath + " to " + outputPath, e);
        context.getCounter(Counter.COPY_FAILED).increment(1);
        throw e;
      } finally {
        injectTestFailure(context, inputInfo);
      }
    }

    /**
     * Create the output folder and optionally set ownership.
     */
    private void createOutputPath(final Path path) throws IOException {
      if (filesUser == null && filesGroup == null) {
        outputFs.mkdirs(path);
      } else {
        Path parent = path.getParent();
        if (!outputFs.exists(parent) && !parent.isRoot()) {
          createOutputPath(parent);
        }
        outputFs.mkdirs(path);
        if (filesUser != null || filesGroup != null) {
          // override the owner when non-null user/group is specified
          outputFs.setOwner(path, filesUser, filesGroup);
        }
        if (filesMode > 0) {
          outputFs.setPermission(path, new FsPermission(filesMode));
        }
      }
    }

    /**
     * Try to Preserve the files attribute selected by the user copying them from the source file
     * This is only required when you are exporting as a different user than "hbase" or on a system
     * that doesn't have the "hbase" user. This is not considered a blocking failure since the user
     * can force a chmod with the user that knows is available on the system.
     */
    private boolean preserveAttributes(final Path path, final FileStatus refStat) {
      FileStatus stat;
      try {
        stat = outputFs.getFileStatus(path);
      } catch (IOException e) {
        LOG.warn("Unable to get the status for file=" + path);
        return false;
      }

      try {
        if (filesMode > 0 && stat.getPermission().toShort() != filesMode) {
          outputFs.setPermission(path, new FsPermission(filesMode));
        } else if (refStat != null && !stat.getPermission().equals(refStat.getPermission())) {
          outputFs.setPermission(path, refStat.getPermission());
        }
      } catch (IOException e) {
        LOG.warn("Unable to set the permission for file=" + stat.getPath() + ": " + e.getMessage());
        return false;
      }

      boolean hasRefStat = (refStat != null);
      String user = stringIsNotEmpty(filesUser) || !hasRefStat ? filesUser : refStat.getOwner();
      String group = stringIsNotEmpty(filesGroup) || !hasRefStat ? filesGroup : refStat.getGroup();
      if (stringIsNotEmpty(user) || stringIsNotEmpty(group)) {
        try {
          if (!(user.equals(stat.getOwner()) && group.equals(stat.getGroup()))) {
            outputFs.setOwner(path, user, group);
          }
        } catch (IOException e) {
          LOG.warn(
            "Unable to set the owner/group for file=" + stat.getPath() + ": " + e.getMessage());
          LOG.warn("The user/group may not exist on the destination cluster: user=" + user
            + " group=" + group);
          return false;
        }
      }

      return true;
    }

    private boolean stringIsNotEmpty(final String str) {
      return str != null && !str.isEmpty();
    }

    private long copyData(final Context context, final Path inputPath, final InputStream in,
      final Path outputPath, final FSDataOutputStream out, final long inputFileSize)
      throws IOException {
      final String statusMessage =
        "copied %s/" + Strings.humanReadableInt(inputFileSize) + " (%.1f%%)";

      try {
        byte[] buffer = new byte[bufferSize];
        long totalBytesWritten = 0;
        int reportBytes = 0;
        int bytesRead;

        while ((bytesRead = in.read(buffer)) > 0) {
          out.write(buffer, 0, bytesRead);
          totalBytesWritten += bytesRead;
          reportBytes += bytesRead;

          if (reportBytes >= reportSize) {
            context.getCounter(Counter.BYTES_COPIED).increment(reportBytes);
            context
              .setStatus(String.format(statusMessage, Strings.humanReadableInt(totalBytesWritten),
                (totalBytesWritten / (float) inputFileSize) * 100.0f) + " from " + inputPath
                + " to " + outputPath);
            reportBytes = 0;
          }
        }

        context.getCounter(Counter.BYTES_COPIED).increment(reportBytes);
        context.setStatus(String.format(statusMessage, Strings.humanReadableInt(totalBytesWritten),
          (totalBytesWritten / (float) inputFileSize) * 100.0f) + " from " + inputPath + " to "
          + outputPath);

        return totalBytesWritten;
      } finally {
        out.close();
        in.close();
      }
    }

    /**
     * Try to open the "source" file. Throws an IOException if the communication with the inputFs
     * fail or if the file is not found.
     */
    private FSDataInputStream openSourceFile(Context context, final SnapshotFileInfo fileInfo)
      throws IOException {
      try {
        Configuration conf = context.getConfiguration();
        FileLink link = null;
        switch (fileInfo.getType()) {
          case HFILE:
            Path inputPath = new Path(fileInfo.getHfile());
            link = getFileLink(inputPath, conf);
            break;
          case WAL:
            String serverName = fileInfo.getWalServer();
            String logName = fileInfo.getWalName();
            link = new WALLink(inputRoot, serverName, logName);
            break;
          default:
            throw new IOException("Invalid File Type: " + fileInfo.getType().toString());
        }
        return link.open(inputFs);
      } catch (IOException e) {
        context.getCounter(Counter.MISSING_FILES).increment(1);
        LOG.error("Unable to open source file=" + fileInfo.toString(), e);
        throw e;
      }
    }

    private FileStatus getSourceFileStatus(Context context, final SnapshotFileInfo fileInfo)
      throws IOException {
      try {
        Configuration conf = context.getConfiguration();
        FileLink link = null;
        switch (fileInfo.getType()) {
          case HFILE:
            Path inputPath = new Path(fileInfo.getHfile());
            link = getFileLink(inputPath, conf);
            break;
          case WAL:
            link = new WALLink(inputRoot, fileInfo.getWalServer(), fileInfo.getWalName());
            break;
          default:
            throw new IOException("Invalid File Type: " + fileInfo.getType().toString());
        }
        return link.getFileStatus(inputFs);
      } catch (FileNotFoundException e) {
        context.getCounter(Counter.MISSING_FILES).increment(1);
        LOG.error("Unable to get the status for source file=" + fileInfo.toString(), e);
        throw e;
      } catch (IOException e) {
        LOG.error("Unable to get the status for source file=" + fileInfo.toString(), e);
        throw e;
      }
    }

    private FileLink getFileLink(Path path, Configuration conf) throws IOException {
      String regionName = HFileLink.getReferencedRegionName(path.getName());
      TableName tableName = HFileLink.getReferencedTableName(path.getName());
      if (MobUtils.getMobRegionInfo(tableName).getEncodedName().equals(regionName)) {
        return HFileLink.buildFromHFileLinkPattern(MobUtils.getQualifiedMobRootDir(conf),
          HFileArchiveUtil.getArchivePath(conf), path);
      }
      return HFileLink.buildFromHFileLinkPattern(inputRoot, inputArchive, path);
    }

    private FileChecksum getFileChecksum(final FileSystem fs, final Path path) {
      try {
        return fs.getFileChecksum(path);
      } catch (IOException e) {
        LOG.warn("Unable to get checksum for file=" + path, e);
        return null;
      }
    }

    /**
     * Utility to compare the file length and checksums for the paths specified.
     */
    private void verifyCopyResult(final FileStatus inputStat, final FileStatus outputStat)
      throws IOException {
      long inputLen = inputStat.getLen();
      long outputLen = outputStat.getLen();
      Path inputPath = inputStat.getPath();
      Path outputPath = outputStat.getPath();

      if (inputLen != outputLen) {
        throw new IOException("Mismatch in length of input:" + inputPath + " (" + inputLen
          + ") and output:" + outputPath + " (" + outputLen + ")");
      }

      // If length==0, we will skip checksum
      if (inputLen != 0 && verifyChecksum) {
        FileChecksum inChecksum = getFileChecksum(inputFs, inputStat.getPath());
        FileChecksum outChecksum = getFileChecksum(outputFs, outputStat.getPath());

        ChecksumComparison checksumComparison = verifyChecksum(inChecksum, outChecksum);
        if (!checksumComparison.equals(ChecksumComparison.TRUE)) {
          StringBuilder errMessage = new StringBuilder("Checksum mismatch between ")
            .append(inputPath).append(" and ").append(outputPath).append(".");

          boolean addSkipHint = false;
          String inputScheme = inputFs.getScheme();
          String outputScheme = outputFs.getScheme();
          if (!inputScheme.equals(outputScheme)) {
            errMessage.append(" Input and output filesystems are of different types.\n")
              .append("Their checksum algorithms may be incompatible.");
            addSkipHint = true;
          } else if (inputStat.getBlockSize() != outputStat.getBlockSize()) {
            errMessage.append(" Input and output differ in block-size.");
            addSkipHint = true;
          } else if (
            inChecksum != null && outChecksum != null
              && !inChecksum.getAlgorithmName().equals(outChecksum.getAlgorithmName())
          ) {
            errMessage.append(" Input and output checksum algorithms are of different types.");
            addSkipHint = true;
          }
          if (addSkipHint) {
            errMessage
              .append(" You can choose file-level checksum validation via "
                + "-Ddfs.checksum.combine.mode=COMPOSITE_CRC when block-sizes"
                + " or filesystems are different.\n")
              .append(" Or you can skip checksum-checks altogether with -no-checksum-verify,")
              .append(
                " for the table backup scenario, you should use -i option to skip checksum-checks.\n")
              .append(" (NOTE: By skipping checksums, one runs the risk of "
                + "masking data-corruption during file-transfer.)\n");
          }
          throw new IOException(errMessage.toString());
        }
      }
    }

    /**
     * Utility to compare checksums
     */
    private ChecksumComparison verifyChecksum(final FileChecksum inChecksum,
      final FileChecksum outChecksum) {
      // If the input or output checksum is null, or the algorithms of input and output are not
      // equal, that means there is no comparison
      // and return not compatible. else if matched, return compatible with the matched result.
      if (
        inChecksum == null || outChecksum == null
          || !inChecksum.getAlgorithmName().equals(outChecksum.getAlgorithmName())
      ) {
        return ChecksumComparison.INCOMPATIBLE;
      } else if (inChecksum.equals(outChecksum)) {
        return ChecksumComparison.TRUE;
      }
      return ChecksumComparison.FALSE;
    }

    /**
     * Check if the two files are equal by looking at the file length, and at the checksum (if user
     * has specified the verifyChecksum flag).
     */
    private boolean sameFile(final FileStatus inputStat, final FileStatus outputStat) {
      // Not matching length
      if (inputStat.getLen() != outputStat.getLen()) return false;

      // Mark files as equals, since user asked for no checksum verification
      if (!verifyChecksum) return true;

      // If checksums are not available, files are not the same.
      FileChecksum inChecksum = getFileChecksum(inputFs, inputStat.getPath());
      if (inChecksum == null) return false;

      FileChecksum outChecksum = getFileChecksum(outputFs, outputStat.getPath());
      if (outChecksum == null) return false;

      return inChecksum.equals(outChecksum);
    }
  }

  // ==========================================================================
  // Input Format
  // ==========================================================================

  /**
   * Extract the list of files (HFiles/WALs) to copy using Map-Reduce.
   * @return list of files referenced by the snapshot (pair of path and size)
   */
  private static List<Pair<SnapshotFileInfo, Long>> getSnapshotFiles(final Configuration conf,
    final FileSystem fs, final Path snapshotDir) throws IOException {
    SnapshotDescription snapshotDesc = SnapshotDescriptionUtils.readSnapshotInfo(fs, snapshotDir);

    final List<Pair<SnapshotFileInfo, Long>> files = new ArrayList<>();
    final TableName table = TableName.valueOf(snapshotDesc.getTable());

    // Get snapshot files
    LOG.info("Loading Snapshot '" + snapshotDesc.getName() + "' hfile list");
    Set<String> addedFiles = new HashSet<>();
    SnapshotReferenceUtil.visitReferencedFiles(conf, fs, snapshotDir, snapshotDesc,
      new SnapshotReferenceUtil.SnapshotVisitor() {
        @Override
        public void storeFile(final RegionInfo regionInfo, final String family,
          final SnapshotRegionManifest.StoreFile storeFile) throws IOException {
          Pair<SnapshotFileInfo, Long> snapshotFileAndSize = null;
          if (!storeFile.hasReference()) {
            String region = regionInfo.getEncodedName();
            String hfile = storeFile.getName();
            snapshotFileAndSize = getSnapshotFileAndSize(fs, conf, table, region, family, hfile,
              storeFile.hasFileSize() ? storeFile.getFileSize() : -1);
          } else {
            Pair<String, String> referredToRegionAndFile =
              StoreFileInfo.getReferredToRegionAndFile(storeFile.getName());
            String referencedRegion = referredToRegionAndFile.getFirst();
            String referencedHFile = referredToRegionAndFile.getSecond();
            snapshotFileAndSize = getSnapshotFileAndSize(fs, conf, table, referencedRegion, family,
              referencedHFile, storeFile.hasFileSize() ? storeFile.getFileSize() : -1);
          }
          String fileToExport = snapshotFileAndSize.getFirst().getHfile();
          if (!addedFiles.contains(fileToExport)) {
            files.add(snapshotFileAndSize);
            addedFiles.add(fileToExport);
          } else {
            LOG.debug("Skip the existing file: {}.", fileToExport);
          }
        }
      });

    return files;
  }

  private static Pair<SnapshotFileInfo, Long> getSnapshotFileAndSize(FileSystem fs,
    Configuration conf, TableName table, String region, String family, String hfile, long size)
    throws IOException {
    Path path = HFileLink.createPath(table, region, family, hfile);
    SnapshotFileInfo fileInfo = SnapshotFileInfo.newBuilder().setType(SnapshotFileInfo.Type.HFILE)
      .setHfile(path.toString()).build();
    if (size == -1) {
      size = HFileLink.buildFromHFileLinkPattern(conf, path).getFileStatus(fs).getLen();
    }
    return new Pair<>(fileInfo, size);
  }

  /**
   * Given a list of file paths and sizes, create around ngroups in as balanced a way as possible.
   * The groups created will have similar amounts of bytes.
   * <p>
   * The algorithm used is pretty straightforward; the file list is sorted by size, and then each
   * group fetch the bigger file available, iterating through groups alternating the direction.
   */
  static List<List<Pair<SnapshotFileInfo, Long>>> getBalancedSplits(
    final Collection<Pair<SnapshotFileInfo, Long>> unsortedFiles, final int ngroups) {
    List<Pair<SnapshotFileInfo, Long>> files = new ArrayList<>(unsortedFiles);
    // Sort files by size, from small to big
    Collections.sort(files, new Comparator<Pair<SnapshotFileInfo, Long>>() {
      public int compare(Pair<SnapshotFileInfo, Long> a, Pair<SnapshotFileInfo, Long> b) {
        long r = a.getSecond() - b.getSecond();
        return (r < 0) ? -1 : ((r > 0) ? 1 : 0);
      }
    });

    // create balanced groups
    List<List<Pair<SnapshotFileInfo, Long>>> fileGroups = new LinkedList<>();
    int hi = files.size() - 1;
    int lo = 0;

    List<Pair<SnapshotFileInfo, Long>> group;
    int dir = 1;
    int g = 0;

    while (hi >= lo) {
      if (g == fileGroups.size()) {
        group = new LinkedList<>();
        fileGroups.add(group);
      } else {
        group = fileGroups.get(g);
      }

      Pair<SnapshotFileInfo, Long> fileInfo = files.get(hi--);

      // add the hi one
      group.add(fileInfo);

      // change direction when at the end or the beginning
      g += dir;
      if (g == ngroups) {
        dir = -1;
        g = ngroups - 1;
      } else if (g < 0) {
        dir = 1;
        g = 0;
      }
    }

    return fileGroups;
  }

  static class ExportSnapshotInputFormat extends InputFormat<BytesWritable, NullWritable> {
    @Override
    public RecordReader<BytesWritable, NullWritable> createRecordReader(InputSplit split,
      TaskAttemptContext tac) throws IOException, InterruptedException {
      return new ExportSnapshotRecordReader(((ExportSnapshotInputSplit) split).getSplitKeys());
    }

    @Override
    public List<InputSplit> getSplits(JobContext context) throws IOException, InterruptedException {
      Configuration conf = context.getConfiguration();
      Path snapshotDir = new Path(conf.get(CONF_SNAPSHOT_DIR));
      FileSystem fs = FileSystem.get(snapshotDir.toUri(), conf);

      List<Pair<SnapshotFileInfo, Long>> snapshotFiles = getSnapshotFiles(conf, fs, snapshotDir);

      Collection<List<Pair<SnapshotFileInfo, Long>>> balancedGroups =
        groupFilesForSplits(conf, snapshotFiles);

      Class<? extends FileLocationResolver> fileLocationResolverClass =
        conf.getClass(CONF_INPUT_FILE_LOCATION_RESOLVER_CLASS, NoopFileLocationResolver.class,
          FileLocationResolver.class);
      FileLocationResolver fileLocationResolver =
        ReflectionUtils.newInstance(fileLocationResolverClass, conf);
      LOG.info("FileLocationResolver {} will provide location metadata for each InputSplit",
        fileLocationResolverClass);

      List<InputSplit> splits = new ArrayList<>(balancedGroups.size());
      for (Collection<Pair<SnapshotFileInfo, Long>> files : balancedGroups) {
        splits.add(new ExportSnapshotInputSplit(files, fileLocationResolver));
      }
      return splits;
    }

    Collection<List<Pair<SnapshotFileInfo, Long>>> groupFilesForSplits(Configuration conf,
      List<Pair<SnapshotFileInfo, Long>> snapshotFiles) {
      int mappers = conf.getInt(CONF_NUM_SPLITS, 0);
      if (mappers == 0 && !snapshotFiles.isEmpty()) {
        mappers = 1 + (snapshotFiles.size() / conf.getInt(CONF_MAP_GROUP, 10));
        mappers = Math.min(mappers, snapshotFiles.size());
        conf.setInt(CONF_NUM_SPLITS, mappers);
        conf.setInt(MR_NUM_MAPS, mappers);
      }

      Class<? extends CustomFileGrouper> inputFileGrouperClass = conf.getClass(
        CONF_INPUT_FILE_GROUPER_CLASS, NoopCustomFileGrouper.class, CustomFileGrouper.class);
      CustomFileGrouper customFileGrouper =
        ReflectionUtils.newInstance(inputFileGrouperClass, conf);
      Collection<Collection<Pair<SnapshotFileInfo, Long>>> groups =
        customFileGrouper.getGroupedInputFiles(snapshotFiles);

      LOG.info("CustomFileGrouper {} split input files into {} groups", inputFileGrouperClass,
        groups.size());
      int mappersPerGroup = groups.isEmpty() ? 1 : Math.max(mappers / groups.size(), 1);
      LOG.info(
        "Splitting each group into {} InputSplits, "
          + "to achieve closest possible amount of mappers to target of {}",
        mappersPerGroup, mappers);

      // Within each group, create splits of equal size. Groups are not mixed together.
      return groups.stream().map(g -> getBalancedSplits(g, mappersPerGroup))
        .flatMap(Collection::stream).toList();
    }

    static class ExportSnapshotInputSplit extends InputSplit implements Writable {

      private List<Pair<BytesWritable, Long>> files;
      private String[] locations;
      private long length;

      public ExportSnapshotInputSplit() {
        this.files = null;
        this.locations = null;
      }

      public ExportSnapshotInputSplit(final Collection<Pair<SnapshotFileInfo, Long>> snapshotFiles,
        FileLocationResolver fileLocationResolver) {
        this.files = new ArrayList<>(snapshotFiles.size());
        for (Pair<SnapshotFileInfo, Long> fileInfo : snapshotFiles) {
          this.files.add(
            new Pair<>(new BytesWritable(fileInfo.getFirst().toByteArray()), fileInfo.getSecond()));
          this.length += fileInfo.getSecond();
        }
        this.locations =
          fileLocationResolver.getLocationsForInputFiles(snapshotFiles).toArray(new String[0]);
        LOG.trace("This ExportSnapshotInputSplit has files {} of collective size {}, "
          + "with location hints: {}", files, length, locations);
      }

      private List<Pair<BytesWritable, Long>> getSplitKeys() {
        return files;
      }

      @Override
      public long getLength() throws IOException, InterruptedException {
        return length;
      }

      @Override
      public String[] getLocations() throws IOException, InterruptedException {
        return locations;
      }

      @Override
      public void readFields(DataInput in) throws IOException {
        int count = in.readInt();
        files = new ArrayList<>(count);
        length = 0;
        for (int i = 0; i < count; ++i) {
          BytesWritable fileInfo = new BytesWritable();
          fileInfo.readFields(in);
          long size = in.readLong();
          files.add(new Pair<>(fileInfo, size));
          length += size;
        }
        int locationCount = in.readInt();
        List<String> locations = new ArrayList<>(locationCount);
        for (int i = 0; i < locationCount; ++i) {
          locations.add(in.readUTF());
        }
        this.locations = locations.toArray(new String[0]);
      }

      @Override
      public void write(DataOutput out) throws IOException {
        out.writeInt(files.size());
        for (final Pair<BytesWritable, Long> fileInfo : files) {
          fileInfo.getFirst().write(out);
          out.writeLong(fileInfo.getSecond());
        }
        out.writeInt(locations.length);
        for (String location : locations) {
          out.writeUTF(location);
        }
      }
    }

    private static class ExportSnapshotRecordReader
      extends RecordReader<BytesWritable, NullWritable> {
      private final List<Pair<BytesWritable, Long>> files;
      private long totalSize = 0;
      private long procSize = 0;
      private int index = -1;

      ExportSnapshotRecordReader(final List<Pair<BytesWritable, Long>> files) {
        this.files = files;
        for (Pair<BytesWritable, Long> fileInfo : files) {
          totalSize += fileInfo.getSecond();
        }
      }

      @Override
      public void close() {
      }

      @Override
      public BytesWritable getCurrentKey() {
        return files.get(index).getFirst();
      }

      @Override
      public NullWritable getCurrentValue() {
        return NullWritable.get();
      }

      @Override
      public float getProgress() {
        return (float) procSize / totalSize;
      }

      @Override
      public void initialize(InputSplit split, TaskAttemptContext tac) {
      }

      @Override
      public boolean nextKeyValue() {
        if (index >= 0) {
          procSize += files.get(index).getSecond();
        }
        return (++index < files.size());
      }
    }
  }

  // ==========================================================================
  // Tool
  // ==========================================================================

  /**
   * Run Map-Reduce Job to perform the files copy.
   */
  private void runCopyJob(final Path inputRoot, final Path outputRoot, final String snapshotName,
    final Path snapshotDir, final boolean verifyChecksum, final String filesUser,
    final String filesGroup, final int filesMode, final int mappers, final int bandwidthMB,
    final String storagePolicy, final String customFileGrouper, final String fileLocationResolver)
    throws IOException, InterruptedException, ClassNotFoundException {
    Configuration conf = getConf();
    if (filesGroup != null) conf.set(CONF_FILES_GROUP, filesGroup);
    if (filesUser != null) conf.set(CONF_FILES_USER, filesUser);
    if (mappers > 0) {
      conf.setInt(CONF_NUM_SPLITS, mappers);
      conf.setInt(MR_NUM_MAPS, mappers);
    }
    conf.setInt(CONF_FILES_MODE, filesMode);
    conf.setBoolean(CONF_CHECKSUM_VERIFY, verifyChecksum);
    conf.set(CONF_OUTPUT_ROOT, outputRoot.toString());
    conf.set(CONF_INPUT_ROOT, inputRoot.toString());
    conf.setInt(CONF_BANDWIDTH_MB, bandwidthMB);
    conf.set(CONF_SNAPSHOT_NAME, snapshotName);
    conf.set(CONF_SNAPSHOT_DIR, snapshotDir.toString());
    if (storagePolicy != null) {
      for (Map.Entry<String, String> entry : storagePolicyPerFamily(storagePolicy).entrySet()) {
        conf.set(generateFamilyStoragePolicyKey(entry.getKey()), entry.getValue());
      }
    }
    if (customFileGrouper != null) {
      conf.set(CONF_INPUT_FILE_GROUPER_CLASS, customFileGrouper);
    }
    if (fileLocationResolver != null) {
      conf.set(CONF_INPUT_FILE_LOCATION_RESOLVER_CLASS, fileLocationResolver);
    }

    String jobname = conf.get(CONF_MR_JOB_NAME, "ExportSnapshot-" + snapshotName);
    Job job = new Job(conf);
    job.setJobName(jobname);
    job.setJarByClass(ExportSnapshot.class);
    TableMapReduceUtil.addDependencyJars(job);
    job.setMapperClass(ExportMapper.class);
    job.setInputFormatClass(ExportSnapshotInputFormat.class);
    job.setOutputFormatClass(NullOutputFormat.class);
    job.setMapSpeculativeExecution(false);
    job.setNumReduceTasks(0);

    // Acquire the delegation Tokens
    Configuration srcConf = HBaseConfiguration.createClusterConf(conf, null, CONF_SOURCE_PREFIX);
    TokenCache.obtainTokensForNamenodes(job.getCredentials(), new Path[] { inputRoot }, srcConf);
    Configuration destConf = HBaseConfiguration.createClusterConf(conf, null, CONF_DEST_PREFIX);
    TokenCache.obtainTokensForNamenodes(job.getCredentials(), new Path[] { outputRoot }, destConf);

    // Run the MR Job
    if (!job.waitForCompletion(true)) {
      throw new ExportSnapshotException(job.getStatus().getFailureInfo());
    }
  }

  private void verifySnapshot(final SnapshotDescription snapshotDesc, final Configuration baseConf,
    final FileSystem fs, final Path rootDir, final Path snapshotDir) throws IOException {
    // Update the conf with the current root dir, since may be a different cluster
    Configuration conf = new Configuration(baseConf);
    CommonFSUtils.setRootDir(conf, rootDir);
    CommonFSUtils.setFsDefault(conf, CommonFSUtils.getRootDir(conf));
    boolean isExpired = SnapshotDescriptionUtils.isExpiredSnapshot(snapshotDesc.getTtl(),
      snapshotDesc.getCreationTime(), EnvironmentEdgeManager.currentTime());
    if (isExpired) {
      throw new SnapshotTTLExpiredException(ProtobufUtil.createSnapshotDesc(snapshotDesc));
    }
    SnapshotReferenceUtil.verifySnapshot(conf, fs, snapshotDir, snapshotDesc);
  }

  private void setConfigParallel(FileSystem outputFs, List<Path> traversedPath,
    BiConsumer<FileSystem, Path> task, Configuration conf) throws IOException {
    ExecutorService pool = Executors
      .newFixedThreadPool(conf.getInt(CONF_COPY_MANIFEST_THREADS, DEFAULT_COPY_MANIFEST_THREADS));
    List<Future<Void>> futures = new ArrayList<>();
    for (Path dstPath : traversedPath) {
      Future<Void> future = (Future<Void>) pool.submit(() -> task.accept(outputFs, dstPath));
      futures.add(future);
    }
    try {
      for (Future<Void> future : futures) {
        future.get();
      }
    } catch (InterruptedException | ExecutionException e) {
      throw new IOException(e);
    } finally {
      pool.shutdownNow();
    }
  }

  private void setOwnerParallel(FileSystem outputFs, String filesUser, String filesGroup,
    Configuration conf, List<Path> traversedPath) throws IOException {
    setConfigParallel(outputFs, traversedPath, (fs, path) -> {
      try {
        fs.setOwner(path, filesUser, filesGroup);
      } catch (IOException e) {
        throw new RuntimeException(
          "set owner for file " + path + " to " + filesUser + ":" + filesGroup + " failed", e);
      }
    }, conf);
  }

  private void setPermissionParallel(final FileSystem outputFs, final short filesMode,
    final List<Path> traversedPath, final Configuration conf) throws IOException {
    if (filesMode <= 0) {
      return;
    }
    FsPermission perm = new FsPermission(filesMode);
    setConfigParallel(outputFs, traversedPath, (fs, path) -> {
      try {
        fs.setPermission(path, perm);
      } catch (IOException e) {
        throw new RuntimeException(
          "set permission for file " + path + " to " + filesMode + " failed", e);
      }
    }, conf);
  }

  private Map<String, String> storagePolicyPerFamily(String storagePolicy) {
    Map<String, String> familyStoragePolicy = new HashMap<>();
    for (String familyConf : storagePolicy.split("&")) {
      String[] familySplit = familyConf.split("=");
      if (familySplit.length != 2) {
        continue;
      }
      // family is key, storage policy is value
      familyStoragePolicy.put(familySplit[0], familySplit[1]);
    }
    return familyStoragePolicy;
  }

  private static String generateFamilyStoragePolicyKey(String family) {
    return CONF_STORAGE_POLICY + "." + family;
  }

  private boolean verifyTarget = true;
  private boolean verifySource = true;
  private boolean verifyChecksum = true;
  private String snapshotName = null;
  private String targetName = null;
  private boolean overwrite = false;
  private String filesGroup = null;
  private String filesUser = null;
  private Path outputRoot = null;
  private Path inputRoot = null;
  private int bandwidthMB = Integer.MAX_VALUE;
  private int filesMode = 0;
  private int mappers = 0;
  private boolean resetTtl = false;
  private String storagePolicy = null;
  private String customFileGrouper = null;
  private String fileLocationResolver = null;

  @Override
  protected void processOptions(CommandLine cmd) {
    snapshotName = cmd.getOptionValue(Options.SNAPSHOT.getLongOpt(), snapshotName);
    targetName = cmd.getOptionValue(Options.TARGET_NAME.getLongOpt(), targetName);
    if (cmd.hasOption(Options.COPY_TO.getLongOpt())) {
      outputRoot = new Path(cmd.getOptionValue(Options.COPY_TO.getLongOpt()));
    }
    if (cmd.hasOption(Options.COPY_FROM.getLongOpt())) {
      inputRoot = new Path(cmd.getOptionValue(Options.COPY_FROM.getLongOpt()));
    }
    mappers = getOptionAsInt(cmd, Options.MAPPERS.getLongOpt(), mappers);
    filesUser = cmd.getOptionValue(Options.CHUSER.getLongOpt(), filesUser);
    filesGroup = cmd.getOptionValue(Options.CHGROUP.getLongOpt(), filesGroup);
    filesMode = getOptionAsInt(cmd, Options.CHMOD.getLongOpt(), filesMode, 8);
    bandwidthMB = getOptionAsInt(cmd, Options.BANDWIDTH.getLongOpt(), bandwidthMB);
    overwrite = cmd.hasOption(Options.OVERWRITE.getLongOpt());
    // And verifyChecksum and verifyTarget with values read from old args in processOldArgs(...).
    verifyChecksum = !cmd.hasOption(Options.NO_CHECKSUM_VERIFY.getLongOpt());
    verifyTarget = !cmd.hasOption(Options.NO_TARGET_VERIFY.getLongOpt());
    verifySource = !cmd.hasOption(Options.NO_SOURCE_VERIFY.getLongOpt());
    resetTtl = cmd.hasOption(Options.RESET_TTL.getLongOpt());
    if (cmd.hasOption(Options.STORAGE_POLICY.getLongOpt())) {
      storagePolicy = cmd.getOptionValue(Options.STORAGE_POLICY.getLongOpt());
    }
    if (cmd.hasOption(Options.CUSTOM_FILE_GROUPER.getLongOpt())) {
      customFileGrouper = cmd.getOptionValue(Options.CUSTOM_FILE_GROUPER.getLongOpt());
    }
    if (cmd.hasOption(Options.FILE_LOCATION_RESOLVER.getLongOpt())) {
      fileLocationResolver = cmd.getOptionValue(Options.FILE_LOCATION_RESOLVER.getLongOpt());
    }
  }

  /**
   * Execute the export snapshot by copying the snapshot metadata, hfiles and wals.
   * @return 0 on success, and != 0 upon failure.
   */
  @Override
  public int doWork() throws IOException {
    Configuration conf = getConf();

    // Check user options
    if (snapshotName == null) {
      System.err.println("Snapshot name not provided.");
      LOG.error("Use -h or --help for usage instructions.");
      return EXIT_FAILURE;
    }

    if (outputRoot == null) {
      System.err
        .println("Destination file-system (--" + Options.COPY_TO.getLongOpt() + ") not provided.");
      LOG.error("Use -h or --help for usage instructions.");
      return EXIT_FAILURE;
    }

    if (targetName == null) {
      targetName = snapshotName;
    }
    if (inputRoot == null) {
      inputRoot = CommonFSUtils.getRootDir(conf);
    } else {
      CommonFSUtils.setRootDir(conf, inputRoot);
    }

    Configuration srcConf = HBaseConfiguration.createClusterConf(conf, null, CONF_SOURCE_PREFIX);
    FileSystem inputFs = FileSystem.get(inputRoot.toUri(), srcConf);
    Configuration destConf = HBaseConfiguration.createClusterConf(conf, null, CONF_DEST_PREFIX);
    FileSystem outputFs = FileSystem.get(outputRoot.toUri(), destConf);
    boolean skipTmp = conf.getBoolean(CONF_SKIP_TMP, false)
      || conf.get(SnapshotDescriptionUtils.SNAPSHOT_WORKING_DIR) != null;
    Path snapshotDir = SnapshotDescriptionUtils.getCompletedSnapshotDir(snapshotName, inputRoot);
    Path snapshotTmpDir =
      SnapshotDescriptionUtils.getWorkingSnapshotDir(targetName, outputRoot, destConf);
    Path outputSnapshotDir =
      SnapshotDescriptionUtils.getCompletedSnapshotDir(targetName, outputRoot);
    Path initialOutputSnapshotDir = skipTmp ? outputSnapshotDir : snapshotTmpDir;
    LOG.debug("inputFs={}, inputRoot={}", inputFs.getUri().toString(), inputRoot);
    LOG.debug("outputFs={}, outputRoot={}, skipTmp={}, initialOutputSnapshotDir={}", outputFs,
      outputRoot.toString(), skipTmp, initialOutputSnapshotDir);

    // throw CorruptedSnapshotException if we can't read the snapshot info.
    SnapshotDescription sourceSnapshotDesc =
      SnapshotDescriptionUtils.readSnapshotInfo(inputFs, snapshotDir);

    // Verify snapshot source before copying files
    if (verifySource) {
      LOG.info("Verify the source snapshot's expiration status and integrity.");
      verifySnapshot(sourceSnapshotDesc, srcConf, inputFs, inputRoot, snapshotDir);
    }

    // Find the necessary directory which need to change owner and group
    Path needSetOwnerDir = SnapshotDescriptionUtils.getSnapshotRootDir(outputRoot);
    if (outputFs.exists(needSetOwnerDir)) {
      if (skipTmp) {
        needSetOwnerDir = outputSnapshotDir;
      } else {
        needSetOwnerDir = SnapshotDescriptionUtils.getWorkingSnapshotDir(outputRoot, destConf);
        if (outputFs.exists(needSetOwnerDir)) {
          needSetOwnerDir = snapshotTmpDir;
        }
      }
    }

    // Check if the snapshot already exists
    if (outputFs.exists(outputSnapshotDir)) {
      if (overwrite) {
        if (!outputFs.delete(outputSnapshotDir, true)) {
          System.err.println("Unable to remove existing snapshot directory: " + outputSnapshotDir);
          return EXIT_FAILURE;
        }
      } else {
        System.err.println("The snapshot '" + targetName + "' already exists in the destination: "
          + outputSnapshotDir);
        return EXIT_FAILURE;
      }
    }

    if (!skipTmp) {
      // Check if the snapshot already in-progress
      if (outputFs.exists(snapshotTmpDir)) {
        if (overwrite) {
          if (!outputFs.delete(snapshotTmpDir, true)) {
            System.err
              .println("Unable to remove existing snapshot tmp directory: " + snapshotTmpDir);
            return EXIT_FAILURE;
          }
        } else {
          System.err
            .println("A snapshot with the same name '" + targetName + "' may be in-progress");
          System.err
            .println("Please check " + snapshotTmpDir + ". If the snapshot has completed, ");
          System.err
            .println("consider removing " + snapshotTmpDir + " by using the -overwrite option");
          return EXIT_FAILURE;
        }
      }
    }

    // Step 1 - Copy fs1:/.snapshot/<snapshot> to fs2:/.snapshot/.tmp/<snapshot>
    // The snapshot references must be copied before the hfiles otherwise the cleaner
    // will remove them because they are unreferenced.
    List<Path> travesedPaths = new ArrayList<>();
    boolean copySucceeded = false;
    try {
      LOG.info("Copy Snapshot Manifest from " + snapshotDir + " to " + initialOutputSnapshotDir);
      travesedPaths =
        FSUtils.copyFilesParallel(inputFs, snapshotDir, outputFs, initialOutputSnapshotDir, conf,
          conf.getInt(CONF_COPY_MANIFEST_THREADS, DEFAULT_COPY_MANIFEST_THREADS));
      copySucceeded = true;
    } catch (IOException e) {
      throw new ExportSnapshotException("Failed to copy the snapshot directory: from=" + snapshotDir
        + " to=" + initialOutputSnapshotDir, e);
    } finally {
      if (copySucceeded) {
        if (filesUser != null || filesGroup != null) {
          LOG.warn(
            (filesUser == null ? "" : "Change the owner of " + needSetOwnerDir + " to " + filesUser)
              + (filesGroup == null
                ? ""
                : ", Change the group of " + needSetOwnerDir + " to " + filesGroup));
          setOwnerParallel(outputFs, filesUser, filesGroup, conf, travesedPaths);
        }
        if (filesMode > 0) {
          LOG.warn("Change the permission of " + needSetOwnerDir + " to " + filesMode);
          setPermissionParallel(outputFs, (short) filesMode, travesedPaths, conf);
        }
      }
    }

    // Write a new .snapshotinfo if the target name is different from the source name or we want to
    // reset TTL for target snapshot.
    if (!targetName.equals(snapshotName) || resetTtl) {
      SnapshotDescription.Builder snapshotDescBuilder =
        SnapshotDescriptionUtils.readSnapshotInfo(inputFs, snapshotDir).toBuilder();
      if (!targetName.equals(snapshotName)) {
        snapshotDescBuilder.setName(targetName);
      }
      if (resetTtl) {
        snapshotDescBuilder.setTtl(HConstants.DEFAULT_SNAPSHOT_TTL);
      }
      SnapshotDescriptionUtils.writeSnapshotInfo(snapshotDescBuilder.build(),
        initialOutputSnapshotDir, outputFs);
      if (filesUser != null || filesGroup != null) {
        outputFs.setOwner(
          new Path(initialOutputSnapshotDir, SnapshotDescriptionUtils.SNAPSHOTINFO_FILE), filesUser,
          filesGroup);
      }
      if (filesMode > 0) {
        outputFs.setPermission(
          new Path(initialOutputSnapshotDir, SnapshotDescriptionUtils.SNAPSHOTINFO_FILE),
          new FsPermission((short) filesMode));
      }
    }

    // Step 2 - Start MR Job to copy files
    // The snapshot references must be copied before the files otherwise the files gets removed
    // by the HFileArchiver, since they have no references.
    try {
      runCopyJob(inputRoot, outputRoot, snapshotName, snapshotDir, verifyChecksum, filesUser,
        filesGroup, filesMode, mappers, bandwidthMB, storagePolicy, customFileGrouper,
        fileLocationResolver);

      LOG.info("Finalize the Snapshot Export");
      if (!skipTmp) {
        // Step 3 - Rename fs2:/.snapshot/.tmp/<snapshot> fs2:/.snapshot/<snapshot>
        if (!outputFs.rename(snapshotTmpDir, outputSnapshotDir)) {
          throw new ExportSnapshotException("Unable to rename snapshot directory from="
            + snapshotTmpDir + " to=" + outputSnapshotDir);
        }
      }

      // Step 4 - Verify snapshot integrity
      if (verifyTarget) {
        LOG.info("Verify the exported snapshot's expiration status and integrity.");
        SnapshotDescription targetSnapshotDesc =
          SnapshotDescriptionUtils.readSnapshotInfo(outputFs, outputSnapshotDir);
        verifySnapshot(targetSnapshotDesc, destConf, outputFs, outputRoot, outputSnapshotDir);
      }

      LOG.info("Export Completed: " + targetName);
      return EXIT_SUCCESS;
    } catch (Exception e) {
      LOG.error("Snapshot export failed", e);
      if (!skipTmp) {
        outputFs.delete(snapshotTmpDir, true);
      }
      outputFs.delete(outputSnapshotDir, true);
      return EXIT_FAILURE;
    }
  }

  @Override
  protected void printUsage() {
    super.printUsage();
    System.out.println("\n" + "Examples:\n" + "  hbase snapshot export \\\n"
      + "    --snapshot MySnapshot --copy-to hdfs://srv2:8082/hbase \\\n"
      + "    --chuser MyUser --chgroup MyGroup --chmod 700 --mappers 16\n" + "\n"
      + "  hbase snapshot export \\\n"
      + "    --snapshot MySnapshot --copy-from hdfs://srv2:8082/hbase \\\n"
      + "    --copy-to hdfs://srv1:50070/hbase");
  }

  @Override
  protected void addOptions() {
    addRequiredOption(Options.SNAPSHOT);
    addOption(Options.COPY_TO);
    addOption(Options.COPY_FROM);
    addOption(Options.TARGET_NAME);
    addOption(Options.NO_CHECKSUM_VERIFY);
    addOption(Options.NO_TARGET_VERIFY);
    addOption(Options.NO_SOURCE_VERIFY);
    addOption(Options.OVERWRITE);
    addOption(Options.CHUSER);
    addOption(Options.CHGROUP);
    addOption(Options.CHMOD);
    addOption(Options.MAPPERS);
    addOption(Options.BANDWIDTH);
    addOption(Options.RESET_TTL);
    addOption(Options.CUSTOM_FILE_GROUPER);
    addOption(Options.FILE_LOCATION_RESOLVER);
  }

  public static void main(String[] args) {
    new ExportSnapshot().doStaticMain(args);
  }
}
