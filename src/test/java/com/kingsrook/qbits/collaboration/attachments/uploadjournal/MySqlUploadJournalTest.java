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


package com.kingsrook.qbits.collaboration.attachments.uploadjournal;


import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import com.github.dockerjava.api.model.ExposedPort;
import com.kingsrook.qbits.collaboration.attachments.uploadjournal.UploadJournalException.Reason;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qqq.backend.core.actions.QBackendTransaction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.metadata.QAuthenticationType;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.authentication.QAuthenticationMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.model.session.QUser;
import com.kingsrook.qqq.backend.module.rdbms.RDBMSBackendModule;
import com.kingsrook.qqq.backend.module.rdbms.actions.RDBMSTransaction;
import com.kingsrook.qqq.backend.module.rdbms.jdbc.ConnectionManager;
import com.kingsrook.qqq.backend.module.rdbms.model.metadata.RDBMSBackendMetaData;
import com.kingsrook.qqq.backend.module.rdbms.model.metadata.RDBMSTableBackendDetails;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.config.Configurator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.extension.TestWatcher;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** Explicit opt-in real MySQL 8.4 qualification. The container and anonymous data
 ** volume belong only to this suite; no existing server, database or credentials
 ** are used. The attachment row is a minimal stand-in, not a full intake fixture.
 *******************************************************************************/
@EnabledIfSystemProperty(named = "mysqlJournalTests", matches = "true")
class MySqlUploadJournalTest
{
   private static final String IMAGE = "mysql@sha256:a0225d1ea6631c8bc2385dc4c2faba17bbf1834ecf369e1659a9a382bd8d5dd7";
   private static final Instant START = Instant.parse("2026-10-30T12:00:00.123Z");
   private static MySQLContainer mysql;
   private static int currentPort;
   private static final Map<String, Level> LOG_LEVELS = new HashMap<>();
   private RDBMSBackendMetaData backend;
   private QInstance instance;
   private JdbcUploadJournal journal;

   @RegisterExtension
   static final TestWatcher FAILURE_DIAGNOSTICS = new TestWatcher()
   {
      /*******************************************************************************
       ** Capture only bounded health/counts after failure, before container teardown.
       *******************************************************************************/
      @Override
      public void testFailed(ExtensionContext context, Throwable cause)
      {
         diagnoseFailure(cause);
      }
   };


   /*******************************************************************************
    ** Never log exception messages, SQL, credentials, row values or session IDs.
    ** Independent diagnostic deadlines do not alter application/test deadlines.
    *******************************************************************************/
   private static void diagnoseFailure(Throwable failure)
   {
      diagnosticCause(failure);
      try
      {
         var inspection = mysql.getDockerClient().inspectContainerCmd(mysql.getContainerId()).exec();
         var state = inspection.getState();
         System.out.println("MYSQL_FIXTURE_HEALTH running=" + state.getRunning() + " oomKilled=" + state.getOOMKilled()
            + " exitCode=" + state.getExitCodeLong() + " restarts=" + inspection.getRestartCount());
      }
      catch(Exception unavailable)
      {
         diagnosticCause(unavailable);
      }
      try(Connection connection = DriverManager.getConnection("jdbc:mysql://" + mysql.getHost() + ":" + currentPort + "/" + mysql.getDatabaseName()
         + "?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=1000&socketTimeout=1000", mysql.getUsername(), mysql.getPassword());
         Statement statement = connection.createStatement())
      {
         statement.setQueryTimeout(1);
         try(ResultSet result = statement.executeQuery("SELECT 1"))
         {
            System.out.println("MYSQL_FIXTURE_SELECT_ONE success=" + (result.next() && result.getInt(1) == 1));
         }
         try(ResultSet result = statement.executeQuery("SELECT COUNT(*), SUM(COMMAND = 'Sleep') FROM information_schema.PROCESSLIST"))
         {
            if(result.next())
            {
               System.out.println("MYSQL_FIXTURE_CONNECTIONS total=" + result.getLong(1) + " sleeping=" + result.getLong(2));
            }
         }
         try(ResultSet result = statement.executeQuery("SELECT COUNT(*), SUM(TRX_STATE = 'LOCK WAIT') FROM information_schema.INNODB_TRX"))
         {
            if(result.next())
            {
               System.out.println("MYSQL_FIXTURE_TRANSACTIONS total=" + result.getLong(1) + " lockWaiting=" + result.getLong(2));
            }
         }
         try(ResultSet result = statement.executeQuery("SELECT COUNT(*), SUM(LOCK_STATUS = 'PENDING') FROM performance_schema.metadata_locks"))
         {
            if(result.next())
            {
               System.out.println("MYSQL_FIXTURE_METADATA_LOCKS total=" + result.getLong(1) + " pending=" + result.getLong(2));
            }
         }
      }
      catch(Exception unavailable)
      {
         diagnosticCause(unavailable);
      }
   }


