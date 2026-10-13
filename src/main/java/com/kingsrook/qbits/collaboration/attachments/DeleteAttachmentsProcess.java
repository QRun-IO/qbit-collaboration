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
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.permissions.CollaborationPermissions;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLine;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLineInterface;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.actions.processes.Status;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QIcon;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractLoadStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractTransformStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.ExtractViaQueryStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.ProcessSummaryProviderInterface;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.StreamedETLWithFrontendProcess;

/*******************************************************************************
 ** Record action with the standard streamed ETL Review and Result screens.
 *******************************************************************************/
public final class DeleteAttachmentsProcess
{
   public static final String NAME = "collaborationDeleteAttachments";

   /*******************************************************************************
    ** Metadata factory only.
    *******************************************************************************/
   private DeleteAttachmentsProcess()
   {
   }

   /*******************************************************************************
    ** Register on the table, never in navigation.
    *******************************************************************************/
   public static QProcessMetaData metadata()
   {
      Map<String, Serializable> values = new HashMap<>();
      values.put(StreamedETLWithFrontendProcess.FIELD_SOURCE_TABLE, CollaborationTables.ATTACHMENT);
      values.put(StreamedETLWithFrontendProcess.FIELD_DESTINATION_TABLE, CollaborationTables.ATTACHMENT);
      values.put(StreamedETLWithFrontendProcess.FIELD_SUPPORTS_FULL_VALIDATION, true);
      values.put(StreamedETLWithFrontendProcess.FIELD_DO_FULL_VALIDATION, true);
      values.put(StreamedETLWithFrontendProcess.FIELD_PREVIEW_MESSAGE,
         "Delete the selected attachments. Storage uses versioning or trash where configured; otherwise deletion is permanent.");
      return (StreamedETLWithFrontendProcess.defineProcessMetaData(ExtractViaQueryStep.class, Review.class, Delete.class, values)
         .withName(NAME).withLabel("Delete attachments").withIcon(new QIcon("delete"))
         .withTableName(CollaborationTables.ATTACHMENT).withPermissionRules(CollaborationPermissions.permissionRules()));
   }

   /*******************************************************************************
    ** Future and past wording for the standard summary renderer.
    *******************************************************************************/
   private static ProcessSummaryLine line(Status status, String future, String past)
   {
      ProcessSummaryLine line = new ProcessSummaryLine(status, 0, null);
      line.setSingularFutureMessage("attachment " + future);
      line.setPluralFutureMessage("attachments " + future);
      line.setSingularPastMessage("attachment " + past);
      line.setPluralPastMessage("attachments " + past);
      return (line);
   }

   /*******************************************************************************
    ** Review re-reads persisted rows and skips unauthorized selections.
    *******************************************************************************/
   public static class Review extends AbstractTransformStep implements ProcessSummaryProviderInterface
   {
      private final AttachmentsService service;
      private final ProcessSummaryLine selected = line(Status.OK, "will be deleted", "deleted");
      private final ProcessSummaryLine skipped = line(Status.WARNING, "cannot be deleted (skipped)", "skipped");

      /*******************************************************************************
       ** QQQ constructor.
       *******************************************************************************/
      public Review()
      {
         this(new AttachmentsService());
      }

      /*******************************************************************************
       ** Injectable service for process tests.
       *******************************************************************************/
      public Review(AttachmentsService service)
      {
         this.service = service;
      }

      /*******************************************************************************
       ** Dry run only; no object or row mutations.
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         for(QRecord row : input.getRecords())
         {
            try
            {
               QRecord checked = service.requireMutable(row.getValue("id"));
               output.getRecords().add(checked);
               selected.incrementCountAndAddPrimaryKey(checked.getValue("id"));
            }
            catch(QException expected)
            {
               skipped.incrementCountAndAddPrimaryKey(row.getValue("id"));
            }
         }
      }

      /*******************************************************************************
       ** Review summary includes authorized and skipped counts.
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
    ** Execute and re-authorize each row; one failure does not stop the batch.
    *******************************************************************************/
   public static class Delete extends AbstractLoadStep implements ProcessSummaryProviderInterface
   {
      private final AttachmentsService service;
      private final ProcessSummaryLine deleted = line(Status.OK, "will be deleted", "deleted");
      private final ProcessSummaryLine failed = line(Status.ERROR, "could not be deleted", "could not be deleted (retry required)");

      /*******************************************************************************
       ** QQQ constructor.
       *******************************************************************************/
      public Delete()
      {
         this(new AttachmentsService());
      }

      /*******************************************************************************
       ** Injectable service for process tests.
       *******************************************************************************/
      public Delete(AttachmentsService service)
      {
         this.service = service;
      }

      /*******************************************************************************
       ** Count success only after both storage and row deletion succeed.
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         for(QRecord row : input.getRecords())
         {
            try
            {
               service.delete(List.of(row.getValue("id")));
               deleted.incrementCountAndAddPrimaryKey(row.getValue("id"));
            }
            catch(QException | RuntimeException expected)
            {
               failed.incrementCountAndAddPrimaryKey(row.getValue("id"));
            }
         }
      }

      /*******************************************************************************
       ** Actual results, plus the Review's skipped rows.
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         deleted.addSelfToListIfAnyCount(lines);
         if(getTransformStep() instanceof Review review)
         {
            review.skipped.addSelfToListIfAnyCount(lines);
         }
         failed.addSelfToListIfAnyCount(lines);
         return (lines);
      }
   }
}
