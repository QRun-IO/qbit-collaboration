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


import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.collaboration.PersonalRecords;
import com.kingsrook.qbits.collaboration.attachments.uploadjournal.UploadCandidate;
import com.kingsrook.qbits.collaboration.attachments.uploadjournal.UploadEntry;
import com.kingsrook.qbits.collaboration.attachments.uploadjournal.UploadJournal;
import com.kingsrook.qbits.collaboration.attachments.uploadjournal.UploadJournalState;
import com.kingsrook.qbits.collaboration.attachments.uploadjournal.UploadReservation;
import com.kingsrook.qbits.collaboration.attachments.uploadjournal.UploadStorageState;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.security.ParentRecordAccess;
import com.kingsrook.qbits.objectstorage.api.ObjectStream;
import com.kingsrook.qbits.objectstorage.api.PutRequest;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QNotFoundException;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;


/*******************************************************************************
 ** Retained-only coordination of one immutable original and its durable owner
 ** manifest. All database ownership stays in Collaboration; lifecycle registration
 ** may commit in its own module after the attachment checkpoint is durable.
 *******************************************************************************/
final class JournaledAttachmentUpload
{
   /*******************************************************************************
    ** The caller can avoid repeating non-mandatory timeline activity on replay.
    *******************************************************************************/
   record Receipt(QRecord attachment, boolean alreadyComplete) {}

   static final long MAX_BYTES = 20L * 1024 * 1024;
   private static final Duration LEASE = Duration.ofMinutes(5);
   private final AttachmentStorage storage;
   private final AttachmentLifecyclePolicy lifecycle;
   private final UploadJournal journal;

   /*******************************************************************************
    ** Explicit owner dependencies; the journal never receives file bytes.
    *******************************************************************************/
   JournaledAttachmentUpload(AttachmentStorage storage, AttachmentLifecyclePolicy lifecycle, UploadJournal journal)
   {
      this.storage = storage;
      this.lifecycle = lifecycle;
      this.journal = journal;
   }

   /*******************************************************************************
    ** Persist intent before any PUT. Conditional creation never replaces an
    ** original. A late or expired PUT cannot authorize metadata registration.
    *******************************************************************************/
   Receipt store(QRecord row, byte[] bytes) throws QException
   {
      requireIndependent();
      UploadCandidate candidate = candidate(row);
      authorize(candidate);
      if(bytes.length > MAX_BYTES || bytes.length != candidate.sizeBytes() || !hash(bytes).equals(candidate.sha256()))
      {
         throw (new QException("Retained original exceeds the qualified bound or changed before storage."));
      }
      UploadEntry entry = journal.prepare(candidate);
      if(entry.state() == UploadJournalState.COMPLETE)
      {
         QRecord existing = stored(entry);
         lifecycle.afterStored(new QRecord(existing));
         return (new Receipt(existing, true));
      }
      UploadReservation reservation = journal.reserve(entry.id(), LEASE);
      try
      {
         lifecycle.beforeStored(new QRecord(row));
         reservation = journal.renew(reservation, LEASE);
         if(entry.storageState() != UploadStorageState.VERIFIED)
         {
            journal.recordStorage(reservation, UploadStorageState.UNKNOWN);
            try
            {
               storage.put(candidate.bucketName(), PutRequest.of(candidate.storageKey(), bytes).withContentLength(candidate.sizeBytes())
                  .withContentType(candidate.contentType()).withContentDisposition("attachment").withIfNoneMatch(true)
                  .withMetadata(Map.of("qqq-upload-manifest", candidate.manifestHash())));
            }
            catch(RuntimeException uncertain)
            {
               try
               {
                  verify(candidate);
               }
               catch(QException verification)
               {
                  verification.addSuppressed(uncertain);
                  throw (verification);
               }
            }
         }
         else
         {
            verify(candidate);
         }
         reservation = journal.renew(reservation, LEASE);
         journal.recordStorage(reservation, UploadStorageState.VERIFIED);
         return (new Receipt(register(reservation), false));
      }
      finally
      {
         journal.release(reservation);
      }
   }

   /*******************************************************************************
    ** Standard recovery reads an already stored original and never writes bytes.
    ** Coordinated intake must retry through its original coordinator and scope.
    *******************************************************************************/
   QRecord recover(Integer journalId) throws QException
   {
      requireIndependent();
      UploadEntry entry = journal.find(journalId).orElseThrow(() -> new QNotFoundException("Not found"));
      if(!PersonalRecords.currentUserId().equals(entry.candidate().actorId()))
      {
         throw (new QNotFoundException("Not found"));
      }
      if(entry.state() == UploadJournalState.COMPLETE)
      {
         QRecord row = stored(entry);
         ParentRecordAccess.requireFeature(row.getValueString("tableName"), row.getValueString("recordId"), "attach");
         return (row);
      }
      authorize(entry.candidate());
      if(entry.candidate().intakeKey() != null)
      {
         throw (new QException("Retry this upload through its original intake request to preserve its registration context."));
      }
      UploadReservation reservation = journal.reserve(entry.id(), LEASE);
      try
      {
         lifecycle.beforeRecovery(row(entry.candidate()));
         verify(entry.candidate());
         reservation = journal.renew(reservation, LEASE);
         journal.recordStorage(reservation, UploadStorageState.VERIFIED);
         return (register(reservation));
      }
      finally
      {
         journal.release(reservation);
      }
   }

