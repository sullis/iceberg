/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.spark.source;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.BatchScan;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.IncrementalAppendScan;
import org.apache.iceberg.IncrementalChangelogScan;
import org.apache.iceberg.MetadataColumns;
import org.apache.iceberg.MetricsConfig;
import org.apache.iceberg.MetricsModes;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SparkDistributedDataScan;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.expressions.AggregateEvaluator;
import org.apache.iceberg.expressions.Binder;
import org.apache.iceberg.expressions.BoundAggregate;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.expressions.ExpressionUtil;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.metrics.InMemoryMetricsReporter;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableSet;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.relocated.com.google.common.collect.Sets;
import org.apache.iceberg.spark.Spark3Util;
import org.apache.iceberg.spark.SparkAggregates;
import org.apache.iceberg.spark.SparkReadConf;
import org.apache.iceberg.spark.SparkReadOptions;
import org.apache.iceberg.spark.SparkSchemaUtil;
import org.apache.iceberg.spark.SparkV2Filters;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.TypeUtil;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.util.SnapshotUtil;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.connector.expressions.aggregate.AggregateFunc;
import org.apache.spark.sql.connector.expressions.aggregate.Aggregation;
import org.apache.spark.sql.connector.expressions.filter.Predicate;
import org.apache.spark.sql.connector.read.Scan;
import org.apache.spark.sql.connector.read.ScanBuilder;
import org.apache.spark.sql.connector.read.Statistics;
import org.apache.spark.sql.connector.read.SupportsPushDownAggregates;
import org.apache.spark.sql.connector.read.SupportsPushDownRequiredColumns;
import org.apache.spark.sql.connector.read.SupportsPushDownV2Filters;
import org.apache.spark.sql.connector.read.SupportsReportStatistics;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.sql.util.CaseInsensitiveStringMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SparkScanBuilder
    implements ScanBuilder,
        SupportsPushDownAggregates,
        SupportsPushDownV2Filters,
        SupportsPushDownRequiredColumns,
        SupportsReportStatistics {

  private static final Logger LOG = LoggerFactory.getLogger(SparkScanBuilder.class);
  private static final Predicate[] NO_PREDICATES = new Predicate[0];
  private StructType pushedAggregateSchema;
  private Scan localScan;

  private final SparkSession spark;
  private final Table table;
  private final CaseInsensitiveStringMap options;
  private final SparkReadConf readConf;
  private final List<String> metaColumns = Lists.newArrayList();
  private final InMemoryMetricsReporter metricsReporter;

  private Schema schema;
  private boolean caseSensitive;
  private List<Expression> filterExpressions = null;
  private Predicate[] pushedPredicates = NO_PREDICATES;

  SparkScanBuilder(
      SparkSession spark,
      Table table,
      String branch,
      Schema schema,
      CaseInsensitiveStringMap options) {
    this.spark = spark;
    this.table = table;
    this.schema = schema;
    this.options = options;
    this.readConf = new SparkReadConf(spark, table, branch, options);
    this.caseSensitive = readConf.caseSensitive();
    this.metricsReporter = new InMemoryMetricsReporter();
  }

  SparkScanBuilder(SparkSession spark, Table table, CaseInsensitiveStringMap options) {
    this(spark, table, table.schema(), options);
  }

  SparkScanBuilder(
      SparkSession spark, Table table, String branch, CaseInsensitiveStringMap options) {
    this(spark, table, branch, SnapshotUtil.schemaFor(table, branch), options);
  }

  SparkScanBuilder(
      SparkSession spark, Table table, Schema schema, CaseInsensitiveStringMap options) {
    this(spark, table, null, schema, options);
  }

  private Expression filterExpression() {
    if (filterExpressions != null) {
      return filterExpressions.stream().reduce(Expressions.alwaysTrue(), Expressions::and);
    }
    return Expressions.alwaysTrue();
  }

  public SparkScanBuilder caseSensitive(boolean isCaseSensitive) {
    this.caseSensitive = isCaseSensitive;
    return this;
  }

  @Override
  public Predicate[] pushPredicates(Predicate[] predicates) {
    // there are 3 kinds of filters:
    // (1) filters that can be pushed down completely and don't have to evaluated by Spark
    //     (e.g. filters that select entire partitions)
    // (2) filters that can be pushed down partially and require record-level filtering in Spark
    //     (e.g. filters that may select some but not necessarily all rows in a file)
    // (3) filters that can't be pushed down at all and have to be evaluated by Spark
    //     (e.g. unsupported filters)
    // filters (1) and (2) are used prune files during job planning in Iceberg
    // filters (2) and (3) form a set of post scan filters and must be evaluated by Spark

    List<Expression> expressions = Lists.newArrayListWithExpectedSize(predicates.length);
    List<Predicate> pushableFilters = Lists.newArrayListWithExpectedSize(predicates.length);
    List<Predicate> postScanFilters = Lists.newArrayListWithExpectedSize(predicates.length);

    for (Predicate predicate : predicates) {
      try {
        Expression expr = SparkV2Filters.convert(predicate);

        if (expr != null) {
          // try binding the expression to ensure it can be pushed down
          Binder.bind(schema.asStruct(), expr, caseSensitive);
          expressions.add(expr);
          pushableFilters.add(predicate);
        }

        if (expr == null
            || unpartitioned()
            || !ExpressionUtil.selectsPartitions(expr, table, caseSensitive)) {
          postScanFilters.add(predicate);
        } else {
          LOG.info("Evaluating completely on Iceberg side: {}", predicate);
        }

      } catch (Exception e) {
        LOG.warn("Failed to check if {} can be pushed down: {}", predicate, e.getMessage());
        postScanFilters.add(predicate);
      }
    }

    this.filterExpressions = expressions;
    this.pushedPredicates = pushableFilters.toArray(new Predicate[0]);

    return postScanFilters.toArray(new Predicate[0]);
  }

  private boolean unpartitioned() {
    return table.specs().values().stream().noneMatch(PartitionSpec::isPartitioned);
  }

  @Override
  public Predicate[] pushedPredicates() {
    return pushedPredicates;
  }

  @Override
  public boolean pushAggregation(Aggregation aggregation) {
    if (!canPushDownAggregation(aggregation)) {
      return false;
    }

    AggregateEvaluator aggregateEvaluator;
    List<BoundAggregate<?, ?>> expressions =
        Lists.newArrayListWithExpectedSize(aggregation.aggregateExpressions().length);

    for (AggregateFunc aggregateFunc : aggregation.aggregateExpressions()) {
      try {
        Expression expr = SparkAggregates.convert(aggregateFunc);
        if (expr != null) {
          Expression bound = Binder.bind(schema.asStruct(), expr, caseSensitive);
          expressions.add((BoundAggregate<?, ?>) bound);
        } else {
          LOG.info(
              "Skipping aggregate pushdown: AggregateFunc {} can't be converted to iceberg expression",
              aggregateFunc);
          return false;
        }
      } catch (IllegalArgumentException e) {
        LOG.info("Skipping aggregate pushdown: Bind failed for AggregateFunc {}", aggregateFunc, e);
        return false;
      }
    }

    aggregateEvaluator = AggregateEvaluator.create(expressions);

    if (!metricsModeSupportsAggregatePushDown(aggregateEvaluator.aggregates())) {
      return false;
    }

    org.apache.iceberg.Scan scan =
        buildIcebergBatchScan(true /* include Column Stats */, schemaWithMetadataColumns());

    try (CloseableIterable<FileScanTask> fileScanTasks = scan.planFiles()) {
      for (FileScanTask task : fileScanTasks) {
        if (!task.deletes().isEmpty()) {
          LOG.info("Skipping aggregate pushdown: detected row level deletes");
          return false;
        }

        aggregateEvaluator.update(task.file());
      }
    } catch (IOException e) {
      LOG.info("Skipping aggregate pushdown: ", e);
      return false;
    }

    if (!aggregateEvaluator.allAggregatorsValid()) {
      return false;
    }

    pushedAggregateSchema =
        SparkSchemaUtil.convert(new Schema(aggregateEvaluator.resultType().fields()));
    InternalRow[] pushedAggregateRows = new InternalRow[1];
    StructLike structLike = aggregateEvaluator.result();
    pushedAggregateRows[0] =
        new StructInternalRow(aggregateEvaluator.resultType()).setStruct(structLike);
    localScan =
        new SparkLocalScan(table, pushedAggregateSchema, pushedAggregateRows, filterExpressions);

    return true;
  }

  private boolean canPushDownAggregation(Aggregation aggregation) {
    if (!(table instanceof BaseTable)) {
      return false;
    }

    if (!readConf.aggregatePushDownEnabled()) {
      return false;
    }

    // If group by expression is the same as the partition, the statistics information can still
    // be used to calculate min/max/count, will enable aggregate push down in next phase.
    // TODO: enable aggregate push down for partition col group by expression
    if (aggregation.groupByExpressions().length > 0) {
      LOG.info("Skipping aggregate pushdown: group by aggregation push down is not supported");
      return false;
    }

    return true;
  }

  private boolean metricsModeSupportsAggregatePushDown(List<BoundAggregate<?, ?>> aggregates) {
    MetricsConfig config = MetricsConfig.forTable(table);
    for (BoundAggregate aggregate : aggregates) {
      String colName = aggregate.columnName();
      if (!colName.equals("*")) {
        MetricsModes.MetricsMode mode = config.columnMode(colName);
        if (mode instanceof MetricsModes.None) {
          LOG.info("Skipping aggregate pushdown: No metrics for column {}", colName);
          return false;
        } else if (mode instanceof MetricsModes.Counts) {
          if (aggregate.op() == Expression.Operation.MAX
              || aggregate.op() == Expression.Operation.MIN) {
            LOG.info(
                "Skipping aggregate pushdown: Cannot produce min or max from count for column {}",
                colName);
            return false;
          }
        } else if (mode instanceof MetricsModes.Truncate) {
          // lower_bounds and upper_bounds may be truncated, so disable push down
          if (aggregate.type().typeId() == Type.TypeID.STRING) {
            if (aggregate.op() == Expression.Operation.MAX
                || aggregate.op() == Expression.Operation.MIN) {
              LOG.info(
                  "Skipping aggregate pushdown: Cannot produce min or max from truncated values for column {}",
                  colName);
              return false;
            }
          }
        }
      }
    }

    return true;
  }

  @Override
  public void pruneColumns(StructType requestedSchema) {
    StructType requestedProjection =
        new StructType(
            Stream.of(requestedSchema.fields())
                .filter(field -> MetadataColumns.nonMetadataColumn(field.name()))
                .toArray(StructField[]::new));

    // the projection should include all columns that will be returned, including those only used in
    // filters
    this.schema =
        SparkSchemaUtil.prune(schema, requestedProjection, filterExpression(), caseSensitive);

    Stream.of(requestedSchema.fields())
        .map(StructField::name)
        .filter(MetadataColumns::isMetadataColumn)
        .distinct()
        .forEach(metaColumns::add);
  }

  private Schema schemaWithMetadataColumns() {
    // metadata columns
    List<Types.NestedField> metadataFields =
        metaColumns.stream()
            .distinct()
            .map(name -> MetadataColumns.metadataColumn(table, name))
            .collect(Collectors.toList());
    Schema metadataSchema = calculateMetadataSchema(metadataFields);

    // schema or rows returned by readers
    return TypeUtil.join(schema, metadataSchema);
  }

  private Schema calculateMetadataSchema(List<Types.NestedField> metaColumnFields) {
    Optional<Types.NestedField> partitionField =
        metaColumnFields.stream()
            .filter(f -> MetadataColumns.PARTITION_COLUMN_ID == f.fieldId())
            .findFirst();

    // only calculate potential column id collision if partition metadata column was requested
    if (!partitionField.isPresent()) {
      return new Schema(metaColumnFields);
    }

    Set<Integer> idsToReassign =
        TypeUtil.indexById(partitionField.get().type().asStructType()).keySet();

    // Calculate used ids by union metadata columns with all base table schemas
    Set<Integer> currentlyUsedIds =
        metaColumnFields.stream().map(Types.NestedField::fieldId).collect(Collectors.toSet());
    Set<Integer> allUsedIds =
        table.schemas().values().stream()
            .map(currSchema -> TypeUtil.indexById(currSchema.asStruct()).keySet())
            .reduce(currentlyUsedIds, Sets::union);

    // Reassign selected ids to deduplicate with used ids.
    AtomicInteger nextId = new AtomicInteger();
    return new Schema(
        metaColumnFields,
        ImmutableSet.of(),
        oldId -> {
          if (!idsToReassign.contains(oldId)) {
            return oldId;
          }
          int candidate = nextId.incrementAndGet();
          while (allUsedIds.contains(candidate)) {
            candidate = nextId.incrementAndGet();
          }
          return candidate;
        });
  }

  @Override
  public Scan build() {
    if (localScan != null) {
      return localScan;
    } else {
      return buildBatchScan();
    }
  }

  private Scan buildBatchScan() {
    Schema expectedSchema = schemaWithMetadataColumns();
    return new SparkBatchQueryScan(
        spark,
        table,
        buildIcebergBatchScan(false /* not include Column Stats */, expectedSchema),
        readConf,
        expectedSchema,
        filterExpressions,
        metricsReporter::scanReport);
  }

  private org.apache.iceberg.Scan buildIcebergBatchScan(boolean withStats, Schema expectedSchema) {
    Long snapshotId = readConf.snapshotId();
    Long asOfTimestamp = readConf.asOfTimestamp();
    String branch = readConf.branch();
    String tag = readConf.tag();

    Preconditions.checkArgument(
        snapshotId == null || asOfTimestamp == null,
        "Cannot set both %s and %s to select which table snapshot to scan",
        SparkReadOptions.SNAPSHOT_ID,
        SparkReadOptions.AS_OF_TIMESTAMP);

    Long startSnapshotId = readConf.startSnapshotId();
    Long endSnapshotId = readConf.endSnapshotId();

    if (snapshotId != null || asOfTimestamp != null) {
      Preconditions.checkArgument(
          startSnapshotId == null && endSnapshotId == null,
          "Cannot set %s and %s for incremental scans when either %s or %s is set",
          SparkReadOptions.START_SNAPSHOT_ID,
          SparkReadOptions.END_SNAPSHOT_ID,
          SparkReadOptions.SNAPSHOT_ID,
          SparkReadOptions.AS_OF_TIMESTAMP);
    }

    Preconditions.checkArgument(
        startSnapshotId != null || endSnapshotId == null,
        "Cannot set only %s for incremental scans. Please, set %s too.",
        SparkReadOptions.END_SNAPSHOT_ID,
        SparkReadOptions.START_SNAPSHOT_ID);

    Long startTimestamp = readConf.startTimestamp();
    Long endTimestamp = readConf.endTimestamp();
    Preconditions.checkArgument(
        startTimestamp == null && endTimestamp == null,
        "Cannot set %s or %s for incremental scans and batch scan. They are only valid for "
            + "changelog scans.",
        SparkReadOptions.START_TIMESTAMP,
        SparkReadOptions.END_TIMESTAMP);

    if (startSnapshotId != null) {
      return buildIncrementalAppendScan(startSnapshotId, endSnapshotId, withStats, expectedSchema);
    } else {
      return buildBatchScan(snapshotId, asOfTimestamp, branch, tag, withStats, expectedSchema);
    }
  }

  private org.apache.iceberg.Scan buildBatchScan(
      Long snapshotId,
      Long asOfTimestamp,
      String branch,
      String tag,
      boolean withStats,
      Schema expectedSchema) {
    BatchScan scan =
        newBatchScan()
            .caseSensitive(caseSensitive)
            .filter(filterExpression())
            .project(expectedSchema)
            .metricsReporter(metricsReporter);

    if (withStats) {
      scan = scan.includeColumnStats();
    }

    if (snapshotId != null) {
      scan = scan.useSnapshot(snapshotId);
    }

    if (asOfTimestamp != null) {
      scan = scan.asOfTime(asOfTimestamp);
    }

    if (branch != null) {
      scan = scan.useRef(branch);
    }

    if (tag != null) {
      scan = scan.useRef(tag);
    }

    return configureSplitPlanning(scan);
  }

  private org.apache.iceberg.Scan buildIncrementalAppendScan(
      long startSnapshotId, Long endSnapshotId, boolean withStats, Schema expectedSchema) {
    IncrementalAppendScan scan =
        table
            .newIncrementalAppendScan()
            .fromSnapshotExclusive(startSnapshotId)
            .caseSensitive(caseSensitive)
            .filter(filterExpression())
            .project(expectedSchema)
            .metricsReporter(metricsReporter);

    if (withStats) {
      scan = scan.includeColumnStats();
    }

    if (endSnapshotId != null) {
      scan = scan.toSnapshot(endSnapshotId);
    }

    return configureSplitPlanning(scan);
  }

  @SuppressWarnings("CyclomaticComplexity")
  public Scan buildChangelogScan() {
    Preconditions.checkArgument(
        readConf.snapshotId() == null
            && readConf.asOfTimestamp() == null
            && readConf.branch() == null
            && readConf.tag() == null,
        "Cannot set neither %s, %s, %s and %s for changelogs",
        SparkReadOptions.SNAPSHOT_ID,
        SparkReadOptions.AS_OF_TIMESTAMP,
        SparkReadOptions.BRANCH,
        SparkReadOptions.TAG);

    Long startSnapshotId = readConf.startSnapshotId();
    Long endSnapshotId = readConf.endSnapshotId();
    Long startTimestamp = readConf.startTimestamp();
    Long endTimestamp = readConf.endTimestamp();

    Preconditions.checkArgument(
        !(startSnapshotId != null && startTimestamp != null),
        "Cannot set both %s and %s for changelogs",
        SparkReadOptions.START_SNAPSHOT_ID,
        SparkReadOptions.START_TIMESTAMP);

    Preconditions.checkArgument(
        !(endSnapshotId != null && endTimestamp != null),
        "Cannot set both %s and %s for changelogs",
        SparkReadOptions.END_SNAPSHOT_ID,
        SparkReadOptions.END_TIMESTAMP);

    if (startTimestamp != null && endTimestamp != null) {
      Preconditions.checkArgument(
          startTimestamp < endTimestamp,
          "Cannot set %s to be greater than %s for changelogs",
          SparkReadOptions.START_TIMESTAMP,
          SparkReadOptions.END_TIMESTAMP);
    }

    boolean emptyScan = false;
    if (startTimestamp != null) {
      if (table.currentSnapshot() == null
          || startTimestamp > table.currentSnapshot().timestampMillis()) {
        emptyScan = true;
      }
      startSnapshotId = getStartSnapshotId(startTimestamp);
    }

    if (endTimestamp != null) {
      endSnapshotId = getEndSnapshotId(endTimestamp);
      if ((startSnapshotId == null && endSnapshotId == null)
          || (startSnapshotId != null && startSnapshotId.equals(endSnapshotId))) {
        emptyScan = true;
      }
    }

    Schema expectedSchema = schemaWithMetadataColumns();

    IncrementalChangelogScan scan =
        table
            .newIncrementalChangelogScan()
            .caseSensitive(caseSensitive)
            .filter(filterExpression())
            .project(expectedSchema)
            .metricsReporter(metricsReporter);

    if (startSnapshotId != null) {
      scan = scan.fromSnapshotExclusive(startSnapshotId);
    }

    if (endSnapshotId != null) {
      scan = scan.toSnapshot(endSnapshotId);
    }

    scan = configureSplitPlanning(scan);

    return new SparkChangelogScan(
        spark, table, scan, readConf, expectedSchema, filterExpressions, emptyScan);
  }

  private Long getStartSnapshotId(Long startTimestamp) {
    Snapshot oldestSnapshotAfter = SnapshotUtil.oldestAncestorAfter(table, startTimestamp);

    if (oldestSnapshotAfter == null) {
      return null;
    } else if (oldestSnapshotAfter.timestampMillis() == startTimestamp) {
      return oldestSnapshotAfter.snapshotId();
    } else {
      return oldestSnapshotAfter.parentId();
    }
  }

  private Long getEndSnapshotId(Long endTimestamp) {
    Long endSnapshotId = null;
    for (Snapshot snapshot : SnapshotUtil.currentAncestors(table)) {
      if (snapshot.timestampMillis() <= endTimestamp) {
        endSnapshotId = snapshot.snapshotId();
        break;
      }
    }
    return endSnapshotId;
  }

  public Scan buildMergeOnReadScan() {
    Preconditions.checkArgument(
        readConf.snapshotId() == null && readConf.asOfTimestamp() == null && readConf.tag() == null,
        "Cannot set time travel options %s, %s, %s for row-level command scans",
        SparkReadOptions.SNAPSHOT_ID,
        SparkReadOptions.AS_OF_TIMESTAMP,
        SparkReadOptions.TAG);

    Preconditions.checkArgument(
        readConf.startSnapshotId() == null && readConf.endSnapshotId() == null,
        "Cannot set incremental scan options %s and %s for row-level command scans",
        SparkReadOptions.START_SNAPSHOT_ID,
        SparkReadOptions.END_SNAPSHOT_ID);

    Snapshot snapshot = SnapshotUtil.latestSnapshot(table, readConf.branch());

    if (snapshot == null) {
      return new SparkBatchQueryScan(
          spark,
          table,
          null,
          readConf,
          schemaWithMetadataColumns(),
          filterExpressions,
          metricsReporter::scanReport);
    }

    // remember the current snapshot ID for commit validation
    long snapshotId = snapshot.snapshotId();

    CaseInsensitiveStringMap adjustedOptions =
        Spark3Util.setOption(SparkReadOptions.SNAPSHOT_ID, Long.toString(snapshotId), options);
    SparkReadConf adjustedReadConf =
        new SparkReadConf(spark, table, readConf.branch(), adjustedOptions);

    Schema expectedSchema = schemaWithMetadataColumns();

    BatchScan scan =
        newBatchScan()
            .useSnapshot(snapshotId)
            .caseSensitive(caseSensitive)
            .filter(filterExpression())
            .project(expectedSchema)
            .metricsReporter(metricsReporter);

    scan = configureSplitPlanning(scan);

    return new SparkBatchQueryScan(
        spark,
        table,
        scan,
        adjustedReadConf,
        expectedSchema,
        filterExpressions,
        metricsReporter::scanReport);
  }

  public Scan buildCopyOnWriteScan() {
    Snapshot snapshot = SnapshotUtil.latestSnapshot(table, readConf.branch());

    if (snapshot == null) {
      return new SparkCopyOnWriteScan(
          spark,
          table,
          readConf,
          schemaWithMetadataColumns(),
          filterExpressions,
          metricsReporter::scanReport);
    }

    Schema expectedSchema = schemaWithMetadataColumns();

    BatchScan scan =
        table
            .newBatchScan()
            .useSnapshot(snapshot.snapshotId())
            .ignoreResiduals()
            .caseSensitive(caseSensitive)
            .filter(filterExpression())
            .project(expectedSchema)
            .metricsReporter(metricsReporter);

    scan = configureSplitPlanning(scan);

    return new SparkCopyOnWriteScan(
        spark,
        table,
        scan,
        snapshot,
        readConf,
        expectedSchema,
        filterExpressions,
        metricsReporter::scanReport);
  }

  private <T extends org.apache.iceberg.Scan<T, ?, ?>> T configureSplitPlanning(T scan) {
    T configuredScan = scan;

    Long splitSize = readConf.splitSizeOption();
    if (splitSize != null) {
      configuredScan = configuredScan.option(TableProperties.SPLIT_SIZE, String.valueOf(splitSize));
    }

    Integer splitLookback = readConf.splitLookbackOption();
    if (splitLookback != null) {
      configuredScan =
          configuredScan.option(TableProperties.SPLIT_LOOKBACK, String.valueOf(splitLookback));
    }

    Long splitOpenFileCost = readConf.splitOpenFileCostOption();
    if (splitOpenFileCost != null) {
      configuredScan =
          configuredScan.option(
              TableProperties.SPLIT_OPEN_FILE_COST, String.valueOf(splitOpenFileCost));
    }

    return configuredScan;
  }

  @Override
  public Statistics estimateStatistics() {
    return ((SupportsReportStatistics) build()).estimateStatistics();
  }

  @Override
  public StructType readSchema() {
    return build().readSchema();
  }

  private BatchScan newBatchScan() {
    if (table instanceof BaseTable && readConf.distributedPlanningEnabled()) {
      return new SparkDistributedDataScan(spark, table, readConf);
    } else {
      return table.newBatchScan();
    }
  }
}
