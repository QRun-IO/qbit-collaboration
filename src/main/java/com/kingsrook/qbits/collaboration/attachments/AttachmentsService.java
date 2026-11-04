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

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import com.kingsrook.qbits.collaboration.CollaborationQBitConfig;
import com.kingsrook.qbits.collaboration.CollaborationSecurity;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.permissions.CollaborationPermissions;
import com.kingsrook.qbits.collaboration.security.ParentRecordAccess;
import com.kingsrook.qbits.objectstorage.api.ObjectStream;
import com.kingsrook.qbits.objectstorage.api.PresignGet;
import com.kingsrook.qbits.objectstorage.api.PutRequest;
import com.kingsrook.qqq.backend.core.actions.QBackendTransaction;
import com.kingsrook.qqq.backend.core.actions.tables.DeleteAction;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.actions.tables.UpdateAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QBadRequestException;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QNotFoundException;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.actions.tables.delete.DeleteInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;

/*******************************************************************************
 ** Attachment operations with parent access checked using the caller's session.
 *******************************************************************************/
public class AttachmentsService
{
   private final CollaborationQBitConfig config;
   private final AttachmentStorage storage;
   private final AttachmentLifecyclePolicy lifecycle;
   private final com.kingsrook.qbits.collaboration.attachments.uploadjournal.UploadJournal uploadJournal;

   /*******************************************************************************
    ** Resolve the installed configuration without a global mutable singleton.
    *******************************************************************************/
   public AttachmentsService()
   {
      this.config = (CollaborationQBitConfig) QContext.getQInstance().getQBits().values().stream()
         .filter(qbit -> qbit.getConfig() instanceof CollaborationQBitConfig).findFirst().orElseThrow().getConfig();
      this.storage = com.kingsrook.qqq.backend.core.actions.customizers.QCodeLoader.getAdHoc(AttachmentStorage.class, config.getAttachmentStorage());
      this.lifecycle = resolveLifecycle(config);
      this.uploadJournal = new com.kingsrook.qbits.collaboration.attachments.uploadjournal.JdbcUploadJournal();
   }

   /*******************************************************************************
    ** Injectable storage boundary for deterministic service tests.
    *******************************************************************************/
   public AttachmentsService(CollaborationQBitConfig config, AttachmentStorage storage)
   {
      this(config, storage, resolveLifecycle(config));
   }

   /*******************************************************************************
    ** Package-local deterministic lifecycle seam for service tests.
    *******************************************************************************/
   AttachmentsService(CollaborationQBitConfig config, AttachmentStorage storage, AttachmentLifecyclePolicy lifecycle)
   {
      this(config, storage, lifecycle, new com.kingsrook.qbits.collaboration.attachments.uploadjournal.JdbcUploadJournal());
   }

   /*******************************************************************************
    ** Explicit test-only package seam; public services always use the durable
    ** qualified journal. It cannot be replaced through application configuration.
    *******************************************************************************/
   AttachmentsService(CollaborationQBitConfig config, AttachmentStorage storage, AttachmentLifecyclePolicy lifecycle,
      com.kingsrook.qbits.collaboration.attachments.uploadjournal.UploadJournal uploadJournal)
   {
      this.config = config;
      this.storage = storage;
      this.lifecycle = java.util.Objects.requireNonNull(lifecycle, "lifecycle");
      this.uploadJournal = java.util.Objects.requireNonNull(uploadJournal, "uploadJournal");
   }

   /*******************************************************************************
    ** Invalid policy configuration must fail before any storage side effect.
    *******************************************************************************/
   private static AttachmentLifecyclePolicy resolveLifecycle(CollaborationQBitConfig config)
   {
      return (java.util.Objects.requireNonNull(com.kingsrook.qqq.backend.core.actions.customizers.QCodeLoader
         .getAdHoc(AttachmentLifecyclePolicy.class, config.getAttachmentLifecycle()), "Could not load attachment lifecycle policy."));
   }

   /*******************************************************************************
    ** Stream to a bounded temporary file while hashing, then put and insert.
    ** The caller owns the input stream. Oversize files never reach object storage.
    *******************************************************************************/
   public AttachmentEntry upload(String tableName, Serializable recordId, String fileName, String contentType, InputStream stream, String description) throws QException
   {
      return (uploadInternal(tableName, recordId, fileName, contentType, stream, description, null, null));
   }

   /*******************************************************************************
    ** Retryable intake for a server coordinator. Keys and manifests are SHA-256
    ** identifiers; keys are additionally scoped to actual actor and parent here.
    ** This path owns persistence and refuses ambient transactions. Replays verify
    ** supplied bytes and rerun registration; they never create another original.
    *******************************************************************************/
   public AttachmentEntry upload(String tableName, Serializable recordId, String fileName, String contentType, InputStream stream, String description,
      String intakeKey, String manifestSha256) throws QException
   {
      if(intakeKey == null || !intakeKey.matches("[0-9a-f]{64}") || manifestSha256 == null || !manifestSha256.matches("[0-9a-f]{64}"))
      {
         throw (new QBadRequestException("Intake key and manifest must be SHA-256 identifiers."));
      }
      if(QContext.getQBackendTransaction() != null)
      {
         throw (new QException("Retryable attachment intake cannot join an ambient transaction."));
      }
      return (uploadInternal(tableName, recordId, fileName, contentType, stream, description, intakeKey, manifestSha256));
   }

