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


package com.kingsrook.qbits.collaboration;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.collaboration.assignment.AssignmentService;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.security.ParentRecordAccess;
import com.kingsrook.qbits.collaboration.sharing.AccessRequestService;
import com.kingsrook.qbits.collaboration.tasks.TasksService;
import com.kingsrook.qqq.backend.core.actions.permissions.CustomPermissionChecker;
import com.kingsrook.qqq.backend.core.context.QContext;
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
 ** Streamed Review/Result actions. Services reauthorize all writes at load time.
 *******************************************************************************/
public final class WorkProcesses
{
   /*******************************************************************************
    ** Metadata factory.
    *******************************************************************************/
   private WorkProcesses()
   {
   }

   /*******************************************************************************
    ** Record actions stay on their source tables, never in app navigation.
    *******************************************************************************/
   public static List<QProcessMetaData> management()
   {
      return (List.of(process(CollaborationTables.ACCESS_REQUEST, "Grant", "Mark granted"), process(CollaborationTables.ACCESS_REQUEST, "Decline", "Decline"),
         process(CollaborationTables.TASK, "Done", "Mark done"), process(CollaborationTables.TASK, "Reopen", "Reopen")));
   }

   /*******************************************************************************
    ** Hosts opt in only tables without their own ownership columns.
    *******************************************************************************/
   public static List<QProcessMetaData> assignments(String table)
   {
      return (List.of(process(table, "Assign", "Assign to"), process(table, "Own", "Take ownership")));
   }

   /*******************************************************************************
    ** Optional notes and assignee input precede the standard Review screen.
    *******************************************************************************/
   private static QProcessMetaData process(String table, String operation, String label)
   {
      boolean access = table.equals(CollaborationTables.ACCESS_REQUEST);
      Map<String, Serializable> values = new HashMap<>();
      values.put(StreamedETLWithFrontendProcess.FIELD_SOURCE_TABLE, table);
      values.put(StreamedETLWithFrontendProcess.FIELD_DESTINATION_TABLE, table);
      values.put(StreamedETLWithFrontendProcess.FIELD_SUPPORTS_FULL_VALIDATION, true);
      values.put(StreamedETLWithFrontendProcess.FIELD_DO_FULL_VALIDATION, true);
      values.put(StreamedETLWithFrontendProcess.FIELD_PREVIEW_MESSAGE, access
         ? "Record the outcome; grant access in your identity system first." : label + " for the selected records.");
      QProcessMetaData process = StreamedETLWithFrontendProcess.defineProcessMetaData(access ? AccessExtract.class : ExtractViaQueryStep.class, Review.class, Apply.class, values)
         .withName("collaboration" + operation + "_" + table).withLabel(label).withTableName(table).withIcon(new QIcon("assignment"))
         .withPermissionRules(new QPermissionRules().withLevel(PermissionLevel.NOT_PROTECTED).withCustomPermissionChecker(new QCodeReference(ActionSecurity.class)));
      if(access || operation.equals("Assign"))
      {
         QFieldMetaData field = new QFieldMetaData(access ? "note" : "assigneeUserId", access ? QFieldType.TEXT : QFieldType.STRING)
            .withLabel(access ? "Note" : "Assignee user ID").withIsRequired(!access).withMaxLength(access ? 2000 : 255);
         process.withStep(0, new QFrontendStepMetaData().withName("details").withLabel(label).withFormField(field)
            .withComponent(new QFrontendComponentMetaData().withType(QComponentType.EDIT_FORM)));
      }
      return (process);
   }

   /*******************************************************************************
    ** Operation and table come from installed process metadata.
    *******************************************************************************/
   private static String operation(RunBackendStepInput input)
   {
      return (input.getProcess().getName().substring("collaboration".length()).split("_")[0]);
   }

   /*******************************************************************************
    ** Share the same dry-run authorization between Review and load.
    *******************************************************************************/
   private static void validate(RunBackendStepInput input, QRecord row) throws QException
   {
      String table = input.getProcess().getTableName();
      if(table.equals(CollaborationTables.ACCESS_REQUEST))
      {
         new AccessRequestService().requireApprover(row.getValueInteger("id"));
         WorkSupport.text(input.getValueString("note"), 2000, false);
      }
      else if(table.equals(CollaborationTables.TASK))
      {
         new TasksService().requireManager(row.getValueInteger("id"));
      }
      else
      {
         Serializable id = row.getValue(QContext.getQInstance().getTable(table).getPrimaryKeyField());
         ParentRecordAccess.requireFeature(table, id, "assign");
         if(operation(input).equals("Assign"))
         {
            WorkSupport.text(input.getValueString("assigneeUserId"), 255, true);
            WorkSupport.requireReader(input.getValueString("assigneeUserId"), table, id);
         }
      }
   }

   /*******************************************************************************
    ** Preserve the role that the selected action does not modify.
    *******************************************************************************/
   private static void apply(RunBackendStepInput input, QRecord row) throws QException
   {
      validate(input, row);
      String table = input.getProcess().getTableName();
      String operation = operation(input);
      if(table.equals(CollaborationTables.ACCESS_REQUEST))
      {
         new AccessRequestService().handle(row.getValueInteger("id"), operation.equals("Grant") ? "GRANTED" : "DECLINED", input.getValueString("note"));
      }
      else if(table.equals(CollaborationTables.TASK))
      {
         if(operation.equals("Done"))
         {
            new TasksService().complete(row.getValueInteger("id"));
         }
         else
         {
            new TasksService().reopen(row.getValueInteger("id"));
         }
      }
      else
      {
         Serializable id = row.getValue(QContext.getQInstance().getTable(table).getPrimaryKeyField());
         AssignmentService service = new AssignmentService();
         QRecord old = service.get(table, id).orElse(new QRecord());
         service.assign(table, id, operation.equals("Own") ? PersonalRecords.currentUserId() : old.getValueString("ownerUserId"),
            operation.equals("Assign") ? input.getValueString("assigneeUserId") : old.getValueString("assigneeUserId"));
      }
   }

