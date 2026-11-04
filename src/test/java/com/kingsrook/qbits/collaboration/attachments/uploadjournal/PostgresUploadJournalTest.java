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
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
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
import com.kingsrook.qqq.backend.module.postgres.model.metadata.PostgreSQLBackendMetaData;
import com.kingsrook.qqq.backend.module.rdbms.RDBMSBackendModule;
import com.kingsrook.qqq.backend.module.rdbms.actions.RDBMSTransaction;
import com.kingsrook.qqq.backend.module.rdbms.jdbc.ConnectionManager;
import com.kingsrook.qqq.backend.module.rdbms.model.metadata.RDBMSBackendMetaData;
import com.kingsrook.qqq.backend.module.rdbms.model.metadata.RDBMSTableBackendDetails;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** Explicit opt-in real PostgreSQL 17 qualification of the upload journal on the
 ** native postgres backend. The container belongs only to this suite (loopback,
 ** dynamic port, random synthetic passwords); the application connects as a
 ** DML-only role, and the superuser is used only for fixture DDL and fault
 ** injection. The attachment row is a minimal stand-in, not a full intake fixture.
 *******************************************************************************/
@EnabledIfSystemProperty(named = "postgresJournalTests", matches = "true")
class PostgresUploadJournalTest
{
   private static final Instant START = Instant.parse("2026-10-30T12:00:00.123Z");
   private static final String DATABASE = "journal_test";
   private static final String APP_ROLE = "journal_app";
   private static final String ROOT_PASSWORD = UUID.randomUUID().toString();
   private static final String APP_PASSWORD = UUID.randomUUID().toString();
   private static final String OPTIONS = "?connectTimeout=5&socketTimeout=30&options=-c%20TimeZone%3DUTC";
   private static GenericContainer<?> postgres;
   private static TimeZone previousZone;
   private static int currentPort;
   // opt-in: reach the disposable server by its container bridge address (no host port published) - for the
   // sibling-container Linux gate, where the host loopback is not the test JVM's loopback
   private static final boolean BRIDGE_ADDRESS = Boolean.getBoolean("postgresJournalTests.bridgeAddress");
   private static String currentHost = "127.0.0.1";
   private PostgreSQLBackendMetaData backend;
   private QInstance instance;
   private JdbcUploadJournal journal;