   /*******************************************************************************
    ** Shared bounded intake; the retryable path preserves uncertain objects for
    ** reconciliation under a deterministic key and never overwrites them.
    *******************************************************************************/
   private AttachmentEntry uploadInternal(String tableName, Serializable recordId, String fileName, String contentType, InputStream stream, String description,
      String intakeKey, String manifestSha256) throws QException
   {
      new com.kingsrook.qbits.collaboration.people.PeopleService().touch();
      ParentRecordAccess.requireFeature(tableName, recordId, "attach");
      String user = requireUser();
      String id = canonicalId(tableName, recordId);
      String name = sanitize(fileName);
      String bucket = config.getAttachmentsBucketName();
      String scopedKey = intakeKey == null ? null : tupleHash("attachment-intake-v1", user, tableName, id, intakeKey);
      String key = scopedKey == null ? "attachments/" + segment(tableName) + "/" + segment(id) + "/" + UUID.randomUUID() + "/" + name
         : "attachments/intake/" + scopedKey;
      if(tableName.length() > 128 || id.length() > 64 || user.length() > 255 || bucket.length() > 255 || key.length() > 1024
         || (contentType != null && contentType.length() > 255))
      {
         throw (new QBadRequestException("Attachment metadata exceeds the supported field lengths."));
      }
      long limit = Math.min(config.getDefaultMaxBytes(), storage.maxBytes(bucket));
      if(scopedKey != null)
      {
         limit = Math.min(limit, 20L * 1024 * 1024);
      }
      Path temporary = null;
      try
      {
         MessageDigest digest = MessageDigest.getInstance("SHA-256");
         temporary = Files.createTempFile("collaboration-attachment-", ".upload");
         long size = 0;
         try(OutputStream out = Files.newOutputStream(temporary))
         {
            byte[] buffer = new byte[8192];
            int read;
            while((read = stream.read(buffer)) != -1)
            {
               if(read > limit - size)
               {
                  throw (new QBadRequestException("Attachment exceeds the size limit of " + limit + " bytes."));
               }
               size += read;
               digest.update(buffer, 0, read);
               out.write(buffer, 0, read);
            }
         }
         String type = contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
         QRecord row = new QRecord().withValue("tableName", tableName).withValue("recordId", id).withValue("fileName", name)
            .withValue("contentType", type).withValue("sizeBytes", size).withValue("sha256", HexFormat.of().formatHex(digest.digest()))
            .withValue("bucketName", bucket).withValue("storageKey", key).withValue("description", description)
            .withValue("createdByUserId", user).withValue("parentDeleted", false);
         row.withValue("retained", lifecycle.isRetained(new QRecord(row)));
         if(scopedKey != null)
         {
            String fingerprint = tupleHash("attachment-manifest-v1", manifestSha256, name, type, description, String.valueOf(size), row.getValueString("sha256"));
            row.withValue("intakeKey", scopedKey).withValue("intakeFingerprint", fingerprint);
            QRecord prior = findIntake(scopedKey);
            if(prior != null)
            {
               if(Boolean.TRUE.equals(prior.getValueBoolean("retained")) && uploadJournal.findByLocation(bucket, key).isPresent())
               {
                  return (journaledUpload(row, temporary));
               }
               return (replayIntake(prior, fingerprint));
            }
            if(Boolean.TRUE.equals(row.getValueBoolean("retained")))
            {
               return (journaledUpload(row, temporary));
            }
            beforeObjectWrite(row);
            createIntakeObject(bucket, key, Files.readAllBytes(temporary), type, fingerprint);
         }
         else
         {
            if(Boolean.TRUE.equals(row.getValueBoolean("retained")))
            {
               return (journaledUpload(row, temporary));
            }
            beforeObjectWrite(row);
            ////////////////////////////////////////////////////////////////////////
            // ordinary uploads keep the transactional cleanup-queue path (355b06d) //
            ////////////////////////////////////////////////////////////////////////
            return (storeUpload(row, temporary));
         }
         boolean rowSaved = false;
         try
         {
            InsertInput input = new InsertInput(CollaborationTables.ATTACHMENT).withTransaction(QContext.getQBackendTransaction()).withRecords(List.of(row));
            input.withPermissionBypass("AttachmentsService checked parent and attach permission before upload.");
            List<QRecord> records = new InsertAction().execute(input).getRecords();
            AttachmentRecords.checkErrors(records);
            rowSaved = true;
            lifecycle.afterStored(new QRecord(records.getFirst()));
            com.kingsrook.qbits.collaboration.activity.ActivityWriter.write(tableName, id, "FILE_ADDED", "File added", CollaborationTables.ATTACHMENT, records.getFirst().getValueString("id"));
            return (entry(records.getFirst()));
         }
         catch(QException | RuntimeException failure)
         {
            if(scopedKey != null)
            {
               QRecord prior = findIntake(scopedKey);
               if(!rowSaved && prior != null)
               {
                  return (replayIntake(prior, row.getValueString("intakeFingerprint")));
               }
               throw (failure);
            }
            try
            {
               if(!rowSaved && !Boolean.TRUE.equals(row.getValueBoolean("retained")))
               {
                  storage.delete(bucket, key);
               }
            }
            catch(RuntimeException cleanupFailure)
            {
               failure.addSuppressed(cleanupFailure);
            }
            throw (failure);
         }
      }
      catch(IOException | NoSuchAlgorithmException e)
      {
         throw (new QException("Could not read attachment upload", e));
      }
      finally
      {
         if(temporary != null)
         {
            try
            {
               Files.deleteIfExists(temporary);
            }
            catch(IOException e)
            {
               temporary.toFile().deleteOnExit();
            }
         }
      }
   }

