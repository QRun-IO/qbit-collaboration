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


import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TimeZone;
import java.util.UUID;
import com.kingsrook.qbits.collaboration.attachments.uploadjournal.UploadJournalException.Reason;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qqq.backend.core.actions.QBackendTransaction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.metadata.QBackendMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.module.rdbms.RDBMSBackendModule;
import com.kingsrook.qqq.backend.module.rdbms.actions.RDBMSTransaction;
import com.kingsrook.qqq.backend.module.rdbms.model.metadata.RDBMSBackendMetaData;


/*******************************************************************************
 ** Real JDBC implementation for file-backed H2 with WRITE_DELAY=0 and explicitly
 ** qualified MySQL 8.4/InnoDB and PostgreSQL 17 connections, with the
 ** journal and the attachment table on the same backend. This is not a portable
 ** production database claim; any other backend, a memory database, a no-op
 ** transaction or an ambient caller transaction is rejected before any write.
 **
 ** Every operation opens and owns its own transaction (commit on success, rollback
 ** on any failure; no partial success). Mutations lock the row with a bounded
 ** FOR UPDATE, verify the lease token, fence and expiry, and then also guard the
 ** UPDATE with the same token and fence and require exactly one row. SQL uses only
 ** the fixed owner table name and literal text; all values are bound parameters.
 ** Nothing here ever deletes a row or an object, and it never touches bytes.
 **
 ** Expiry uses this JVM's clock. Hosts must keep lease durations well above their
 ** clock skew; the fence, not the clock, is what stops a stale holder from
 ** writing once a newer reservation exists.
 **
 ** Runtime checks cannot qualify a deployment's disks, backups or failover policy.
 *******************************************************************************/
public final class JdbcUploadJournal implements UploadJournal
{
   private static final String TABLE = CollaborationTables.UPLOAD_JOURNAL;
   private static final int QUERY_TIMEOUT_SECONDS = 10;
   private static final int MAXIMUM_LIST = 500;
   private static final Duration MINIMUM_LEASE = Duration.ofSeconds(1);
   private static final Duration MAXIMUM_LEASE = Duration.ofMinutes(30);

   private static final String COLUMNS = "id, table_name, record_id, created_by_user_id, file_name, content_type, description, size_bytes, sha256,"
      + " bucket_name, storage_key, retained, intake_key, intake_fingerprint, locator_hash, manifest_hash, state, storage_state, attachment_id,"
      + " lease_token, lease_until, fence";
   private static final String SELECT_ALL = "SELECT " + COLUMNS + " FROM " + TABLE;
   private static final String BY_ID = SELECT_ALL + " WHERE id = ?";
   private static final String BY_ID_LOCKED = BY_ID + " FOR UPDATE";
   private static final String BY_LOCATOR = SELECT_ALL + " WHERE locator_hash = ?";
   private static final String BY_INTAKE = SELECT_ALL + " WHERE intake_key = ?";
   private static final String INCOMPLETE = SELECT_ALL + " WHERE state <> 'COMPLETE' ORDER BY id LIMIT ?";

   private static final String INSERT = "INSERT INTO " + TABLE + " (create_date, modify_date, table_name, record_id, created_by_user_id, file_name,"
      + " content_type, description, size_bytes, sha256, bucket_name, storage_key, retained, intake_key, intake_fingerprint, locator_hash,"
      + " manifest_hash, state, storage_state, fence) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PREPARED', 'PENDING', 0)";
   private static final String GRANT = "UPDATE " + TABLE + " SET lease_token = ?, lease_until = ?, fence = ?, modify_date = ? WHERE id = ? AND fence = ?";
   private static final String SET_STORAGE = "UPDATE " + TABLE + " SET storage_state = ?, modify_date = ? WHERE id = ? AND lease_token = ? AND fence = ?";
   private static final String SET_REGISTERED = "UPDATE " + TABLE + " SET state = 'REGISTERED', attachment_id = ?, modify_date = ?"
      + " WHERE id = ? AND lease_token = ? AND fence = ? AND state = 'PREPARED'";
   private static final String SET_COMPLETE = "UPDATE " + TABLE + " SET state = 'COMPLETE', lease_token = NULL, lease_until = NULL, modify_date = ?"
      + " WHERE id = ? AND lease_token = ? AND fence = ? AND state = 'REGISTERED'";
   private static final String CLEAR_LEASE = "UPDATE " + TABLE + " SET lease_token = NULL, lease_until = NULL, modify_date = ?"
      + " WHERE id = ? AND lease_token = ? AND fence = ?";

