/*
 * QQQ - Low-code Application Framework for Engineers.
 * Copyright (C) 2021-2025.  Kingsrook, LLC
 * 651 N Broad St Ste 205 # 6917 | Middletown DE 19709 | United States
 * contact@kingsrook.com
 * https://github.com/Kingsrook/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.kingsrook.qbits.collaboration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Arrays;
import java.util.Locale;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import com.kingsrook.qbits.collaboration.mywork.MyWorkService;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.module.postgres.model.metadata.PostgreSQLBackendMetaData;
import com.kingsrook.qqq.backend.module.rdbms.jdbc.ConnectionManager;
import com.kingsrook.qqq.backend.module.rdbms.jdbc.C3P0PooledConnectionProvider;
import com.kingsrook.qqq.backend.module.rdbms.model.metadata.ConnectionPoolSettings;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import static org.assertj.core.api.Assertions.assertThat;

/*******************************************************************************
 ** Real PostgreSQL query counts for the bounded, caller-authorized My Work scan.
 *******************************************************************************/
class MyWorkPostgresTest extends PersonalServiceFixture
{
   private static GenericContainer<?> postgres;

   /*******************************************************************************
    ** Collect SQL counts in an owned disposable database, never an application DB.
    *******************************************************************************/
   @BeforeAll
   static void startPostgres()
   {
      postgres = new GenericContainer<>("postgres:17-alpine")
         .withEnv("POSTGRES_PASSWORD", "mywork-fixture-only").withExposedPorts(5432)
         .withCommand("postgres", "-c", "shared_preload_libraries=pg_stat_statements")
         .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2));
      postgres.start();
   }

   /*******************************************************************************
    ** Stop only the database owned by this class.
    *******************************************************************************/
   @AfterAll
   static void stopPostgres()
   {
      if(postgres != null)
      {
         postgres.stop();
      }
   }

   /*******************************************************************************
    ** Reuse the real authorization fixture with PostgreSQL persistence.
    *******************************************************************************/
   @Override
   @BeforeEach
   public void setUp() throws Exception
   {
      super.setUp();
      instance.getBackends().remove("memory");
      instance.addBackend(new PostgreSQLBackendMetaData().withName("memory").withHostName(postgres.getHost())
         .withPort(postgres.getMappedPort(5432)).withDatabaseName("postgres")
         .withUsername("postgres").withPassword("mywork-fixture-only"));
      var backend = (PostgreSQLBackendMetaData) instance.getBackend("memory");
      backend.setConnectionProvider(new QCodeReference(C3P0PooledConnectionProvider.class));
      backend.setConnectionPoolSettings(new ConnectionPoolSettings().withInitialPoolSize(1).withMinPoolSize(1)
         .withMaxPoolSize(3).withAcquireIncrement(1).withCheckoutTimeoutSeconds(2));
      try(Connection connection = connect(); var sql = connection.createStatement())
      {
         sql.execute("CREATE EXTENSION pg_stat_statements");
         sql.execute(CollaborationSchema.postgresScript(config));
         sql.execute("CREATE TABLE document (id INTEGER PRIMARY KEY, title TEXT, tenant TEXT)");
         sql.execute("INSERT INTO document VALUES (1, 'Visible', 'a'), (3, 'Private', 'b')");
         sql.execute("INSERT INTO collaboration_task (id, table_name, record_id, title, assignee_user_id, created_by_user_id, status, parent_deleted, due_at) "
            + "SELECT n, 'document', CASE WHEN n=601 THEN '3' ELSE '1' END, 'Task ' || n, 'alice', 'alice', 'OPEN', false, "
            + "CASE WHEN n%2=0 THEN TIMESTAMPTZ '2026-10-22 00:00:00Z' + (601-n)*INTERVAL '1 second' ELSE NULL END FROM generate_series(1,601) n");
         sql.execute("SELECT pg_stat_statements_reset()");
      }
   }

   /*******************************************************************************
    ** Release owner pools before container teardown.
    *******************************************************************************/
   @Override
   public void tearDown()
   {
      ConnectionManager.closeConnectionProviders(instance);
      super.tearDown();
   }

   /*******************************************************************************
    ** Exact counts/order and bounded SQL parent/page queries on the real backend.
    *******************************************************************************/
   @Test
   void summaryPreservesVisibilityWithBoundedSqlQueries() throws Exception
   {
      var summary = new MyWorkService().summary();
      assertThat(summary.openTaskCount()).isEqualTo(600);
      assertThat(summary.tasks()).extracting(row -> row.getValueInteger("id"))
         .containsExactly(600, 598, 596, 594, 592, 590, 588, 586, 584, 582);
      try(Connection connection = connect(); var sql = connection.createStatement();
         var rows = sql.executeQuery("SELECT query, calls FROM pg_stat_statements WHERE query ILIKE 'SELECT%'") )
      {
         long parents = 0;
         long tasks = 0;
         while(rows.next())
         {
            String query = rows.getString(1);
            if(query.contains("\"document\""))
            {
               parents += rows.getLong(2);
            }
            if(query.contains("\"collaboration_task\""))
            {
               tasks += rows.getLong(2);
            }
         }
         assertThat(parents).as("SQL parent queries").isBetween(1L, 8L);
         assertThat(tasks).as("SQL task page queries").isEqualTo(5);
      }
      QContext.getQSession().getSecurityKeyValues().put("tenant", new ArrayList<>(List.of("b")));
      assertThat(new MyWorkService().summary().openTaskCount()).isEqualTo(1);
      if(Boolean.getBoolean("qqq.mywork.load"))
      {
         QContext.getQSession().getSecurityKeyValues().put("tenant", new ArrayList<>(List.of("a")));
         measureLoad();
      }
   }

   /*******************************************************************************
    ** Opt-in reproducible SQL work measurement, not a deployment capacity claim.
    *******************************************************************************/
   private void measureLoad() throws Exception
   {
      final int tasks = 2000;
      final int samples = 10;
      List<String> report = new ArrayList<>();
      report.add("tasks,parents,samples,p50_ms,p95_ms,p99_ms,sql_calls,sql_execution_ms");
      for(int parents : List.of(10, tasks))
      {
         try(Connection connection = connect(); var sql = connection.createStatement())
         {
            sql.execute("TRUNCATE collaboration_task, document");
            sql.execute("INSERT INTO document SELECT n, 'Document ' || n, CASE WHEN n%10=0 THEN 'b' ELSE 'a' END FROM generate_series(1," + parents + ") n");
            sql.execute("INSERT INTO collaboration_task (id, table_name, record_id, title, assignee_user_id, created_by_user_id, status, parent_deleted) "
               + "SELECT n, 'document', (((n-1)%" + parents + ")+1)::text, 'Task ' || n, 'alice', 'alice', 'OPEN', false FROM generate_series(1," + tasks + ") n");
         }
         // Warm the JVM and pool without counting these calls in the samples.
         for(int warmup = 0; warmup < 2; warmup++)
         {
            assertThat(new MyWorkService().summary().openTaskCount()).isEqualTo(1800);
         }
         try(Connection connection = connect(); var sql = connection.createStatement())
         {
            sql.execute("SELECT pg_stat_statements_reset()");
         }
         double[] elapsed = new double[samples];
         for(int sample = 0; sample < samples; sample++)
         {
            long start = System.nanoTime();
            var summary = new MyWorkService().summary();
            elapsed[sample] = (System.nanoTime() - start) / 1_000_000.0;
            assertThat(summary.openTaskCount()).isEqualTo(1800);
            assertThat(summary.tasks()).extracting(row -> row.getValueInteger("id"))
               .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 11);
         }
         var backend = (PostgreSQLBackendMetaData) instance.getBackend("memory");
         var pool = ConnectionManager.getPoolMetrics(instance, backend);
         // C3P0 returns connections asynchronously; allow a bounded check-in interval.
         long deadline = System.nanoTime() + 2_000_000_000L;
         while(pool.get("connectionsBusy").intValue() != 0 && System.nanoTime() < deadline)
         {
            Thread.sleep(10);
            pool = ConnectionManager.getPoolMetrics(instance, backend);
         }
         assertThat(pool.get("connectionsTotal").intValue()).isBetween(1, 3);
         assertThat(pool.get("connectionsBusy").intValue()).isZero();
         assertThat(pool.get("checkoutTimeouts").longValue()).isZero();
         Arrays.sort(elapsed);
         try(Connection connection = connect(); var sql = connection.createStatement();
            var rows = sql.executeQuery("SELECT COALESCE(sum(calls),0), COALESCE(sum(total_exec_time),0) FROM pg_stat_statements "
               + "WHERE query ILIKE 'SELECT%' AND (query LIKE '%\"document\"%' OR query LIKE '%\"collaboration_task\"%' "
               + "OR query LIKE '%\"collaboration_record_assignment\"%' OR query LIKE '%\"collaboration_mention\"%')"))
         {
            assertThat(rows.next()).isTrue();
            report.add(String.format(Locale.ROOT, "%d,%d,%d,%.3f,%.3f,%.3f,%d,%.3f", tasks, parents, samples,
               elapsed[4], elapsed[9], elapsed[9], rows.getLong(1), rows.getDouble(2)));
         }
      }
      Path output = Path.of("target/mywork-load.csv");
      Files.createDirectories(output.getParent());
      Files.write(output, report);
      System.out.println("My Work local PostgreSQL workload: " + output.toAbsolutePath());
      report.forEach(System.out::println);
   }

   /*******************************************************************************
    ** A fixture-owned connection for setup and independent SQL observations.
    *******************************************************************************/
   private Connection connect() throws Exception
   {
      return (DriverManager.getConnection("jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/postgres", "postgres", "mywork-fixture-only"));
   }
}