   /*******************************************************************************
    ** On transactional locking backends, own a transaction only after spooling
    ** the bounded input. An existing outer transaction retains the upload lock.
    *******************************************************************************/
   private AttachmentEntry storeUpload(QRecord row, Path temporary) throws QException, IOException
   {
      var transaction = QContext.getQBackendTransaction();
      if(transaction == null && AttachmentCleanupQueue.supportsUploadOwnership())
      {
         try(var owned = com.kingsrook.qqq.backend.core.actions.QBackendTransaction.openFor(new InsertInput(CollaborationTables.ATTACHMENT)))
         {
            QContext.setTransaction(owned);
            try
            {
               AttachmentEntry result = storeUpload(row, temporary);
               owned.commit();
               return (result);
            }
            catch(QException | IOException | RuntimeException failure)
            {
               try
               {
                  owned.rollback();
               }
               catch(QException rollbackFailure)
               {
                  failure.addSuppressed(rollbackFailure);
               }
               throw (failure);
            }
            finally
            {
               QContext.clearTransaction();
            }
         }
      }
      if(transaction != null && !transaction.supportsRollbackCallbacks())
      {
         throw (new QException("Attachment uploads require a transaction with confirmed rollback support."));
      }
      String bucket = row.getValueString("bucketName");
      String key = row.getValueString("storageKey");
      AttachmentCleanupQueue cleanup = new AttachmentCleanupQueue(storage);
      QRecord pending = cleanup.beginUpload(bucket, key);
      try(InputStream upload = Files.newInputStream(temporary))
      {
         storage.put(bucket, PutRequest.of(key, upload).withContentLength(row.getValueLong("sizeBytes"))
            .withContentType(AttachmentMediaPolicy.storedType(row.getValueString("contentType")))
            .withContentDisposition(AttachmentMediaPolicy.disposition(row.getValueString("contentType"))));
      }
      catch(IOException | RuntimeException failure)
      {
         if(pending == null)
         {
            try
            {
               cleanup.compensateUpload(bucket, key);
            }
            catch(RuntimeException cleanupFailure)
            {
               failure.addSuppressed(cleanupFailure);
            }
         }
         throw (failure);
      }
      if(transaction != null && pending == null)
      {
         var context = new com.kingsrook.qqq.backend.core.context.CapturedContext(QContext.getQInstance(), QContext.getQSession());
         transaction.addAfterRollbackCallback(() -> QContext.withTemporaryContext(context,
            () -> cleanup.compensateUpload(bucket, key)));
      }
      boolean rowSaved = false;
      try
      {
         InsertInput input = new InsertInput(CollaborationTables.ATTACHMENT).withTransaction(QContext.getQBackendTransaction()).withRecords(List.of(row));
         input.withPermissionBypass("AttachmentsService checked parent and attach permission before upload.");
         List<QRecord> records = new InsertAction().execute(input).getRecords();
         AttachmentRecords.checkErrors(records);
         rowSaved = true;
         cleanup.completeUpload(pending);
         lifecycle.afterStored(new QRecord(records.getFirst()));
         com.kingsrook.qbits.collaboration.activity.ActivityWriter.write(row.getValueString("tableName"), row.getValueString("recordId"),
            "FILE_ADDED", "File added", CollaborationTables.ATTACHMENT, records.getFirst().getValueString("id"));
         return (entry(records.getFirst()));
      }
      catch(QException | RuntimeException failure)
      {
         if(!rowSaved && pending == null)
         {
            try
            {
               cleanup.compensateUpload(bucket, key);
            }
            catch(RuntimeException cleanupFailure)
            {
               failure.addSuppressed(cleanupFailure);
            }
         }
         throw (failure);
      }
   }

   /*******************************************************************************
    ** Retained bytes cannot be written before durable intent or under an ambient
    ** transaction whose later rollback could lose their metadata association.
    *******************************************************************************/
   private void beforeObjectWrite(QRecord row) throws QException
   {
      if(Boolean.TRUE.equals(row.getValueBoolean("retained")) && QContext.getQBackendTransaction() != null)
      {
         throw (new QException("Retained attachment storage cannot join an ambient transaction."));
      }
      lifecycle.beforeStored(new QRecord(row));
   }

