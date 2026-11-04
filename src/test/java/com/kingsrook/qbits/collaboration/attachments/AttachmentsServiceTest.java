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
import java.util.Optional;
import com.kingsrook.qbits.collaboration.CollaborationQBitConfig;
import com.kingsrook.qbits.collaboration.CollaborationSchema;
import com.kingsrook.qbits.collaboration.HomeWidgetData;
import com.kingsrook.qbits.collaboration.PersonalServiceFixture;
import com.kingsrook.qbits.collaboration.app.CollaborationHome;
import com.kingsrook.qbits.collaboration.attachments.uploadjournal.MemoryUploadJournal;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.permissions.CollaborationPermissions;
import com.kingsrook.qbits.collaboration.security.ParentRecordAccess;
import com.kingsrook.qbits.objectstorage.api.PresignGet;
import com.kingsrook.qbits.objectstorage.api.ObjectStream;
import com.kingsrook.qbits.objectstorage.api.ObjectHeaders;
import com.kingsrook.qbits.objectstorage.api.StoredObject;
import com.kingsrook.qbits.objectstorage.api.StorageException;
import com.kingsrook.qbits.objectstorage.api.PutRequest;
import com.kingsrook.qqq.backend.core.actions.QBackendTransaction;
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
   private MemoryUploadJournal journal;
   private AttachmentsService service;
   private TestLifecycle lifecycle;

   /*******************************************************************************
    ** No Docker, network, object-storage grants or collaboration.view needed.
    *******************************************************************************/
   @BeforeEach
   void attachments()
   {
      lifecycle = null;
      storage = new FakeStorage();
      journal = new MemoryUploadJournal();
      service = memoryService();
   }

   /*******************************************************************************
    ** Standard downloads retain lifecycle checks and the authenticated descriptor.
    *******************************************************************************/
   @Test
   void authenticatedDeliveryPreservesPolicyAndExactUrl() throws Exception
   {
      AttachmentEntry file = upload("download.txt", "original");
      java.util.concurrent.atomic.AtomicBoolean deny = new java.util.concurrent.atomic.AtomicBoolean();
      URL destination = URI.create("https://app.example/qqq/v1/qbit/files/exact").toURL();
      AttachmentLifecyclePolicy policy = new AttachmentLifecyclePolicy()
      {
         /***********************************************************************
          ** Policy refusal must happen before a delivery is issued.
          ***********************************************************************/
         @Override
         public void beforeAccess(QRecord row, boolean inline) throws QException
         {
            if(deny.get())
            {
               throw new QException("Denied by lifecycle");
            }
         }

         /***********************************************************************
          ** No storage signing or alternate byte owner is needed.
          ***********************************************************************/
         @Override
         public AttachmentDownload downloadDelivery(QRecord row)
         {
            assertThat(row.getValueInteger("id")).isEqualTo(file.id());
            return (new AttachmentDownload(destination, true));
         }
      };
      service = new AttachmentsService(config, storage, policy, journal);
      assertThat(service.downloadDelivery(file.id())).isEqualTo(new AttachmentDownload(destination, true));
      assertThat(service.downloadUrl(file.id(), false)).isEqualTo(destination);
      assertThat(service.downloadDelivery(file.id(), true)).isEqualTo(new AttachmentDownload(destination, true));
      assertThat(service.downloadUrl(file.id(), true)).isEqualTo(destination);
      AttachmentDownload signed = new AttachmentDownload(URI.create("https://storage.example/file?signature=secret").toURL(), false);
      assertThat(signed.toString()).doesNotContain("secret", "signature", "storage.example");
      for(String invalid : java.util.List.of("https://user:password@storage.example/file", "https://storage.example/file#fragment", "http:/missing-host"))
      {
         assertThatThrownBy(() -> new AttachmentDownload(URI.create(invalid).toURL(), false)).isInstanceOf(IllegalArgumentException.class);
      }
      deny.set(true);
      assertThatThrownBy(() -> service.downloadDelivery(file.id())).hasMessageContaining("Denied by lifecycle");
      assertThatThrownBy(() -> new AttachmentDownload(URI.create("file:///tmp/bytes").toURL(), true)).isInstanceOf(IllegalArgumentException.class);
   }

   /*******************************************************************************
    ** Retention protects mixed bulk deletion without preventing metadata edits.
    *******************************************************************************/
   @Test
   void retainedFilesAreNotDeletable() throws Exception
   {
      withLifecycle();
      AttachmentEntry ordinary = upload("ordinary.txt", "first");
      lifecycle.retained = true;
      AttachmentEntry retained = upload("retained.txt", "second");
      lifecycle.retainedId = retained.id();
      assertThat(retained.canDelete()).isFalse();
      assertThat(retained.canEdit()).isTrue();
      assertThatThrownBy(() -> service.delete(List.of(ordinary.id(), retained.id()))).hasMessageContaining("retained");
      assertThat(storage.objects).hasSize(2);
      assertThatThrownBy(() -> service.requireDeletable(retained.id())).hasMessageContaining("retained");
      assertThatThrownBy(() -> service.deleteStoredRow(raw(retained.id()))).hasMessageContaining("retained");
      assertThat(service.rename(retained.id(), "renamed.txt", null).fileName()).isEqualTo("renamed.txt");
      assertThat(lifecycle.metadataCalls).isEqualTo(1);
      lifecycle.metadataFails = true;
      assertThatThrownBy(() -> service.rename(retained.id(), "denied.txt", null)).hasMessageContaining("Metadata audit unavailable");
      assertThat(raw(retained.id()).getValueString("fileName")).isEqualTo("renamed.txt");
      lifecycle.retentionFails = true;
      assertThatThrownBy(() -> service.delete(List.of(ordinary.id()))).hasMessageContaining("Retention unavailable");
      assertThat(storage.objects).hasSize(2);
   }

   /*******************************************************************************
    ** Repeated cleanup advances past retained rows and never removes their bytes.
    *******************************************************************************/
   @Test
   void retainedParentCleanupCrossesPageBoundary() throws Exception
   {
      withLifecycle();
      lifecycle.retained = true;
      for(int i = 0; i < 205; i++)
      {
         upload("photo-" + i + ".jpg", "original");
      }
      new DeleteAction().execute(new DeleteInput("document").withPrimaryKeys(List.of(1)));
      AttachmentCleanup cleanup = new AttachmentCleanup(service);
      cleanup.onParentsDeleted("document", List.of(1));
      cleanup.onParentsDeleted("document", List.of(1));
      List<QRecord> rows = AttachmentRecords.query(new QQueryFilter());
      assertThat(rows).hasSize(205).allSatisfy(row -> assertThat(row.getValueBoolean("parentDeleted")).isTrue());
      assertThat(storage.objects).hasSize(205);
      assertThatThrownBy(() -> service.downloadUrl(rows.getFirst().getValue("id"), false)).hasMessage("Not found");
      assertThat(lifecycle.accesses).isEmpty();
   }

   /*******************************************************************************
    ** Both standard preview fields and explicit downloads pass the same policy.
    *******************************************************************************/
   @Test
   void accessPolicyIsStrictAndRunsAfterAuthorization() throws Exception
   {
      withLifecycle();
      AttachmentEntry uploaded = upload("audit.txt", "original");
      assertThat(lifecycle.storedCalls).isEqualTo(1);
      service.downloadUrl(uploaded.id(), false);
      new AttachmentPreviewCustomizer().filter(List.of(raw(uploaded.id())), false, true, service);
      assertThat(lifecycle.accesses).containsExactly(false, true, false);
      assertThat(storage.signatures).hasSize(3);
      lifecycle.accessFails = true;
      assertThatThrownBy(() -> service.downloadUrl(uploaded.id(), false)).hasMessageContaining("Access audit unavailable");
      assertThatThrownBy(() -> new AttachmentPreviewCustomizer().filter(List.of(raw(uploaded.id())), false, true, service))
         .hasMessageContaining("Access audit unavailable");
      assertThat(storage.signatures).hasSize(3);
      lifecycle.accessRuntimeFails = true;
      assertThatThrownBy(() -> new AttachmentPreviewCustomizer().filter(List.of(raw(uploaded.id())), false, true, service))
         .hasMessageContaining("Attachment access policy failed");
      assertThat(storage.signatures).hasSize(3);
      lifecycle.accesses.clear();
      QContext.getQSession().getSecurityKeyValues().remove("tenant");
      assertThatThrownBy(() -> service.downloadUrl(uploaded.id(), false)).hasMessage("Not found");
      assertThat(new AttachmentPreviewCustomizer().filter(List.of(raw(uploaded.id())), false, true, service)).isEmpty();
      assertThat(lifecycle.accesses).isEmpty();
   }

   /*******************************************************************************
    ** A policy registration failure preserves the original for reconciliation.
    *******************************************************************************/
   @Test
   void storedPolicyFailureDoesNotDeleteOriginal() throws Exception
   {
      withLifecycle();
      lifecycle.storedFails = true;
      assertThatThrownBy(() -> upload("preserved.txt", "original")).hasMessageContaining("Registration unavailable");
      assertThat(storage.objects).hasSize(1);
      assertThat(AttachmentRecords.query(new QQueryFilter())).hasSize(1);
   }

   /*******************************************************************************
    ** Configuration removal cannot turn a retained original into a deletable file.
    *******************************************************************************/
   @Test
   void persistedRetentionSurvivesProviderRemoval() throws Exception
   {
      withLifecycle();
      lifecycle.retained = true;
      AttachmentEntry file = upload("retained.txt", "original");
      assertThat(raw(file.id()).getValueBoolean("retained")).isTrue();
      config.withAttachmentLifecycle(new QCodeReference(AttachmentLifecyclePolicy.class));
      service = memoryService();
      assertThat(service.list("document", 1).getFirst().canDelete()).isFalse();
      assertThatThrownBy(() -> service.delete(List.of(file.id()))).hasMessageContaining("retained");
      assertThatThrownBy(() -> service.downloadUrl(file.id(), false)).hasMessageContaining("requires its lifecycle policy");
      assertThat(storage.signatures).isEmpty();
      new DeleteAction().execute(new DeleteInput("document").withPrimaryKeys(List.of(1)));
      new AttachmentCleanup(service).onParentsDeleted("document", List.of(1));
      assertThat(raw(file.id()).getValueBoolean("parentDeleted")).isTrue();
      assertThat(storage.objects).hasSize(1);
   }

   /*******************************************************************************
    ** Failed intake classification never writes object bytes or attachment rows.
    *******************************************************************************/
   @Test
   void unavailableRetentionPolicyDeniesIntake() throws Exception
   {
      withLifecycle();
      lifecycle.retentionFails = true;
      assertThatThrownBy(() -> upload("blocked.txt", "bytes")).hasMessageContaining("Retention unavailable");
      assertThat(storage.objects).isEmpty();
      assertThat(AttachmentRecords.query(new QQueryFilter())).isEmpty();
   }

   /*******************************************************************************
    ** Core validation reads authorize rows without generating presentation URLs.
    *******************************************************************************/
   @Test
   void internalReadsDoNotIssueUrlsButDisplayReadsDo() throws Exception
   {
      CountingLifecycle.accessCalls = 0;
      PreviewStorage.signatureCalls = 0;
      config.withAttachmentLifecycle(new QCodeReference(CountingLifecycle.class))
         .withAttachmentStorage(new QCodeReference(PreviewStorage.class));
      service = memoryService();
      AttachmentEntry file = upload("presentation.txt", "original");
      login("alice", CollaborationPermissions.VIEW);
      QRecord internal = new GetAction().execute(new GetInput(CollaborationTables.ATTACHMENT).withPrimaryKey(file.id())).getRecord();
      assertThat(internal.getValue("previewUrl")).isNull();
      assertThat(internal.getValue("downloadUrl")).isNull();
      assertThat(internal.getValue("previewDelivery")).isNull();
      assertThat(internal.getValue("downloadDelivery")).isNull();
      QRecord stale = new QRecord(internal).withValue("previewDelivery", "AUTHENTICATED_FETCH").withValue("downloadDelivery", "SIGNED_URL");
      QRecord cleared = new AttachmentPreviewCustomizer().filter(List.of(stale), true, false, service).getFirst();
      assertThat(cleared.getValue("previewDelivery")).isNull();
      assertThat(cleared.getValue("downloadDelivery")).isNull();
      service.rename(file.id(), "renamed.txt", null);
      assertThat(CountingLifecycle.accessCalls).isZero();
      assertThat(PreviewStorage.signatureCalls).isZero();
      QRecord displayed = new GetAction().execute(new GetInput(CollaborationTables.ATTACHMENT).withPrimaryKey(file.id())
         .withShouldGenerateDisplayValues(true)).getRecord();
      assertThat(displayed.getValueString("previewUrl")).startsWith("https://signed.example/");
      assertThat(displayed.getValueString("downloadUrl")).startsWith("https://signed.example/");
      assertThat(displayed.getValueString("previewDelivery")).isEqualTo("SIGNED_URL");
      assertThat(displayed.getValueString("downloadDelivery")).isEqualTo("SIGNED_URL");
      new QueryAction().execute(new QueryInput(CollaborationTables.ATTACHMENT).withShouldGenerateDisplayValues(true));
      assertThat(CountingLifecycle.accessCalls).isEqualTo(4);
      assertThat(PreviewStorage.signatureCalls).isEqualTo(4);
      QContext.getQSession().getSecurityKeyValues().remove("tenant");
      assertThat(new GetAction().execute(new GetInput(CollaborationTables.ATTACHMENT).withPrimaryKey(file.id())).getRecord()).isNull();
      assertThat(new QueryAction().execute(new QueryInput(CollaborationTables.ATTACHMENT)).getRecords()).isEmpty();
      assertThat(new QueryAction().execute(new QueryInput(CollaborationTables.ATTACHMENT).withShouldGenerateDisplayValues(true)).getRecords()).isEmpty();
      assertThat(CountingLifecycle.accessCalls).isEqualTo(4);
      assertThat(PreviewStorage.signatureCalls).isEqualTo(4);
   }

   /*******************************************************************************
    ** Count policy calls across independently constructed request services.
    *******************************************************************************/
   public static class CountingLifecycle extends AttachmentLifecyclePolicy
   {
      private static int accessCalls;

      /*******************************************************************************
       ** Observe URL intent without depending on a service instance lifetime.
       *******************************************************************************/
      @Override
      public void beforeAccess(QRecord attachment, boolean inline)
      {
         accessCalls++;
      }
   }

   /*******************************************************************************
    ** Configured preview-only storage adapter; no network or object-store access.
    *******************************************************************************/
   public static class PreviewStorage extends AttachmentStorage
   {
      private static int signatureCalls;

      /*******************************************************************************
       ** Return a synthetic capability and count actual signing calls.
       *******************************************************************************/
      @Override
      public URL presign(String name, PresignGet request)
      {
         signatureCalls++;
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

   /*******************************************************************************
    ** Inject a per-test policy while configuring internal metadata reads too.
    *******************************************************************************/
   private AttachmentsService memoryService()
   {
      AttachmentLifecyclePolicy selected = com.kingsrook.qqq.backend.core.actions.customizers.QCodeLoader.getAdHoc(AttachmentLifecyclePolicy.class, config.getAttachmentLifecycle());
      return (new AttachmentsService(config, storage, selected, journal));
   }

   /*******************************************************************************
    ** Inject a per-test policy while configuring internal metadata reads too.
    *******************************************************************************/
   private void withLifecycle()
   {
      config.withAttachmentLifecycle(new QCodeReference(TestLifecycle.class));
      lifecycle = new TestLifecycle();
      service = new AttachmentsService(config, storage, lifecycle, journal);
   }

   /*******************************************************************************
    ** Reference transfers need explicit retention authority and caller ownership.
    *******************************************************************************/
   @Test
   void archiveTransferRequiresPolicyAndTransaction() throws Exception
   {
      withLifecycle();
      lifecycle.retained = true;
      AttachmentEntry file = upload("retained.txt", "original");
      assertThatThrownBy(() -> service.transferRetainedReference(file.id(), "document", 2, null))
         .hasMessageContaining("explicit transaction");
      assertThatThrownBy(() -> service.transferRetainedReference(file.id(), "document", 2, new QBackendTransaction()))
         .hasMessageContaining("archive policy");
      QContext.setTransaction(new QBackendTransaction());
      try
      {
         assertThatThrownBy(() -> service.transferRetainedReference(file.id(), "document", 2, new QBackendTransaction()))
            .hasMessageContaining("explicit transaction");
      }
      finally
      {
         QContext.clearTransaction();
      }
      assertThat(raw(file.id()).getValueString("recordId")).isEqualTo("1");
      assertThat(storage.objects).hasSize(1);
   }

   /*******************************************************************************
    ** A trusted policy cannot replace native target visibility with an approval.
    *******************************************************************************/
   @Test
   void archiveTransferChecksBothParentsBeforePolicy() throws Exception
   {
      withLifecycle();
      lifecycle.retained = true;
      AttachmentEntry file = upload("retained.txt", "original");
      TransferPolicy policy = new TransferPolicy();
      service = new AttachmentsService(config, storage, policy, journal);
      QBackendTransaction transaction = new QBackendTransaction();
      assertThatThrownBy(() -> service.transferRetainedReference(file.id(), "document", 3, transaction)).hasMessage("Not found");
      assertThatThrownBy(() -> service.transferRetainedReference(file.id(), "document", 999, transaction)).hasMessage("Not found");
      QContext.getQSession().getSecurityKeyValues().clear();
      QContext.getQSession().withSecurityKeyValue("tenant", "b");
      assertThatThrownBy(() -> service.transferRetainedReference(file.id(), "document", 3, transaction)).hasMessage("Not found");
      assertThat(policy.beforeCalls).isZero();
      assertThat(policy.afterCalls).isZero();
      assertThat(storage.objects).hasSize(1);
   }

   /*******************************************************************************
    ** The owner changes only the canonical reference, never the original content.
    *******************************************************************************/
   @Test
   void archiveTransferPreservesOriginalAndPassesExactTransaction() throws Exception
   {
      withLifecycle();
      lifecycle.retained = true;
      AttachmentEntry file = upload("retained.txt", "original");
      QRecord prior = raw(file.id());
      Map<String, byte[]> originalObjects = new HashMap<>(storage.objects);
      TransferPolicy policy = new TransferPolicy();
      service = new AttachmentsService(config, storage, policy, journal);
      QBackendTransaction transaction = new QBackendTransaction();
      login("bob");
      service.transferRetainedReference(file.id(), "document", 2, transaction);
      QRecord updated = raw(file.id());
      assertThat(updated.getValueString("recordId")).isEqualTo("2");
      assertThat(updated.getValueString("tableName")).isEqualTo("document");
      for(String field : prior.getValues().keySet())
      {
         if(!List.of("tableName", "recordId", "modifyDate").contains(field))
         {
            assertThat(updated.getValue(field)).as(field).isEqualTo(prior.getValue(field));
         }
      }
      assertThat(storage.objects).containsExactlyInAnyOrderEntriesOf(originalObjects);
      assertThat(storage.readCount).isZero();
      assertThat(policy.beforeCalls).isEqualTo(1);
      assertThat(policy.afterCalls).isEqualTo(1);
      assertThat(policy.transaction).isSameAs(transaction);
      assertThat(policy.targetId).isEqualTo("2");
      assertThat(policy.prior.getValueString("recordId")).isEqualTo("1");
      assertThat(service.list("document", 2)).hasSize(1);
      assertThat(service.list("document", 1)).isEmpty();
   }

   /*******************************************************************************
    ** Outcome failure reaches the transaction owner; memory does not prove rollback.
    *******************************************************************************/
   @Test
   void archiveTransferPropagatesOutcomeFailure() throws Exception
   {
      withLifecycle();
      lifecycle.retained = true;
      AttachmentEntry file = upload("retained.txt", "original");
      TransferPolicy policy = new TransferPolicy();
      policy.failAfter = true;
      service = new AttachmentsService(config, storage, policy, journal);
      assertThatThrownBy(() -> service.transferRetainedReference(file.id(), "document", 2, new QBackendTransaction()))
         .hasMessage("Archive audit unavailable");
      assertThat(policy.beforeCalls).isEqualTo(1);
      assertThat(policy.afterCalls).isEqualTo(1);
      assertThat(storage.objects).hasSize(1);
   }

   /*******************************************************************************
    ** An ordinary attachment cannot acquire the archive-only transfer capability.
    *******************************************************************************/
   @Test
   void archiveTransferRejectsOrdinaryAttachments() throws Exception
   {
      AttachmentEntry file = upload("ordinary.txt", "original");
      TransferPolicy policy = new TransferPolicy();
      service = new AttachmentsService(config, storage, policy, journal);
      assertThatThrownBy(() -> service.transferRetainedReference(file.id(), "document", 2, new QBackendTransaction()))
         .hasMessageContaining("Only a retained attachment");
      assertThat(policy.beforeCalls).isZero();
      assertThat(raw(file.id()).getValueString("recordId")).isEqualTo("1");
   }

   /*******************************************************************************
    ** Test-only authority observes calls; real JDBC/archive policy lives in its QBit.
    *******************************************************************************/
   private static class TransferPolicy extends AttachmentLifecyclePolicy
   {
      private int beforeCalls;
      private int afterCalls;
      private boolean failAfter;
      private QBackendTransaction transaction;
      private String targetId;
      private QRecord prior;

      /***************************************************************************
       ** Observe the canonical target and the caller's exact transaction.
       ***************************************************************************/
      @Override
      public void beforeReferenceTransfer(QRecord attachment, String tableName, String recordId, QBackendTransaction transaction)
      {
         beforeCalls++;
         this.transaction = transaction;
         targetId = recordId;
         prior = attachment;
      }

      /***************************************************************************
       ** A failed outcome must escape so the caller can roll back its transaction.
       ***************************************************************************/
      @Override
      public void afterReferenceTransfer(QRecord attachment, String tableName, String recordId, QBackendTransaction transaction) throws QException
      {
         afterCalls++;
         if(failAfter)
         {
            throw (new QException("Archive audit unavailable"));
         }
      }
   }

   /*******************************************************************************
    ** Per-test policy instance, exposed only to the enclosing deterministic tests.
    *******************************************************************************/
   public static class TestLifecycle extends AttachmentLifecyclePolicy
   {
      private boolean retained;
      private Integer retainedId;
      private boolean retentionFails;
      private boolean accessFails;
      private boolean accessRuntimeFails;
      private boolean storedFails;
      private boolean beforeStoredFails;
      private int beforeStoredCalls;
      private boolean metadataFails;
      private int storedCalls;
      private int metadataCalls;
      private final List<Boolean> accesses = new ArrayList<>();

      /*******************************************************************************
       ** Public no-argument constructor for host-configured policy loading.
       *******************************************************************************/
      public TestLifecycle()
      {
      }

      /*******************************************************************************
       ** Record successful registration callbacks or simulate a failed registry.
       *******************************************************************************/
      @Override
      public void beforeStored(QRecord attachment) throws QException
      {
         beforeStoredCalls++;
         if(beforeStoredFails)
         {
            throw (new QException("Storage intent unavailable"));
         }
      }

      /*******************************************************************************
       ** Explicit fixture-only recovery policy; real durability is tested with JDBC.
       *******************************************************************************/
      @Override
      public void beforeRecovery(QRecord candidate)
      {
      }

      /*******************************************************************************
       ** Simulate registration after storage; this memory fixture is not durability proof.
       *******************************************************************************/
      @Override
      public void afterStored(QRecord attachment) throws QException
      {
         storedCalls++;
         if(storedFails)
         {
            throw (new QException("Registration unavailable"));
         }
      }

      /*******************************************************************************
       ** Simulate a durable access event writer failure before signing.
       *******************************************************************************/
      @Override
      public void beforeAccess(QRecord attachment, boolean inline) throws QException
      {
         accesses.add(inline);
         if(accessRuntimeFails)
         {
            throw (new IllegalStateException("Unexpected policy failure"));
         }
         if(accessFails)
         {
            throw (new QException("Access audit unavailable"));
         }
      }

      /*******************************************************************************
       ** Protect all rows or one specific retained row, independently of parent.
       *******************************************************************************/
      @Override
      public boolean isRetained(QRecord attachment) throws QException
      {
         if(retentionFails)
         {
            throw (new QException("Retention unavailable"));
         }
         return (retained && (retainedId == null || retainedId.equals(attachment.getValueInteger("id"))));
      }

      /*******************************************************************************
       ** Track proposed edits separately from successful committed writes.
       *******************************************************************************/
      @Override
      public void beforeMetadataChange(QRecord attachment, QRecord changes) throws QException
      {
         metadataCalls++;
         if(metadataFails)
         {
            throw (new QException("Metadata audit unavailable"));
         }
      }

      /*******************************************************************************
       ** This memory fixture tests callback ordering, not durable transactions.
       *******************************************************************************/
      @Override
      public void validateMetadataTransaction(QRecord attachment, QBackendTransaction transaction)
      {
      }

      /*******************************************************************************
       ** Production providers must append their event using the owner transaction.
       *******************************************************************************/
      @Override
      public void afterMetadataChange(QRecord attachment, QRecord changes, QBackendTransaction transaction)
      {
      }
   }

   /*******************************************************************************
    ** Upload, metadata, newest-first list, signed download and deletion round trip.
    *******************************************************************************/
   /*******************************************************************************
    ** The action steps can be constructed without a QContext, as QQQ does during
    ** instance validation (issue #20); the service is created on first use.
    *******************************************************************************/
   @Test
   void stepsConstructWithoutContext()
   {
      QContext.clear();
      assertThat(new DeleteAttachmentsProcess.Review()).isNotNull();
      assertThat(new DeleteAttachmentsProcess.Delete()).isNotNull();
   }



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
    ** Content is authorized and verified before release, and buffers are immutable.
    *******************************************************************************/
   @Test
   void verifiedContentRead() throws Exception
   {
      AttachmentEntry file = upload("original.txt", "hello");
      AttachmentContent content = service.readContent(file.id(), 5);
      assertThat(content.attachmentId()).isEqualTo(file.id());
      assertThat(content.sha256()).isEqualTo(file.sha256());
      assertThat(content.contentType()).isEqualTo("text/plain");
      assertThat(content.bytes()).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
      byte[] copy = content.bytes();
      copy[0] = 0;
      assertThat(content.bytes()[0]).isEqualTo((byte) 'h');
      assertThat(storage.lastReadClosed).isTrue();
      assertThat(storage.signatures).isEmpty();
      assertThatThrownBy(() -> service.readContent(file.id(), 4)).hasMessageContaining("limit");
      assertThatThrownBy(() -> service.readContent(file.id(), 21 * 1024 * 1024)).hasMessageContaining("20 MiB");
      assertThat(storage.readCount).isEqualTo(1);
      QContext.getQSession().getSecurityKeyValues().remove("tenant");
      assertThatThrownBy(() -> service.readContent(file.id(), 5)).isInstanceOf(QNotFoundException.class);
      assertThat(storage.readCount).isEqualTo(1);
   }

   /*******************************************************************************
    ** Changed, truncated and oversized originals never escape the integrity gate.
    *******************************************************************************/
   @Test
   void corruptContentRead() throws Exception
   {
      AttachmentEntry file = upload("original.txt", "hello");
      String key = storage.objects.keySet().iterator().next();
      storage.objects.put(key, "wrong".getBytes(StandardCharsets.UTF_8));
      assertThatThrownBy(() -> service.readContent(file.id(), 5)).hasMessageContaining("integrity");
      assertThat(storage.lastReadClosed).isTrue();
      storage.objects.put(key, "four".getBytes(StandardCharsets.UTF_8));
      assertThatThrownBy(() -> service.readContent(file.id(), 5)).isInstanceOf(AttachmentContentIntegrityException.class);
      storage.reportLength = false;
      assertThatThrownBy(() -> service.readContent(file.id(), 5)).hasMessageContaining("integrity");
      storage.objects.put(key, "toolong".getBytes(StandardCharsets.UTF_8));
      assertThatThrownBy(() -> service.readContent(file.id(), 5)).hasMessageContaining("integrity");
      assertThat(storage.lastReadClosed).isTrue();
   }

   /*******************************************************************************
    ** Same intent reuses one original while conflicting bytes or metadata fail.
    *******************************************************************************/
   @Test
   void retryableIntakePreservesOneOriginal() throws Exception
   {
      String request = "a".repeat(64);
      String manifest = "b".repeat(64);
      AttachmentEntry first = service.upload("document", 1, "evidence.txt", "text/plain", stream("hello"), "caption", request, manifest);
      AttachmentEntry replay = service.upload("document", "01", "evidence.txt", "text/plain", stream("hello"), "caption", request, manifest);
      assertThat(replay.id()).isEqualTo(first.id());
      assertThat(storage.objects).hasSize(1);
      assertThatThrownBy(() -> service.upload("document", 1, "evidence.txt", "text/plain", stream("other"), "caption", request, manifest))
         .hasMessageContaining("conflicts");
      assertThatThrownBy(() -> service.upload("document", 1, "renamed.txt", "text/plain", stream("hello"), "caption", request, manifest))
         .hasMessageContaining("conflicts");
      assertThat(storage.objects).hasSize(1);
      service.upload("document", 2, "evidence.txt", "text/plain", stream("hello"), "caption", request, manifest);
      assertThat(storage.objects).hasSize(2);
   }

   /*******************************************************************************
    ** Completed retained retries do not create another file-added timeline event.
    *******************************************************************************/
   @Test
   void completedRetainedReplayDoesNotRepeatTimelineActivity() throws Exception
   {
      withLifecycle();
      lifecycle.retained = true;
      String request = "c".repeat(64);
      String manifest = "d".repeat(64);
      var first = service.upload("document", 1, "evidence.txt", "text/plain", stream("hello"), null, request, manifest);
      var replay = service.upload("document", 1, "evidence.txt", "text/plain", stream("hello"), null, request, manifest);
      assertThat(replay.id()).isEqualTo(first.id());
      assertThat(com.kingsrook.qbits.collaboration.CollaborationRecords.query(CollaborationTables.ACTIVITY,
         new QQueryFilter().withCriteria("type", QCriteriaOperator.EQUALS, "FILE_ADDED"))).hasSize(1);
      assertThat(lifecycle.storedCalls).as("mandatory idempotent registration runs for each coordinator replay").isEqualTo(2);
      assertThat(storage.objects).hasSize(1);
   }

   /*******************************************************************************
    ** A metadata insert failure leaves a deterministic original recoverable.
    *******************************************************************************/
   @Test
   void retryableIntakeRecoversStoredObject() throws Exception
   {
      TestLifecycle lifecycle = new TestLifecycle();
      service = new AttachmentsService(config, storage, lifecycle, journal);
      QTableMetaData table = instance.getTable(CollaborationTables.ATTACHMENT);
      Map<String, QCodeReference> original = new HashMap<>(table.getCustomizers());
      table.withCustomizer(TableCustomizers.PRE_INSERT_RECORD, new QCodeReference(RejectInsert.class));
      String request = "c".repeat(64);
      String manifest = "d".repeat(64);
      assertThatThrownBy(() -> service.upload("document", 1, "evidence.txt", "text/plain", stream("hello"), null, request, manifest))
         .hasMessageContaining("save attachment");
      assertThat(storage.objects).hasSize(1);
      table.setCustomizers(original);
      AttachmentEntry recovered = service.upload("document", 1, "evidence.txt", "text/plain", stream("hello"), null, request, manifest);
      assertThat(recovered.sha256()).hasSize(64);
      assertThat(storage.objects).hasSize(1);
      assertThat(service.list("document", 1)).hasSize(1);
   }

   /*******************************************************************************
    ** Both audited content-read phases must succeed before releasing any bytes.
    *******************************************************************************/
   @Test
   void retainedContentReadRequiresExplicitAuditPolicy() throws Exception
   {
      AttachmentLifecyclePolicy policy = new AttachmentLifecyclePolicy()
      {
         /***********************************************************************
          ** Mark the fixture retained while retaining the default read denial.
          ***********************************************************************/
         @Override
         public boolean isRetained(QRecord attachment)
         {
            return (true);
         }

         /***********************************************************************
          ** This fixture exercises read denial after explicitly allowed storage.
          ***********************************************************************/
         @Override
         public void beforeStored(QRecord attachment)
         {
         }
      };
      service = new AttachmentsService(config, storage, policy, journal);
      AttachmentEntry file = upload("retained.txt", "hello");
      assertThatThrownBy(() -> service.readContent(file.id(), 5)).hasMessageContaining("content-read policy");
      assertThat(storage.readCount).isZero();
      AttachmentLifecyclePolicy partialPolicy = new AttachmentLifecyclePolicy()
      {
         /***********************************************************************
          ** A before hook alone must not bypass mandatory read-result recording.
          ***********************************************************************/
         @Override
         public void beforeContentRead(QRecord attachment)
         {
         }
      };
      service = new AttachmentsService(config, storage, partialPolicy, journal);
      assertThatThrownBy(() -> service.readContent(file.id(), 5)).hasMessageContaining("read-result policy");
      assertThat(storage.readCount).isEqualTo(1);
      assertThat(storage.lastReadClosed).isTrue();
   }

   /*******************************************************************************
    ** An older retention provider cannot silently omit atomic metadata auditing.
    *******************************************************************************/
   @Test
   void retainedMetadataRequiresTransactionPolicyBeforeMutation() throws Exception
   {
      withLifecycle();
      lifecycle.retained = true;
      AttachmentEntry file = upload("original.txt", "hello");
      AttachmentLifecyclePolicy legacyProvider = new AttachmentLifecyclePolicy()
      {
         /***********************************************************************
          ** An older provider handles intent but has no atomic result contract.
          ***********************************************************************/
         @Override
         public void beforeMetadataChange(QRecord attachment, QRecord changes)
         {
         }
      };
      service = new AttachmentsService(config, storage, legacyProvider, journal);
      assertThatThrownBy(() -> service.rename(file.id(), "changed.txt", null))
         .hasMessageContaining("atomic audit transaction policy");
      assertThat(raw(file.id()).getValueString("fileName")).isEqualTo("original.txt");
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
      List<QRecord> results = customizer.filter(List.of(visible, hidden), false, true, service);
      assertThat(results).hasSize(1);
      assertThat(storage.signatures).hasSize(2);
      assertThat(results.getFirst().getValueString("previewUrl")).contains("signed.example");
      assertThat(results.getFirst().getValueString("downloadUrl")).contains("signed.example");
      assertThat(results.getFirst().getDisplayValue("downloadUrl")).isEqualTo("photo.png");
      assertThat(results.getFirst().getDisplayValue("downloadUrl:downloadUrlDynamic")).isEqualTo(results.getFirst().getValueString("downloadUrl"));
      assertThat(visible.getValue("previewUrl")).isNull();
      assertThat(raw(row.id()).getValue("previewUrl")).isNull();
      assertThatThrownBy(() -> customizer.filter(List.of(hidden), true, true, service)).hasMessage("Not found");
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
    ** Storage failures leave durable cleanup; trusted cleanup works after parent deletion.
    *******************************************************************************/
   @Test
   void failuresAndCleanup() throws Exception
   {
      AttachmentEntry row = upload("file.txt", "x");
      storage.failDelete = true;
      assertThatThrownBy(() -> service.delete(List.of(row.id()))).hasMessage("Storage unavailable");
      assertThat(service.list("document", 1)).isEmpty();
      assertThat(storage.objects).hasSize(1);
      storage.failDelete = false;
      admin();
      assertThat(new AttachmentCleanupQueue(storage).retryPending()).isEqualTo(1);
      login("alice");
      upload("parent-cleanup.txt", "parent bytes");
      new DeleteAction().execute(new DeleteInput("document").withPrimaryKeys(List.of(1)));
      assertThatThrownBy(() -> service.list("document", 1)).hasMessage("Not found");
      QContext.getQSession().getPermissions().clear();
      AttachmentCleanup cleanup = new AttachmentCleanup(service);
      cleanup.onParentsDeleted("document", List.of());
      cleanup.onParentsDeleted("document", List.of(1));
      cleanup.onParentsDeleted("document", List.of(1));
      admin();
      new AttachmentCleanupQueue(storage).retryPending();
      assertThat(storage.objects).isEmpty();
      assertThat(AttachmentRecords.query(new QQueryFilter())).isEmpty();
   }

   /*******************************************************************************
    ** A required intent failure prevents even the first object write.
    *******************************************************************************/
   @Test
   void retainedStorageRequiresIntentBeforePut() throws Exception
   {
      withLifecycle();
      lifecycle.retained = true;
      lifecycle.beforeStoredFails = true;
      assertThatThrownBy(() -> upload("evidence.txt", "original")).hasMessageContaining("Storage intent unavailable");
      assertThat(storage.objects).isEmpty();
      assertThat(AttachmentRecords.query(new QQueryFilter())).isEmpty();
      assertThat(lifecycle.storedCalls).isZero();
      assertThatThrownBy(() -> new AttachmentLifecyclePolicy().beforeStored(new QRecord().withValue("retained", true)))
         .hasMessageContaining("durable write-intent");
      lifecycle.beforeStoredFails = false;
      QContext.setTransaction(new QBackendTransaction());
      try
      {
         assertThatThrownBy(() -> upload("evidence.txt", "original")).isInstanceOf(QException.class).hasMessageContaining("own committed transactions");
      }
      finally
      {
         QContext.clearTransaction();
      }
      assertThat(storage.objects).isEmpty();
   }

   /*******************************************************************************
    ** A stored retained original survives a metadata insertion failure.
    *******************************************************************************/
   @Test
   void retainedFailedInsertNeverDeletesOriginal() throws Exception
   {
      withLifecycle();
      lifecycle.retained = true;
      instance.getTable(CollaborationTables.ATTACHMENT).withCustomizer(TableCustomizers.PRE_INSERT_RECORD, new QCodeReference(RejectInsert.class));
      assertThatThrownBy(() -> upload("evidence.txt", "original")).hasMessageContaining("Could not save attachment");
      assertThat(storage.objects).hasSize(1);
      assertThat(AttachmentRecords.query(new QQueryFilter())).isEmpty();
      assertThat(lifecycle.beforeStoredCalls).isEqualTo(1);
      assertThat(lifecycle.storedCalls).isZero();
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
      assertThat(HomeWidgetData.stats(Instant.now())).containsEntry("Attachments", 0);
      assertThat(HomeWidgetData.subheader(CollaborationHome.kpis(Instant.now()), "Attachments")).isEqualTo("No attachments yet");
      upload("a.txt", "12345");
      upload("b.txt", "123");
      assertThat(HomeWidgetData.stats(Instant.now())).containsEntry("Attachments", 2);
      assertThat(HomeWidgetData.subheader(CollaborationHome.kpis(Instant.now()), "Attachments")).isEqualTo("8 B in total");
      assertThat(CollaborationHome.humanSize(0)).isEqualTo("0 B");
      assertThat(CollaborationHome.humanSize(1023)).isEqualTo("1023 B");
      assertThat(CollaborationHome.humanSize(4813)).isEqualTo("4.7 KB");
      assertThat(CollaborationHome.humanSize(5L * 1024 * 1024)).isEqualTo("5.0 MB");
      assertThat(CollaborationHome.humanSize(3L * 1024 * 1024 * 1024)).isEqualTo("3.0 GB");
      String ddl = CollaborationSchema.postgresScript(config);
      assertThat(ddl).contains("CREATE TABLE IF NOT EXISTS collaboration_attachment", "size_bytes BIGINT", "parent_deleted BOOLEAN DEFAULT FALSE NOT NULL",
         "ON collaboration_attachment (table_name, record_id)", "retained BOOLEAN DEFAULT FALSE NOT NULL",
         "ALTER TABLE collaboration_attachment ADD COLUMN IF NOT EXISTS retained BOOLEAN NOT NULL DEFAULT FALSE").doesNotContain("preview_url", "download_url");
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
      private final Map<String, Map<String, String>> objectMetadata = new HashMap<>();
      private final List<PresignGet> signatures = new ArrayList<>();
      private long limit = Long.MAX_VALUE;
      private PutRequest lastPut;
      private PresignGet lastGet;
      private boolean failDelete;
      private Integer readCount = 0;
      private Boolean lastReadClosed = false;
      private Boolean reportLength = true;

      /***************************************************************************
       ** Simulate a closeable object response with independently reported length.
       ***************************************************************************/
      @Override
      public ObjectStream open(String name, String key)
      {
         readCount++;
         lastReadClosed = false;
         byte[] bytes = objects.get(name + "/" + key);
         ByteArrayInputStream input = new ByteArrayInputStream(bytes)
         {
            /*******************************************************************
             ** Observe cleanup on success and on every integrity failure path.
             *******************************************************************/
            @Override
            public void close() throws IOException
            {
               lastReadClosed = true;
               super.close();
            }
         };
         return (new ObjectStream(input, reportLength ? (long) bytes.length : null, "text/plain", null, Instant.now(),
            new ObjectHeaders().withUserMetadata(objectMetadata.get(name + "/" + key))));
      }

      /***************************************************************************
       ** Report presence for retry reconciliation without exposing content.
       ***************************************************************************/
      @Override
      public Optional<StoredObject> head(String name, String key)
      {
         byte[] bytes = objects.get(name + "/" + key);
         return (bytes == null ? Optional.empty() : Optional.of(new StoredObject().withSize((long) bytes.length)));
      }

      @Override
      public long maxBytes(String name)
      {
         return (limit);
      }

      @Override
      public void put(String name, PutRequest request)
      {
         lastPut = request;
         String key = name + "/" + request.getKey();
         if(Boolean.TRUE.equals(request.getIfNoneMatch()) && objects.containsKey(key))
         {
            throw (new StorageException(StorageException.Reason.PRECONDITION_FAILED, "Object already exists"));
         }
         try
         {
            objects.put(key, request.getBytes() == null ? request.getStream().readAllBytes() : request.getBytes().clone());
            objectMetadata.put(key, request.getMetadata());
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
