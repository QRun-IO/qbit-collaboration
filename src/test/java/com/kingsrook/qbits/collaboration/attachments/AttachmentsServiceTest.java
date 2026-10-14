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
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.collaboration.CollaborationQBitConfig;
import com.kingsrook.qbits.collaboration.CollaborationSchema;
import com.kingsrook.qbits.collaboration.PersonalServiceFixture;
import com.kingsrook.qbits.collaboration.app.CollaborationHome;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.permissions.CollaborationPermissions;
import com.kingsrook.qbits.collaboration.security.ParentRecordAccess;
import com.kingsrook.qbits.objectstorage.api.PresignGet;
import com.kingsrook.qbits.objectstorage.api.PutRequest;
import com.kingsrook.qqq.backend.core.actions.permissions.PermissionEnforcement;
import com.kingsrook.qqq.backend.core.actions.customizers.TableCustomizerInterface;
import com.kingsrook.qqq.backend.core.actions.customizers.TableCustomizers;
import com.kingsrook.qqq.backend.core.actions.processes.RunProcessAction;
import com.kingsrook.qqq.backend.core.actions.tables.CountAction;
import com.kingsrook.qqq.backend.core.actions.tables.DeleteAction;
import com.kingsrook.qqq.backend.core.actions.tables.GetAction;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.actions.tables.UpdateAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QNotFoundException;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLine;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessOutput;
import com.kingsrook.qqq.backend.core.model.actions.tables.count.CountInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.delete.DeleteInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.get.GetInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.model.statusmessages.BadInputStatusMessage;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.StreamedETLWithFrontendProcess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*******************************************************************************
 ** Real QQQ memory actions and parent locks, with an in-memory storage boundary.
 *******************************************************************************/
public class AttachmentsServiceTest extends PersonalServiceFixture
{
   private static AttachmentsService processService;
   private FakeStorage storage;
   private AttachmentsService service;

   /*******************************************************************************
    ** No Docker, network, object-storage grants or collaboration.view needed.
    *******************************************************************************/
   @BeforeEach
   void attachments()
   {
      storage = new FakeStorage();
      service = new AttachmentsService(config, storage);
   }

   /*******************************************************************************
    ** Upload, metadata, newest-first list, signed download and deletion round trip.
    *******************************************************************************/
   @Test
   void roundTrip() throws Exception
   {
      AttachmentEntry first = upload("../hello.txt", "hello");
      AttachmentEntry second = upload("C:\\fakepath\\other.txt", "other");
      assertThat(first.fileName()).isEqualTo("hello.txt");
      assertThat(first.sizeBytes()).isEqualTo(5L);
      assertThat(first.sha256()).isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
      assertThat(first.createdByUserId()).isEqualTo("alice");
      assertThat(first.canDelete()).isTrue();
      assertThat(service.list("document", "01")).extracting(AttachmentEntry::id).containsExactly(second.id(), first.id());
      assertThat(storage.objects.values()).anySatisfy(bytes -> assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("hello"));
      assertThat(storage.lastPut.getContentType()).isEqualTo("text/plain");
      assertThat(storage.lastPut.getContentDisposition()).isEqualTo("inline");
      assertThat(storage.objects.keySet()).allMatch(key -> key.startsWith("collaboration/attachments/document/1/"));
      assertThat(service.downloadUrl(first.id(), false).toString()).contains("signed.example");
      assertThat(storage.lastGet.getTtl()).isEqualTo(Duration.ofMinutes(5));
      assertThat(storage.lastGet.getDownloadAs()).isEqualTo("hello.txt");
      service.downloadUrl(first.id(), true);
      assertThat(storage.lastGet.getDownloadAs()).isNull();
      assertThat(raw(first.id()).getValue("previewUrl")).isNull();
      service.delete(List.of(first.id(), first.id()));
      assertThat(storage.objects).hasSize(1);
      assertThat(service.list("document", 1)).extracting(AttachmentEntry::id).containsExactly(second.id());
   }

