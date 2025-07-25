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
package org.apache.iceberg.spark.extensions;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.util.List;
import org.apache.iceberg.ParameterizedTestExtension;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.exceptions.ValidationException;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableList;
import org.apache.spark.sql.AnalysisException;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(ParameterizedTestExtension.class)
public class TestRollbackToSnapshotProcedure extends ExtensionsTestBase {
  @AfterEach
  public void removeTables() {
    sql("DROP TABLE IF EXISTS %s", tableName);
  }

  @TestTemplate
  public void testRollbackToSnapshotUsingPositionalArgs() {
    sql("CREATE TABLE %s (id bigint NOT NULL, data string) USING iceberg", tableName);
    sql("INSERT INTO TABLE %s VALUES (1, 'a')", tableName);

    Table table = validationCatalog.loadTable(tableIdent);
    Snapshot firstSnapshot = table.currentSnapshot();

    sql("INSERT INTO TABLE %s VALUES (1, 'a')", tableName);

    assertEquals(
        "Should have expected rows",
        ImmutableList.of(row(1L, "a"), row(1L, "a")),
        sql("SELECT * FROM %s ORDER BY id", tableName));

    table.refresh();

    Snapshot secondSnapshot = table.currentSnapshot();

    List<Object[]> output =
        sql(
            "CALL %s.system.rollback_to_snapshot('%s', %dL)",
            catalogName, tableIdent, firstSnapshot.snapshotId());

    assertEquals(
        "Procedure output must match",
        ImmutableList.of(row(secondSnapshot.snapshotId(), firstSnapshot.snapshotId())),
        output);

    assertEquals(
        "Rollback must be successful",
        ImmutableList.of(row(1L, "a")),
        sql("SELECT * FROM %s ORDER BY id", tableName));
  }

  @TestTemplate
  public void testRollbackToSnapshotUsingNamedArgs() {
    sql("CREATE TABLE %s (id bigint NOT NULL, data string) USING iceberg", tableName);
    sql("INSERT INTO TABLE %s VALUES (1, 'a')", tableName);

    Table table = validationCatalog.loadTable(tableIdent);
    Snapshot firstSnapshot = table.currentSnapshot();

    sql("INSERT INTO TABLE %s VALUES (1, 'a')", tableName);

    assertEquals(
        "Should have expected rows",
        ImmutableList.of(row(1L, "a"), row(1L, "a")),
        sql("SELECT * FROM %s ORDER BY id", tableName));

    table.refresh();

    Snapshot secondSnapshot = table.currentSnapshot();

    List<Object[]> output =
        sql(
            "CALL %s.system.rollback_to_snapshot(snapshot_id => %dL, table => '%s')",
            catalogName, firstSnapshot.snapshotId(), tableIdent);

    assertEquals(
        "Procedure output must match",
        ImmutableList.of(row(secondSnapshot.snapshotId(), firstSnapshot.snapshotId())),
        output);

    assertEquals(
        "Rollback must be successful",
        ImmutableList.of(row(1L, "a")),
        sql("SELECT * FROM %s ORDER BY id", tableName));
  }

  @TestTemplate
  public void testRollbackToSnapshotRefreshesRelationCache() {
    sql("CREATE TABLE %s (id bigint NOT NULL, data string) USING iceberg", tableName);
    sql("INSERT INTO TABLE %s VALUES (1, 'a')", tableName);

    Table table = validationCatalog.loadTable(tableIdent);
    Snapshot firstSnapshot = table.currentSnapshot();

    sql("INSERT INTO TABLE %s VALUES (1, 'a')", tableName);

    table.refresh();

    Snapshot secondSnapshot = table.currentSnapshot();

    Dataset<Row> query = spark.sql("SELECT * FROM " + tableName + " WHERE id = 1");
    query.createOrReplaceTempView("tmp");

    spark.sql("CACHE TABLE tmp");

    assertEquals(
        "View should have expected rows",
        ImmutableList.of(row(1L, "a"), row(1L, "a")),
        sql("SELECT * FROM tmp"));

    List<Object[]> output =
        sql(
            "CALL %s.system.rollback_to_snapshot(table => '%s', snapshot_id => %dL)",
            catalogName, tableIdent, firstSnapshot.snapshotId());

    assertEquals(
        "Procedure output must match",
        ImmutableList.of(row(secondSnapshot.snapshotId(), firstSnapshot.snapshotId())),
        output);

    assertEquals(
        "View cache must be invalidated", ImmutableList.of(row(1L, "a")), sql("SELECT * FROM tmp"));

    sql("UNCACHE TABLE tmp");
  }

  @TestTemplate
  public void testRollbackToSnapshotWithQuotedIdentifiers() {
    sql("CREATE TABLE %s (id bigint NOT NULL, data string) USING iceberg", tableName);
    sql("INSERT INTO TABLE %s VALUES (1, 'a')", tableName);

    Table table = validationCatalog.loadTable(tableIdent);
    Snapshot firstSnapshot = table.currentSnapshot();

    sql("INSERT INTO TABLE %s VALUES (1, 'a')", tableName);

    assertEquals(
        "Should have expected rows",
        ImmutableList.of(row(1L, "a"), row(1L, "a")),
        sql("SELECT * FROM %s ORDER BY id", tableName));

    table.refresh();

    Snapshot secondSnapshot = table.currentSnapshot();

    StringBuilder quotedNamespaceBuilder = new StringBuilder();
    for (String level : tableIdent.namespace().levels()) {
      quotedNamespaceBuilder.append("`");
      quotedNamespaceBuilder.append(level);
      quotedNamespaceBuilder.append("`");
    }
    String quotedNamespace = quotedNamespaceBuilder.toString();

    List<Object[]> output =
        sql(
            "CALL %s.system.rollback_to_snapshot('%s', %d)",
            catalogName,
            quotedNamespace + ".`" + tableIdent.name() + "`",
            firstSnapshot.snapshotId());

    assertEquals(
        "Procedure output must match",
        ImmutableList.of(row(secondSnapshot.snapshotId(), firstSnapshot.snapshotId())),
        output);

    assertEquals(
        "Rollback must be successful",
        ImmutableList.of(row(1L, "a")),
        sql("SELECT * FROM %s ORDER BY id", tableName));
  }

