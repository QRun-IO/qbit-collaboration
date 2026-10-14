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


package com.kingsrook.qbits.collaboration.customfields;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLine;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLineInterface;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.actions.processes.Status;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.PermissionLevel;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.QPermissionRules;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractLoadStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractTransformStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.ExtractViaQueryStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.ProcessSummaryProviderInterface;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.StreamedETLWithFrontendProcess;

/*******************************************************************************
 ** Standard streamed Review/Result field actions, never navigation entries.
 *******************************************************************************/
public final class FieldProcesses
{
   /*******************************************************************************
    ** Static metadata factory.
    *******************************************************************************/
   private FieldProcesses()
   {
   }

   /*******************************************************************************
    ** Bind the action operation into trusted process metadata.
    *******************************************************************************/
   public static List<QProcessMetaData> metadata()
   {
      List<QProcessMetaData> processes = new ArrayList<>();
      for(String operation : List.of("Publish", "Archive", "Rebuild"))
      {
         var values = new HashMap<String, Serializable>();
         values.put(StreamedETLWithFrontendProcess.FIELD_SOURCE_TABLE, CollaborationTables.FIELD_DEFINITION);
         values.put(StreamedETLWithFrontendProcess.FIELD_DESTINATION_TABLE, CollaborationTables.FIELD_DEFINITION);
         values.put(StreamedETLWithFrontendProcess.FIELD_SUPPORTS_FULL_VALIDATION, true);
         values.put(StreamedETLWithFrontendProcess.FIELD_DO_FULL_VALIDATION, true);
         values.put(StreamedETLWithFrontendProcess.FIELD_PREVIEW_MESSAGE, operation + " selected custom fields.");
         processes.add(StreamedETLWithFrontendProcess.defineProcessMetaData(ExtractViaQueryStep.class, Review.class, Apply.class, values)
            .withName("collaboration" + operation + "Field").withLabel(operation.equals("Rebuild") ? "Rebuild index" : operation + " field")
            .withTableName(CollaborationTables.FIELD_DEFINITION)
            .withPermissionRules(new QPermissionRules().withLevel(PermissionLevel.NOT_PROTECTED).withCustomPermissionChecker(new QCodeReference(FieldSecurity.class))));
      }
      return (processes);
   }

   /*******************************************************************************
    ** Request parameters cannot substitute another lifecycle operation.
    *******************************************************************************/
   private static String operation(RunBackendStepInput input)
   {
      return (input.getProcess().getName().substring("collaboration".length()).replace("Field", ""));
   }

   /*******************************************************************************
    ** Summary language works for both singular and plural selections.
    *******************************************************************************/
   private static ProcessSummaryLine line(Status status, String message)
   {
      var line = new ProcessSummaryLine(status, 0, null);
      line.setSingularFutureMessage(message);
      line.setPluralFutureMessage(message);
      line.setSingularPastMessage(message);
      line.setPluralPastMessage(message);
      return (line);
   }

   /*******************************************************************************
    ** Review validates every row and counts values retained by archive.
    *******************************************************************************/
   public static class Review extends AbstractTransformStep implements ProcessSummaryProviderInterface
   {
      private final ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();

      /*******************************************************************************
       ** Failed rows are omitted from the load stream with an explicit reason.
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         for(var row : input.getRecords())
         {
            var id = row.getValue("id");
            ProcessSummaryLine summary;
            try
            {
               var definition = FieldDefinitions.get(id);
               FieldDefinitions.review(definition, operation(input));
               String message = operation(input) + " field " + definition.getValueString("label");
               if("Archive".equals(operation(input)))
               {
                  message += "; " + FieldDefinitions.valueCount(definition) + " records have a value (values retained)";
               }
               summary = line(Status.OK, message);
               output.getRecords().add(row);
            }
            catch(QException e)
            {
               summary = line(Status.WARNING, "Skipped: " + e.getMessage());
            }
            summary.incrementCountAndAddPrimaryKey(id);
            lines.add(summary);
         }
      }

      /*******************************************************************************
       ** Streamed pages accumulate an explicit Review summary.
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean result)
      {
         return (lines);
      }
   }

   /*******************************************************************************
    ** Apply rechecks permissions and status, then reports actual outcomes.
    *******************************************************************************/
   public static class Apply extends AbstractLoadStep implements ProcessSummaryProviderInterface
   {
      private final ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();

      /*******************************************************************************
       ** One failed field does not prevent other selected fields from completing.
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         for(var row : input.getRecords())
         {
            ProcessSummaryLine summary;
            try
            {
               FieldDefinitions.apply(row.getValue("id"), operation(input));
               summary = line(Status.OK, operation(input) + " completed");
            }
            catch(QException e)
            {
               summary = line(Status.ERROR, "Failed: " + e.getMessage());
            }
            summary.incrementCountAndAddPrimaryKey(row.getValue("id"));
            lines.add(summary);
         }
      }

      /*******************************************************************************
       ** Report completed actions independently of Review predictions.
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean result)
      {
         return (lines);
      }
   }
}