   /*******************************************************************************
    ** Both collaboration and bucket size limits are enforced while streaming.
    *******************************************************************************/
   @Test
   void limitsAndExtensions() throws Exception
   {
      config.withDefaultMaxBytes(4L);
      assertThatThrownBy(() -> upload("large.txt", "hello")).hasMessageContaining("size limit");
      assertThat(storage.objects).isEmpty();
      assertThat(service.list("document", 1)).isEmpty();
      upload("exact.txt", "four");
      config.withDefaultMaxBytes(100L);
      storage.limit = 2;
      assertThatThrownBy(() -> upload("bucket.txt", "abc")).hasMessageContaining("size limit of 2");
      config.withBlockedExtensions(List.of(".EXE"));
      assertThatThrownBy(() -> upload("bad.exe", "x")).hasMessageContaining("blocked");
      assertThatThrownBy(() -> upload("..", "x")).hasMessageContaining("Invalid file name");
      assertThatThrownBy(() -> upload(" ", "x")).hasMessageContaining("file name is required");
      assertThatThrownBy(() -> service.upload("document", 1, "x", "x".repeat(256), stream("x"), null)).hasMessageContaining("field lengths");
   }

   /*******************************************************************************
    ** A locked, missing or table-denied parent always gives the same Not found.
    *******************************************************************************/
   @Test
   void invisibleParents() throws Exception
   {
      AttachmentEntry row = upload("visible.txt", "hello");
      for(String id : List.of("3", "999", "invalid"))
      {
         assertThatThrownBy(() -> service.upload("document", id, "a.txt", null, stream("x"), null))
            .isInstanceOf(QNotFoundException.class).hasMessage("Not found");
         assertThatThrownBy(() -> service.list("document", id)).hasMessage("Not found");
      }
      QContext.getQSession().getSecurityKeyValues().remove("tenant");
      assertThatThrownBy(() -> service.downloadUrl(row.id(), false)).hasMessage("Not found");
      assertThatThrownBy(() -> service.delete(List.of(row.id()))).hasMessage("Not found");
      QContext.getQSession().getPermissions().remove("document.read");
      assertThatThrownBy(() -> service.list("document", 1)).hasMessage("Not found");
      assertThatThrownBy(() -> ParentRecordAccess.requireVisibleParent("missing", 1)).hasMessage("Not found");
      assertThatThrownBy(() -> ParentRecordAccess.requireVisibleParent(CollaborationTables.ATTACHMENT, row.id())).hasMessage("Not found");
   }

   /*******************************************************************************
    ** Explicit attach uses the table's permission base and does not require EDIT.
    *******************************************************************************/
   @Test
   void explicitAttachWithoutEdit() throws Exception
   {
      QContext.getQSession().getPermissions().remove("document.write");
      assertThatThrownBy(() -> upload("denied.txt", "x")).isInstanceOf(QPermissionDeniedException.class);
      QContext.getQSession().withPermissions("document.attach");
      AttachmentEntry row = upload("allowed.txt", "x");
      service.delete(List.of(row.id()));
      instance.getTable("document").getPermissionRules().setPermissionBaseName("documents");
      QContext.getQSession().withPermissions("documents.read", "documents.attach");
      upload("custom-base.txt", "x");
   }

   /*******************************************************************************
    ** Ownership, moderation, and feature revocation are independent checks.
    *******************************************************************************/
   @Test
   void ownOtherAndAdmin() throws Exception
   {
      AttachmentEntry alice = upload("alice.txt", "a");
      login("bob");
      AttachmentEntry bob = upload("bob.txt", "b");
      assertThat(service.list("document", 1)).filteredOn(row -> row.id().equals(alice.id())).extracting(AttachmentEntry::canDelete).containsExactly(false);
      assertThatThrownBy(() -> service.delete(List.of(bob.id(), alice.id()))).isInstanceOf(QPermissionDeniedException.class);
      assertThat(storage.objects).hasSize(2);
      assertThatThrownBy(() -> service.rename(alice.id(), "stolen.txt", null)).isInstanceOf(QPermissionDeniedException.class);
      QContext.getQSession().getPermissions().remove("document.write");
      assertThatThrownBy(() -> service.delete(List.of(bob.id()))).isInstanceOf(QPermissionDeniedException.class);
      admin();
      QContext.getQSession().withPermissions("document.attach");
      QContext.getQSession().getPermissions().remove("document.write");
      assertThat(service.rename(alice.id(), "renamed.txt", "new description").fileName()).isEqualTo("renamed.txt");
      assertThat(service.describe(alice.id(), "").description()).isEmpty();
      service.downloadUrl(alice.id(), false);
      assertThat(storage.lastGet.getDownloadAs()).isEqualTo("renamed.txt");
      service.delete(List.of(alice.id(), bob.id()));
      assertThat(storage.objects).isEmpty();
   }

