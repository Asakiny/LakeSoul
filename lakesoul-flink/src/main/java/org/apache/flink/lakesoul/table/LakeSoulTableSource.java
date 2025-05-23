// SPDX-FileCopyrightText: 2023 LakeSoul Contributors
//
// SPDX-License-Identifier: Apache-2.0

package org.apache.flink.lakesoul.table;

import com.dmetasoul.lakesoul.meta.DBManager;
import com.dmetasoul.lakesoul.meta.DBUtil;
import com.dmetasoul.lakesoul.meta.entity.JniWrapper;
import com.dmetasoul.lakesoul.meta.entity.PartitionInfo;
import com.dmetasoul.lakesoul.meta.entity.TableInfo;
import io.substrait.expression.Expression;
import io.substrait.proto.Plan;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.lakesoul.source.LakeSoulRowDataSource;
import org.apache.flink.lakesoul.substrait.SubstraitFlinkUtil;
import org.apache.flink.lakesoul.tool.LakeSoulSinkOptions;
import org.apache.flink.lakesoul.types.TableId;
import org.apache.flink.table.connector.ChangelogMode;
import org.apache.flink.table.connector.RowLevelModificationScanContext;
import org.apache.flink.table.connector.source.DynamicTableSource;
import org.apache.flink.table.connector.source.ScanTableSource;
import org.apache.flink.table.connector.source.SourceProvider;
import org.apache.flink.table.connector.source.abilities.SupportsFilterPushDown;
import org.apache.flink.table.connector.source.abilities.SupportsLimitPushDown;
import org.apache.flink.table.connector.source.abilities.SupportsProjectionPushDown;
import org.apache.flink.table.connector.source.abilities.SupportsRowLevelModificationScan;
import org.apache.flink.table.expressions.ResolvedExpression;
import org.apache.flink.table.runtime.arrow.ArrowUtils;
import org.apache.flink.table.types.logical.LogicalType;
import org.apache.flink.table.types.logical.RowType;
import org.apache.flink.table.types.logical.VarCharType;
import org.apache.flink.types.RowKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.dmetasoul.lakesoul.lakesoul.io.substrait.SubstraitUtil.applyPartitionFilters;
import static com.dmetasoul.lakesoul.lakesoul.io.substrait.SubstraitUtil.not;
import static com.dmetasoul.lakesoul.lakesoul.io.substrait.SubstraitUtil.substraitExprToProto;
import static com.dmetasoul.lakesoul.meta.DBConfig.LAKESOUL_RANGE_PARTITION_SPLITTER;