   /*******************************************************************************
    ** Attachment and journal checkpoint commit together. Replays use the same
    ** immutable attachment id and retry the mandatory idempotent lifecycle hook.
    *******************************************************************************/
   private QRecord register(UploadReservation reservation) throws QException
   {
      authorize(reservation.candidate());
      UploadEntry entry = journal.register(reservation, (transaction, candidate, journalId) ->
      {
         InsertInput input = new InsertInput(CollaborationTables.ATTACHMENT).withTransaction(transaction).withRecords(List.of(row(candidate)));
         input.withPermissionBypass("Retained owner journal reauthorized the original actor and current parent attach capability.");
         List<QRecord> records = new InsertAction().execute(input).getRecords();
         AttachmentRecords.checkErrors(records);
         if(records.size() != 1)
         {
            throw (new QException("Retained registration did not create exactly one owner row."));
         }
         return (records.getFirst().getValueInteger("id"));
      });
      QRecord stored = stored(entry);
      lifecycle.afterStored(new QRecord(stored));
      reservation = journal.renew(reservation, LEASE);
      journal.complete(reservation);
      return (stored);
   }

   /*******************************************************************************
    ** Exact original bytes must be readable; missing or ambiguous storage never
    ** becomes a cancellation or permission to delete the source record.
    *******************************************************************************/
   private void verify(UploadCandidate candidate) throws QException
   {
      if(candidate.sizeBytes() > MAX_BYTES)
      {
         throw (new QException("Retained recovery exceeds the qualified content bound."));
      }
      try(ObjectStream object = storage.open(candidate.bucketName(), candidate.storageKey()))
      {
         byte[] bytes = object.getInputStream().readNBytes(Math.toIntExact(candidate.sizeBytes()) + 1);
         if(bytes.length != candidate.sizeBytes() || !hash(bytes).equals(candidate.sha256()))
         {
            throw (new QException("Stored original does not match its immutable journal manifest."));
         }
      }
      catch(IOException | RuntimeException failure)
      {
         throw (new QException("Original storage remains unresolved; no absence or deletion is inferred.", failure));
      }
   }

   /*******************************************************************************
    ** Resolve the checkpoint without exposing storage coordinates to clients.
    *******************************************************************************/
   private QRecord stored(UploadEntry entry) throws QException
   {
      List<QRecord> rows = AttachmentRecords.query(new QQueryFilter().withCriteria("id", QCriteriaOperator.EQUALS, entry.attachmentId()).withLimit(1));
      if(rows.size() != 1 || !entry.candidate().sha256().equals(rows.getFirst().getValueString("sha256"))
         || !entry.candidate().sizeBytes().equals(rows.getFirst().getValueLong("sizeBytes"))
         || !entry.candidate().actorId().equals(rows.getFirst().getValueString("createdByUserId")))
      {
         throw (new QException("Retained journal checkpoint does not match its immutable attachment."));
      }
      return (rows.getFirst());
   }

   /*******************************************************************************
    ** Authorization is current and actor-specific; the manifest cannot impersonate.
    *******************************************************************************/
   private void authorize(UploadCandidate candidate) throws QException
   {
      if(!PersonalRecords.currentUserId().equals(candidate.actorId()))
      {
         throw (new QNotFoundException("Not found"));
      }
      ParentRecordAccess.requireFeature(candidate.tableName(), candidate.recordId(), "attach");
   }

   /*******************************************************************************
    ** Retained registration cannot participate in an unrelated caller transaction.
    *******************************************************************************/
   private void requireIndependent() throws QException
   {
      if(QContext.getQBackendTransaction() != null)
      {
         throw (new QException("Retained upload recovery requires its own committed transactions."));
      }
   }

   /*******************************************************************************
    ** Server-owned values only; no client-supplied locator or actor is accepted.
    *******************************************************************************/
   private UploadCandidate candidate(QRecord row)
   {
      return (new UploadCandidate(row.getValueString("tableName"), row.getValueString("recordId"), row.getValueString("createdByUserId"),
         row.getValueString("fileName"), row.getValueString("contentType"), row.getValueString("description"), row.getValueLong("sizeBytes"),
         row.getValueString("sha256"), row.getValueString("bucketName"), row.getValueString("storageKey"), row.getValueBoolean("retained"),
         row.getValueString("intakeKey"), row.getValueString("intakeFingerprint")));
   }

   /*******************************************************************************
    ** Reconstruct only owner metadata from the immutable saved manifest.
    *******************************************************************************/
   private QRecord row(UploadCandidate candidate)
   {
      return (new QRecord().withValue("tableName", candidate.tableName()).withValue("recordId", candidate.recordId())
         .withValue("createdByUserId", candidate.actorId()).withValue("fileName", candidate.fileName()).withValue("contentType", candidate.contentType())
         .withValue("description", candidate.description()).withValue("sizeBytes", candidate.sizeBytes()).withValue("sha256", candidate.sha256())
         .withValue("bucketName", candidate.bucketName()).withValue("storageKey", candidate.storageKey()).withValue("retained", true)
         .withValue("parentDeleted", false).withValue("intakeKey", candidate.intakeKey()).withValue("intakeFingerprint", candidate.intakeFingerprint()));
   }

   /*******************************************************************************
    ** SHA-256 of actual bounded bytes, never a storage metadata assertion.
    *******************************************************************************/
   private String hash(byte[] bytes) throws QException
   {
      try
      {
         return (HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
      }
      catch(NoSuchAlgorithmException failure)
      {
         throw (new QException("SHA-256 is unavailable.", failure));
      }
   }
}
