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
import java.util.Optional;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.permissions.CollaborationPermissions;
import com.kingsrook.qqq.backend.core.actions.QBackendTransaction;
import com.kingsrook.qqq.backend.core.actions.permissions.CustomPermissionChecker;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.actions.AbstractActionInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLine;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLineInterface;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.actions.processes.Status;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QIcon;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.MetaDataWithPermissionRules;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.PermissionLevel;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.QPermissionRules;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractExtractStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractLoadStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractTransformStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.ProcessSummaryProviderInterface;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.StreamedETLWithFrontendProcess;


/*******************************************************************************
 ** "Create rows from configuration": a table action on Feature Settings that
 ** writes the code configuration out as editable rows (FeatureSettingsDefaults).
 ** It changes no behavior on its own, only makes the effective settings visible
 ** and editable. It does nothing once the table has any row. Standard streamed
 ** ETL: Review lists the rows that will be created, Result the rows created.
 *******************************************************************************/
public final class FeatureSettingsDefaultsProcess
{
   public static final String NAME = "collaborationCreateFeatureSettings";



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private FeatureSettingsDefaultsProcess()
   {
   }



   /*******************************************************************************
    ** The process: a table-level action (no records need to be selected).
    *******************************************************************************/
   public static QProcessMetaData process()
   {
      Map<String, Serializable> values = new HashMap<>();
      values.put(StreamedETLWithFrontendProcess.FIELD_SOURCE_TABLE, CollaborationTables.FEATURE_SETTING);
      values.put(StreamedETLWithFrontendProcess.FIELD_DESTINATION_TABLE, CollaborationTables.FEATURE_SETTING);
      values.put(StreamedETLWithFrontendProcess.FIELD_SUPPORTS_FULL_VALIDATION, false);
      values.put(StreamedETLWithFrontendProcess.FIELD_PREVIEW_MESSAGE,
         "These rows will be created from the current configuration. Nothing changes: the rows say what the configuration already says, and you can edit them afterwards.");
      QProcessMetaData process = StreamedETLWithFrontendProcess.defineProcessMetaData(Extract.class, Transform.class, Load.class, values);
      process.setName(NAME);
      process.setLabel("Create rows from configuration");
      process.setIcon(new QIcon("playlist_add"));
      process.setTableName(CollaborationTables.FEATURE_SETTING);
      process.setMinInputRecords(0);
      process.setPermissionRules(new QPermissionRules().withLevel(PermissionLevel.HAS_ACCESS_PERMISSION).withPermissionBaseName("collaboration")
         .withCustomPermissionChecker(new QCodeReference(AdminOnly.class)));
      return (process);
   }



   /*******************************************************************************
    ** Collaboration administrators only (the table itself is admin-write).
    *******************************************************************************/
   public static class AdminOnly implements CustomPermissionChecker
   {
      /*******************************************************************************
       **
       *******************************************************************************/
      @Override
      public void checkPermissionsThrowing(AbstractActionInput input, MetaDataWithPermissionRules metadata) throws QPermissionDeniedException
      {
         QSession session = QContext.getQSession();
         if(session == null || !session.hasPermission(CollaborationPermissions.ADMIN))
         {
            throw (new QPermissionDeniedException("This needs the " + CollaborationPermissions.ADMIN + " permission."));
         }
      }
   }



   /*******************************************************************************
    ** Extract: the configured rows (none when the table already has rows).
    *******************************************************************************/
   public static class Extract extends AbstractExtractStep
   {
      /*******************************************************************************
       **
       *******************************************************************************/
      @Override
      public Integer doCount(RunBackendStepInput input) throws QException
      {
         return (FeatureSettingsDefaults.missingRows(QContext.getQInstance(), CollaborationRecords.config()).size());
      }



      /*******************************************************************************
       **
       *******************************************************************************/
      @Override
      public void run(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         for(QRecord row : FeatureSettingsDefaults.missingRows(QContext.getQInstance(), CollaborationRecords.config()))
         {
            getRecordPipe().addRecord(row);
         }
      }
   }



   /*******************************************************************************
    ** Transform: counts by scope for the review screen.
    *******************************************************************************/
   public static class Transform extends AbstractTransformStep implements ProcessSummaryProviderInterface
   {
      private final Map<String, ProcessSummaryLine> byScope = new HashMap<>();



      /*******************************************************************************
       **
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         for(QRecord record : input.getRecords())
         {
            String scope = record.getValueString("scopeType");
            byScope.computeIfAbsent(scope, s -> line(Status.OK, scope + " setting will be created", scope + " settings will be created",
               scope + " setting was created", scope + " settings were created")).incrementCount();
            output.getRecords().add(record);
         }
      }



      /*******************************************************************************
       **
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         byScope.values().forEach(line -> line.addSelfToListIfAnyCount(lines));
         if(lines.isEmpty())
         {
            lines.add(new ProcessSummaryLine(Status.INFO, null, "Feature settings already has rows, so nothing was created. Edit the rows directly."));
         }
         return (lines);
      }
   }



   /*******************************************************************************
    ** Load: insert through the standard action with the administrator's own session.
    *******************************************************************************/
   public static class Load extends AbstractLoadStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine created = line(Status.OK, "setting was created", "settings were created", "setting was created", "settings were created");



      /*******************************************************************************
       ** One transaction for the whole load: the override refresh waits for the
       ** commit, so a failure on a later page can never leave some rows (for
       ** example Instance rows without the App and Table rows that restate the
       ** code) in effect.
       *******************************************************************************/
      @Override
      public Optional<QBackendTransaction> openTransaction(RunBackendStepInput input) throws QException
      {
         return (Optional.of(QBackendTransaction.openFor(new InsertInput(CollaborationTables.FEATURE_SETTING))));
      }



      /*******************************************************************************
       **
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         List<QRecord> inserted = new InsertAction().execute(new InsertInput(CollaborationTables.FEATURE_SETTING).withRecords(input.getRecords())
            .withTransaction(getTransaction().orElse(null))).getRecords();
         for(QRecord record : inserted)
         {
            if(record.getErrors() != null && !record.getErrors().isEmpty())
            {
               throw (new QException("Could not create feature settings: " + record.getErrors()));
            }
            created.incrementCount();
            output.getRecords().add(record);
         }
      }



      /*******************************************************************************
       **
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         created.addSelfToListIfAnyCount(lines);
         return (lines);
      }
   }



   /*******************************************************************************
    ** A summary line with its four messages.
    *******************************************************************************/
   private static ProcessSummaryLine line(Status status, String singularFuture, String pluralFuture, String singularPast, String pluralPast)
   {
      ProcessSummaryLine line = new ProcessSummaryLine(status, 0, null);
      line.setSingularFutureMessage(singularFuture);
      line.setPluralFutureMessage(pluralFuture);
      line.setSingularPastMessage(singularPast);
      line.setPluralPastMessage(pluralPast);
      return (line);
   }

}