public class LakeSoulTableSource
        implements SupportsFilterPushDown, SupportsProjectionPushDown, ScanTableSource,
        SupportsRowLevelModificationScan, SupportsLimitPushDown {

    private static final Logger LOG = LoggerFactory.getLogger(LakeSoulTableSource.class);

    // NOTE: if adding fields in this class, do remember to add assignments in copy methods
    // of both this class and its subclass.

    protected TableId tableId;

    protected RowType tableRowType;

    protected boolean isBounded;

    protected List<String> pkColumns;

    protected List<String> partitionColumns;

    protected int[][] projectedFields;

    protected Map<String, String> optionParams;

    protected List<Map<String, String>> remainingPartitions;

    protected Plan pushedFilters;
    protected LakeSoulRowLevelModificationScanContext modificationContext;
    protected Plan partitionFilters;


    public LakeSoulTableSource(TableId tableId,
                               RowType rowType,
                               boolean isStreaming,
                               List<String> pkColumns,
                               List<String> partitionColumns,
                               Map<String, String> optionParams) {
        this.tableId = tableId;
        this.tableRowType = rowType;
        this.isBounded = isStreaming;
        this.pkColumns = pkColumns;
        this.partitionColumns = partitionColumns;
        this.optionParams = optionParams;
        this.modificationContext = null;
    }
    public TableId getTableId(){
        return this.tableId;
    }
    @Override
    public DynamicTableSource copy() {
        LakeSoulTableSource newInstance = new LakeSoulTableSource(this.tableId,
                this.tableRowType,
                this.isBounded,
                this.pkColumns,
                this.partitionColumns,
                this.optionParams);
        newInstance.projectedFields = this.projectedFields;
        newInstance.remainingPartitions = this.remainingPartitions;
        newInstance.pushedFilters = this.pushedFilters;
        return newInstance;
    }

    @Override
    public String asSummaryString() {
        return toString();
    }

    @Override
    public Result applyFilters(List<ResolvedExpression> filters) {
        // first we filter out partition filter conditions
        LOG.info("Applying filters to native io: {}, table {}", filters, tableId);
        long startTime = System.currentTimeMillis();
        List<ResolvedExpression> completePartitionFilters = new ArrayList<>();
        List<ResolvedExpression> nonPartitionFilters = new ArrayList<>();
        DBManager dbManager = new DBManager();
        TableInfo tableInfo =
                dbManager.getTableInfoByNameAndNamespace(tableId.table(), tableId.schema());

        DBUtil.TablePartitionKeys partitionKeys = DBUtil.parseTableInfoPartitions(tableInfo.getPartitions());
        Set<String> partitionCols = new LinkedHashSet<>(partitionKeys.rangeKeys);
        for (ResolvedExpression filter : filters) {
            if (SubstraitFlinkUtil.filterAllPartitionColumn(filter, partitionCols)) {
                completePartitionFilters.add(filter);
            } else {
                nonPartitionFilters.add(filter);
            }
        }
        LOG.info("completePartitionFilters: {}, table {}", completePartitionFilters, tableId);
        LOG.info("nonPartitionFilters: {}, table {}", nonPartitionFilters, tableId);

        if (!completePartitionFilters.isEmpty()) {
            Tuple2<Result, Expression> substraitPartitionExpr = SubstraitFlinkUtil.flinkExprToSubStraitExpr(
                    completePartitionFilters
            );
            Expression partitionFilter = substraitPartitionExpr.f1;
            if (isDelete()) {
                partitionFilter = not(partitionFilter);
            }
            this.partitionFilters = substraitExprToProto(partitionFilter, tableInfo.getTableName());
            List<PartitionInfo> remainingPartitionInfo = Collections.emptyList();
            Map<String, String> remainingPartitionKeys =
                    SubstraitFlinkUtil.equalityFilterFieldNames(completePartitionFilters);
            if (remainingPartitionKeys.size() == completePartitionFilters.size()) {
                // all partition filters are equality literal filter
                if (partitionKeys.rangeKeys.stream().allMatch(remainingPartitionKeys::containsKey)) {
                    // 1. equality partition filters contains all partition cols
                    // for this case we directly get one partition by partition_info's primary key
                    String partitionDesc = partitionKeys.rangeKeys.stream()
                            .map(rangeCol -> {
                                String value = remainingPartitionKeys.get(rangeCol);
                                return rangeCol + "=" + value;
                            })
                            .collect(Collectors.joining(LAKESOUL_RANGE_PARTITION_SPLITTER));
                    LOG.info("Apply all equality partition filter with partitionDesc: {}, table {}", partitionDesc, tableId);
                    List<PartitionInfo> pInfo = dbManager.getOnePartition(tableInfo.getTableId(), partitionDesc);
                    if (pInfo != null && !pInfo.isEmpty()) {
                        remainingPartitionInfo = pInfo;
                    }
                } else {
                    // construct ts_query
                    String paritionQuery = remainingPartitionKeys.entrySet().stream()
                                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                            .collect(Collectors.joining(" & "));
                    LOG.info("Apply partial equality partition filter with query: {}, table {}", paritionQuery, tableId);
                    remainingPartitionInfo = dbManager.getPartitionInfosByPartialFilter(tableInfo.getTableId(), paritionQuery);
                }
            } else {
                List<String> allPartitionDesc = dbManager.getTableAllPartitionDesc(tableInfo.getTableId());
                List<PartitionInfo> allPartitionInfo = allPartitionDesc.stream().map(
                                desc -> PartitionInfo.newBuilder()
                                        .setPartitionDesc(desc)
                                        .build())
                        .collect(Collectors.toList());
                Schema tableSchema = ArrowUtils.toArrowSchema(tableRowType);
                List<Field>
                        partitionFields =
                        partitionColumns.stream().map(tableSchema::findField).collect(Collectors.toList());

                Schema partitionSchema = new Schema(partitionFields);
                remainingPartitionInfo =
                        applyPartitionFilters(allPartitionInfo, partitionSchema, this.partitionFilters);
            }
            remainingPartitions = partitionInfoToPartitionMap(remainingPartitionInfo);
            setModificationContextSourcePartitions(
                    JniWrapper.newBuilder().addAllPartitionInfo(remainingPartitionInfo).build());
            setModificationContextPartitionFilter(this.partitionFilters);
        }
        long endTime = System.currentTimeMillis();
        LOG.info("Finished applying filters for table {}, time {}ms", tableId, endTime - startTime);

        if (isBounded) {
            // pushdown all filters for batch scan
            // find acceptable non partition filters
            Tuple2<Result, Expression> pushDownResultAndSubstraitExpr = SubstraitFlinkUtil.flinkExprToSubStraitExpr(
                    nonPartitionFilters
            );

            this.pushedFilters = substraitExprToProto(pushDownResultAndSubstraitExpr.f1, tableInfo.getTableName());
            setModificationContextNonPartitionFilter(this.pushedFilters);

            // add accepted non-partition filters to completePartitionFilters as all accepted filters
            completePartitionFilters.addAll(pushDownResultAndSubstraitExpr.f0.getAcceptedFilters());
            LOG.info("Applied filters to native io in batch mode: {}, accepted {}, remaining {}, table {}", this.pushedFilters,
                    completePartitionFilters,
                    pushDownResultAndSubstraitExpr.f0.getRemainingFilters(), tableId);

            return Result.of(completePartitionFilters, pushDownResultAndSubstraitExpr.f0.getRemainingFilters());
        } else {
            // for streaming scan, we cannot pushdown any non-complete-partition filters
            LOG.info("Applied filters to native io in streaming mode: {}, accepted {}, remaining {}, table {}", this.pushedFilters,
                    completePartitionFilters,
                    nonPartitionFilters, tableId);
            return Result.of(completePartitionFilters, nonPartitionFilters);
        }
    }

    private boolean isDelete() {
        LakeSoulRowLevelModificationScanContext context = getModificationContext();
        return context != null && context.isDelete();
    }

    private void setModificationContextPartitionFilter(Plan filter) {
        if (modificationContext != null) {
            modificationContext.setPartitionFilters(filter);
        }
    }

    private void setModificationContextNonPartitionFilter(Plan filter) {
        if (modificationContext != null) {
            modificationContext.setNonPartitionFilters(filter);
        }
    }

    private void setModificationContextSourcePartitions(JniWrapper sourcePartitions) {
        if (modificationContext != null) {
            modificationContext.setSourcePartitionInfo(sourcePartitions);
        }
    }

    private List<Map<String, String>> partitionInfoToPartitionMap(List<PartitionInfo> partitionInfoList) {
        List<Map<String, String>> partitions = new ArrayList<>();
        for (PartitionInfo info : partitionInfoList) {
            String partitionDesc = info.getPartitionDesc();
            partitions.add(DBUtil.parsePartitionDesc(partitionDesc));
        }
        return partitions;
    }

    @Override
    public boolean supportsNestedProjection() {
        return false;
    }

    @Override
    public void applyProjection(int[][] projectedFields) {
        this.projectedFields = projectedFields;
    }

    private List<PartitionInfo> listPartitionInfo() {
        DBManager dbManager = new DBManager();
        TableInfo tableInfo =
                dbManager.getTableInfoByNameAndNamespace(tableId.table(), tableId.schema());
        return dbManager.getAllPartitionInfo(tableInfo.getTableId());
    }

    private int[] getFieldIndexs() {
        return (projectedFields == null || projectedFields.length == 0) ?
                IntStream.range(0, this.tableRowType.getFieldCount()).toArray() :
                Arrays.stream(projectedFields).mapToInt(array -> array[0]).toArray();
    }

    protected RowType readFields() {
        int[] fieldIndexs = getFieldIndexs();
        return RowType.of(Arrays.stream(fieldIndexs).mapToObj(this.tableRowType::getTypeAt).toArray(LogicalType[]::new),
                Arrays.stream(fieldIndexs).mapToObj(this.tableRowType.getFieldNames()::get).toArray(String[]::new));
    }

    private RowType readFieldsAddPk(String cdcColumn) {
        int[] fieldIndexs = getFieldIndexs();
        List<LogicalType> projectTypes =
                Arrays.stream(fieldIndexs).mapToObj(this.tableRowType::getTypeAt).collect(Collectors.toList());
        List<String> projectNames =
                Arrays.stream(fieldIndexs).mapToObj(this.tableRowType.getFieldNames()::get)
                        .collect(Collectors.toList());
        List<String> pkNamesNotExistInReadFields = new ArrayList<>();
        List<LogicalType> pkTypesNotExistInReadFields = new ArrayList<>();
        for (String pk : pkColumns) {
            if (!projectNames.contains(pk)) {
                pkNamesNotExistInReadFields.add(pk);
                pkTypesNotExistInReadFields.add(this.tableRowType.getTypeAt(tableRowType.getFieldIndex(pk)));
            }
        }
        projectNames.addAll(pkNamesNotExistInReadFields);
        projectTypes.addAll(pkTypesNotExistInReadFields);
        if (!cdcColumn.equals("") && !projectNames.contains(cdcColumn)) {
            projectNames.add(cdcColumn);
            projectTypes.add(new VarCharType());
        }
        return RowType.of(projectTypes.toArray(new LogicalType[0]),
                projectNames.toArray(new String[0]));
    }

    @Override
    public ChangelogMode getChangelogMode() {
        boolean isCdc = !optionParams.getOrDefault(LakeSoulSinkOptions.CDC_CHANGE_COLUMN, "").isEmpty();
        if (!this.isBounded && isCdc) {
            return ChangelogMode.upsert();
        } else if (!this.isBounded && !this.pkColumns.isEmpty()) {
            return ChangelogMode.newBuilder()
                    .addContainedKind(RowKind.INSERT)
                    .addContainedKind(RowKind.UPDATE_AFTER)
                    .build();
        } else {
            // batch read or streaming read without pk
            return ChangelogMode.insertOnly();
        }
    }

    @Override
    public ScanRuntimeProvider getScanRuntimeProvider(ScanContext scanContext) {
        String cdcColumn = optionParams.getOrDefault(LakeSoulSinkOptions.CDC_CHANGE_COLUMN,
                "");

        return SourceProvider.of(
                new LakeSoulRowDataSource(
                        this.tableId,
                        this.tableRowType,
                        readFields(),
                        readFieldsAddPk(cdcColumn),
                        this.isBounded,
                        this.pkColumns,
                        this.partitionColumns,
                        this.optionParams,
                        this.remainingPartitions,
                        this.pushedFilters,
                        this.partitionFilters
                ));
    }

    @Override
    public String toString() {
        return "LakeSoulTableSource{" +
                "tableId=" + tableId +
                ", tableRowType=" + tableRowType +
                ", isBounded=" + isBounded +
                ", pkColumns=" + pkColumns +
                ", partitionColumns=" + partitionColumns +
                ", projectedFields=" + Arrays.toString(projectedFields) +
                ", optionParams=" + optionParams +
                ", remainingPartitions=" + remainingPartitions +
                ", pushedFilters=" + pushedFilters +
                ", modificationContext=" + modificationContext +
                ", partitionFilters=" + partitionFilters +
                '}';
    }

    @Override
    public RowLevelModificationScanContext applyRowLevelModificationScan(
            RowLevelModificationType rowLevelModificationType,
            @Nullable
            RowLevelModificationScanContext previousContext) {
        if (previousContext == null || previousContext instanceof LakeSoulRowLevelModificationScanContext) {
            this.modificationContext =
                    new LakeSoulRowLevelModificationScanContext(rowLevelModificationType, listPartitionInfo());

            return modificationContext;
        }
        throw new RuntimeException(
                "LakeSoulTableSource.applyRowLevelModificationScan only supports LakeSoulRowLevelModificationScanContext");
    }

    public LakeSoulRowLevelModificationScanContext getModificationContext() {
        return modificationContext;
    }

    @Override
    public void applyLimit(long limit) {
        this.optionParams.put(LakeSoulSinkOptions.LIMIT.key(),String.valueOf(limit));
    }
}