   /*******************************************************************************
    ** Start only the disposable server with explicit durable settings. pgjdbc
    ** announces the JVM zone as the session TimeZone, so the suite runs in UTC.
    *******************************************************************************/
   @BeforeAll
   static void start() throws Exception
   {
      previousZone = TimeZone.getDefault();
      TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
      postgres = new GenericContainer<>("postgres:17")
         .withEnv("POSTGRES_PASSWORD", ROOT_PASSWORD).withEnv("POSTGRES_DB", DATABASE)
         .withCommand("postgres", "-c", "fsync=on", "-c", "synchronous_commit=on", "-c", "full_page_writes=on")
         .withExposedPorts(5432)
         .withCreateContainerCmdModifier(command ->
         {
            command.getHostConfig().withMemory(768L * 1024 * 1024);
            if(!BRIDGE_ADDRESS)
            {
               command.getHostConfig().withPortBindings(new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0), new ExposedPort(5432)));
            }
         })
         .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2).withStartupTimeout(Duration.ofMinutes(2)));
      postgres.start();
      locateServer();
      try(Connection connection = root(); Statement statement = connection.createStatement())
      {
         statement.execute("CREATE ROLE " + APP_ROLE + " LOGIN PASSWORD '" + APP_PASSWORD + "' NOSUPERUSER NOCREATEDB NOCREATEROLE");
         statement.execute("ALTER ROLE " + APP_ROLE + " SET lock_timeout='5s'");
         statement.execute("DROP SCHEMA public CASCADE");
         statement.execute("CREATE SCHEMA public");
         statement.execute("GRANT USAGE ON SCHEMA public TO " + APP_ROLE);
         statement.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO " + APP_ROLE);
         statement.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO " + APP_ROLE);
      }
   }



   /*******************************************************************************
    ** Destroy only the container created by this suite and restore the JVM zone.
    *******************************************************************************/
   @AfterAll
   static void stop()
   {
      ConnectionManager.closeConnectionProviders();
      if(postgres != null)
      {
         postgres.close();
      }
      if(previousZone != null)
      {
         TimeZone.setDefault(previousZone);
      }
   }



   /*******************************************************************************
    ** Native postgres backend metadata with the DML-only identity, a fresh journal
    ** and attachment pair, and the shipped owner script.
    *******************************************************************************/
   @BeforeEach
   void setUp() throws Exception
   {
      ConnectionManager.closeConnectionProviders();
      TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
      execute("ALTER ROLE " + APP_ROLE + " SET lock_timeout='5s'");
      execute("ALTER ROLE " + APP_ROLE + " RESET synchronous_commit");
      execute("ALTER ROLE " + APP_ROLE + " RESET default_transaction_isolation");
      execute("ALTER ROLE " + APP_ROLE + " RESET default_transaction_read_only");
      backend = new PostgreSQLBackendMetaData();
      backend.setName("postgres_journal");
      backend.setDatabaseName(DATABASE);
      backend.setUsername(APP_ROLE);
      backend.setPassword(APP_PASSWORD);
      backend.setJdbcUrl(appUrl());
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
      execute("DROP COLLATION IF EXISTS journal_case_insensitive");
      execute(UploadJournalTable.postgresScript());
      execute("CREATE TABLE collaboration_attachment (id INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, file_name VARCHAR(255))");
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
      ConnectionManager.closeConnectionProviders();
      TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
   }



   /*******************************************************************************
    ** Only the native postgres module with both strings is admitted; the generic
    ** rdbms backend type with a postgres vendor label is not.
    *******************************************************************************/
   @Test
   void admitsOnlyTheNativePostgresBackend()
   {
      assertThat(JdbcUploadJournal.isQualifiedBackend(backend)).isTrue();
      backend.setBackendType("rdbms");
      assertThat(JdbcUploadJournal.isQualifiedBackend(backend)).isFalse();
      assertUnsupported();
      backend.setBackendType("postgres");
      backend.setVendor("aurora-postgresql");
      assertThat(JdbcUploadJournal.isQualifiedBackend(backend)).isFalse();
      backend.setVendor("postgres");
      assertThat(JdbcUploadJournal.isQualifiedBackend(backend)).isTrue();
   }



   /*******************************************************************************
    ** The shipped script is re-runnable, and its CHECK guards stand on the server:
    ** REGISTERED or COMPLETE without an attachment id, and a half intake pair, are refused.
    *******************************************************************************/
   @Test
   void scriptIsRerunnableAndConstraintsHold() throws Exception
   {
      execute(UploadJournalTable.postgresScript());
      String insert = "INSERT INTO collaboration_upload_journal (create_date, modify_date, table_name, record_id, created_by_user_id, file_name, content_type,"
         + " size_bytes, sha256, bucket_name, storage_key, retained, intake_key, intake_fingerprint, locator_hash, manifest_hash, state, storage_state, attachment_id, fence)"
         + " VALUES (now(), now(), 't', '1', 'a', 'f', 'c', 1, 's', 'b', 'k', TRUE, %s, %s, 'L%s', 'm', '%s', '%s', %s, 0)";
      assertThatThrownBy(() -> execute(String.format(insert, "NULL", "NULL", "1", "REGISTERED", "VERIFIED", "NULL"))).isInstanceOf(SQLException.class)
         .extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("23514");
      assertThatThrownBy(() -> execute(String.format(insert, "'k'", "NULL", "2", "PREPARED", "PENDING", "NULL"))).isInstanceOf(SQLException.class)
         .extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("23514");
      assertThatThrownBy(() -> execute(String.format(insert, "NULL", "NULL", "3", "REGISTERED", "PENDING", "1"))).isInstanceOf(SQLException.class)
         .extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("23514");
      execute(String.format(insert, "NULL", "NULL", "4", "PREPARED", "PENDING", "NULL"));
      assertThatThrownBy(() -> execute(String.format(insert, "NULL", "NULL", "4", "PREPARED", "PENDING", "NULL"))).isInstanceOf(SQLException.class)
         .extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("23505");
      assertThat(count("collaboration_upload_journal")).isEqualTo(1);
   }



   /*******************************************************************************
    ** Acknowledged metadata survives a real database process restart, not merely
    ** an open pooled connection. The same candidate replays its original row.
    *******************************************************************************/
   @Test
   void committedCandidateSurvivesDatabaseRestart() throws Exception
   {
      UploadEntry original = journal.prepare(candidate("restart"));
      ConnectionManager.closeConnectionProviders();
      postgres.getDockerClient().restartContainerCmd(postgres.getContainerId()).exec();
      locateServer();
      backend.setJdbcUrl(appUrl());
      long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
      boolean ready = false;
      while(System.nanoTime() < deadline)
      {
         try(Connection connection = root(); Statement statement = connection.createStatement())
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
    ** Wrong metadata mappings, databases and ambient transactions fail before a
    ** manifest write.
    *******************************************************************************/
   @Test
   void rejectsWrongBackendDatabaseMappingAndAmbientTransaction() throws Exception
   {
      PostgreSQLBackendMetaData other = new PostgreSQLBackendMetaData();
      other.setName("other_backend");
      instance.addBackend(other);
      instance.getTable(CollaborationTables.ATTACHMENT).setBackendName("other_backend");
      assertUnsupported();
      instance.getTable(CollaborationTables.ATTACHMENT).setBackendName(backend.getName());
      instance.getTable(CollaborationTables.ATTACHMENT).setBackendDetails(new RDBMSTableBackendDetails().withTableName("other_attachment"));
      assertUnsupported();
      instance.getTable(CollaborationTables.ATTACHMENT).setBackendDetails(null);
      try(Connection connection = DriverManager.getConnection("jdbc:postgresql://" + currentHost + ":" + currentPort + "/postgres" + OPTIONS, "postgres", ROOT_PASSWORD))
      {
         connection.setAutoCommit(false);
         assertThatThrownBy(() -> PostgresJournalDatabase.requireQualifiedConnection(connection, backend)).isInstanceOf(UploadJournalException.class);
      }
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
    ** Runtime settings are authoritative: weak commit durability, an unbounded or
    ** missing lock timeout, a stronger isolation level and a read-only default
    ** each refuse before a write. Role-level settings are restored in setUp.
    *******************************************************************************/
   @Test
   void rejectsUnsafeSessionSettings() throws Exception
   {
      journal.prepare(candidate("baseline"));
      execute("ALTER ROLE " + APP_ROLE + " SET synchronous_commit='off'");
      assertUnsupported();
      execute("ALTER ROLE " + APP_ROLE + " RESET synchronous_commit");
      execute("ALTER ROLE " + APP_ROLE + " SET lock_timeout='60s'");
      assertUnsupported();
      execute("ALTER ROLE " + APP_ROLE + " RESET lock_timeout");
      assertUnsupported();
      execute("ALTER ROLE " + APP_ROLE + " SET lock_timeout='5s'");
      execute("ALTER ROLE " + APP_ROLE + " SET default_transaction_isolation='repeatable read'");
      assertUnsupported();
      execute("ALTER ROLE " + APP_ROLE + " RESET default_transaction_isolation");
      execute("ALTER ROLE " + APP_ROLE + " SET default_transaction_read_only=on");
      assertUnsupported();
      execute("ALTER ROLE " + APP_ROLE + " RESET default_transaction_read_only");
      ConnectionManager.closeConnectionProviders();
      assertThat(journal.prepare(candidate("restored")).id()).isPositive();
      assertThat(count("collaboration_upload_journal")).isEqualTo(2);
   }



   /*******************************************************************************
    ** Physical drift is refused: an unlogged owner table (lost in crash recovery)
    ** and a non-deterministic collation on a journal column.
    *******************************************************************************/
   @Test
   void rejectsUnloggedTableAndNonDeterministicCollation() throws Exception
   {
      execute("ALTER TABLE collaboration_attachment SET UNLOGGED");
      assertUnsupported();
      execute("ALTER TABLE collaboration_attachment SET LOGGED");
      execute("ALTER TABLE collaboration_upload_journal SET UNLOGGED");
      assertUnsupported();
      execute("ALTER TABLE collaboration_upload_journal SET LOGGED");
      execute("CREATE COLLATION journal_case_insensitive (provider = icu, locale = 'und-u-ks-level2', deterministic = false)");
      execute("ALTER TABLE collaboration_upload_journal ALTER COLUMN created_by_user_id TYPE VARCHAR(255) COLLATE journal_case_insensitive");
      assertUnsupported();
      execute("ALTER TABLE collaboration_upload_journal ALTER COLUMN created_by_user_id TYPE VARCHAR(255) COLLATE \"default\"");
      assertThat(journal.prepare(candidate("restored-drift")).id()).isPositive();
   }



   /*******************************************************************************
    ** A missing or partial unique index would let identities duplicate, so each is
    ** refused until the complete index is back. A changed candidate at the original
    ** location stays an immutable conflict.
    *******************************************************************************/
   @Test
   void rejectsMissingAndPartialUniqueIndexesAndManifestConflict() throws Exception
   {
      UploadCandidate original = candidate("immutable");
      journal.prepare(original);
      UploadCandidate changed = new UploadCandidate(original.tableName(), original.recordId(), original.actorId(), "changed.pdf", original.contentType(), original.description(),
         original.sizeBytes(), original.sha256(), original.bucketName(), original.storageKey(), true, null, null);
      assertThatThrownBy(() -> journal.prepare(changed)).isInstanceOf(UploadJournalException.class)
         .extracting(e -> ((UploadJournalException) e).getReason()).isEqualTo(Reason.IMMUTABLE_CONFLICT);
      execute("DROP INDEX uq_collaboration_upload_journal_locator");
      assertUnsupported();
      execute("CREATE UNIQUE INDEX uq_collaboration_upload_journal_locator ON collaboration_upload_journal (locator_hash) WHERE locator_hash <> ''");
      assertUnsupported();
      execute("DROP INDEX uq_collaboration_upload_journal_locator");
      execute("CREATE UNIQUE INDEX uq_collaboration_upload_journal_locator ON collaboration_upload_journal (locator_hash)");
      execute("DROP INDEX uq_collaboration_upload_journal_intake");
      assertUnsupported();
      execute("CREATE UNIQUE INDEX uq_collaboration_upload_journal_intake ON collaboration_upload_journal (intake_key)");
      assertThat(journal.prepare(original).id()).isPositive();
      assertThat(count("collaboration_upload_journal")).isEqualTo(1);
   }



   /*******************************************************************************
    ** Native generic writes cannot bypass journal ownership.
    *******************************************************************************/
   @Test
   void nativeWritePolicyDeniesDirectInsert()
   {
      assertThatThrownBy(() -> new UploadJournalSecurity().checkPermissionsThrowing(new InsertInput(UploadJournalTable.NAME), instance.getTable(UploadJournalTable.NAME)))
         .isInstanceOf(com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException.class);
   }



   /*******************************************************************************
    ** The actual server lock timeout (SQLSTATE 55P03) maps to BUSY and leaves no
    ** granted lease.
    *******************************************************************************/
   @Test
   void lockTimeoutIsBoundedAndDoesNotGrantLease() throws Exception
   {
      Integer id = journal.prepare(candidate("locked")).id();
      try(Connection connection = root(); PreparedStatement lock = connection.prepareStatement("SELECT id FROM collaboration_upload_journal WHERE id=? FOR UPDATE"))
      {
         connection.setAutoCommit(false);
         lock.setInt(1, id);
         try(ResultSet row = lock.executeQuery())
         {
            assertThat(row.next()).isTrue();
            long started = System.nanoTime();
            assertThatThrownBy(() -> journal.reserve(id, Duration.ofSeconds(5))).isInstanceOf(UploadJournalException.class)
               .extracting(e -> ((UploadJournalException) e).getReason()).isEqualTo(Reason.BUSY);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isBetween(3000L, 20000L);
         }
         connection.rollback();
      }
      assertThat(journal.find(id).orElseThrow().fence()).isZero();
      assertThat(journal.reserve(id, Duration.ofSeconds(5)).fence()).isEqualTo(1L);
   }



   /*******************************************************************************
    ** Lease instants round-trip as TIMESTAMPTZ. A host whose JVM zone is not UTC
    ** is refused (pgjdbc would announce that zone to the session) rather than
    ** silently shifting leases, and the instant is exact once the zone is UTC.
    *******************************************************************************/
   @Test
   void leaseInstantsRoundTripAndNonUtcHostIsRefused() throws Exception
   {
      UploadReservation reservation = verified("timezone");
      assertThat(reservation.leaseUntil()).isEqualTo(START.plusSeconds(60));
      TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));
      ConnectionManager.closeConnectionProviders();
      assertUnsupported();
      TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
      ConnectionManager.closeConnectionProviders();
      assertThat(journal.find(reservation.journalId()).orElseThrow().leaseUntil()).isEqualTo(START.plusSeconds(60));
      assertThat(journal.renew(reservation, Duration.ofMinutes(2)).leaseUntil()).isEqualTo(START.plusSeconds(120));
   }



   /*******************************************************************************
    ** Assert a typed refusal on a fresh pooled session, so role-level setting
    ** changes are actually observed.
    *******************************************************************************/
   private void assertUnsupported()
   {
      ConnectionManager.closeConnectionProviders();
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
         .prepareStatement("INSERT INTO collaboration_attachment(file_name) VALUES ('fixture.pdf')", new String[] { "id" }))
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
    ** The application's JDBC URL for the current dynamic port.
    *******************************************************************************/
   private static String appUrl()
   {
      return ("jdbc:postgresql://" + currentHost + ":" + currentPort + "/" + DATABASE + OPTIONS);
   }



   /*******************************************************************************
    ** Find the disposable server: the published loopback port by default, or (opt-in
    ** bridge mode) the container's own bridge address and the server port.
    *******************************************************************************/
   private static void locateServer()
   {
      if(BRIDGE_ADDRESS)
      {
         currentHost = postgres.getContainerInfo().getNetworkSettings().getNetworks().values().iterator().next().getIpAddress();
         currentPort = 5432;
      }
      else
      {
         currentHost = "127.0.0.1";
         currentPort = Integer.parseInt(postgres.getCurrentContainerInfo().getNetworkSettings().getPorts().getBindings().get(new ExposedPort(5432))[0].getHostPortSpec());
      }
   }



   /*******************************************************************************
    ** Independent superuser connection for fixture DDL, fault injection and
    ** committed-state assertions.
    *******************************************************************************/
   private static Connection root() throws SQLException
   {
      return (DriverManager.getConnection("jdbc:postgresql://" + currentHost + ":" + currentPort + "/" + DATABASE + OPTIONS, "postgres", ROOT_PASSWORD));
   }



   /*******************************************************************************
    ** Execute fixed trusted fixture SQL, never application-supplied identifiers.
    *******************************************************************************/
   private void execute(String sql) throws SQLException
   {
      try(Connection connection = root(); Statement statement = connection.createStatement())
      {
         statement.execute(sql);
      }
   }



   /*******************************************************************************
    ** Count a fixed owner table through a new committed connection.
    *******************************************************************************/
   private long count(String table) throws SQLException
   {
      try(Connection connection = root(); Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table))
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
