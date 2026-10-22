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

package com.kingsrook.qbits.collaboration.attachments;


import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.collaboration.CollaborationSchema;
import com.kingsrook.qbits.collaboration.PersonalServiceFixture;
import com.kingsrook.qbits.objectstorage.api.PutRequest;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.module.postgres.model.metadata.PostgreSQLBackendMetaData;
import com.kingsrook.qqq.backend.module.rdbms.actions.RDBMSTransaction;
import com.kingsrook.qqq.backend.module.rdbms.jdbc.ConnectionManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** Real PostgreSQL transaction boundaries with deterministic storage fault injection.
 *******************************************************************************/
class AttachmentTransactionsPostgresTest extends PersonalServiceFixture
{
   private static GenericContainer<?> postgres;
   private RecordingStorage storage;
   private AttachmentsService service;


   /*******************************************************************************
    ** An isolated database container is required; never use an application database.
    *******************************************************************************/
   @BeforeAll
   static void startPostgres()
   {
      postgres = new GenericContainer<>("postgres:17-alpine")
         .withEnv("POSTGRES_PASSWORD", "attachment-fixture-only").withExposedPorts(5432)
         .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2));
      postgres.start();
   }


   /*******************************************************************************
    ** Stop only the database owned by this fixture.
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
    ** Reuse the authorization fixture, replacing its backend with real PostgreSQL.
    *******************************************************************************/
   @Override
   @BeforeEach
   public void setUp() throws Exception
   {
      super.setUp();
      instance.getBackends().remove("memory");
      instance.addBackend(new PostgreSQLBackendMetaData().withName("memory").withHostName(postgres.getHost())
         .withPort(postgres.getMappedPort(5432)).withDatabaseName("postgres")
         .withUsername("postgres").withPassword("attachment-fixture-only"));
      try(Connection connection = connect(); var sql = connection.createStatement())
      {
         sql.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public");
         sql.execute(CollaborationSchema.postgresScript(config));
         sql.execute("CREATE TABLE document (id INTEGER PRIMARY KEY, title TEXT, tenant TEXT)");
         sql.execute("INSERT INTO document VALUES (1, 'Visible', 'a'), (3, 'Private', 'b')");
      }
      storage = new RecordingStorage();
      service = new AttachmentsService(config, storage);
   }


   /*******************************************************************************
    ** Close owner connections before the isolated schema is replaced.
    *******************************************************************************/
   @Override
   public void tearDown()
   {
      ConnectionManager.closeConnectionProviders(instance);
      super.tearDown();
   }


   /*******************************************************************************
    ** Later outer rollback removes both the SQL row and the uploaded object.
    *******************************************************************************/
   @Test
   void uploadOuterRollbackCompensatesObject() throws Exception
   {
      try(var tx = new RDBMSTransaction(connect()))
      {
         QContext.setTransaction(tx);
         upload();
         assertThat(storage.objects).hasSize(1);
         tx.rollback();
         QContext.clearTransaction();
      }
      assertThat(rowCount()).isZero();
      assertThat(storage.objects).isEmpty();
   }


   /*******************************************************************************
    ** A rejected outer transaction cannot delete bytes from an existing attachment.
    *******************************************************************************/
   @Test
   void deleteOuterRollbackPreservesAttachment() throws Exception
   {
      AttachmentEntry entry = upload();
      try(var tx = new RDBMSTransaction(connect()))
      {
         QContext.setTransaction(tx);
         service.delete(List.of(entry.id()));
         tx.rollback();
         QContext.clearTransaction();
      }
      assertThat(rowCount()).isEqualTo(1);
      assertThat(storage.objects).hasSize(1);
      assertThat(pendingCount()).isZero();
   }


   /*******************************************************************************
    ** A committed deletion removes metadata and bytes after the transaction commits.
    *******************************************************************************/
   @Test
   void deleteCommitRemovesAttachment() throws Exception
   {
      AttachmentEntry entry = upload();
      try(var tx = new RDBMSTransaction(connect()))
      {
         QContext.setTransaction(tx);
         service.delete(List.of(entry.id()));
         assertThat(storage.objects).hasSize(1);
         tx.commit();
         QContext.clearTransaction();
      }
      assertThat(rowCount()).isZero();
      assertThat(storage.objects).isEmpty();
   }


   /*******************************************************************************
    ** Database rejection must not leave a visible row whose bytes were already deleted.
    *******************************************************************************/
   @Test
   void metadataDeleteFailurePreservesDownloadableObject() throws Exception
   {
      AttachmentEntry entry = upload();
      try(Connection connection = connect(); var sql = connection.createStatement())
      {
         sql.execute("CREATE FUNCTION reject_attachment_delete() RETURNS trigger LANGUAGE plpgsql AS $$ "
            + "BEGIN RAISE EXCEPTION 'injected metadata delete rejection'; END $$");
         sql.execute("CREATE TRIGGER reject_attachment_delete BEFORE DELETE ON collaboration_attachment "
            + "FOR EACH ROW EXECUTE FUNCTION reject_attachment_delete()");
      }
      assertThatThrownBy(() -> service.delete(List.of(entry.id()))).isInstanceOf(Exception.class);
      assertThat(rowCount()).isEqualTo(1);
      assertThat(storage.objects).as("a retained visible attachment must retain its bytes").hasSize(1);
      assertThat(pendingCount()).isZero();
   }


   /*******************************************************************************
    ** A provider failure leaves a durable locator; a new worker can retry after restart.
    *******************************************************************************/
   @Test
   void storageDeleteFailureRetainsIntentAndRetrySucceeds() throws Exception
   {
      AttachmentEntry entry = upload();
      storage.failDelete = true;
      assertThatThrownBy(() -> service.delete(List.of(entry.id()))).isInstanceOf(IllegalStateException.class);
      assertThat(rowCount()).isZero();
      assertThat(pendingCount()).isEqualTo(1);
      assertThat(storage.objects).hasSize(1);
      storage.failDelete = false;
      assertThatThrownBy(() -> new AttachmentCleanupQueue(storage).retryPending())
         .isInstanceOf(com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException.class);
      admin();
      assertThat(new AttachmentCleanupQueue(storage).retryPending()).isEqualTo(1);
      assertThat(rowCount()).isZero();
      assertThat(pendingCount()).isZero();
      assertThat(storage.objects).isEmpty();
   }


   /*******************************************************************************
    ** A database commit survives loss of every in-process post-commit callback.
    *******************************************************************************/
   @Test
   void committedIntentSurvivesLostCallback() throws Exception
   {
      AttachmentEntry entry = upload();
      try(var tx = new RDBMSTransaction(connect())
      {
         /***********************************************************************
          ** Simulate process loss after commit, before any callback can execute.
          ***********************************************************************/
         @Override
         public void addAfterCommitCallback(Runnable callback)
         {
         }
      })
      {
         QContext.setTransaction(tx);
         service.delete(List.of(entry.id()));
         tx.commit();
         QContext.clearTransaction();
      }
      assertThat(rowCount()).isZero();
      assertThat(pendingCount()).isEqualTo(1);
      assertThat(storage.objects).hasSize(1);
      admin();
      assertThat(new AttachmentCleanupQueue(storage).retryPending()).isEqualTo(1);
      assertThat(storage.objects).isEmpty();
      assertThat(pendingCount()).isZero();
   }


   /*******************************************************************************
    ** Losing queue acknowledgement after deleting bytes must remain replayable.
    *******************************************************************************/
   @Test
   void queueAcknowledgementFailureIsReplayable() throws Exception
   {
      AttachmentEntry entry = upload();
      try(Connection connection = connect(); var sql = connection.createStatement())
      {
         sql.execute("CREATE FUNCTION reject_cleanup_ack() RETURNS trigger LANGUAGE plpgsql AS $$ "
            + "BEGIN RAISE EXCEPTION 'injected cleanup acknowledgement rejection'; END $$");
         sql.execute("CREATE TRIGGER reject_cleanup_ack BEFORE DELETE ON collaboration_attachment_cleanup "
            + "FOR EACH ROW EXECUTE FUNCTION reject_cleanup_ack()");
      }
      assertThatThrownBy(() -> service.delete(List.of(entry.id()))).isInstanceOf(Exception.class);
      assertThat(rowCount()).isZero();
      assertThat(storage.objects).isEmpty();
      assertThat(pendingCount()).isEqualTo(1);
      try(Connection connection = connect(); var sql = connection.createStatement())
      {
         sql.execute("DROP TRIGGER reject_cleanup_ack ON collaboration_attachment_cleanup");
      }
      admin();
      assertThat(new AttachmentCleanupQueue(storage).retryPending()).isEqualTo(1);
      assertThat(new AttachmentCleanupQueue(storage).retryPending()).isZero();
      assertThat(pendingCount()).isZero();
   }


   /*******************************************************************************
    ** Queue observations use independent committed database state.
    *******************************************************************************/
   private int pendingCount() throws Exception
   {
      try(Connection connection = connect(); var sql = connection.createStatement();
         var rows = sql.executeQuery("SELECT COUNT(*) FROM collaboration_attachment_cleanup"))
      {
         rows.next();
         return (rows.getInt(1));
      }
   }


   /*******************************************************************************
    ** Recovery locations are admin-only and cannot be forged through table writes.
    *******************************************************************************/
   @Test
   void cleanupQueueIsServiceManaged() throws Exception
   {
      var query = new com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput(
         com.kingsrook.qbits.collaboration.model.CollaborationTables.ATTACHMENT_CLEANUP);
      assertThatThrownBy(() -> new com.kingsrook.qqq.backend.core.actions.tables.QueryAction().execute(query))
         .isInstanceOf(com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException.class);
      admin();
      assertThat(new com.kingsrook.qqq.backend.core.actions.tables.QueryAction().execute(query).getRecords()).isEmpty();
      var insert = new com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput(
         com.kingsrook.qbits.collaboration.model.CollaborationTables.ATTACHMENT_CLEANUP)
         .withRecords(List.of(new com.kingsrook.qqq.backend.core.model.data.QRecord()));
      assertThatThrownBy(() -> new com.kingsrook.qqq.backend.core.actions.tables.InsertAction().execute(insert))
         .isInstanceOf(com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException.class);
   }


   /*******************************************************************************
    ** Use the actual service and its caller's record permissions.
    *******************************************************************************/
   private AttachmentEntry upload() throws Exception
   {
      return (service.upload("document", 1, "hello.txt", "text/plain", new ByteArrayInputStream(new byte[] { 1, 2, 3 }), null));
   }


   /*******************************************************************************
    ** Independent JDBC observation, outside the service transaction.
    *******************************************************************************/
   private int rowCount() throws Exception
   {
      try(Connection connection = connect(); var sql = connection.createStatement();
         var rows = sql.executeQuery("SELECT COUNT(*) FROM collaboration_attachment"))
      {
         rows.next();
         return (rows.getInt(1));
      }
   }


   /*******************************************************************************
    ** Connect only to this disposable fixture's mapped port.
    *******************************************************************************/
   private static Connection connect() throws Exception
   {
      return (DriverManager.getConnection("jdbc:postgresql://" + postgres.getHost() + ":"
         + postgres.getMappedPort(5432) + "/postgres", "postgres", "attachment-fixture-only"));
   }


   /*******************************************************************************
    ** Preserve bytes and inject provider failure independently of PostgreSQL.
    *******************************************************************************/
   private static class RecordingStorage extends AttachmentStorage
   {
      private final Map<String, byte[]> objects = new HashMap<>();
      private boolean failDelete;

      /***************************************************************************
       ** Keep the production service's upload bound active.
       ***************************************************************************/
      @Override
      public long maxBytes(String name)
      {
         return (1024L);
      }

      /***************************************************************************
       ** Consume the same upload stream a real provider receives.
       ***************************************************************************/
      @Override
      public void put(String name, PutRequest request)
      {
         try
         {
            objects.put(name + "/" + request.getKey(), request.getStream().readAllBytes());
         }
         catch(IOException e)
         {
            throw (new IllegalStateException(e));
         }
      }

      /***************************************************************************
       ** Simulate a provider refusing deletion without losing stored bytes.
       ***************************************************************************/
      @Override
      public void delete(String name, String key)
      {
         if(failDelete)
         {
            throw (new IllegalStateException("injected storage delete failure"));
         }
         objects.remove(name + "/" + key);
      }
   }
}