   /*******************************************************************************
    ** Retained originals use qualified bounded conditional creation and durable
    ** owner recovery; ordinary uploads retain the established streaming behavior.
    *******************************************************************************/
   private AttachmentEntry journaledUpload(QRecord row, Path temporary) throws QException, IOException
   {
      if(row.getValueLong("sizeBytes") > JournaledAttachmentUpload.MAX_BYTES)
      {
         throw (new QBadRequestException("Retained uploads are limited to 20 MiB by the qualified conditional storage path."));
      }
      var receipt = new JournaledAttachmentUpload(storage, lifecycle, uploadJournal).store(row, Files.readAllBytes(temporary));
      QRecord stored = receipt.attachment();
      if(!receipt.alreadyComplete())
      {
         com.kingsrook.qbits.collaboration.activity.ActivityWriter.write(stored.getValueString("tableName"), stored.getValueString("recordId"),
            "FILE_ADDED", "File added", CollaborationTables.ATTACHMENT, stored.getValueString("id"));
      }
      return (entry(stored));
   }

   /*******************************************************************************
    ** Native confirmation discovery checks the configured current adoption policy.
    ** No original bytes are read and no audit success is asserted during review.
    *******************************************************************************/
   public QRecord requireAdoptable(Integer attachmentId) throws QException
   {
      QRecord row = requireMutable(attachmentId);
      lifecycle.authorizeAdoption(new QRecord(row));
      return (row);
   }

   /*******************************************************************************
    ** Adopt this exact visible original without uploading, copying or deleting
    ** storage. Registration retries retain the same owner attachment identity.
    *******************************************************************************/
   public AttachmentEntry adoptRetainedAttachment(Integer attachmentId) throws QException
   {
      new ExistingAttachmentAdoption(this, storage, lifecycle).adopt(attachmentId);
      return (entry(requireRow(attachmentId)));
   }

   /*******************************************************************************
    ** Recover a visible retained upload without accepting bytes, locators or an
    ** actor from the client. Original-actor and current-parent checks are mandatory.
    *******************************************************************************/
   public AttachmentEntry recoverRetainedUpload(Integer journalId) throws QException
   {
      return (entry(new JournaledAttachmentUpload(storage, lifecycle, uploadJournal).recover(journalId)));
   }

   /*******************************************************************************
    ** Conditional single-request puts retain the same original across retries.
    ** Byte arrays deliberately avoid a multipart provider path whose conditional
    ** completion support is not qualified. The caller enforces a 20 MiB limit.
    *******************************************************************************/
   private void createIntakeObject(String bucket, String key, byte[] bytes, String type, String fingerprint) throws QException
   {
      try
      {
         storage.put(bucket, PutRequest.of(key, bytes).withContentLength((long) bytes.length).withContentType(AttachmentMediaPolicy.storedType(type))
            .withContentDisposition("attachment").withIfNoneMatch(true).withMetadata(Map.of("qqq-intake-fingerprint", fingerprint)));
      }
      catch(RuntimeException uncertain)
      {
         try
         {
            if(storage.head(bucket, key).isEmpty())
            {
               throw (uncertain);
            }
            try(ObjectStream existing = storage.open(bucket, key))
            {
               byte[] original = existing.getInputStream().readNBytes(bytes.length + 1);
               if(existing.getHeaders() == null || existing.getHeaders().getUserMetadata() == null
                  || !fingerprint.equals(existing.getHeaders().getUserMetadata().get("qqq-intake-fingerprint")) || !MessageDigest.isEqual(original, bytes))
               {
                  throw (new QException("An intake request cannot replace an existing original."));
               }
            }
         }
         catch(IOException | RuntimeException verificationFailure)
         {
            throw (new QException("Could not reconcile attachment intake storage.", verificationFailure));
         }
      }
   }

   /*******************************************************************************
    ** Internal lookup is keyed by authenticated actor and canonical parent scope.
    *******************************************************************************/
   private QRecord findIntake(String scopedKey) throws QException
   {
      List<QRecord> rows = AttachmentRecords.query(new QQueryFilter().withCriteria("intakeKey", QCriteriaOperator.EQUALS, scopedKey).withLimit(1));
      return (rows.isEmpty() ? null : rows.getFirst());
   }

   /*******************************************************************************
    ** Reauthorize persisted identity and retry idempotent owner registration.
    *******************************************************************************/
   private AttachmentEntry replayIntake(QRecord row, String fingerprint) throws QException
   {
      if(Boolean.TRUE.equals(row.getValueBoolean("parentDeleted")))
      {
         throw (new QNotFoundException("Not found"));
      }
      ParentRecordAccess.requireFeature(row.getValueString("tableName"), row.getValueString("recordId"), "attach");
      if(!fingerprint.equals(row.getValueString("intakeFingerprint")))
      {
         throw (new QBadRequestException("Intake request conflicts with its original manifest."));
      }
      lifecycle.afterStored(new QRecord(row));
      return (entry(row));
   }