   /*******************************************************************************
    ** A bounded class/state chain is safe even when driver messages contain secrets.
    *******************************************************************************/
   private static void diagnosticCause(Throwable failure)
   {
      for(int depth = 0; failure != null && depth < 12; depth++, failure = failure.getCause())
      {
         String detail = "";
         if(failure instanceof SQLException sql)
         {
            String state = sql.getSQLState();
            detail = " state=" + (state != null && state.matches("[A-Z0-9]{5}") ? state : "unknown") + " code=" + sql.getErrorCode();
         }
         System.out.println("MYSQL_FIXTURE_FAILURE class=" + failure.getClass().getName() + detail);
      }
   }



   /*******************************************************************************
    ** Start only the pinned disposable provider, with a random synthetic password.
    *******************************************************************************/
   @BeforeAll
   static void start()
   {
      for(String logger : List.of("org.apache.hc", "org.apache.http", "org.testcontainers", "com.github.dockerjava"))
      {
         LOG_LEVELS.put(logger, LogManager.getLogger(logger).getLevel());
         Configurator.setLevel(logger, Level.WARN);
      }
      mysql = new MySQLContainer(DockerImageName.parse(IMAGE)).withDatabaseName("journal_test").withUsername("root")
         .withPassword(UUID.randomUUID().toString()).withCommand("--innodb-flush-log-at-trx-commit=1", "--sync-binlog=1", "--innodb-lock-wait-timeout=5");
      mysql.start();
      currentPort = mysql.getMappedPort(3306);
   }


   /*******************************************************************************
    ** Destroy only the provider created by this suite and its private volume.
    *******************************************************************************/
   @AfterAll
   static void stop()
   {
      if(mysql != null)
      {
         mysql.stop();
      }
      LOG_LEVELS.forEach(Configurator::setLevel);
   }


   /*******************************************************************************
    ** Exercise native URL construction, rather than configuring a custom provider.
    *******************************************************************************/
   @BeforeEach
   void setUp() throws Exception
   {
      ConnectionManager.resetConnectionProviders();
      backend = new RDBMSBackendMetaData().withName("mysql_journal").withVendor("mysql").withHostName(mysql.getHost())
         .withPort(currentPort).withDatabaseName(mysql.getDatabaseName()).withUsername(mysql.getUsername()).withPassword(mysql.getPassword());
      instance = new QInstance().withInstanceDefaultAuthentication(new QAuthenticationMetaData().withName("anonymous").withType(QAuthenticationType.FULLY_ANONYMOUS));
      instance.addBackend(backend);
      for(String name : List.of(UploadJournalTable.NAME, CollaborationTables.ATTACHMENT))
      {
         instance.addTable(new QTableMetaData().withName(name).withBackendName(backend.getName()).withPrimaryKeyField("id")
            .withField(new QFieldMetaData("id", QFieldType.INTEGER)));
      }
      context();
      execute("DROP TABLE IF EXISTS collaboration_upload_journal");
      execute("DROP TABLE IF EXISTS collaboration_attachment");
      execute(UploadJournalTable.mysqlScript());
      execute("CREATE TABLE collaboration_attachment (id INTEGER AUTO_INCREMENT PRIMARY KEY, file_name VARCHAR(255)) ENGINE=InnoDB");
      journal = new JdbcUploadJournal(Clock.fixed(START, ZoneOffset.UTC));
   }


