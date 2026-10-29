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
    ** Failed first-page entries must not starve later healthy cleanup requests.
    *******************************************************************************/
   @Test
   void failedBatchDoesNotStarveLaterRequests() throws Exception
   {
      try(Connection connection = connect(); var sql = connection.prepareStatement(
         "INSERT INTO collaboration_attachment_cleanup (attachment_id, bucket_name, storage_key) VALUES (?, 'collaboration', ?)"))
      {
         for(int id = 1; id <= 101; id++)
         {
            String key = "queued/" + id;
            sql.setInt(1, id);
            sql.setString(2, key);
            sql.executeUpdate();
            storage.objects.put("collaboration/" + key, new byte[] { 1 });
            if(id <= 100)
            {
               storage.failedKeys.add(key);
            }
         }
      }
      admin();
      assertThatThrownBy(() -> new AttachmentCleanupQueue(storage).retryPending()).isInstanceOf(Exception.class);
      assertThat(storage.deleteAttempts.get()).isEqualTo(100);
      assertThat(new AttachmentCleanupQueue(storage).retryPending()).isEqualTo(1);
      assertThat(storage.deleteAttempts.get()).isEqualTo(101);
      assertThat(pendingCount()).isEqualTo(100);
      assertThat(storage.objects).doesNotContainKey("collaboration/queued/101");
      storage.failedKeys.clear();
      try(Connection connection = connect(); var sql = connection.createStatement())
      {
         sql.execute("UPDATE collaboration_attachment_cleanup SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second'");
      }
      assertThat(new AttachmentCleanupQueue(storage).retryPending()).isEqualTo(100);
      assertThat(pendingCount()).isZero();
      assertThat(storage.objects).isEmpty();
   }


   /*******************************************************************************
    ** Failed rollback compensation must preserve a durable locator for later cleanup.
    *******************************************************************************/
   @Test
   void failedUploadRollbackCompensationRemainsRetryable() throws Exception
   {
      try(var tx = new RDBMSTransaction(connect()))
      {
         QContext.setTransaction(tx);
         upload();
         storage.failDelete = true;
         tx.rollback();
         QContext.clearTransaction();
      }
      assertThat(rowCount()).isZero();
      assertThat(storage.objects).hasSize(1);
      assertThat(pendingCount()).isEqualTo(1);
      storage.failDelete = false;
      admin();
      assertThat(new AttachmentCleanupQueue(storage).retryPending()).isEqualTo(1);
      assertThat(storage.objects).isEmpty();
      assertThat(pendingCount()).isZero();
   }


   /*******************************************************************************
    ** Upgrading the first queue shape preserves pending deletions and is repeatable.
    *******************************************************************************/
   @Test
   void cleanupSchemaUpgradePreservesPendingRequests() throws Exception
   {
      try(Connection connection = connect(); var sql = connection.createStatement())
      {
         sql.execute("ALTER TABLE collaboration_attachment_cleanup DROP COLUMN next_attempt_at");
         sql.execute("ALTER TABLE collaboration_attachment_cleanup ALTER COLUMN attachment_id SET NOT NULL");
         sql.execute("CREATE UNIQUE INDEX uq_collaboration_attachment_cleanup_1 ON collaboration_attachment_cleanup (attachment_id)");
         sql.execute("INSERT INTO collaboration_attachment_cleanup (attachment_id, bucket_name, storage_key) VALUES (42, 'collaboration', 'pending/42')");
         sql.execute(CollaborationSchema.postgresScript(config));
         sql.execute(CollaborationSchema.postgresScript(config));
         sql.execute("INSERT INTO collaboration_attachment_cleanup (bucket_name, storage_key) VALUES ('collaboration', 'rollback/one'), ('collaboration', 'rollback/two')");
      }
      assertThat(pendingCount()).isEqualTo(3);
   }


   /*******************************************************************************
    ** A provider may store bytes and then fail before acknowledging the upload.
    *******************************************************************************/
   @Test
   void ambiguousUploadFailureRetainsCompensation() throws Exception
   {
      storage.failPutAfterWrite = true;
      storage.failDelete = true;
      assertThatThrownBy(this::upload).isInstanceOf(IllegalStateException.class)
         .hasMessage("injected upload acknowledgement failure");
      assertThat(rowCount()).isZero();
      assertThat(storage.objects).hasSize(1);
      assertThat(pendingCount()).isEqualTo(1);
      storage.failDelete = false;
      admin();
      assertThat(new AttachmentCleanupQueue(storage).retryPending()).isEqualTo(1);
      assertThat(storage.objects).isEmpty();
      assertThat(pendingCount()).isZero();
   }


   /*******************************************************************************
    ** Replay real provider deletes after the database rejects queue acknowledgement.
    *******************************************************************************/
   @Test
   void minioDeletionReplayPreservesOtherObjects() throws Exception
   {
      try(var minio = new GenericContainer<>("cgr.dev/chainguard/minio@sha256:dc455cdd4db6e390b1b26514f15ff37681a777bf8dab41aac445a0259f751e56")
         .withEnv("MINIO_ROOT_USER", "collaboration-test").withEnv("MINIO_ROOT_PASSWORD", "collaboration-test-password")
         .withCommand("server", "/tmp/minio-data").withExposedPorts(9000)
         .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000)))
      {
         minio.start();
         String endpoint = "http://" + minio.getHost() + ":" + minio.getMappedPort(9000);
         try(var client = software.amazon.awssdk.services.s3.S3Client.builder()
            .endpointOverride(java.net.URI.create(endpoint)).region(software.amazon.awssdk.regions.Region.US_EAST_1)
            .forcePathStyle(true).credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
               software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("collaboration-test", "collaboration-test-password"))).build())
         {
            client.createBucket(b -> b.bucket("collaboration-plain"));
            client.createBucket(b -> b.bucket("collaboration-versioned"));
            client.putBucketVersioning(b -> b.bucket("collaboration-versioned").versioningConfiguration(v ->
               v.status(software.amazon.awssdk.services.s3.model.BucketVersioningStatus.ENABLED)));
            System.setProperty("collaboration.minio.access", "collaboration-test");
            System.setProperty("collaboration.minio.secret", "collaboration-test-password");
            var storageConfig = new com.kingsrook.qbits.objectstorage.ObjectStorageQBitConfig()
               .withBackendName("memory")
               .withAllowedSecretRefPatterns(List.of("prop.collaboration.minio.*"))
               .withBucketScoping(false)
               .withConnection(new com.kingsrook.qbits.objectstorage.config.StorageConnectionConfig("fixture")
                  .withEndpoint(endpoint).withRegion("us-east-1")
                  .withAddressingStyle(com.kingsrook.qbits.objectstorage.config.AddressingStyle.PATH)
                  .withChecksumMode(com.kingsrook.qbits.objectstorage.config.ChecksumMode.WHEN_REQUIRED)
                  .withAccessKeyRef("${prop.collaboration.minio.access}").withSecretKeyRef("${prop.collaboration.minio.secret}"));
            for(String alias : List.of("plain", "versioned"))
            {
               storageConfig.withBucket(new com.kingsrook.qbits.objectstorage.config.StorageBucketConfig(alias)
                  .withConnection("fixture").withBucketName("collaboration-" + alias)
                  .withRole(com.kingsrook.qbits.objectstorage.config.BucketRole.PRIVATE).withIndexed(false));
            }
            com.kingsrook.qbits.objectstorage.api.ObjectStorage.configureStandalone(storageConfig);
            for(String alias : List.of("plain", "versioned"))
            {
               config.withAttachmentsBucketName(alias);
               service = new AttachmentsService(config, new AttachmentStorage());
               AttachmentEntry removed = upload();
               AttachmentEntry retained = upload();
               String removedKey = storedKey(removed.id());
               String retainedKey = storedKey(retained.id());
               try(Connection connection = connect(); var sql = connection.createStatement())
               {
                  sql.execute("CREATE OR REPLACE FUNCTION reject_provider_ack() RETURNS trigger LANGUAGE plpgsql AS $$ "
                     + "BEGIN RAISE EXCEPTION 'injected provider acknowledgement rejection'; END $$");
                  sql.execute("CREATE TRIGGER reject_provider_ack BEFORE DELETE ON collaboration_attachment_cleanup "
                     + "FOR EACH ROW EXECUTE FUNCTION reject_provider_ack()");
               }
               assertThatThrownBy(() -> service.delete(List.of(removed.id()))).isInstanceOf(Exception.class);
               assertThat(pendingCount()).isEqualTo(1);
               try(Connection connection = connect(); var sql = connection.createStatement())
               {
                  sql.execute("DROP TRIGGER reject_provider_ack ON collaboration_attachment_cleanup");
               }
               admin();
               assertThat(new AttachmentCleanupQueue(new AttachmentStorage()).retryPending()).isEqualTo(1);
               assertThat(pendingCount()).isZero();
               var bucket = com.kingsrook.qbits.objectstorage.api.ObjectStorage.asSystem("collaboration-test", "replay").bucket(alias);
               assertThat(bucket.head(removedKey)).isEmpty();
               assertThat(bucket.head(retainedKey)).isPresent();
               assertThat(client.getObjectAsBytes(b -> b.bucket("collaboration-" + alias).key(retainedKey)).asByteArray())
                  .containsExactly((byte) 1, (byte) 2, (byte) 3);
               service.delete(List.of(retained.id()));
               login("alice");
            }
         }
      }
      finally
      {
         com.kingsrook.qbits.objectstorage.internal.ObjectStorageRuntime.reset();
         com.kingsrook.qbits.objectstorage.internal.ConfigSync.reset();
         System.clearProperty("collaboration.minio.access");
         System.clearProperty("collaboration.minio.secret");
      }
   }


   /*******************************************************************************
    ** Observe the immutable provider key from committed SQL metadata.
    *******************************************************************************/
   private String storedKey(int attachmentId) throws Exception
   {
      try(Connection connection = connect(); var sql = connection.prepareStatement("SELECT storage_key FROM collaboration_attachment WHERE id = ?"))
      {
         sql.setInt(1, attachmentId);
         try(var rows = sql.executeQuery())
         {
            assertThat(rows.next()).isTrue();
            return (rows.getString(1));
         }
      }
   }


   /*******************************************************************************
    ** Two workers may observe the same committed intent; replay must remain safe.
    *******************************************************************************/
   @Test
   void concurrentWorkersCanReplayTheSameIntent() throws Exception
   {
      AttachmentEntry entry = upload();
      storage.failDelete = true;
      assertThatThrownBy(() -> service.delete(List.of(entry.id()))).isInstanceOf(IllegalStateException.class);
      storage.failDelete = false;
      storage.concurrentDeletes = new java.util.concurrent.CountDownLatch(2);
      admin();
      var session = QContext.getQSession();
      try(var executor = java.util.concurrent.Executors.newFixedThreadPool(2))
      {
         java.util.concurrent.Callable<Integer> retry = () ->
         {
            QContext.init(instance, session);
            try
            {
               return (new AttachmentCleanupQueue(storage).retryPending());
            }
            finally
            {
               QContext.clear();
            }
         };
         var first = executor.submit(retry);
         var second = executor.submit(retry);
         assertThat(first.get(20, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(1);
         assertThat(second.get(20, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(1);
      }
      assertThat(rowCount()).isZero();
      assertThat(pendingCount()).isZero();
      assertThat(storage.objects).isEmpty();
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
      private final Map<String, byte[]> objects = new java.util.concurrent.ConcurrentHashMap<>();
      private boolean failDelete;
      private boolean failPutAfterWrite;
      private final java.util.Set<String> failedKeys = new java.util.HashSet<>();
      private final java.util.concurrent.atomic.AtomicInteger deleteAttempts = new java.util.concurrent.atomic.AtomicInteger();
      private java.util.concurrent.CountDownLatch concurrentDeletes;

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
            if(failPutAfterWrite)
            {
               throw (new IllegalStateException("injected upload acknowledgement failure"));
            }
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
         deleteAttempts.incrementAndGet();
         if(concurrentDeletes != null)
         {
            concurrentDeletes.countDown();
            try
            {
               if(!concurrentDeletes.await(10, java.util.concurrent.TimeUnit.SECONDS))
               {
                  throw (new IllegalStateException("Concurrent cleanup worker did not arrive"));
               }
            }
            catch(InterruptedException e)
            {
               Thread.currentThread().interrupt();
               throw (new IllegalStateException(e));
            }
         }
         if(failDelete || failedKeys.contains(key))
         {
            throw (new IllegalStateException("injected storage delete failure"));
         }
         objects.remove(name + "/" + key);
      }
   }
}
