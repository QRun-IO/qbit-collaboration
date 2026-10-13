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


package com.kingsrook.qbits.collaboration.follow;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.collaboration.security.ParentRecordAccess;
import com.kingsrook.qqq.backend.core.actions.permissions.TablePermissionSubType;
import com.kingsrook.qqq.backend.core.actions.permissions.UseTablePermissionCustomPermissionChecker;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QNotFoundException;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLine;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLineInterface;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.actions.processes.Status;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QIcon;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.QPermissionRules;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractLoadStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractTransformStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.ExtractViaQueryStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.ProcessSummaryProviderInterface;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.StreamedETLWithFrontendProcess;

/*******************************************************************************
 ** Standard streamed ETL Follow/Unfollow actions for any host table with a key.
 *******************************************************************************/
public final class FollowProcesses
{
   /*******************************************************************************
    ** Metadata factory.
    *******************************************************************************/
   private FollowProcesses()
   {
   }

   /*******************************************************************************
    ** Hosts can register these directly or configure followActionTables.
    *******************************************************************************/
   public static List<QProcessMetaData> processesFor(String tableName)
   {
      return (List.of(process(tableName, true), process(tableName, false)));
   }

   /*******************************************************************************
    ** Process table association puts actions on records, never in navigation.
    *******************************************************************************/
   private static QProcessMetaData process(String table, boolean follow)
   {
      Map<String, Serializable> values = new HashMap<>();
      values.put(StreamedETLWithFrontendProcess.FIELD_SOURCE_TABLE, table);
      values.put(StreamedETLWithFrontendProcess.FIELD_DESTINATION_TABLE, table);
      values.put(StreamedETLWithFrontendProcess.FIELD_SUPPORTS_FULL_VALIDATION, true);
      values.put(StreamedETLWithFrontendProcess.FIELD_DO_FULL_VALIDATION, true);
      values.put(StreamedETLWithFrontendProcess.FIELD_PREVIEW_MESSAGE, (follow ? "Follow" : "Unfollow") + " the selected records.");
      return (StreamedETLWithFrontendProcess.defineProcessMetaData(ExtractViaQueryStep.class, Review.class, Apply.class, values)
         .withName("collaboration" + (follow ? "Follow_" : "Unfollow_") + table).withLabel(follow ? "Follow" : "Unfollow")
         .withTableName(table).withIcon(new QIcon(follow ? "notifications" : "notifications_off"))
         .withPermissionRules(new QPermissionRules().withCustomPermissionChecker(UseTablePermissionCustomPermissionChecker.build(table, TablePermissionSubType.READ))));
   }

   /*******************************************************************************
    ** Stable process metadata, not client values, chooses table and operation.
    *******************************************************************************/
   private static String table(RunBackendStepInput input)
   {
      return (input.getProcess().getTableName());
   }

   /*******************************************************************************
    ** Summary renderer has both Review and Result wording.
    *******************************************************************************/
   private static ProcessSummaryLine line(Status status, String future, String past)
   {
      ProcessSummaryLine line = new ProcessSummaryLine(status, 0, null);
      line.setSingularFutureMessage("record " + future);
      line.setPluralFutureMessage("records " + future);
      line.setSingularPastMessage("record " + past);
      line.setPluralPastMessage("records " + past);
      return (line);
   }

   /*******************************************************************************
    ** Dry-run authorization re-reads parents under the current session.
    *******************************************************************************/
   public static class Review extends AbstractTransformStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine selected = line(Status.OK, "will be processed", "processed");
      private final ProcessSummaryLine skipped = line(Status.WARNING, "is unavailable (skipped)", "skipped");

      /*******************************************************************************
       ** No writes during review.
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         String table = table(input);
         String key = QContext.getQInstance().getTable(table).getPrimaryKeyField();
         for(QRecord row : input.getRecords())
         {
            try
            {
               QRecord parent = ParentRecordAccess.requireVisibleParent(table, row.getValue(key));
               output.getRecords().add(parent);
               selected.incrementCountAndAddPrimaryKey(parent.getValue(key));
            }
            catch(QNotFoundException expected)
            {
               skipped.incrementCountAndAddPrimaryKey(row.getValue(key));
            }
         }
      }

      /*******************************************************************************
       ** Authorized and skipped selections.
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
    ** Re-authorize during load; report actual successes and failures.
    *******************************************************************************/
   public static class Apply extends AbstractLoadStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine done = line(Status.OK, "will be processed", "processed");
      private final ProcessSummaryLine failed = line(Status.ERROR, "cannot be processed", "failed (retry required)");

      /*******************************************************************************
       ** A failed record does not prevent other selections being processed.
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         String table = table(input);
         String key = QContext.getQInstance().getTable(table).getPrimaryKeyField();
         for(QRecord row : input.getRecords())
         {
            try
            {
               if(input.getProcessName().equals("collaborationFollow_" + table))
               {
                  new FollowService().follow(table, row.getValue(key), null, null);
               }
               else
               {
                  new FollowService().unfollow(table, row.getValue(key));
               }
               done.incrementCountAndAddPrimaryKey(row.getValue(key));
            }
            catch(QException expected)
            {
               failed.incrementCountAndAddPrimaryKey(row.getValue(key));
            }
         }
      }

      /*******************************************************************************
       ** Result includes review skips and load failures.
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         done.addSelfToListIfAnyCount(lines);
         if(getTransformStep() instanceof Review review)
         {
            review.skipped.addSelfToListIfAnyCount(lines);
         }
         failed.addSelfToListIfAnyCount(lines);
         return (lines);
      }
   }
}