   /*******************************************************************************
    ** Direct table writes cannot bypass authorization or the storage lifecycle.
    *******************************************************************************/
   @Test
   void directWritesDeniedAndPanelBypassesBrowseGrant() throws Exception
   {
      AttachmentEntry row = upload("ok.txt", "x");
      assertThatThrownBy(() -> new QueryAction().execute(new QueryInput(CollaborationTables.ATTACHMENT))).isInstanceOf(QPermissionDeniedException.class);
      assertThat(service.list("document", 1)).hasSize(1);
      admin();
      assertThatThrownBy(() -> new InsertAction().execute(new InsertInput(CollaborationTables.ATTACHMENT).withRecords(List.of(new QRecord()))))
         .isInstanceOf(QPermissionDeniedException.class);
      assertThatThrownBy(() -> new UpdateAction().execute(new UpdateInput(CollaborationTables.ATTACHMENT)
         .withRecords(List.of(new QRecord().withValue("id", row.id()).withValue("createdByUserId", "admin")))))
         .isInstanceOf(QPermissionDeniedException.class);
   }

   /*******************************************************************************
    ** The real pre-query hook restricts query and count, including an empty IN.
    *******************************************************************************/
   @Test
   void tableReadRestrictionAndPostFiltering() throws Exception
   {
      AttachmentEntry visible = upload("visible.txt", "x");
      QContext.getQSession().withSecurityKeyValue("allTenants", true);
      service.upload("document", 3, "secret.txt", "text/plain", stream("secret"), null);
      login("alice", CollaborationPermissions.VIEW);
      QueryInput query = new QueryInput(CollaborationTables.ATTACHMENT);
      assertThat(new QueryAction().execute(query).getRecords()).extracting(row -> row.getValueInteger("id")).containsExactly(visible.id());
      assertThat(query.getFilter().toString()).contains("tableName", "document");
      assertThat(new CountAction().execute(new CountInput(CollaborationTables.ATTACHMENT)).getCount()).isEqualTo(2);
      int hiddenId = AttachmentRecords.query(new QQueryFilter().withCriteria("recordId", QCriteriaOperator.EQUALS, "3")).getFirst().getValueInteger("id");
      assertThat(new GetAction().execute(new GetInput(CollaborationTables.ATTACHMENT).withPrimaryKey(hiddenId)).getRecord()).isNull();
      QContext.getQSession().getPermissions().remove("document.read");
      assertThat(new QueryAction().execute(new QueryInput(CollaborationTables.ATTACHMENT)).getRecords()).isEmpty();
      assertThat(new CountAction().execute(new CountInput(CollaborationTables.ATTACHMENT)).getCount()).isZero();
   }

