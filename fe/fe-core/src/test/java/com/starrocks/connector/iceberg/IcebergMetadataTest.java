// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.connector.iceberg;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.Table;
import com.starrocks.common.AlreadyExistsException;
import com.starrocks.common.DdlException;
import com.starrocks.common.MetaNotFoundException;
import com.starrocks.connector.exception.StarRocksConnectorException;
import com.starrocks.connector.iceberg.cost.IcebergMetricsReporter;
import mockit.Expectations;
import mockit.Mocked;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.hive.HiveTableOperations;
import org.apache.iceberg.hive.IcebergHiveCatalog;
import org.apache.thrift.TException;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static com.starrocks.catalog.Table.TableType.ICEBERG;

public class IcebergMetadataTest {
    private static final String CATALOG_NAME = "IcebergCatalog";

    @Test
    public void testListDatabaseNames(@Mocked IcebergCatalog icebergCatalog) {
        new Expectations() {
            {
                icebergCatalog.listAllDatabases();
                result = Lists.newArrayList("db1", "db2");
                minTimes = 0;
            }
        };

        IcebergMetadata metadata = new IcebergMetadata(CATALOG_NAME, icebergCatalog);
        List<String> expectResult = Lists.newArrayList("db1", "db2");
        Assert.assertEquals(expectResult, metadata.listDbNames());
    }

    @Test
    public void testGetDB(@Mocked IcebergHiveCatalog icebergHiveCatalog) throws Exception {
        String db = "db";

        new Expectations() {
            {
                icebergHiveCatalog.getDB(db);
                result = new Database(0, db);
                minTimes = 0;
            }
        };

        IcebergMetadata metadata = new IcebergMetadata(CATALOG_NAME, icebergHiveCatalog);
        Database expectResult = new Database(0, db);
        Assert.assertEquals(expectResult, metadata.getDb(db));
    }


    @Test
    public void testListTableNames(@Mocked IcebergHiveCatalog icebergHiveCatalog) {
        String db1 = "db1";
        String tbl1 = "tbl1";
        String tbl2 = "tbl2";

        new Expectations() {
            {
                icebergHiveCatalog.listTables(Namespace.of(db1));
                result = Lists.newArrayList(TableIdentifier.of(db1, tbl1), TableIdentifier.of(db1, tbl2));
                minTimes = 0;
            }
        };

        IcebergMetadata metadata = new IcebergMetadata(CATALOG_NAME, icebergHiveCatalog);
        List<String> expectResult = Lists.newArrayList("tbl1", "tbl2");
        Assert.assertEquals(expectResult, metadata.listTableNames(db1));
    }

    @Test
    public void testGetTable(@Mocked IcebergHiveCatalog icebergHiveCatalog,
                             @Mocked HiveTableOperations hiveTableOperations) {

        new Expectations() {
            {
                icebergHiveCatalog.loadTable(TableIdentifier.of("db", "tbl"));
                result = new BaseTable(hiveTableOperations, "tbl");
                minTimes = 0;
            }
        };

        IcebergMetadata metadata = new IcebergMetadata(CATALOG_NAME, icebergHiveCatalog);
        Table expectResult = new Table(100000000, "tbl", ICEBERG, new ArrayList<>());
        Table actual = metadata.getTable("db", "tbl");
        Assert.assertEquals(expectResult.getName(), actual.getName());
        Assert.assertEquals(expectResult.getType(), actual.getType());
    }

    @Test
    public void testNotExistTable(@Mocked IcebergHiveCatalog icebergHiveCatalog,
                                  @Mocked HiveTableOperations hiveTableOperations) {
        new Expectations() {
            {
                icebergHiveCatalog.loadTable(TableIdentifier.of("db", "tbl"), (Optional<IcebergMetricsReporter>) any);
                result = new BaseTable(hiveTableOperations, "tbl");
                minTimes = 0;

                icebergHiveCatalog.loadTable(TableIdentifier.of("db", "tbl2"), (Optional<IcebergMetricsReporter>) any);
                result = new StarRocksConnectorException("not found");
            }
        };

        IcebergMetadata metadata = new IcebergMetadata(CATALOG_NAME, icebergHiveCatalog);
        Assert.assertNull(metadata.getTable("db", "tbl2"));
    }

