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


import com.kingsrook.qbits.collaboration.attachments.uploadjournal.UploadJournalTable;
import com.kingsrook.qbits.collaboration.permissions.CollaborationPermissions;
import com.kingsrook.qqq.backend.core.actions.processes.BackendStep;
import com.kingsrook.qqq.backend.core.actions.tables.GetAction;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.actions.tables.get.GetInput;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;


/*******************************************************************************
 ** A visible native recovery action for retained original registration. It never
 ** accepts replacement bytes, a storage path, an actor, or an intake scope.
 *******************************************************************************/
public final class RecoverRetainedUploadProcess
{
   public static final String NAME = "collaborationRecoverRetainedUpload";

   /*******************************************************************************
    ** Metadata utility.
    *******************************************************************************/
   private RecoverRetainedUploadProcess()
   {
   }

   /*******************************************************************************
    ** Standard QQQ confirmation and result screens, attached to the journal table.
    *******************************************************************************/
   public static QProcessMetaData metadata()
   {
      return (new QProcessMetaData().withName(NAME).withTableName(UploadJournalTable.NAME).withLabel("Recover retained upload")
         .withMinInputRecords(1).withMaxInputRecords(1).withPermissionRules(CollaborationPermissions.permissionRules())
         .withStep(new com.kingsrook.qqq.backend.core.model.metadata.processes.QBackendStepMetaData().withName("prepare").withCode(new QCodeReference(Prepare.class))
            .withInputData(new com.kingsrook.qqq.backend.core.model.metadata.processes.QFunctionInputMetaData()
               .withRecordListMetaData(new com.kingsrook.qqq.backend.core.model.metadata.processes.QRecordListMetaData().withTableName(UploadJournalTable.NAME))))
         .withStep(new com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendStepMetaData().withName("confirm").withLabel("Recover the original upload")
            .withComponent(new com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendComponentMetaData()
               .withType(com.kingsrook.qqq.backend.core.model.metadata.processes.QComponentType.EDIT_FORM))
            .withFormField(new QFieldMetaData("confirmRecovery", QFieldType.BOOLEAN).withLabel("Verify the stored original and retry registration").withDefaultValue(false).withIsRequired(true)))
         .withStep(new com.kingsrook.qqq.backend.core.model.metadata.processes.QBackendStepMetaData().withName("recover").withCode(new QCodeReference(Recover.class)))
         .withStep(new com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendStepMetaData().withName("result").withLabel("Original registration saved")
            .withComponent(new com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendComponentMetaData()
               .withType(com.kingsrook.qqq.backend.core.model.metadata.processes.QComponentType.VIEW_FORM))
            .withViewField(new QFieldMetaData("attachmentId", QFieldType.INTEGER).withLabel("Retained attachment")
               .withPossibleValueSourceName(com.kingsrook.qbits.collaboration.model.CollaborationTables.ATTACHMENT))));
   }

   /*******************************************************************************
    ** Resolve one visible journal selection before displaying confirmation.
    *******************************************************************************/
   public static final class Prepare implements BackendStep
   {
      /*******************************************************************************
       ** Read-only preview under current actor and parent visibility.
       *******************************************************************************/
      @Override
      public void run(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         if(input.getRecords() == null || input.getRecords().size() != 1)
         {
            throw (new QException("Select exactly one retained upload."));
         }
         var row = new GetAction().execute(new GetInput(UploadJournalTable.NAME).withPrimaryKey(input.getRecords().getFirst().getValue("id"))).getRecord();
         if(row == null)
         {
            throw (new QException("Retained upload is unavailable."));
         }
         if(row.getValueString("intakeKey") != null)
         {
            throw (new QException("Retry this upload through its original intake request to preserve its context."));
         }
         output.addValue("journalId", row.getValue("id"));
         output.addValue("confirmRecovery", false);
      }
   }

   /*******************************************************************************
    ** Execution reauthorizes independently of the earlier preview.
    *******************************************************************************/
   public static final class Recover implements BackendStep
   {
      /*******************************************************************************
       ** The owner returns only after mandatory lifecycle registration completes.
       *******************************************************************************/
      @Override
      public void run(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         if(!Boolean.TRUE.equals(input.getValue("confirmRecovery")))
         {
            throw (new QException("Confirm retained upload recovery to continue."));
         }
         output.addValue("attachmentId", new AttachmentsService().recoverRetainedUpload(input.getValueInteger("journalId")).id());
      }
   }
}