   /*******************************************************************************
    ** Signed previews are generated only after checking visibility and stay virtual.
    *******************************************************************************/
   @Test
   void previews() throws Exception
   {
      AttachmentEntry row = upload("photo.png", "image");
      QRecord visible = raw(row.id());
      QRecord hidden = new QRecord(visible).withValue("recordId", "3");
      AttachmentPreviewCustomizer customizer = new AttachmentPreviewCustomizer();
      List<QRecord> results = customizer.filter(List.of(visible, hidden), false, service);
      assertThat(results).hasSize(1);
      assertThat(storage.signatures).hasSize(2);
      assertThat(results.getFirst().getValueString("previewUrl")).contains("signed.example");
      assertThat(results.getFirst().getValueString("downloadUrl")).contains("signed.example");
      assertThat(results.getFirst().getDisplayValue("downloadUrl")).isEqualTo("photo.png");
      assertThat(results.getFirst().getDisplayValue("downloadUrl:downloadUrlDynamic")).isEqualTo(results.getFirst().getValueString("downloadUrl"));
      assertThat(visible.getValue("previewUrl")).isNull();
      assertThat(raw(row.id()).getValue("previewUrl")).isNull();
      assertThatThrownBy(() -> customizer.filter(List.of(hidden), true, service)).hasMessage("Not found");
   }

   /*******************************************************************************
    ** Review is dry, Result is actual, and authority is checked again after Review.
    *******************************************************************************/
   @Test
   void deleteProcessReviewAndResult() throws Exception
   {
      AttachmentEntry alice = upload("alice.txt", "a");
      login("bob");
      AttachmentEntry bob = upload("bob.txt", "b");
      DeleteAttachmentsProcess.Review review = new DeleteAttachmentsProcess.Review(service);
      RunBackendStepOutput reviewed = new RunBackendStepOutput();
      review.runOnePage(new RunBackendStepInput().withRecords(List.of(raw(alice.id()), raw(bob.id()))), reviewed);
      assertThat(reviewed.getRecords()).extracting(row -> row.getValueInteger("id")).containsExactly(bob.id());
      assertThat(storage.objects).hasSize(2);
      assertThat(review.getProcessSummary(reviewed, false)).hasSize(2);
      ProcessSummaryLine planned = (ProcessSummaryLine) review.getProcessSummary(reviewed, false).getFirst();
      assertThat(planned.getCount()).isEqualTo(1);
      assertThat(planned.getSingularFutureMessage()).contains("will be deleted");
      DeleteAttachmentsProcess.Delete delete = new DeleteAttachmentsProcess.Delete(service);
      delete.setTransformStep(review);
      delete.runOnePage(new RunBackendStepInput().withRecords(reviewed.getRecords()), new RunBackendStepOutput());
      assertThat(delete.getProcessSummary(new RunBackendStepOutput(), true)).hasSize(2);
      ProcessSummaryLine result = (ProcessSummaryLine) delete.getProcessSummary(new RunBackendStepOutput(), true).getFirst();
      assertThat(result.getSingularPastMessage()).isEqualTo("attachment deleted");
      assertThat(result.getCount()).isEqualTo(1);
      assertThat(storage.objects).hasSize(1);
      delete.runOnePage(new RunBackendStepInput().withRecords(List.of(raw(alice.id()))), new RunBackendStepOutput());
      assertThat(delete.getProcessSummary(new RunBackendStepOutput(), true)).hasSize(3);
      assertThat(instance.getProcess(DeleteAttachmentsProcess.NAME).getLabel()).isEqualTo("Delete attachments");
   }