   /*******************************************************************************
    ** No connection or thread-local authority survives a fixture.
    *******************************************************************************/
   @AfterEach
   void close() throws Exception
   {
      QContext.clearTransaction();
      QContext.clear();
      ConnectionManager.resetConnectionProviders();
   }


   /*******************************************************************************
    ** Acknowledged metadata survives a real database process restart, not merely
    ** an open pooled connection. The same candidate replays its original row.
    *******************************************************************************/
   @Test
   void committedCandidateSurvivesDatabaseRestart() throws Exception
   {
      UploadEntry original = journal.prepare(candidate("restart"));
      ConnectionManager.resetConnectionProviders();
      mysql.getDockerClient().restartContainerCmd(mysql.getContainerId()).exec();
      currentPort = Integer.parseInt(mysql.getCurrentContainerInfo().getNetworkSettings().getPorts().getBindings().get(new ExposedPort(3306))[0].getHostPortSpec());
      backend.setPort(currentPort);
      long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
      boolean ready = false;
      while(System.nanoTime() < deadline)
      {
         try(Connection connection = connection(); Statement statement = connection.createStatement())
         {
            statement.execute("SELECT 1");
            ready = true;
            break;
         }
         catch(SQLException transientStartup)
         {
            Thread.sleep(250);
         }
      }
      assertThat(ready).isTrue();
      assertThat(journal.prepare(candidate("restart")).id()).isEqualTo(original.id());
      assertThat(count("collaboration_upload_journal")).isEqualTo(1);
   }


   /*******************************************************************************
    ** A registrar failure rolls back both its attachment and the owner checkpoint.
    *******************************************************************************/
   @Test
   void registrationAndCheckpointCommitOrRollbackTogether() throws Exception
   {
      UploadReservation reservation = verified("rollback");
      assertThatThrownBy(() -> journal.register(reservation, (transaction, candidate, id) ->
      {
         insertAttachment(transaction);
         throw (new QException("Synthetic registrar failure."));
      })).isInstanceOf(QException.class);
      assertThat(count("collaboration_attachment")).isZero();
      assertThat(journal.find(reservation.journalId()).orElseThrow().state()).isEqualTo(UploadJournalState.PREPARED);
      UploadEntry registered = journal.register(reservation, (transaction, candidate, id) -> insertAttachment(transaction));
      assertThat(registered.state()).isEqualTo(UploadJournalState.REGISTERED);
      assertThat(journal.complete(reservation).state()).isEqualTo(UploadJournalState.COMPLETE);
      assertThat(journal.complete(reservation).attachmentId()).isEqualTo(registered.attachmentId());
      assertThat(count("collaboration_attachment")).isEqualTo(1);
   }