    @Test(expected = AlreadyExistsException.class)
    public void testCreateDuplicatedDb(@Mocked IcebergHiveCatalog icebergHiveCatalog) throws AlreadyExistsException {
        IcebergMetadata metadata = new IcebergMetadata(CATALOG_NAME, icebergHiveCatalog);
        new Expectations() {
            {
                icebergHiveCatalog.listAllDatabases();
                result = Lists.newArrayList("iceberg_db");
                minTimes = 0;
            }
        };

        metadata.createDb("iceberg_db", new HashMap<>());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCreateDbWithErrorConfig() throws AlreadyExistsException {
        IcebergHiveCatalog hiveCatalog = new IcebergHiveCatalog();
        hiveCatalog.initialize("iceberg_catalog", new HashMap<>());
        IcebergMetadata metadata = new IcebergMetadata(CATALOG_NAME, hiveCatalog);

        new Expectations(hiveCatalog) {
            {
                hiveCatalog.listAllDatabases();
                result = Lists.newArrayList();
                minTimes = 0;
            }
        };

        metadata.createDb("iceberg_db", ImmutableMap.of("error_key", "error_value"));
    }

    @Test
    public void testCreateDbInvalidateLocation() {
        IcebergHiveCatalog hiveCatalog = new IcebergHiveCatalog();
        hiveCatalog.initialize("iceberg_catalog", new HashMap<>());
        IcebergMetadata metadata = new IcebergMetadata(CATALOG_NAME, hiveCatalog);

        new Expectations(hiveCatalog) {
            {
                hiveCatalog.listAllDatabases();
                result = Lists.newArrayList();
                minTimes = 0;
            }
        };

        try {
            metadata.createDb("iceberg_db", ImmutableMap.of("location", "hdfs:xx/aaaxx"));
            Assert.fail();
        } catch (Exception e) {
            Assert.assertTrue(e instanceof StarRocksConnectorException);
            Assert.assertTrue(e.getMessage().contains("Invalid location URI"));
        }
    }

    @Test
    public void testNormalCreateDb() throws AlreadyExistsException, DdlException {
        IcebergHiveCatalog hiveCatalog = new IcebergHiveCatalog();
        hiveCatalog.initialize("iceberg_catalog", new HashMap<>());
        IcebergMetadata metadata = new IcebergMetadata(CATALOG_NAME, hiveCatalog);

        new Expectations(hiveCatalog) {
            {
                hiveCatalog.listAllDatabases();
                result = Lists.newArrayList();
                minTimes = 0;

                hiveCatalog.createHiveDatabase((org.apache.hadoop.hive.metastore.api.Database) any);
                result = null;
                minTimes = 0;
            }
        };
        metadata.createDb("iceberg_db");
    }

    @Test
    public void testDropNotEmptyTable() {
        IcebergHiveCatalog icebergHiveCatalog = new IcebergHiveCatalog();
        icebergHiveCatalog.initialize("iceberg_catalog", new HashMap<>());
        IcebergMetadata metadata = new IcebergMetadata(CATALOG_NAME, icebergHiveCatalog);
        List<TableIdentifier> mockTables = new ArrayList<>();
        mockTables.add(TableIdentifier.of("table1"));
        mockTables.add(TableIdentifier.of("table2"));

        new Expectations(icebergHiveCatalog) {
            {
                icebergHiveCatalog.listTables(Namespace.of("iceberg_db"));
                result = mockTables;
                minTimes = 0;
            }
        };

        try {
            metadata.dropDb("iceberg_db", true);
            Assert.fail();
        } catch (Exception e) {
            Assert.assertTrue(e instanceof StarRocksConnectorException);
            Assert.assertTrue(e.getMessage().contains("Database iceberg_db not empty"));
        }
    }

    @Test
    public void testDropDbFailed() throws TException, InterruptedException {
        IcebergHiveCatalog icebergHiveCatalog = new IcebergHiveCatalog();
        icebergHiveCatalog.initialize("iceberg_catalog", new HashMap<>());
        IcebergMetadata metadata = new IcebergMetadata(CATALOG_NAME, icebergHiveCatalog);

        new Expectations(icebergHiveCatalog) {
            {
                icebergHiveCatalog.listTables(Namespace.of("iceberg_db"));
                result = Lists.newArrayList();
                minTimes = 0;
            }
        };

        try {
            metadata.dropDb("iceberg_db", true);
            Assert.fail();
        } catch (Exception e) {
            Assert.assertTrue(e instanceof MetaNotFoundException);
            Assert.assertTrue(e.getMessage().contains("Failed to access database"));
        }

        new Expectations(icebergHiveCatalog) {
            {
                icebergHiveCatalog.getDB("iceberg_db");
                result = null;
                minTimes = 0;
            }
        };

        try {
            metadata.dropDb("iceberg_db", true);
            Assert.fail();
        } catch (Exception e) {
            Assert.assertTrue(e instanceof MetaNotFoundException);
            Assert.assertTrue(e.getMessage().contains("Not found database"));
        }

        new Expectations(icebergHiveCatalog) {
            {
                icebergHiveCatalog.getDB("iceberg_db");
                result = new Database();
                minTimes = 0;
            }
        };

        try {
            metadata.dropDb("iceberg_db", true);
            Assert.fail();
        } catch (Exception e) {
            Assert.assertTrue(e instanceof MetaNotFoundException);
            Assert.assertTrue(e.getMessage().contains("Database location is empty"));
        }
    }

    @Test
    public void testNormalDropDb() throws MetaNotFoundException, TException, InterruptedException {
        IcebergHiveCatalog icebergHiveCatalog = new IcebergHiveCatalog();
        icebergHiveCatalog.initialize("iceberg_catalog", new HashMap<>());
        IcebergMetadata metadata = new IcebergMetadata(CATALOG_NAME, icebergHiveCatalog);

        new Expectations(icebergHiveCatalog) {
            {
                icebergHiveCatalog.listTables(Namespace.of("iceberg_db"));
                result = Lists.newArrayList();
                minTimes = 0;

                icebergHiveCatalog.getDB("iceberg_db");
                result = new Database(1, "db", "hdfs:namenode:9000/user/hive/iceberg_location");
                minTimes = 0;

                icebergHiveCatalog.dropDatabaseInHiveMetastore("iceberg_db");
                result = null;
                minTimes = 0;
            }
        };
        metadata.dropDb("iceberg_db", true);
    }
}