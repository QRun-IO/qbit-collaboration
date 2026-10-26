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
import java.util.Set;
import com.kingsrook.qbits.collaboration.customfields.CustomFieldValueProvider;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QVirtualFieldMetaData;
import com.kingsrook.qqq.backend.module.postgres.model.metadata.PostgreSQLBackendMetaData;
import com.kingsrook.qqq.backend.module.rdbms.actions.RDBMSTransaction;
import com.kingsrook.qqq.backend.module.rdbms.jdbc.ConnectionManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import static org.assertj.core.api.Assertions.assertThat;

/*******************************************************************************
 ** Verify provider reads using a real database and explicit caller transactions.
 *******************************************************************************/
class CustomFieldValuesPostgresTest extends PersonalServiceFixture
{
   private static GenericContainer<?> postgres;

   /*******************************************************************************
    ** Own an isolated database; never connect to an application database.
    *******************************************************************************/
   @BeforeAll
   static void startPostgres()
   {
      postgres = new GenericContainer<>("postgres:17-alpine").withEnv("POSTGRES_PASSWORD", "eav-fixture-only")
         .withExposedPorts(5432).waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2));
      postgres.start();
   }

   /*******************************************************************************
    ** Stop only the fixture's database.
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
    ** Reuse the secured host metadata with real PostgreSQL storage.
    *******************************************************************************/
   @Override
   @BeforeEach
   public void setUp() throws Exception
   {
      super.setUp();
      instance.getBackends().remove("memory");
      instance.addBackend(new PostgreSQLBackendMetaData().withName("memory").withHostName(postgres.getHost())
         .withPort(postgres.getMappedPort(5432)).withDatabaseName("postgres").withUsername("postgres").withPassword("eav-fixture-only"));
      try(Connection connection = connect(); var sql = connection.createStatement())
      {
         sql.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public");
         sql.execute(CollaborationSchema.postgresScript(config));
         sql.execute("CREATE TABLE document (id INTEGER PRIMARY KEY, title TEXT, tenant TEXT)");
         sql.execute("INSERT INTO document VALUES (1, 'Visible', 'a'), (3, 'Private', 'b')");
      }
      CollaborationRecords.insert(CollaborationTables.FIELD_DEFINITION, new QRecord().withValue("tableName", "document")
         .withValue("name", "rating").withValue("label", "Rating").withValue("type", "INTEGER").withValue("status", "ACTIVE"));
      instance.getTable("document").withVirtualField(new QVirtualFieldMetaData("cf_rating", QFieldType.INTEGER).withLabel("Rating")
         .withIsQuerySelectable(true).withValueProvider(new QCodeReference(CustomFieldValueProvider.class)));
   }

   /*******************************************************************************
    ** Release transaction state and pooled connections before parent fixture cleanup.
    *******************************************************************************/
   @Override
   public void tearDown()
   {
      QContext.clearTransaction();
      ConnectionManager.closeConnectionProviders(instance);
      super.tearDown();
   }

   /*******************************************************************************
    ** A separate connection establishes committed visibility independently.
    *******************************************************************************/
   private Connection connect() throws Exception
   {
      return (DriverManager.getConnection("jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432)
         + "/postgres", "postgres", "eav-fixture-only"));
   }

   /*******************************************************************************
    ** Both vocabulary and assignments use the explicit transaction's visibility.
    *******************************************************************************/
   @ParameterizedTest
   @ValueSource(booleans = {false, true})
   void tagLabelsSeePendingVocabularyAndCompletion(boolean commit) throws Exception
   {
      instance.getTable("document").withVirtualField(new QVirtualFieldMetaData("collaborationTags", QFieldType.STRING).withLabel("Tags")
         .withIsQuerySelectable(true).withPossibleValueSourceName(CollaborationTables.TAG).withShouldTranslatePossibleValues(false)
         .withValueProvider(new QCodeReference(com.kingsrook.qbits.collaboration.tags.TagValueProvider.class)));
      var tags = new com.kingsrook.qbits.collaboration.tags.TagsService();
      try(var transaction = new RDBMSTransaction(connect()))
      {
         QContext.setTransaction(transaction);
         var tag = tags.create("document", "Pending transaction label", null);
         tags.apply("document", 1, java.util.List.of(tag.id()), java.util.List.of());
         QContext.clearTransaction();
         assertThat(QContext.getQBackendTransaction()).isNull();
         var pending = new QueryAction().execute(new QueryInput("document").withFieldNamesToInclude(Set.of("collaborationTags"))
            .withShouldTranslatePossibleValues(true).withShouldGenerateDisplayValues(true).withTransaction(transaction)).getRecords();
         assertThat(pending).hasSize(1);
         assertThat(pending.getFirst().getValues()).containsOnlyKeys("collaborationTags").containsEntry("collaborationTags", "Pending transaction label");
         assertThat(pending.getFirst().getDisplayValue("collaborationTags")).isEqualTo("Pending transaction label");
         assertThat(new QueryAction().execute(new QueryInput("document")).getRecords().getFirst().getValue("collaborationTags")).isNull();
         if(commit)
         {
            transaction.commit();
         }
         else
         {
            transaction.rollback();
         }
      }
      assertThat(new QueryAction().execute(new QueryInput("document")).getRecords().getFirst().getValue("collaborationTags"))
         .isEqualTo(commit ? "Pending transaction label" : null);
   }

   /*******************************************************************************
    ** Explicit query transactions see pending values even without thread context.
    *******************************************************************************/
   @ParameterizedTest
   @ValueSource(booleans = {false, true})
   void explicitTransactionSeesPendingValueAndCompletion(boolean commit) throws Exception
   {
      try(var transaction = new RDBMSTransaction(connect()))
      {
         QContext.setTransaction(transaction);
         CollaborationRecords.insert(CollaborationTables.FIELD_VALUE, new QRecord().withValue("tableName", "document").withValue("recordId", "1")
            .withValue("fieldName", "rating").withValue("valueNumber", 42).withValue("parentDeleted", false));
         QContext.clearTransaction();
         assertThat(QContext.getQBackendTransaction()).isNull();
         var pending = new QueryAction().execute(new QueryInput("document").withFieldNamesToInclude(Set.of("cf_rating"))
            .withTransaction(transaction)).getRecords();
         assertThat(pending).hasSize(1);
         assertThat(pending.getFirst().getValues()).containsOnlyKeys("cf_rating").containsEntry("cf_rating", 42);
         assertThat(new QueryAction().execute(new QueryInput("document")).getRecords().getFirst().getValue("cf_rating")).isNull();
         if(commit)
         {
            transaction.commit();
         }
         else
         {
            transaction.rollback();
         }
      }
      assertThat(new QueryAction().execute(new QueryInput("document")).getRecords().getFirst().getValue("cf_rating")).isEqualTo(commit ? 42 : null);
   }
}