   /*******************************************************************************
    ** Unique preparation and exclusive leases hold across real simultaneous JDBC
    ** connections. A new fence rejects the old holder after expiry.
    *******************************************************************************/
   @Test
   void concurrentPrepareAndReservationFence() throws Exception
   {
      try(var executor = Executors.newFixedThreadPool(4))
      {
         CountDownLatch start = new CountDownLatch(1);
         List<Future<UploadEntry>> prepares = new ArrayList<>();
         for(int i = 0; i < 4; i++)
         {
            prepares.add(executor.submit(() ->
            {
               context();
               start.await();
               return (journal.prepare(candidate("race")));
            }));
         }
         start.countDown();
         Integer id = prepares.getFirst().get(30, TimeUnit.SECONDS).id();
         for(Future<UploadEntry> prepared : prepares)
         {
            assertThat(prepared.get(30, TimeUnit.SECONDS).id()).isEqualTo(id);
         }
         assertThat(count("collaboration_upload_journal")).isEqualTo(1);
         List<Future<Object>> reservations = new ArrayList<>();
         CountDownLatch reserveStart = new CountDownLatch(1);
         for(int i = 0; i < 4; i++)
         {
            reservations.add(executor.submit(() ->
            {
               context();
               reserveStart.await();
               try
               {
                  return (journal.reserve(id, Duration.ofSeconds(5)));
               }
               catch(UploadJournalException denied)
               {
                  return (denied.getReason());
               }
            }));
         }
         reserveStart.countDown();
         List<Object> outcomes = new ArrayList<>();
         for(Future<Object> reservation : reservations)
         {
            outcomes.add(reservation.get(30, TimeUnit.SECONDS));
         }
         assertThat(outcomes.stream().filter(UploadReservation.class::isInstance)).hasSize(1);
         assertThat(outcomes.stream().filter(Reason.class::isInstance)).containsOnly(Reason.LEASE_HELD);
         UploadReservation old = (UploadReservation) outcomes.stream().filter(UploadReservation.class::isInstance).findFirst().orElseThrow();
         JdbcUploadJournal later = new JdbcUploadJournal(Clock.fixed(START.plusSeconds(6), ZoneOffset.UTC));
         UploadReservation next = later.reserve(id, Duration.ofSeconds(5));
         assertThat(next.fence()).isGreaterThan(old.fence());
         assertThatThrownBy(() -> later.recordStorage(old, UploadStorageState.VERIFIED)).isInstanceOf(UploadJournalException.class);
      }
   }


   /*******************************************************************************
    ** Wrong metadata mappings, catalogs, and ambient transactions fail before a
    ** manifest write. Aurora is not an alias for the tested MySQL provider.
    *******************************************************************************/
   @Test
   void rejectsWrongBackendCatalogMappingAndAmbientTransaction() throws Exception
   {
      instance.addBackend(new RDBMSBackendMetaData().withName("other_backend").withVendor("mysql"));
      instance.getTable(CollaborationTables.ATTACHMENT).setBackendName("other_backend");
      assertUnsupported();
      instance.getTable(CollaborationTables.ATTACHMENT).setBackendName(backend.getName());
      instance.getTable(CollaborationTables.ATTACHMENT).setBackendDetails(new RDBMSTableBackendDetails().withTableName("other_attachment"));
      assertUnsupported();
      instance.getTable(CollaborationTables.ATTACHMENT).setBackendDetails(null);
      try(Connection connection = connection())
      {
         connection.setAutoCommit(false);
         connection.setCatalog("mysql");
         assertThatThrownBy(() -> MySqlJournalDatabase.requireQualifiedConnection(connection, backend)).isInstanceOf(UploadJournalException.class);
      }
      backend.setVendor("aurora-mysql");
      assertUnsupported();
      backend.setVendor("mysql");
      try(QBackendTransaction transaction = new RDBMSBackendModule().openTransaction(new InsertInput(UploadJournalTable.NAME)))
      {
         QContext.setTransaction(transaction);
         assertThatThrownBy(() -> journal.prepare(candidate("denied"))).isInstanceOf(UploadJournalException.class)
            .extracting(e -> ((UploadJournalException) e).getReason()).isEqualTo(Reason.AMBIENT_TRANSACTION);
      }
      QContext.clearTransaction();
      assertThat(count("collaboration_upload_journal")).isZero();
   }


