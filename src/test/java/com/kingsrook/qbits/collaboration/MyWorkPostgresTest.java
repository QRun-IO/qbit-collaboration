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

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import com.kingsrook.qbits.collaboration.mywork.MyWorkService;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.module.postgres.model.metadata.PostgreSQLBackendMetaData;
import com.kingsrook.qqq.backend.module.rdbms.jdbc.ConnectionManager;
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
   }

   /*******************************************************************************
    ** A fixture-owned connection for setup and independent SQL observations.
    *******************************************************************************/
   private Connection connect() throws Exception
   {
      return (DriverManager.getConnection("jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/postgres", "postgres", "mywork-fixture-only"));
   }
}