   private final Clock clock;



   /*******************************************************************************
    ** Journal using the system clock.
    *******************************************************************************/
   public JdbcUploadJournal()
   {
      this(Clock.systemUTC());
   }



   /*******************************************************************************
    ** Package-local deterministic clock seam for lease-expiry tests.
    *******************************************************************************/
   JdbcUploadJournal(Clock clock)
   {
      this.clock = Objects.requireNonNull(clock, "clock");
   }



   /*******************************************************************************
    ** One database transaction's worth of work.
    *******************************************************************************/
   @FunctionalInterface
   private interface Work<T>
   {
      /*******************************************************************************
       ** Run inside the owned, qualified transaction.
       *******************************************************************************/
      T run(RDBMSTransaction transaction, Connection connection) throws SQLException, QException;
   }



   /*******************************************************************************
    ** A locked or read row with the journal-private secrets.
    *******************************************************************************/
   private record Row(UploadEntry entry, String leaseToken, String manifestHash)
   {
   }



   /*******************************************************************************
    ** Static admission of file H2, native mysql, or the native postgres backend
    ** (vendor and backend type both "postgres"). Actual engine, catalog, table
    ** storage and durability checks additionally run in every transaction.
    *******************************************************************************/
   public static boolean isQualifiedBackend(QBackendMetaData backend)
   {
      if(backend instanceof RDBMSBackendMetaData postgres && PostgresJournalDatabase.is(postgres))
      {
         return (true);
      }
      return (backend instanceof RDBMSBackendMetaData metadata && RDBMSBackendModule.NAME.equals(metadata.getBackendType())
         && ("mysql".equals(metadata.getVendor()) || ("h2".equals(metadata.getVendor())
            && metadata.getJdbcUrl() != null && metadata.getJdbcUrl().startsWith("jdbc:h2:file:"))));
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public UploadEntry prepare(UploadCandidate candidate) throws QException
   {
      Objects.requireNonNull(candidate, "candidate");
      try
      {
         return (inTransaction((transaction, connection) -> insertOrReplay(connection, candidate)));
      }
      catch(UploadJournalException e)
      {
         if(e.getReason() != Reason.DUPLICATE)
         {
            throw (e);
         }
         // Lost a unique-key race: the winner committed, so read its row and compare.
         return (inTransaction((transaction, connection) ->
         {
            UploadEntry existing = existing(connection, candidate);
            if(existing == null)
            {
               throw (new UploadJournalException(Reason.ILLEGAL_STATE, "The journal reported a duplicate but no matching row exists."));
            }
            return (existing);
         }));
      }
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public UploadReservation reserve(Integer journalId, Duration lease) throws QException
   {
      if(lease == null || lease.compareTo(MINIMUM_LEASE) < 0 || lease.compareTo(MAXIMUM_LEASE) > 0)
      {
         throw (new IllegalArgumentException("The lease must be between 1 second and 30 minutes."));
      }
      return (inTransaction((transaction, connection) ->
      {
         Row row = lock(connection, journalId);
         Instant now = now();
         if(row.entry().state() == UploadJournalState.COMPLETE)
         {
            throw (new UploadJournalException(Reason.ILLEGAL_STATE, "This upload is already complete."));
         }
         if(row.entry().isLeased(now))
         {
            throw (new UploadJournalException(Reason.LEASE_HELD, "Another holder has a live reservation on this upload."));
         }
         String token = UUID.randomUUID().toString();
         Long fence = row.entry().fence() + 1;
         Instant until = now.plus(lease);
         if(update(connection, GRANT, token, Timestamp.from(until), fence, Timestamp.from(now), journalId, row.entry().fence()) != 1)
         {
            throw (new UploadJournalException(Reason.LEASE_HELD, "Another holder took this reservation first."));
         }
         return (new UploadReservation(row.entry().leased(fence, until), token, fence, until));
      }));
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public UploadEntry recordStorage(UploadReservation reservation, UploadStorageState storageState) throws QException
   {
      Objects.requireNonNull(reservation, "reservation");
      if(storageState == null || storageState == UploadStorageState.PENDING)
      {
         throw (new UploadJournalException(Reason.ILLEGAL_STATE, "Storage knowledge can only advance to UNKNOWN or VERIFIED."));
      }
      return (inTransaction((transaction, connection) ->
      {
         Row row = requireHolder(connection, reservation);
         UploadStorageState current = row.entry().storageState();
         if(current == storageState)
         {
            return (row.entry());
         }
         if(current == UploadStorageState.VERIFIED)
         {
            throw (new UploadJournalException(Reason.ILLEGAL_STATE, "Verified storage cannot move back to UNKNOWN."));
         }
         if(update(connection, SET_STORAGE, storageState.name(), Timestamp.from(now()), reservation.journalId(), reservation.leaseToken(), reservation.fence()) != 1)
         {
            throw (stale());
         }
         return (read(connection, reservation.journalId()));
      }));
   }

   /*******************************************************************************
    ** Renew at synchronous stage boundaries without reviving an expired holder.
    *******************************************************************************/
   @Override
   public UploadReservation renew(UploadReservation reservation, Duration lease) throws QException
   {
      if(lease == null || lease.compareTo(MINIMUM_LEASE) < 0 || lease.compareTo(MAXIMUM_LEASE) > 0)
      {
         throw (new IllegalArgumentException("The lease must be between 1 second and 30 minutes."));
      }
      return (inTransaction((transaction, connection) ->
      {
         Row row = requireHolder(connection, reservation);
         Instant until = now().plus(lease);
         if(update(connection, "UPDATE " + TABLE + " SET lease_until=?, modify_date=? WHERE id=? AND lease_token=? AND fence=?",
            Timestamp.from(until), Timestamp.from(now()), reservation.journalId(), reservation.leaseToken(), reservation.fence()) != 1)
         {
            throw (stale());
         }
         return (new UploadReservation(row.entry().leased(reservation.fence(), until), reservation.leaseToken(), reservation.fence(), until));
      }));
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public UploadEntry register(UploadReservation reservation, AttachmentRegistrar registrar) throws QException
   {
      Objects.requireNonNull(reservation, "reservation");
      Objects.requireNonNull(registrar, "registrar");
      return (inTransaction((transaction, connection) ->
      {
         Row row = requireHolder(connection, reservation);
         if(row.entry().state() != UploadJournalState.PREPARED)
         {
            return (row.entry());
         }
         if(row.entry().storageState() != UploadStorageState.VERIFIED)
         {
            throw (new UploadJournalException(Reason.STORAGE_NOT_VERIFIED, "The original object has not been verified, so it cannot be registered."));
         }
         Integer attachmentId = registrar.insert(transaction, row.entry().candidate(), row.entry().id());
         if(attachmentId == null || attachmentId < 1)
         {
            throw (new UploadJournalException(Reason.ILLEGAL_STATE, "The registrar did not return an attachment id."));
         }
         if(connection.isClosed() || connection.getAutoCommit())
         {
            throw (new UploadJournalException(Reason.ILLEGAL_STATE, "The registrar altered the journal's owned transaction."));
         }
         if(update(connection, SET_REGISTERED, attachmentId, Timestamp.from(now()), reservation.journalId(), reservation.leaseToken(), reservation.fence()) != 1)
         {
            throw (stale());
         }
         return (read(connection, reservation.journalId()));
      }));
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public UploadEntry complete(UploadReservation reservation) throws QException
   {
      Objects.requireNonNull(reservation, "reservation");
      return (inTransaction((transaction, connection) ->
      {
         Row finished = lock(connection, reservation.journalId());
         if(finished.entry().state() == UploadJournalState.COMPLETE && finished.entry().fence().equals(reservation.fence()))
         {
            return (finished.entry());
         }
         Row row = requireHolder(connection, reservation);
         if(row.entry().state() != UploadJournalState.REGISTERED || row.entry().attachmentId() == null)
         {
            throw (new UploadJournalException(Reason.ILLEGAL_STATE, "Only a registered upload can be completed."));
         }
         if(update(connection, SET_COMPLETE, Timestamp.from(now()), reservation.journalId(), reservation.leaseToken(), reservation.fence()) != 1)
         {
            throw (stale());
         }
         return (read(connection, reservation.journalId()));
      }));
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public boolean release(UploadReservation reservation) throws QException
   {
      Objects.requireNonNull(reservation, "reservation");
      return (inTransaction((transaction, connection) ->
      {
         Row row = lock(connection, reservation.journalId());
         if(row.entry().state() == UploadJournalState.COMPLETE || !holds(row, reservation))
         {
            return (false);
         }
         return (update(connection, CLEAR_LEASE, Timestamp.from(now()), reservation.journalId(), reservation.leaseToken(), reservation.fence()) == 1);
      }));
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public Optional<UploadEntry> find(Integer journalId) throws QException
   {
      return (inTransaction((transaction, connection) ->
      {
         List<Row> rows = select(connection, BY_ID, journalId);
         return (rows.isEmpty() ? Optional.<UploadEntry>empty() : Optional.of(rows.getFirst().entry()));
      }));
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public Optional<UploadEntry> findByLocation(String bucketName, String storageKey) throws QException
   {
      String hash = UploadCandidate.locatorHash(bucketName, storageKey);
      return (inTransaction((transaction, connection) ->
      {
         List<Row> rows = select(connection, BY_LOCATOR, hash);
         return (rows.isEmpty() ? Optional.<UploadEntry>empty() : Optional.of(rows.getFirst().entry()));
      }));
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public List<UploadEntry> listIncomplete(int limit) throws QException
   {
      if(limit < 1 || limit > MAXIMUM_LIST)
      {
         throw (new IllegalArgumentException("The limit must be between 1 and " + MAXIMUM_LIST + "."));
      }
      return (inTransaction((transaction, connection) ->
      {
         List<UploadEntry> entries = new ArrayList<>();
         for(Row row : select(connection, INCOMPLETE, limit))
         {
            entries.add(row.entry());
         }
         return (List.copyOf(entries));
      }));
   }



   /*******************************************************************************
    ** Replay an identical manifest or insert the candidate as PREPARED.
    *******************************************************************************/
   private UploadEntry insertOrReplay(Connection connection, UploadCandidate candidate) throws SQLException, QException
   {
      UploadEntry existing = existing(connection, candidate);
      if(existing != null)
      {
         return (existing);
      }
      Timestamp now = Timestamp.from(now());
      update(connection, INSERT, now, now, candidate.tableName(), candidate.recordId(), candidate.actorId(), candidate.fileName(),
         candidate.contentType(), candidate.description(), candidate.sizeBytes(), candidate.sha256(), candidate.bucketName(),
         candidate.storageKey(), candidate.retained(), candidate.intakeKey(), candidate.intakeFingerprint(), candidate.locatorHash(),
         candidate.manifestHash());
      List<Row> rows = select(connection, BY_LOCATOR, candidate.locatorHash());
      if(rows.isEmpty())
      {
         throw (new UploadJournalException(Reason.ILLEGAL_STATE, "The prepared candidate could not be read back."));
      }
      return (rows.getFirst().entry());
   }



   /*******************************************************************************
    ** The row already holding this location, if its manifest is identical. Any other
    ** row sharing the location or intake key is an immutable conflict. Null if none.
    *******************************************************************************/
   private UploadEntry existing(Connection connection, UploadCandidate candidate) throws SQLException, QException
   {
      List<Row> byLocator = select(connection, BY_LOCATOR, candidate.locatorHash());
      if(!byLocator.isEmpty())
      {
         if(!candidate.manifestHash().equals(byLocator.getFirst().manifestHash()))
         {
            throw (new UploadJournalException(Reason.IMMUTABLE_CONFLICT, "This object location is already journaled with a different manifest."));
         }
         return (byLocator.getFirst().entry());
      }
      if(candidate.intakeKey() != null && !select(connection, BY_INTAKE, candidate.intakeKey()).isEmpty())
      {
         throw (new UploadJournalException(Reason.IMMUTABLE_CONFLICT, "This intake key is already journaled for a different object."));
      }
      return (null);
   }



   /*******************************************************************************
    ** Lock the row under the bounded database wait, or NOT_FOUND.
    *******************************************************************************/
   private Row lock(Connection connection, Integer journalId) throws SQLException, QException
   {
      List<Row> rows = journalId == null ? List.of() : select(connection, BY_ID_LOCKED, journalId);
      if(rows.isEmpty())
      {
         throw (new UploadJournalException(Reason.NOT_FOUND, "No such upload journal row."));
      }
      return (rows.getFirst());
   }



   /*******************************************************************************
    ** Lock the row and require the reservation to still own a live lease on it.
    *******************************************************************************/
   private Row requireHolder(Connection connection, UploadReservation reservation) throws SQLException, QException
   {
      Row row = lock(connection, reservation.journalId());
      if(row.entry().state() == UploadJournalState.COMPLETE || !holds(row, reservation))
      {
         throw (stale());
      }
      return (row);
   }



   /*******************************************************************************
    ** Token (compared in constant time), fence and unexpired lease all match.
    *******************************************************************************/
   private boolean holds(Row row, UploadReservation reservation)
   {
      return (row.leaseToken() != null && reservation.leaseToken() != null
         && MessageDigest.isEqual(row.leaseToken().getBytes(StandardCharsets.UTF_8), reservation.leaseToken().getBytes(StandardCharsets.UTF_8))
         && row.entry().fence().equals(reservation.fence()) && row.entry().isLeased(now()));
   }



   /*******************************************************************************
    ** The standard refusal for a holder that no longer owns the row.
    *******************************************************************************/
   private static UploadJournalException stale()
   {
      return (new UploadJournalException(Reason.STALE_RESERVATION, "The reservation no longer owns this upload; reserve it again."));
   }



   /*******************************************************************************
    ** Read one row that the transaction just changed.
    *******************************************************************************/
   private UploadEntry read(Connection connection, Integer journalId) throws SQLException, QException
   {
      List<Row> rows = select(connection, BY_ID, journalId);
      if(rows.isEmpty())
      {
         throw (new UploadJournalException(Reason.NOT_FOUND, "No such upload journal row."));
      }
      return (rows.getFirst().entry());
   }



   /*******************************************************************************
    ** Bounded query with one bound parameter, verifying each row's integrity.
    *******************************************************************************/
   private List<Row> select(Connection connection, String sql, Object parameter) throws SQLException, QException
   {
      List<Row> rows = new ArrayList<>();
      try(PreparedStatement statement = connection.prepareStatement(sql))
      {
         statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
         statement.setObject(1, parameter);
         try(ResultSet result = statement.executeQuery())
         {
            while(result.next())
            {
               rows.add(row(result));
            }
         }
      }
      return (rows);
   }



   /*******************************************************************************
    ** Rebuild and verify a row: the preserved manifest must still hash to the
    ** stored manifest and locator, or the row was altered outside this journal.
    *******************************************************************************/
   private Row row(ResultSet result) throws SQLException, QException
   {
      UploadCandidate candidate;
      try
      {
         candidate = new UploadCandidate(result.getString("table_name"), result.getString("record_id"), result.getString("created_by_user_id"),
            result.getString("file_name"), result.getString("content_type"), result.getString("description"), result.getLong("size_bytes"),
            result.getString("sha256"), result.getString("bucket_name"), result.getString("storage_key"), result.getBoolean("retained"),
            result.getString("intake_key"), result.getString("intake_fingerprint"));
      }
      catch(IllegalArgumentException e)
      {
         throw (new UploadJournalException(Reason.IMMUTABLE_CONFLICT, "A journal row no longer holds a valid manifest."));
      }
      String manifestHash = result.getString("manifest_hash");
      if(!candidate.manifestHash().equals(manifestHash) || !candidate.locatorHash().equals(result.getString("locator_hash")))
      {
         throw (new UploadJournalException(Reason.IMMUTABLE_CONFLICT, "A journal row's manifest no longer matches its recorded hashes."));
      }
      int attachment = result.getInt("attachment_id");
      Integer attachmentId = result.wasNull() ? null : attachment;
      Timestamp leaseUntil = "MySQL".equals(result.getStatement().getConnection().getMetaData().getDatabaseProductName())
         ? result.getTimestamp("lease_until", Calendar.getInstance(TimeZone.getTimeZone("UTC"))) : result.getTimestamp("lease_until");
      UploadEntry entry;
      try
      {
         entry = new UploadEntry(result.getInt("id"), candidate, UploadJournalState.valueOf(result.getString("state")),
            UploadStorageState.valueOf(result.getString("storage_state")), attachmentId, result.getLong("fence"),
            leaseUntil == null ? null : leaseUntil.toInstant());
      }
      catch(IllegalArgumentException e)
      {
         throw (new UploadJournalException(Reason.ILLEGAL_STATE, "A journal row holds an unknown state."));
      }
      return (new Row(entry, result.getString("lease_token"), manifestHash));
   }



   /*******************************************************************************
    ** Execute one bound update and return its row count.
    *******************************************************************************/
   private int update(Connection connection, String sql, Object... parameters) throws SQLException
   {
      try(PreparedStatement statement = connection.prepareStatement(sql))
      {
         statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
         for(int i = 0; i < parameters.length; i++)
         {
            if(parameters[i] instanceof Timestamp timestamp && "MySQL".equals(connection.getMetaData().getDatabaseProductName()))
            {
               statement.setTimestamp(i + 1, timestamp, Calendar.getInstance(TimeZone.getTimeZone("UTC")));
            }
            else
            {
               statement.setObject(i + 1, parameters[i]);
            }
         }
         return (statement.executeUpdate());
      }
   }



   /*******************************************************************************
    ** Current time at the millisecond precision the database stores.
    *******************************************************************************/
   private Instant now()
   {
      return (clock.instant().truncatedTo(ChronoUnit.MILLIS));
   }



   /*******************************************************************************
    ** Own one real transaction: qualify it, run the work, then commit; roll back
    ** every failure so there is never a partial success.
    *******************************************************************************/
   private <T> T inTransaction(Work<T> work) throws QException
   {
      if(QContext.getQBackendTransaction() != null)
      {
         throw (new UploadJournalException(Reason.AMBIENT_TRANSACTION, "The upload journal owns its transactions and cannot join an ambient one."));
      }
      RDBMSBackendMetaData backend = requireQualifiedBackends();
      try(QBackendTransaction opened = new RDBMSBackendModule().openTransaction(new InsertInput(TABLE)))
      {
         if(!(opened instanceof RDBMSTransaction transaction) || !transaction.getClass().equals(RDBMSTransaction.class))
         {
            throw (new UploadJournalException(Reason.UNSUPPORTED_BACKEND, "The upload journal requires the database's actual transaction."));
         }
         try
         {
            requireQualifiedConnection(transaction.getConnection(), backend);
            T result = work.run(transaction, transaction.getConnection());
            transaction.commit();
            return (result);
         }
         catch(SQLException e)
         {
            rollback(transaction, e);
            throw (map(e));
         }
         catch(QException | RuntimeException failure)
         {
            rollback(transaction, failure);
            throw (failure);
         }
      }
   }



   /*******************************************************************************
    ** Roll back, keeping any rollback failure as suppressed on the real failure.
    *******************************************************************************/
   private static void rollback(RDBMSTransaction transaction, Throwable failure)
   {
      try
      {
         transaction.rollback();
      }
      catch(QException | RuntimeException rollbackFailure)
      {
         failure.addSuppressed(rollbackFailure);
      }
   }



   /*******************************************************************************
    ** Classify a database failure by SQLSTATE: unique violation, bounded-wait
    ** lock timeout or deadlock, or anything else.
    *******************************************************************************/
   private static UploadJournalException map(SQLException e)
   {
      Throwable cursor = e;
      for(int depth = 0; cursor != null && depth < 8; depth++, cursor = cursor.getCause())
      {
         if(cursor instanceof SQLException sql)
         {
            if("23505".equals(sql.getSQLState()) || ("23000".equals(sql.getSQLState()) && sql.getErrorCode() == 1062))
            {
               return (new UploadJournalException(Reason.DUPLICATE, "The journal row already exists.", e));
            }
            if("50200".equals(sql.getSQLState()) || "40001".equals(sql.getSQLState()) || "HYT00".equals(sql.getSQLState())
               || "55P03".equals(sql.getSQLState()) || "40P01".equals(sql.getSQLState())
               || sql.getErrorCode() == 1205 || sql.getErrorCode() == 1213)
            {
               return (new UploadJournalException(Reason.BUSY, "The upload journal row stayed busy past the bounded wait.", e));
            }
         }
      }
      return (new UploadJournalException(Reason.DATABASE, "The upload journal database operation failed.", e));
   }



   /*******************************************************************************
    ** The journal and the attachment table must share one qualified backend, so the
    ** metadata insert and the checkpoint can be one real transaction.
    *******************************************************************************/
   private static RDBMSBackendMetaData requireQualifiedBackends() throws QException
   {
      QInstance instance = QContext.getQInstance();
      QBackendMetaData journal = instance == null ? null : instance.getBackendForTable(TABLE);
      QBackendMetaData attachments = instance == null ? null : instance.getBackendForTable(CollaborationTables.ATTACHMENT);
      if(!isQualifiedBackend(journal) || attachments == null || !journal.getName().equals(attachments.getName()))
      {
         throw (new UploadJournalException(Reason.UNSUPPORTED_BACKEND,
            "The upload journal requires qualified H2, MySQL 8.4 or PostgreSQL 17 shared by the journal and attachment tables."));
      }
      return ((RDBMSBackendMetaData) journal);
   }



   /*******************************************************************************
    ** Qualify the actual connection, not merely its class: open, writable,
    ** transactional, the configured file database, and an actual SETTINGS query
    ** reporting WRITE_DELAY=0 so a commit is on disk before it returns.
    *******************************************************************************/
   private static void requireQualifiedConnection(Connection connection, RDBMSBackendMetaData backend) throws SQLException, QException
   {
      if(PostgresJournalDatabase.is(backend))
      {
         PostgresJournalDatabase.requireQualifiedConnection(connection, backend);
         return;
      }
      if("mysql".equals(backend.getVendor()))
      {
         MySqlJournalDatabase.requireQualifiedConnection(connection, backend);
         return;
      }
      String expected = backend.getJdbcUrl().split(";")[0];
      if(connection.isClosed() || connection.isReadOnly() || connection.getAutoCommit()
         || !expected.equals(String.valueOf(connection.getMetaData().getURL()).split(";")[0]))
      {
         throw (new UploadJournalException(Reason.UNSUPPORTED_BACKEND, "The upload journal needs a writable, non-autocommit connection to its configured database."));
      }
      boolean writeDelayIsZero = false;
      try(Statement statement = connection.createStatement())
      {
         statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
         try(ResultSet settings = statement.executeQuery("SELECT * FROM INFORMATION_SCHEMA.SETTINGS"))
         {
            while(settings.next())
            {
               if("WRITE_DELAY".equalsIgnoreCase(settings.getString(1)))
               {
                  writeDelayIsZero = "0".equals(settings.getString(2));
               }
            }
         }
      }
      if(!writeDelayIsZero)
      {
         throw (new UploadJournalException(Reason.UNSUPPORTED_BACKEND, "The upload journal requires H2 WRITE_DELAY=0 as reported by the database's SETTINGS."));
      }
   }
}