   /*******************************************************************************
    ** Runtime settings and the real attachment engine are authoritative. Restore
    ** all disposable-server globals even when an assertion fails.
    *******************************************************************************/
   @Test
   void rejectsNonDurableSettingsAndNonTransactionalAttachment() throws Exception
   {
      try
      {
         execute("SET GLOBAL innodb_flush_log_at_trx_commit=2");
         assertUnsupported();
         execute("SET GLOBAL innodb_flush_log_at_trx_commit=1");
         execute("SET GLOBAL sync_binlog=0");
         assertUnsupported();
      }
      finally
      {
         execute("SET GLOBAL innodb_flush_log_at_trx_commit=1");
         execute("SET GLOBAL sync_binlog=1");
      }
      execute("ALTER TABLE collaboration_attachment ENGINE=MyISAM");
      assertUnsupported();
      assertThat(count("collaboration_upload_journal")).isZero();
   }


   /*******************************************************************************
    ** The SQL schema itself preserves case and trailing spaces in identifiers,
    ** and native generic writes cannot bypass journal ownership.
    *******************************************************************************/
   @Test
   void binaryIdentifiersAndNativeWritePolicy() throws Exception
   {
      journal.prepare(candidate("binary"));
      try(Connection connection = connection(); Statement statement = connection.createStatement();
         ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM collaboration_upload_journal WHERE created_by_user_id IN ('Alice', 'alice ')"))
      {
         result.next();
         assertThat(result.getInt(1)).isZero();
      }
      assertThatThrownBy(() -> new UploadJournalSecurity().checkPermissionsThrowing(new InsertInput(UploadJournalTable.NAME), instance.getTable(UploadJournalTable.NAME)))
         .isInstanceOf(com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException.class);
      execute("ALTER TABLE collaboration_upload_journal DEFAULT COLLATE=utf8mb4_0900_ai_ci");
      assertUnsupported();
   }


   /*******************************************************************************
    ** The actual server lock timeout maps to BUSY and leaves no granted lease.
    *******************************************************************************/
   @Test
   void lockTimeoutIsBoundedAndDoesNotGrantLease() throws Exception
   {
      Integer id = journal.prepare(candidate("locked")).id();
      try(Connection connection = connection(); PreparedStatement lock = connection.prepareStatement("SELECT id FROM collaboration_upload_journal WHERE id=? FOR UPDATE"))
      {
         connection.setAutoCommit(false);
         lock.setInt(1, id);
         try(ResultSet row = lock.executeQuery())
         {
            assertThat(row.next()).isTrue();
            long started = System.nanoTime();
            assertThatThrownBy(() -> journal.reserve(id, Duration.ofSeconds(5))).isInstanceOf(UploadJournalException.class)
               .extracting(e -> ((UploadJournalException) e).getReason()).isEqualTo(Reason.BUSY);
            System.out.println("MYSQL_FIXTURE_PHASE reserveBusyMillis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
         }
         System.out.println("MYSQL_FIXTURE_PHASE beforeIndependentRollback");
         diagnoseFailure(null);
         connection.rollback();
         System.out.println("MYSQL_FIXTURE_PHASE afterIndependentRollback");
      }
      assertThat(journal.find(id).orElseThrow().fence()).isZero();
      assertThat(journal.reserve(id, Duration.ofSeconds(5)).fence()).isEqualTo(1L);
   }


   /*******************************************************************************
    ** Actual column/index drift is refused even if the table default still looks
    ** correct. A changed candidate at the original location remains a conflict.
    *******************************************************************************/
   @Test
   void columnAndIndexDriftAndManifestConflictFailClosed() throws Exception
   {
      UploadCandidate original = candidate("immutable");
      journal.prepare(original);
      UploadCandidate changed = new UploadCandidate(original.tableName(), original.recordId(), original.actorId(), "changed.pdf", original.contentType(), original.description(),
         original.sizeBytes(), original.sha256(), original.bucketName(), original.storageKey(), true, null, null);
      assertThatThrownBy(() -> journal.prepare(changed)).isInstanceOf(UploadJournalException.class)
         .extracting(e -> ((UploadJournalException) e).getReason()).isEqualTo(Reason.IMMUTABLE_CONFLICT);
      execute("ALTER TABLE collaboration_upload_journal MODIFY created_by_user_id VARCHAR(255) COLLATE utf8mb4_0900_ai_ci NOT NULL");
      assertUnsupported();
      execute("ALTER TABLE collaboration_upload_journal MODIFY created_by_user_id VARCHAR(255) COLLATE utf8mb4_0900_bin NOT NULL");
      execute("DROP INDEX uq_collaboration_upload_journal_locator ON collaboration_upload_journal");
      assertUnsupported();
      assertThat(count("collaboration_upload_journal")).isEqualTo(1);
   }


   /*******************************************************************************
    ** Lease instants are stable when app hosts use different JVM default zones.
    ** A non-UTC database session is refused rather than silently shifting leases.
    *******************************************************************************/
   @Test
   void leaseInstantsUseExplicitUtcAcrossJvmZones() throws Exception
   {
      TimeZone prior = TimeZone.getDefault();
      try
      {
         TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));
         UploadReservation reservation = verified("timezone");
         assertThat(reservation.leaseUntil()).isEqualTo(START.plusSeconds(60));
         ConnectionManager.resetConnectionProviders();
         TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));
         assertThat(journal.find(reservation.journalId()).orElseThrow().leaseUntil()).isEqualTo(START.plusSeconds(60));
         assertThat(journal.renew(reservation, Duration.ofMinutes(2)).leaseUntil()).isEqualTo(START.plusSeconds(120));
         try(Connection connection = connection(); Statement statement = connection.createStatement())
         {
            connection.setAutoCommit(false);
            statement.execute("SET SESSION time_zone='+01:00'");
            assertThatThrownBy(() -> MySqlJournalDatabase.requireQualifiedConnection(connection, backend)).isInstanceOf(UploadJournalException.class);
         }
      }
      finally
      {
         ConnectionManager.resetConnectionProviders();
         TimeZone.setDefault(prior);
      }
   }


   /*******************************************************************************
    ** Qualification never mutates a host's pooled session. Accepted settings are
    ** preserved, while an excessive wait is refused and also left unchanged.
    *******************************************************************************/
   @Test
   void lockTimeoutQualificationPreservesHostSession() throws Exception
   {
      try(Connection connection = connection(); Statement statement = connection.createStatement())
      {
         connection.setAutoCommit(false);
         statement.execute("SET SESSION innodb_lock_wait_timeout=3");
         MySqlJournalDatabase.requireQualifiedConnection(connection, backend);
         try(ResultSet value = statement.executeQuery("SELECT @@SESSION.innodb_lock_wait_timeout"))
         {
            value.next();
            assertThat(value.getInt(1)).isEqualTo(3);
         }
         statement.execute("SET SESSION innodb_lock_wait_timeout=6");
         assertThatThrownBy(() -> MySqlJournalDatabase.requireQualifiedConnection(connection, backend)).isInstanceOf(UploadJournalException.class)
            .extracting(e -> ((UploadJournalException) e).getReason()).isEqualTo(Reason.UNSUPPORTED_BACKEND);
         try(ResultSet value = statement.executeQuery("SELECT @@SESSION.innodb_lock_wait_timeout"))
         {
            value.next();
            assertThat(value.getInt(1)).isEqualTo(6);
         }
      }
   }


   /*******************************************************************************
    ** Detect-only page protection and read-only sessions cannot promise durable
    ** journal writes. Qualification must leave the host session unchanged.
    *******************************************************************************/
   @Test
   void doublewriteAndSessionReadOnlyAreRequired() throws Exception
   {
      try
      {
         execute("SET GLOBAL innodb_doublewrite=DETECT_ONLY");
         assertUnsupported();
      }
      finally
      {
         execute("SET GLOBAL innodb_doublewrite=ON");
      }
      try(Connection connection = connection(); Statement statement = connection.createStatement())
      {
         connection.setAutoCommit(false);
         statement.execute("SET SESSION transaction_read_only=1");
         assertThatThrownBy(() -> MySqlJournalDatabase.requireQualifiedConnection(connection, backend)).isInstanceOf(UploadJournalException.class)
            .extracting(e -> ((UploadJournalException) e).getReason()).isEqualTo(Reason.UNSUPPORTED_BACKEND);
         try(ResultSet value = statement.executeQuery("SELECT @@SESSION.transaction_read_only"))
         {
            value.next();
            assertThat(value.getBoolean(1)).isTrue();
         }
         statement.execute("SET SESSION transaction_read_only=0");
         MySqlJournalDatabase.requireQualifiedConnection(connection, backend);
      }
      assertThat(journal.prepare(candidate("restored-durability")).id()).isPositive();
   }


   /*******************************************************************************
    ** Assert a typed refusal, with no connection URL in its public message.
    *******************************************************************************/
   private void assertUnsupported()
   {
      assertThatThrownBy(() -> journal.prepare(candidate("denied"))).isInstanceOf(UploadJournalException.class)
         .extracting(e -> ((UploadJournalException) e).getReason()).isEqualTo(Reason.UNSUPPORTED_BACKEND);
   }


   /*******************************************************************************
    ** Prepare a leased manifest whose stored bytes have been verified by owner.
    *******************************************************************************/
   private UploadReservation verified(String label) throws Exception
   {
      UploadReservation reservation = journal.reserve(journal.prepare(candidate(label)).id(), Duration.ofMinutes(1));
      journal.recordStorage(reservation, UploadStorageState.VERIFIED);
      return (reservation);
   }


   /*******************************************************************************
    ** Insert only through the owner's exact transaction.
    *******************************************************************************/
   private static Integer insertAttachment(QBackendTransaction transaction) throws QException
   {
      try(PreparedStatement statement = ((RDBMSTransaction) transaction).getConnection()
         .prepareStatement("INSERT INTO collaboration_attachment(file_name) VALUES ('fixture.pdf')", Statement.RETURN_GENERATED_KEYS))
      {
         statement.executeUpdate();
         try(ResultSet keys = statement.getGeneratedKeys())
         {
            keys.next();
            return (keys.getInt(1));
         }
      }
      catch(SQLException e)
      {
         throw (new QException("Synthetic attachment insert failed.", e));
      }
   }


   /*******************************************************************************
    ** Independent connection for committed-state assertions and fixture setup.
    *******************************************************************************/
   private Connection connection() throws SQLException
   {
      return (DriverManager.getConnection("jdbc:mysql://" + mysql.getHost() + ":" + currentPort + "/" + mysql.getDatabaseName()
         + "?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=1000&socketTimeout=10000", mysql.getUsername(), mysql.getPassword()));
   }


   /*******************************************************************************
    ** Execute fixed trusted fixture SQL, never application-supplied identifiers.
    *******************************************************************************/
   private void execute(String sql) throws SQLException
   {
      try(Connection connection = connection(); Statement statement = connection.createStatement())
      {
         statement.execute(sql);
      }
   }


   /*******************************************************************************
    ** Count a fixed owner table through a new committed connection.
    *******************************************************************************/
   private long count(String table) throws SQLException
   {
      try(Connection connection = connection(); Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table))
      {
         result.next();
         return (result.getLong(1));
      }
   }


   /*******************************************************************************
    ** Establish the same synthetic actor independently on each test thread.
    *******************************************************************************/
   private void context()
   {
      QContext.init(instance, new QSession().withUser(new QUser().withIdReference("alice")));
   }


   /*******************************************************************************
    ** Server-shaped immutable candidate; no real customer data or storage is used.
    *******************************************************************************/
   private static UploadCandidate candidate(String label)
   {
      return (new UploadCandidate("document", "1", "alice", label + ".pdf", "application/pdf", "Synthetic fixture", 5L, "a".repeat(64),
         "fixture-bucket", "retained/" + label, true, null, null));
   }
}