   /*******************************************************************************
    ** Length-prefix all tuple members, including null, so delimiters cannot collide.
    *******************************************************************************/
   private String tupleHash(String... values) throws QException
   {
      try
      {
         ByteArrayOutputStream buffer = new ByteArrayOutputStream();
         try(DataOutputStream output = new DataOutputStream(buffer))
         {
            for(String value : values)
            {
               byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
               output.writeInt(value == null ? -1 : bytes.length);
               output.write(bytes);
            }
         }
         return (HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(buffer.toByteArray())));
      }
      catch(IOException | NoSuchAlgorithmException e)
      {
         throw (new QException("Could not identify attachment intake.", e));
      }
   }

   /*******************************************************************************
    ** Newest first, independently of collaboration.view.
    *******************************************************************************/
   public List<AttachmentEntry> list(String tableName, Serializable recordId) throws QException
   {
      List<AttachmentEntry> entries = new ArrayList<>();
      for(QRecord row : AttachmentRecords.query(listFilter(tableName, recordId)))
      {
         entries.add(entry(row));
      }
      return (entries);
   }

   /*******************************************************************************
    ** Standard Files-tab pages use one bounded native query, with one same-parent
    ** lookahead row. Authorization is current on every request; offsets are not
    ** snapshots across concurrent insertions or deletions.
    *******************************************************************************/
   public AttachmentPage listPage(String tableName, Serializable recordId, int offset, int limit) throws QException
   {
      if(offset < 0 || limit < 1 || limit > 100)
      {
         throw (new QBadRequestException("Offset must be nonnegative and limit must be between 1 and 100."));
      }
      List<QRecord> rows = AttachmentRecords.query(listFilter(tableName, recordId).withSkip(offset).withLimit(limit + 1));
      List<AttachmentEntry> entries = new ArrayList<>();
      for(QRecord row : rows)
      {
         // Preserve the same metadata-policy evaluation for the lookahead row.
         AttachmentEntry visible = entry(row);
         if(entries.size() < limit)
         {
            entries.add(visible);
         }
      }
      return (new AttachmentPage(List.copyOf(entries), rows.size() > limit));
   }

   /*******************************************************************************
    ** Only authorized display metadata and continuation, never a raw total.
    *******************************************************************************/
   public record AttachmentPage(List<AttachmentEntry> items, boolean hasMore)
   {
   }

   /*******************************************************************************
    ** Shared canonical source authority, deletion filter and deterministic order.
    *******************************************************************************/
   private QQueryFilter listFilter(String tableName, Serializable recordId) throws QException
   {
      new com.kingsrook.qbits.collaboration.people.PeopleService().touch();
      String id = canonicalId(tableName, recordId);
      return (new QQueryFilter().withCriteria("tableName", QCriteriaOperator.EQUALS, tableName)
         .withCriteria("recordId", QCriteriaOperator.EQUALS, id).withCriteria("parentDeleted", QCriteriaOperator.NOT_EQUALS, true)
         .withOrderBy(new QFilterOrderBy("createDate", false)).withOrderBy(new QFilterOrderBy("id", false)));
   }

   /*******************************************************************************
    ** Re-check the attachment's actual parent before signing a five-minute GET.
    ** Inline uses the stored inline header; RC.2 only overrides attachment names.
    *******************************************************************************/
   public URL downloadUrl(Serializable attachmentId, boolean inline) throws QException
   {
      new com.kingsrook.qbits.collaboration.people.PeopleService().touch();
      return (url(requireRow(attachmentId), inline));
   }

   /*******************************************************************************
    ** Standard download clients preserve the selected delivery authentication mode.
    *******************************************************************************/
   public AttachmentDownload downloadDelivery(Serializable attachmentId) throws QException
   {
      new com.kingsrook.qbits.collaboration.people.PeopleService().touch();
      return (downloadDelivery(attachmentId, false));
   }

   /***************************************************************************
    ** Preserve the explicit transport mode for preview and download consumers.
    ***************************************************************************/
   public AttachmentDownload downloadDelivery(Serializable attachmentId, boolean inline) throws QException
   {
      return (delivery(requireRow(attachmentId), inline));
   }

   /*******************************************************************************
    ** Read a bounded exact original for trusted integrations such as printing.
    ** Authorize the actual parent, enforce lifecycle policy and verify persisted
    ** length and SHA-256 before releasing any bytes. No signed URL is involved.
    *******************************************************************************/
   public AttachmentContent readContent(Serializable attachmentId, Integer maximumBytes) throws QException
   {
      QRecord row = requireRow(attachmentId);
      requireUser();
      requireRetainedPolicy(row);
      Long size = row.getValueLong("sizeBytes");
      String sha256 = row.getValueString("sha256");
      if(maximumBytes == null || maximumBytes < 1 || maximumBytes > 20 * 1024 * 1024)
      {
         throw (new QBadRequestException("Content read limit must be between 1 byte and 20 MiB."));
      }
      if(size == null || size < 0 || size > maximumBytes || sha256 == null || !sha256.matches("[0-9a-f]{64}"))
      {
         throw (new QBadRequestException("Attachment exceeds the content read limit or lacks integrity metadata."));
      }
      lifecycle.beforeContentRead(new QRecord(row));
      byte[] bytes;
      try(ObjectStream content = storage.open(row.getValueString("bucketName"), row.getValueString("storageKey")))
      {
         if(content.getContentLength() != null && !size.equals(content.getContentLength()))
         {
            throw (new AttachmentContentIntegrityException());
         }
         bytes = content.getInputStream().readNBytes(size.intValue() + 1);
         String actualHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
         if(bytes.length != size.longValue() || !sha256.equals(actualHash))
         {
            throw (new AttachmentContentIntegrityException());
         }
      }
      catch(IOException | NoSuchAlgorithmException | RuntimeException e)
      {
         throw (new QException("Could not read attachment content.", e));
      }
      lifecycle.afterContentRead(new QRecord(row), size);
      return (new AttachmentContent(row.getValueInteger("id"), row.getValueString("fileName"), row.getValueString("contentType"), sha256, bytes));
   }

   /*******************************************************************************
    ** Sign an already-authorized row, also used by the preview customizer.
    *******************************************************************************/
   URL url(QRecord row, boolean inline) throws QException
   {
      return (delivery(row, inline).url());
   }

   /*******************************************************************************
    ** Resolve only the configured owner policy, after exact row authorization.
    *******************************************************************************/
   AttachmentDownload delivery(QRecord row, boolean inline) throws QException
   {
      requireRetainedPolicy(row);
      try
      {
         lifecycle.beforeAccess(new QRecord(row), inline);
      }
      catch(RuntimeException e)
      {
         // Preview generation may ignore storage failures, never policy failures.
         throw (new QException("Attachment access policy failed.", e));
      }
      AttachmentDownload configured = lifecycle.downloadDelivery(new QRecord(row));
      if(configured != null)
      {
         return (configured);
      }
      PresignGet request = PresignGet.of(row.getValueString("storageKey")).ttl(Duration.ofMinutes(5));
      ///////////////////////////////////////////////////////////////////////////////////////////
      // Objects stored before AttachmentMediaPolicy keep their original headers (possibly an   //
      // active type served inline).  The link therefore forces an attachment disposition for   //
      // every type that is not a known passive format, whatever the caller asked for; passive  //
      // previews stay inline.                                                                  //
      ///////////////////////////////////////////////////////////////////////////////////////////
      if(!inline || !"inline".equals(AttachmentMediaPolicy.disposition(row.getValueString("contentType"))))
      {
         request.downloadAs(row.getValueString("fileName"));
      }
      return (new AttachmentDownload(storage.presign(row.getValueString("bucketName"), request), false));
   }

   /*******************************************************************************
    ** Authorize all selected rows before the first storage mutation.
    *******************************************************************************/
   public void delete(List<? extends Serializable> attachmentIds) throws QException
   {
      new com.kingsrook.qbits.collaboration.people.PeopleService().touch();
      List<QRecord> rows = new ArrayList<>();
      for(Serializable id : attachmentIds.stream().distinct().toList())
      {
         rows.add(requireDeletable(id));
      }
      for(QRecord row : rows)
      {
         deleteStoredRow(row);
         com.kingsrook.qbits.collaboration.activity.ActivityWriter.write(row.getValueString("tableName"), row.getValueString("recordId"), "FILE_REMOVED", "File removed", CollaborationTables.ATTACHMENT, row.getValueString("id"));
      }
   }

   /*******************************************************************************
    ** Change display metadata only; the immutable storage key stays stable.
    ** Null means unchanged; an empty description clears it.
    *******************************************************************************/
   public AttachmentEntry rename(Serializable attachmentId, String fileName, String description) throws QException
   {
      new com.kingsrook.qbits.collaboration.people.PeopleService().touch();
      QRecord row = requireMutable(attachmentId);
      requireRetainedPolicy(row);
      QRecord change = new QRecord().withValue("id", row.getValue("id"));
      if(fileName != null)
      {
         change.setValue("fileName", sanitize(fileName));
      }
      if(description != null)
      {
         change.setValue("description", description);
      }
      lifecycle.beforeMetadataChange(new QRecord(row), new QRecord(change));
      UpdateInput input = new UpdateInput(CollaborationTables.ATTACHMENT).withTransaction(QContext.getQBackendTransaction()).withRecords(List.of(change));
      input.setTableMetaData(AttachmentRecords.storageTable());
      input.withPermissionBypass("AttachmentsService checked the actual parent and author/admin before metadata update.");
      QBackendTransaction callerTransaction = QContext.getQBackendTransaction();
      try(QBackendTransaction ownedTransaction = callerTransaction == null ? QBackendTransaction.openFor(input) : null)
      {
         QBackendTransaction transaction = ownedTransaction == null ? callerTransaction : ownedTransaction;
         input.setTransaction(transaction);
         try
         {
            lifecycle.validateMetadataTransaction(new QRecord(row), transaction);
            AttachmentRecords.checkErrors(new UpdateAction().execute(input).getRecords());
            lifecycle.afterMetadataChange(new QRecord(row), new QRecord(change), transaction);
            if(ownedTransaction != null)
            {
               ownedTransaction.commit();
            }
         }
         catch(QException | RuntimeException failure)
         {
            if(ownedTransaction != null)
            {
               try
               {
                  ownedTransaction.rollback();
               }
               catch(QException rollbackFailure)
               {
                  failure.addSuppressed(rollbackFailure);
               }
            }
            throw (failure);
         }
      }
      return (entry(requireRow(attachmentId)));
   }

   /*******************************************************************************
    ** Description-only convenience operation.
    *******************************************************************************/
   public AttachmentEntry describe(Serializable attachmentId, String description) throws QException
   {
      return (rename(attachmentId, null, description));
   }

   /*******************************************************************************
    ** Transfer a retained attachment's reference to an authorized archive parent.
    ** The caller owns the transaction and must roll it back on any failure. Bytes,
    ** hashes, author, storage coordinates and the retained flag never change.
    *******************************************************************************/
   public void transferRetainedReference(Serializable attachmentId, String tableName, Serializable recordId, QBackendTransaction transaction) throws QException
   {
      requireUser();
      if(transaction == null || (QContext.getQBackendTransaction() != null && QContext.getQBackendTransaction() != transaction))
      {
         throw (new QException("Archive transfer requires the caller's explicit transaction."));
      }
      QRecord row = requireRow(attachmentId);
      if(!Boolean.TRUE.equals(row.getValueBoolean("retained")) || tableName == null || recordId == null)
      {
         throw (new QException("Only a retained attachment can be transferred to an archive."));
      }
      var candidate = uploadJournal.findByLocation(row.getValueString("bucketName"), row.getValueString("storageKey"));
      if(candidate.isPresent() && candidate.get().state() != com.kingsrook.qbits.collaboration.attachments.uploadjournal.UploadJournalState.COMPLETE)
      {
         throw (new QException("Retained upload registration must complete before archival reference transfer."));
      }
      QRecord target = com.kingsrook.qqq.backend.core.actions.collaboration.RecordReferenceSecurityHelper.visibleParent(tableName, recordId, transaction);
      if(target == null)
      {
         throw (new QNotFoundException("Not found"));
      }
      String canonical = target.getValueString(QContext.getQInstance().getTable(tableName).getPrimaryKeyField());
      lifecycle.beforeReferenceTransfer(new QRecord(row), tableName, canonical, transaction);
      QRecord change = new QRecord().withValue("id", row.getValue("id")).withValue("tableName", tableName).withValue("recordId", canonical);
      com.kingsrook.qqq.backend.core.actions.collaboration.RecordReferenceSecurityHelper.copyMirroredLocks(
         QContext.getQInstance().getTable(tableName), target, QContext.getQInstance().getTable(CollaborationTables.ATTACHMENT), change);
      UpdateInput input = new UpdateInput(CollaborationTables.ATTACHMENT).withTransaction(transaction)
         .withRecords(List.of(change));
      input.setTableMetaData(com.kingsrook.qbits.collaboration.CollaborationRecords.cleanupMetadata(CollaborationTables.ATTACHMENT));
      input.withPermissionBypass("Installed retention owner authorized this exact archival reference transfer and transaction.");
      AttachmentRecords.checkErrors(new UpdateAction().execute(input).getRecords());
      lifecycle.afterReferenceTransfer(new QRecord(row), tableName, canonical, transaction);
   }

   /*******************************************************************************
    ** Process Review uses persisted identity rather than trusting client rows.
    *******************************************************************************/
   public QRecord requireMutable(Serializable attachmentId) throws QException
   {
      new com.kingsrook.qbits.collaboration.people.PeopleService().touch();
      QRecord row = requireRow(attachmentId);
      requireUser();
      if(!canModify(row))
      {
         throw (new QPermissionDeniedException("Only the author or collaboration.admin can change this attachment."));
      }
      ParentRecordAccess.requireFeature(row.getValueString("tableName"), row.getValueString("recordId"), "attach");
      return (row);
   }

   /*******************************************************************************
    ** Deletion reviews must include retention as well as parent and ownership.
    *******************************************************************************/
   public QRecord requireDeletable(Serializable attachmentId) throws QException
   {
      QRecord row = requireMutable(attachmentId);
      requireNotRetained(row);
      return (row);
   }

   /*******************************************************************************
    ** Atomically remove metadata and retain a durable storage cleanup request.
    *******************************************************************************/
   void deleteStoredRow(QRecord row) throws QException
   {
      deleteStoredRow(row, false);
   }

   /*******************************************************************************
    ** Core cleanup supplies an already deleted parent; user deletion retains checks.
    *******************************************************************************/
   void deleteStoredRow(QRecord row, boolean cleanup) throws QException
   {
      if(isRetained(row))
      {
         if(!cleanup)
         {
            throw (new QPermissionDeniedException("This attachment is retained and cannot be deleted."));
         }
         UpdateInput mark = new UpdateInput(CollaborationTables.ATTACHMENT).withTransaction(QContext.getQBackendTransaction())
            .withRecords(List.of(new QRecord().withValue("id", row.getValue("id")).withValue("parentDeleted", true)));
         mark.setTableMetaData(com.kingsrook.qbits.collaboration.CollaborationRecords.cleanupMetadata(CollaborationTables.ATTACHMENT));
         mark.withPermissionBypass("Trusted parent cleanup retains the original and marks its parent deleted.");
         AttachmentRecords.checkErrors(new UpdateAction().execute(mark).getRecords());
         return;
      }
      new AttachmentCleanupQueue(storage).delete(row, cleanup);
   }

   /*******************************************************************************
    ** Missing rows and invisible parents have the same response.
    *******************************************************************************/
   private QRecord requireRow(Serializable id) throws QException
   {
      List<QRecord> rows = AttachmentRecords.query(new QQueryFilter().withCriteria("id", QCriteriaOperator.EQUALS, id));
      if(rows.isEmpty() || Boolean.TRUE.equals(rows.getFirst().getValueBoolean("parentDeleted")))
      {
         throw (new QNotFoundException("Not found"));
      }
      QRecord row = rows.getFirst();
      ParentRecordAccess.requireVisibleParent(row.getValueString("tableName"), row.getValueString("recordId"));
      return (row);
   }

   /*******************************************************************************
    ** Use the parent's actual primary key so alternate numeric strings converge.
    *******************************************************************************/
   private String canonicalId(String table, Serializable id) throws QException
   {
      return (ParentRecordAccess.requireVisibleParent(table, id).getValueString(QContext.getQInstance().getTable(table).getPrimaryKeyField()));
   }

   /*******************************************************************************
    ** Ownership is layered on parent visibility; admins still need that visibility.
    *******************************************************************************/
   private boolean canModify(QRecord row)
   {
      String user = CollaborationSecurity.userId(QContext.getQSession());
      return (user != null && (isAdmin() || user.equals(row.getValueString("createdByUserId"))));
   }

   /*******************************************************************************
    ** Collaboration moderation grant.
    *******************************************************************************/
   private boolean isAdmin()
   {
      return (QContext.getQSession() != null && QContext.getQSession().hasPermission(CollaborationPermissions.ADMIN));
   }

   /*******************************************************************************
    ** Stable author identity is mandatory for writes.
    *******************************************************************************/
   private String requireUser() throws QPermissionDeniedException
   {
      String user = CollaborationSecurity.userId(QContext.getQSession());
      if(user == null || user.isBlank())
      {
         throw (new QPermissionDeniedException("Sign in to manage attachments."));
      }
      return (user);
   }

   /*******************************************************************************
    ** Return display data, without internal storage coordinates.
    *******************************************************************************/
   private AttachmentEntry entry(QRecord row) throws QException
   {
      boolean canEdit = canModify(row) && !(Boolean.TRUE.equals(row.getValueBoolean("retained")) && lifecycle.getClass().equals(AttachmentLifecyclePolicy.class));
      if(canEdit)
      {
         try
         {
            ParentRecordAccess.requireFeature(row.getValueString("tableName"), row.getValueString("recordId"), "attach");
         }
         catch(QPermissionDeniedException e)
         {
            canEdit = false;
         }
      }
      return (new AttachmentEntry(row.getValueInteger("id"), row.getValueString("tableName"), row.getValueString("recordId"),
         row.getValueString("fileName"), row.getValueString("contentType"), row.getValueLong("sizeBytes"), row.getValueString("sha256"),
         row.getValueString("description"), row.getValueString("createdByUserId"), row.getValueInstant("createDate"), canEdit && !isRetained(row), canEdit));
   }

   /*******************************************************************************
    ** Preflight every selected row before deleting any storage object.
    *******************************************************************************/
   private void requireNotRetained(QRecord row) throws QException
   {
      if(isRetained(row))
      {
         throw (new QPermissionDeniedException("This attachment is retained and cannot be deleted."));
      }
   }

   /*******************************************************************************
    ** Persisted retention survives removal or reconfiguration of the provider.
    *******************************************************************************/
   private boolean isRetained(QRecord row) throws QException
   {
      return (Boolean.TRUE.equals(row.getValueBoolean("retained")) || lifecycle.isRetained(new QRecord(row)));
   }

   /*******************************************************************************
    ** Removing the configured provider must not permit unaudited retained access.
    *******************************************************************************/
   private void requireRetainedPolicy(QRecord row) throws QException
   {
      if(Boolean.TRUE.equals(row.getValueBoolean("retained")) && lifecycle.getClass().equals(AttachmentLifecyclePolicy.class))
      {
         throw (new QException("Retained attachment access requires its lifecycle policy."));
      }
   }

   /*******************************************************************************
    ** Strip paths and unsafe header/key characters; enforce extension policy.
    *******************************************************************************/
   private String sanitize(String fileName) throws QException
   {
      if(fileName == null || fileName.isBlank())
      {
         throw (new QBadRequestException("A file name is required."));
      }
      String name = fileName.replace('\\', '/');
      name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._ -]", "_").strip();
      if(name.isBlank() || name.equals(".") || name.equals("..") || name.length() > 255)
      {
         throw (new QBadRequestException("Invalid file name."));
      }
      String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
      if(config.getBlockedExtensions().stream().anyMatch(blocked -> extension.equals(blocked.replaceFirst("^\\.", "").toLowerCase(Locale.ROOT))))
      {
         throw (new QBadRequestException("This file extension is blocked."));
      }
      return (name);
   }

   /*******************************************************************************
    ** Keep arbitrary string primary keys inside one object-key segment.
    *******************************************************************************/
   private String segment(String value)
   {
      return (URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20").replace(".", "%2E"));
   }
}