   /*******************************************************************************
    ** Run the actual streamed ETL through its frontend break and resume boundary.
    *******************************************************************************/
   @Test
   @SuppressWarnings("unchecked")
   void streamedProcess() throws Exception
   {
      login("alice", CollaborationPermissions.VIEW);
      AttachmentEntry row = upload("streamed.txt", "x");
      processService = service;
      instance.getProcess(DeleteAttachmentsProcess.NAME).getBackendStep(StreamedETLWithFrontendProcess.STEP_NAME_EXECUTE)
         .getInputMetaData().getFieldThrowing(StreamedETLWithFrontendProcess.FIELD_LOAD_CODE).setDefaultValue(new QCodeReference(FakeDelete.class));
      try
      {
         RunProcessInput start = new RunProcessInput();
         start.setProcessName(DeleteAttachmentsProcess.NAME);
         start.addValue("recordsParam", "recordIds");
         start.addValue("recordIds", row.id().toString());
         start.setFrontendStepBehavior(RunProcessInput.FrontendStepBehavior.BREAK);
         RunProcessOutput review = new RunProcessAction().execute(start);
         List<ProcessSummaryLine> reviewLines = (List<ProcessSummaryLine>) review.getValues().get(StreamedETLWithFrontendProcess.FIELD_VALIDATION_SUMMARY);
         assertThat(reviewLines).anySatisfy(line ->
         {
            line.pickMessage(false);
            assertThat(line.getMessage()).contains("will be deleted");
            assertThat(line.getCount()).isEqualTo(1);
         });
         assertThat(storage.objects).hasSize(1);
         RunProcessInput go = new RunProcessInput();
         go.setProcessName(DeleteAttachmentsProcess.NAME);
         go.setProcessUUID(review.getProcessUUID());
         go.setStartAfterStep(StreamedETLWithFrontendProcess.STEP_NAME_REVIEW);
         go.setFrontendStepBehavior(RunProcessInput.FrontendStepBehavior.SKIP);
         RunProcessOutput result = new RunProcessAction().execute(go);
         List<ProcessSummaryLine> resultLines = (List<ProcessSummaryLine>) result.getValues().get(StreamedETLWithFrontendProcess.FIELD_PROCESS_SUMMARY);
         assertThat(resultLines).anySatisfy(line ->
         {
            line.pickMessage(true);
            assertThat(line.getMessage()).isEqualTo("attachment deleted");
            assertThat(line.getCount()).isEqualTo(1);
         });
         assertThat(storage.objects).isEmpty();
         assertThat(service.list("document", 1)).isEmpty();
      }
      finally
      {
         processService = null;
      }
   }

   /*******************************************************************************
    ** Only storage changes in the end-to-end process test, via the normal service.
    *******************************************************************************/
   public static class FakeDelete extends DeleteAttachmentsProcess.Delete
   {
      /*******************************************************************************
       ** Instantiated by the real process loader.
       *******************************************************************************/
      public FakeDelete()
      {
         super(processService);
      }
   }

   /*******************************************************************************
    ** Storage failures leave rows for retries; trusted cleanup works after parent deletion.
    *******************************************************************************/
   @Test
   void failuresAndCleanup() throws Exception
   {
      AttachmentEntry row = upload("file.txt", "x");
      storage.failDelete = true;
      assertThatThrownBy(() -> service.delete(List.of(row.id()))).hasMessage("Storage unavailable");
      assertThat(service.list("document", 1)).hasSize(1);
      storage.failDelete = false;
      new DeleteAction().execute(new DeleteInput("document").withPrimaryKeys(List.of(1)));
      assertThatThrownBy(() -> service.list("document", 1)).hasMessage("Not found");
      QContext.getQSession().getPermissions().clear();
      AttachmentCleanup cleanup = new AttachmentCleanup(service);
      cleanup.onParentsDeleted("document", List.of());
      cleanup.onParentsDeleted("document", List.of(1));
      cleanup.onParentsDeleted("document", List.of(1));
      assertThat(storage.objects).isEmpty();
      assertThat(AttachmentRecords.query(new QQueryFilter())).isEmpty();
   }

   /*******************************************************************************
    ** A rejected row compensates the put; a broken upload never creates a row.
    *******************************************************************************/
   @Test
   void failedInsertAndInputStream() throws Exception
   {
      instance.getTable(CollaborationTables.ATTACHMENT).withCustomizer(TableCustomizers.PRE_INSERT_RECORD, new QCodeReference(RejectInsert.class));
      assertThatThrownBy(() -> upload("long.txt", "x")).isInstanceOf(QException.class).hasMessageContaining("Could not save attachment");
      assertThat(storage.objects).isEmpty();
      assertThat(AttachmentRecords.query(new QQueryFilter())).isEmpty();
      assertThatThrownBy(() -> service.upload("document", 1, "x", null, new java.io.InputStream()
      {
         @Override
         public int read() throws IOException
         {
            throw (new IOException("broken source"));
         }
      }, null)).isInstanceOf(QException.class).hasMessageContaining("Could not read attachment");
      assertThat(storage.objects).isEmpty();
   }

