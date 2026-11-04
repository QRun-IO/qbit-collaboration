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


import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qqq.backend.core.actions.permissions.CustomPermissionChecker;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.actions.AbstractActionInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLine;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLineInterface;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.actions.processes.Status;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QIcon;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.MetaDataWithPermissionRules;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.PermissionLevel;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.QPermissionRules;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QComponentType;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendComponentMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendStepMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractLoadStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractTransformStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.ExtractViaQueryStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.ProcessSummaryProviderInterface;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.StreamedETLWithFrontendProcess;


/*******************************************************************************
 ** Explicit provider settlement through the standard table action and Review flow.
 *******************************************************************************/
public class ResolveAttachmentUploadProcess implements CustomPermissionChecker
{
   public static final String NAME = "collaborationResolveAttachmentUpload";
   public static final String EVIDENCE = "settlementEvidence";


   /*******************************************************************************
    ** Administrative table action, never a navigation entry or scheduled process.
    *******************************************************************************/
   public static QProcessMetaData metadata()
   {
      Map<String, Serializable> values = new HashMap<>();
      values.put(StreamedETLWithFrontendProcess.FIELD_SOURCE_TABLE, CollaborationTables.ATTACHMENT_CLEANUP);
      values.put(StreamedETLWithFrontendProcess.FIELD_DESTINATION_TABLE, CollaborationTables.ATTACHMENT_CLEANUP);
      values.put(StreamedETLWithFrontendProcess.FIELD_SUPPORTS_FULL_VALIDATION, true);
      values.put(StreamedETLWithFrontendProcess.FIELD_DO_FULL_VALIDATION, true);
      values.put(StreamedETLWithFrontendProcess.FIELD_PREVIEW_MESSAGE,
         "Confirm that the provider has completed or cancelled every selected upload. Object absence or elapsed time alone is not proof. Current bytes will be deleted; the settlement receipt will be retained.");
      return (StreamedETLWithFrontendProcess.defineProcessMetaData(ExtractViaQueryStep.class, Review.class, Apply.class, values)
         .withName(NAME).withLabel("Resolve settled uploads").withTableName(CollaborationTables.ATTACHMENT_CLEANUP).withIcon(new QIcon("check"))
         .withPermissionRules(new QPermissionRules().withLevel(PermissionLevel.NOT_PROTECTED)
            .withCustomPermissionChecker(new QCodeReference(ResolveAttachmentUploadProcess.class)))
         .withStep(0, new QFrontendStepMetaData().withName("evidence").withLabel("Confirm provider settlement")
            .withFormField(new QFieldMetaData(EVIDENCE, QFieldType.STRING).withLabel("Provider completion or cancellation evidence")
               .withMaxLength(2000).withIsRequired(true))
            .withComponent(new QFrontendComponentMetaData().withType(QComponentType.EDIT_FORM))));
   }


   /*******************************************************************************
    ** Guard direct process invocation as well as menu visibility.
    *******************************************************************************/
   @Override
   public void checkPermissionsThrowing(AbstractActionInput input, MetaDataWithPermissionRules metadata) throws QPermissionDeniedException
   {
      AttachmentCleanupQueue.requireSettlementOperator();
   }


   /*******************************************************************************
    ** Shared standard summary language for Review and Result.
    *******************************************************************************/
   private static ProcessSummaryLine line(Status status, String future, String past)
   {
      ProcessSummaryLine line = new ProcessSummaryLine(status);
      line.setSingularFutureMessage(future);
      line.setPluralFutureMessage(future);
      line.setSingularPastMessage(past);
      line.setPluralPastMessage(past);
      return (line);
   }


   /*******************************************************************************
    ** Review never changes provider state or records a completed settlement.
    *******************************************************************************/
   public static class Review extends AbstractTransformStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine selected = line(Status.OK, "uploads will be resolved", "uploads selected");
      private final ProcessSummaryLine skipped = line(Status.WARNING, "resolved records or committed deletions will be skipped", "records skipped");


      /*******************************************************************************
       ** Require evidence before allowing the administrator to review selected rows.
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         AttachmentCleanupQueue.requireSettlementOperator();
         AttachmentCleanupQueue.validateEvidence(input.getValueString(EVIDENCE));
         for(QRecord row : input.getRecords())
         {
            if(row.getValue("attachmentId") != null || row.getValue("resolvedAt") != null)
            {
               skipped.incrementCountAndAddPrimaryKey(row.getValue("id"));
            }
            else
            {
               output.getRecords().add(row);
               selected.incrementCountAndAddPrimaryKey(row.getValue("id"));
            }
         }
      }


      /*******************************************************************************
       ** Keep streamed totals across pages.
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         selected.addSelfToListIfAnyCount(lines);
         skipped.addSelfToListIfAnyCount(lines);
         return (lines);
      }
   }


   /*******************************************************************************
    ** Apply checks current authorization, row ownership and references again.
    *******************************************************************************/
   public static class Apply extends AbstractLoadStep implements ProcessSummaryProviderInterface
   {
      private final AttachmentCleanupQueue queue;
      private final ProcessSummaryLine done = line(Status.OK, "uploads will be resolved", "uploads resolved");
      private final ProcessSummaryLine failed = line(Status.ERROR, "uploads cannot be resolved", "uploads unresolved; inspect and retry");


      /*******************************************************************************
       ** Construction is also used during context-free metadata validation.
       *******************************************************************************/
      public Apply()
      {
         this(new AttachmentCleanupQueue());
      }


      /*******************************************************************************
       ** Use the same service with a fixture storage provider in process tests.
       *******************************************************************************/
      public Apply(AttachmentCleanupQueue queue)
      {
         this.queue = queue;
      }


      /*******************************************************************************
       ** Each locator has its own transaction and immutable settlement receipt.
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         AttachmentCleanupQueue.requireSettlementOperator();
         AttachmentCleanupQueue.validateEvidence(input.getValueString(EVIDENCE));
         for(QRecord row : input.getRecords())
         {
            try
            {
               queue.resolveUpload(row.getValue("id"), input.getValueString(EVIDENCE));
               done.incrementCountAndAddPrimaryKey(row.getValue("id"));
            }
            catch(QException | RuntimeException failure)
            {
               failed.incrementCountAndAddPrimaryKey(row.getValue("id"));
            }
         }
      }


      /*******************************************************************************
       ** Report actual application results and retain review exclusions.
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         done.addSelfToListIfAnyCount(lines);
         failed.addSelfToListIfAnyCount(lines);
         if(getTransformStep() instanceof Review review)
         {
            review.skipped.addSelfToListIfAnyCount(lines);
         }
         return (lines);
      }
   }
}