  @TestTemplate
  public void testRollbackToSnapshotWithoutExplicitCatalog() {
    assumeThat(catalogName).as("Working only with the session catalog").isEqualTo("spark_catalog");

    sql("CREATE TABLE %s (id bigint NOT NULL, data string) USING iceberg", tableName);
    sql("INSERT INTO TABLE %s VALUES (1, 'a')", tableName);

    Table table = validationCatalog.loadTable(tableIdent);
    Snapshot firstSnapshot = table.currentSnapshot();

    sql("INSERT INTO TABLE %s VALUES (1, 'a')", tableName);

    assertEquals(
        "Should have expected rows",
        ImmutableList.of(row(1L, "a"), row(1L, "a")),
        sql("SELECT * FROM %s ORDER BY id", tableName));

    table.refresh();

    Snapshot secondSnapshot = table.currentSnapshot();

    // use camel case intentionally to test case sensitivity
    List<Object[]> output =
        sql("CALL SyStEm.rOLlBaCk_to_SnApShOt('%s', %dL)", tableIdent, firstSnapshot.snapshotId());

    assertEquals(
        "Procedure output must match",
        ImmutableList.of(row(secondSnapshot.snapshotId(), firstSnapshot.snapshotId())),
        output);

    assertEquals(
        "Rollback must be successful",
        ImmutableList.of(row(1L, "a")),
        sql("SELECT * FROM %s ORDER BY id", tableName));
  }

  @TestTemplate
  public void testRollbackToInvalidSnapshot() {
    sql("CREATE TABLE %s (id bigint NOT NULL, data string) USING iceberg", tableName);

    assertThatThrownBy(
            () -> sql("CALL %s.system.rollback_to_snapshot('%s', -1L)", catalogName, tableIdent))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Cannot roll back to unknown snapshot id: -1");
  }

  @TestTemplate
  public void testInvalidRollbackToSnapshotCases() {
    assertThatThrownBy(
            () ->
                sql(
                    "CALL %s.system.rollback_to_snapshot(namespace => 'n1', table => 't', 1L)",
                    catalogName))
        .isInstanceOf(AnalysisException.class)
        .hasMessage(
            "[UNEXPECTED_POSITIONAL_ARGUMENT] Cannot invoke routine `rollback_to_snapshot` because it contains positional argument(s) following the named argument assigned to `table`; please rearrange them so the positional arguments come first and then retry the query again. SQLSTATE: 4274K");

    assertThatThrownBy(() -> sql("CALL %s.custom.rollback_to_snapshot('n', 't', 1L)", catalogName))
        .isInstanceOf(AnalysisException.class)
        .hasMessage(
            "[FAILED_TO_LOAD_ROUTINE] Failed to load routine `%s`.`custom`.`rollback_to_snapshot`. SQLSTATE: 38000",
            catalogName);

    assertThatThrownBy(() -> sql("CALL %s.system.rollback_to_snapshot('t')", catalogName))
        .isInstanceOf(AnalysisException.class)
        .hasMessage(
            "[REQUIRED_PARAMETER_NOT_FOUND] Cannot invoke routine `rollback_to_snapshot` because the parameter named `snapshot_id` is required, but the routine call did not supply a value. Please update the routine call to supply an argument value (either positionally at index 0 or by name) and retry the query again. SQLSTATE: 4274K");

    assertThatThrownBy(() -> sql("CALL %s.system.rollback_to_snapshot(1L)", catalogName))
        .isInstanceOf(AnalysisException.class)
        .hasMessage(
            "[REQUIRED_PARAMETER_NOT_FOUND] Cannot invoke routine `rollback_to_snapshot` because the parameter named `snapshot_id` is required, but the routine call did not supply a value. Please update the routine call to supply an argument value (either positionally at index 0 or by name) and retry the query again. SQLSTATE: 4274K");

    assertThatThrownBy(() -> sql("CALL %s.system.rollback_to_snapshot(table => 't')", catalogName))
        .isInstanceOf(AnalysisException.class)
        .hasMessage(
            "[REQUIRED_PARAMETER_NOT_FOUND] Cannot invoke routine `rollback_to_snapshot` because the parameter named `snapshot_id` is required, but the routine call did not supply a value. Please update the routine call to supply an argument value (either positionally at index 1 or by name) and retry the query again. SQLSTATE: 4274K");

    assertThatThrownBy(() -> sql("CALL %s.system.rollback_to_snapshot('t', '2.2')", catalogName))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith(
            "[CAST_INVALID_INPUT] The value '2.2' of the type \"STRING\" cannot be cast to \"BIGINT\" because it is malformed.");

    assertThatThrownBy(() -> sql("CALL %s.system.rollback_to_snapshot('', 1L)", catalogName))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Cannot handle an empty identifier for argument table");
  }
}