   /*******************************************************************************
    ** Exercise the real QQQ per-record validation failure/compensation path.
    *******************************************************************************/
   public static class RejectInsert implements TableCustomizerInterface
   {
      @Override
      public List<QRecord> preInsert(InsertInput input, List<QRecord> records, boolean isPreview)
      {
         records.forEach(row -> row.withError(new BadInputStatusMessage("Rejected by host validation")));
         return (records);
      }
   }

   /*******************************************************************************
    ** Real counts and bytes on the home, with explicit approximation and empty state.
    *******************************************************************************/
   @Test
   void homeAndSchema() throws Exception
   {
      admin();
      assertThat(CollaborationHome.render(Instant.now())).contains("Attachments: 0", "No attachments yet.", "Total size (bytes): 0");
      upload("a.txt", "12345");
      upload("b.txt", "123");
      assertThat(CollaborationHome.render(Instant.now())).contains("Attachments: 2", "Total size (bytes): 8", "Approximate totals").doesNotContain("No attachments yet.");
      String ddl = CollaborationSchema.postgresScript(config);
      assertThat(ddl).contains("CREATE TABLE IF NOT EXISTS collaboration_attachment", "size_bytes BIGINT", "parent_deleted BOOLEAN DEFAULT FALSE NOT NULL",
         "ON collaboration_attachment (table_name, record_id)").doesNotContain("preview_url", "download_url");
      QTableMetaData table = instance.getTable(CollaborationTables.ATTACHMENT);
      assertThat(table.getField("sizeBytes").getType()).isEqualTo(QFieldType.LONG);
      assertThat(table.getVirtualField("previewUrl")).isNotNull();
      assertThat(config.getDefaultMaxBytes()).isEqualTo(104857600L);
      assertThat(config.getAttachmentsBucketName()).isEqualTo("collaboration");
      assertThat(config.getBlockedExtensions()).isEmpty();
      assertThatThrownBy(() -> config.withDefaultMaxBytes(0L).validate(instance)).hasMessageContaining("defaultMaxBytes");
   }

   /*******************************************************************************
    ** Short upload helper using the first visible parent.
    *******************************************************************************/
   private AttachmentEntry upload(String name, String value) throws QException
   {
      return (service.upload("document", 1, name, "text/plain", stream(value), "description"));
   }

   /*******************************************************************************
    ** UTF-8 payload.
    *******************************************************************************/
   private ByteArrayInputStream stream(String value)
   {
      return (new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)));
   }

   /*******************************************************************************
    ** Inspect persistence without producing URLs.
    *******************************************************************************/
   private QRecord raw(Integer id) throws QException
   {
      return (AttachmentRecords.query(new QQueryFilter().withCriteria("id", QCriteriaOperator.EQUALS, id)).getFirst());
   }

   /*******************************************************************************
    ** Fake bucket preserving bytes and capturing API requests. No provider emulation.
    *******************************************************************************/
   private static class FakeStorage extends AttachmentStorage
   {
      private final Map<String, byte[]> objects = new HashMap<>();
      private final List<PresignGet> signatures = new ArrayList<>();
      private long limit = Long.MAX_VALUE;
      private PutRequest lastPut;
      private PresignGet lastGet;
      private boolean failDelete;

      @Override
      public long maxBytes(String name)
      {
         return (limit);
      }

      @Override
      public void put(String name, PutRequest request)
      {
         lastPut = request;
         try
         {
            objects.put(name + "/" + request.getKey(), request.getStream().readAllBytes());
         }
         catch(IOException e)
         {
            throw (new IllegalStateException(e));
         }
      }

      @Override
      public void delete(String name, String key)
      {
         if(failDelete)
         {
            throw (new IllegalStateException("Storage unavailable"));
         }
         objects.remove(name + "/" + key);
      }

      @Override
      public URL presign(String name, PresignGet request)
      {
         lastGet = request;
         signatures.add(request);
         try
         {
            return (URI.create("https://signed.example/" + request.getKey()).toURL());
         }
         catch(Exception e)
         {
            throw (new IllegalStateException(e));
         }
      }
   }
}