   /*******************************************************************************
    ** Consistent Review and Result counts.
    *******************************************************************************/
   private static ProcessSummaryLine line(Status status, String future, String past)
   {
      ProcessSummaryLine line = new ProcessSummaryLine(status, 0, null);
      line.setSingularFutureMessage(future);
      line.setPluralFutureMessage(future);
      line.setSingularPastMessage(past);
      line.setPluralPastMessage(past);
      return (line);
   }

   /*******************************************************************************
    ** Restrict action availability while keeping row authorization in the services.
    *******************************************************************************/
   public static class ActionSecurity implements CustomPermissionChecker
   {
      /*******************************************************************************
       ** Access actions require admin or a configured approver permission.
       *******************************************************************************/
      @Override
      public void checkPermissionsThrowing(AbstractActionInput input, MetaDataWithPermissionRules metadata) throws QPermissionDeniedException
      {
         PersonalRecords.currentUserId();
         String table = ((QProcessMetaData) metadata).getTableName();
         if(table.equals(CollaborationTables.ACCESS_REQUEST))
         {
            if(QContext.getQSession().hasPermission("collaboration.admin"))
            {
               return;
            }
            try
            {
               for(String host : QContext.getQInstance().getTables().keySet())
               {
                  if(!host.startsWith(CollaborationQBitConfig.TABLE_NAME_PREFIX)
                     && QContext.getQSession().hasPermission(new AccessRequestService().approverPermission(host)))
                  {
                     return;
                  }
               }
            }
            catch(QException e)
            {
               throw (new QPermissionDeniedException("Could not resolve access approver permission", e));
            }
            throw (new QPermissionDeniedException("This needs the access approver permission."));
         }
         if(!table.equals(CollaborationTables.TASK))
         {
            try
            {
               ParentRecordAccess.requireTableFeature(table, "assign");
            }
            catch(QException e)
            {
               throw (new QPermissionDeniedException("This needs the table assign permission or EDIT.", e));
            }
         }
      }
   }

   /*******************************************************************************
    ** Approvers can process requests without broadening requester/admin table reads.
    *******************************************************************************/
   public static class AccessExtract extends ExtractViaQueryStep
   {
      /*******************************************************************************
       ** Only authorized rows enter the process pipe or appear in its preview.
       *******************************************************************************/
      @Override
      public void run(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         int count = 0;
         for(QRecord row : CollaborationRecords.query(CollaborationTables.ACCESS_REQUEST, getQueryFilter(input)))
         {
            new AccessRequestService().requireApprover(row.getValueInteger("id"));
            getRecordPipe().addRecord(row);
            count++;
            if(getLimit() != null && count >= getLimit())
            {
               break;
            }
         }
      }

      /*******************************************************************************
       ** Avoid exposing an unfiltered count outside the table owner lock.
       *******************************************************************************/
      @Override
      public Integer doCount(RunBackendStepInput input)
      {
         return (null);
      }
   }

   /*******************************************************************************
    ** Review validates recipient access and authority without writing.
    *******************************************************************************/
   public static class Review extends AbstractTransformStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine selected = line(Status.OK, "records will be processed", "records processed");
      private final ProcessSummaryLine skipped = line(Status.WARNING, "records unavailable (skipped)", "records skipped");

      /*******************************************************************************
       ** Only validated rows advance to load.
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         if(input.getProcess().getTableName().equals(CollaborationTables.ACCESS_REQUEST))
         {
            selected.setSingularFutureMessage("Record the outcome; grant access in your identity system first.");
            selected.setPluralFutureMessage("Record the outcome; grant access in your identity system first.");
         }
         for(QRecord row : input.getRecords())
         {
            try
            {
               validate(input, row);
               output.getRecords().add(row);
               selected.incrementCountAndAddPrimaryKey(row.getValue(QContext.getQInstance().getTable(input.getProcess().getTableName()).getPrimaryKeyField()));
            }
            catch(QException expected)
            {
               skipped.incrementCountAndAddPrimaryKey(row.getValue("id"));
            }
         }
      }

      /*******************************************************************************
       ** Explicit identity-system prerequisite accompanies access-request reviews.
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
    ** Result reports actual writes and any permission changes since Review.
    *******************************************************************************/
   public static class Apply extends AbstractLoadStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine done = line(Status.OK, "records will be processed", "records processed");
      private final ProcessSummaryLine failed = line(Status.ERROR, "records cannot be processed", "records failed (retry required)");

      /*******************************************************************************
       ** Each write goes through its service authorization again.
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         for(QRecord row : input.getRecords())
         {
            try
            {
               apply(input, row);
               done.incrementCountAndAddPrimaryKey(row.getValue("id"));
            }
            catch(QException expected)
            {
               failed.incrementCountAndAddPrimaryKey(row.getValue("id"));
            }
         }
      }

      /*******************************************************************************
       ** Retain Review skips alongside successful and failed loads.
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
