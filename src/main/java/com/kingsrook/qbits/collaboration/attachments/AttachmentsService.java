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
import java.util.UUID;
import com.kingsrook.qbits.collaboration.CollaborationQBitConfig;
import com.kingsrook.qbits.collaboration.CollaborationSecurity;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.permissions.CollaborationPermissions;
import com.kingsrook.qbits.collaboration.security.ParentRecordAccess;
import com.kingsrook.qbits.objectstorage.api.PresignGet;
import com.kingsrook.qbits.objectstorage.api.PutRequest;
import com.kingsrook.qqq.backend.core.actions.tables.DeleteAction;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.actions.tables.UpdateAction;
import com.kingsrook.qqq.backend.core.context.QContext;
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

   /*******************************************************************************
    ** Resolve the installed configuration without a global mutable singleton.
    *******************************************************************************/
   public AttachmentsService()
   {
      this((CollaborationQBitConfig) QContext.getQInstance().getQBits().values().stream()
         .filter(qbit -> qbit.getConfig() instanceof CollaborationQBitConfig).findFirst().orElseThrow().getConfig(), new AttachmentStorage());
   }

   /*******************************************************************************
    ** Injectable storage boundary for deterministic service tests.
    *******************************************************************************/
   public AttachmentsService(CollaborationQBitConfig config, AttachmentStorage storage)
   {
      this.config = config;
      this.storage = storage;
   }

   /*******************************************************************************
    ** Stream to a bounded temporary file while hashing, then put and insert.
    ** The caller owns the input stream. Oversize files never reach object storage.
    *******************************************************************************/
   public AttachmentEntry upload(String tableName, Serializable recordId, String fileName, String contentType, InputStream stream, String description) throws QException
   {
      new com.kingsrook.qbits.collaboration.people.PeopleService().touch();
      ParentRecordAccess.requireFeature(tableName, recordId, "attach");
      String user = requireUser();
      String id = canonicalId(tableName, recordId);
      String name = sanitize(fileName);
      String bucket = config.getAttachmentsBucketName();
      String key = "attachments/" + segment(tableName) + "/" + segment(id) + "/" + UUID.randomUUID() + "/" + name;
      if(tableName.length() > 128 || id.length() > 64 || user.length() > 255 || bucket.length() > 255 || key.length() > 1024
         || (contentType != null && contentType.length() > 255))
      {
         throw (new QException("Attachment metadata exceeds the supported field lengths."));
      }
      long limit = Math.min(config.getDefaultMaxBytes(), storage.maxBytes(bucket));
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
                  throw (new QException("Attachment exceeds the size limit of " + limit + " bytes."));
               }
               size += read;
               digest.update(buffer, 0, read);
               out.write(buffer, 0, read);
            }
         }
         String type = contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
         try(InputStream upload = Files.newInputStream(temporary))
         {
            storage.put(bucket, PutRequest.of(key, upload).withContentLength(size).withContentType(type).withContentDisposition("inline"));
         }
         boolean rowSaved = false;
         try
         {
            QRecord row = new QRecord().withValue("tableName", tableName).withValue("recordId", id).withValue("fileName", name)
               .withValue("contentType", type).withValue("sizeBytes", size).withValue("sha256", HexFormat.of().formatHex(digest.digest()))
               .withValue("bucketName", bucket).withValue("storageKey", key).withValue("description", description)
               .withValue("createdByUserId", user).withValue("parentDeleted", false);
            InsertInput input = new InsertInput(CollaborationTables.ATTACHMENT).withRecords(List.of(row));
            input.withPermissionBypass("AttachmentsService checked parent and attach permission before upload.");
            List<QRecord> records = new InsertAction().execute(input).getRecords();
            AttachmentRecords.checkErrors(records);
            rowSaved = true;
            com.kingsrook.qbits.collaboration.activity.ActivityWriter.write(tableName, id, "FILE_ADDED", "File added", CollaborationTables.ATTACHMENT, records.getFirst().getValueString("id"));
            return (entry(records.getFirst()));
         }
         catch(QException | RuntimeException failure)
         {
            try
            {
               if(!rowSaved)
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
    ** Newest first, independently of collaboration.view.
    *******************************************************************************/
   public List<AttachmentEntry> list(String tableName, Serializable recordId) throws QException
   {
      new com.kingsrook.qbits.collaboration.people.PeopleService().touch();
      String id = canonicalId(tableName, recordId);
      List<AttachmentEntry> entries = new ArrayList<>();
      for(QRecord row : AttachmentRecords.query(new QQueryFilter().withCriteria("tableName", QCriteriaOperator.EQUALS, tableName)
         .withCriteria("recordId", QCriteriaOperator.EQUALS, id).withCriteria("parentDeleted", QCriteriaOperator.NOT_EQUALS, true)
         .withOrderBy(new QFilterOrderBy("createDate", false)).withOrderBy(new QFilterOrderBy("id", false))))
      {
         entries.add(entry(row));
      }
      return (entries);
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
    ** Sign an already-authorized row, also used by the preview customizer.
    *******************************************************************************/
   URL url(QRecord row, boolean inline)
   {
      PresignGet request = PresignGet.of(row.getValueString("storageKey")).ttl(Duration.ofMinutes(5));
      if(!inline)
      {
         request.downloadAs(row.getValueString("fileName"));
      }
      return (storage.presign(row.getValueString("bucketName"), request));
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
         rows.add(requireMutable(id));
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
      QRecord change = new QRecord().withValue("id", row.getValue("id"));
      if(fileName != null)
      {
         change.setValue("fileName", sanitize(fileName));
      }
      if(description != null)
      {
         change.setValue("description", description);
      }
      UpdateInput input = new UpdateInput(CollaborationTables.ATTACHMENT).withRecords(List.of(change));
      input.setTableMetaData(AttachmentRecords.storageTable());
      input.withPermissionBypass("AttachmentsService checked the actual parent and author/admin before metadata update.");
      AttachmentRecords.checkErrors(new UpdateAction().execute(input).getRecords());
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
    ** Process Review uses persisted identity rather than trusting client rows.
    *******************************************************************************/
   public QRecord requireMutable(Serializable attachmentId) throws QException
   {
      new com.kingsrook.qbits.collaboration.people.PeopleService().touch();
      QRecord row = requireRow(attachmentId);
      requireUser();
      if(!canDelete(row))
      {
         throw (new QPermissionDeniedException("Only the author or collaboration.admin can change this attachment."));
      }
      if(!isAdmin())
      {
         ParentRecordAccess.requireFeature(row.getValueString("tableName"), row.getValueString("recordId"), "attach");
      }
      return (row);
   }

   /*******************************************************************************
    ** Delete storage first so a provider failure leaves a retryable row.
    *******************************************************************************/
   void deleteStoredRow(QRecord row) throws QException
   {
      storage.delete(row.getValueString("bucketName"), row.getValueString("storageKey"));
      DeleteInput input = new DeleteInput(CollaborationTables.ATTACHMENT).withPrimaryKeys(List.of(row.getValue("id")));
      input.setTableMetaData(AttachmentRecords.storageTable());
      input.withPermissionBypass("Attachment service authorized deletion, or trusted parent cleanup selected this row.");
      AttachmentRecords.checkErrors(new DeleteAction().execute(input).getRecordsWithErrors());
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
   private boolean canDelete(QRecord row)
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
   private AttachmentEntry entry(QRecord row)
   {
      return (new AttachmentEntry(row.getValueInteger("id"), row.getValueString("tableName"), row.getValueString("recordId"),
         row.getValueString("fileName"), row.getValueString("contentType"), row.getValueLong("sizeBytes"), row.getValueString("sha256"),
         row.getValueString("description"), row.getValueString("createdByUserId"), row.getValueInstant("createDate"), canDelete(row)));
   }

   /*******************************************************************************
    ** Strip paths and unsafe header/key characters; enforce extension policy.
    *******************************************************************************/
   private String sanitize(String fileName) throws QException
   {
      if(fileName == null || fileName.isBlank())
      {
         throw (new QException("A file name is required."));
      }
      String name = fileName.replace('\\', '/');
      name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._ -]", "_").strip();
      if(name.isBlank() || name.equals(".") || name.equals("..") || name.length() > 255)
      {
         throw (new QException("Invalid file name."));
      }
      String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
      if(config.getBlockedExtensions().stream().anyMatch(blocked -> extension.equals(blocked.replaceFirst("^\\.", "").toLowerCase(Locale.ROOT))))
      {
         throw (new QException("This file extension is blocked."));
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
