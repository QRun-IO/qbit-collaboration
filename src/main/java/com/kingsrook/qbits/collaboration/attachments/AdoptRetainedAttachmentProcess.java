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


import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.permissions.CollaborationPermissions;
import com.kingsrook.qqq.backend.core.actions.processes.BackendStep;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;


/*******************************************************************************
 ** A visible native adoption action for an existing original. It never
 ** accepts replacement bytes, a storage path, an actor, or an intake scope.
 *******************************************************************************/
public final class AdoptRetainedAttachmentProcess
{
   public static final String NAME = "collaborationAdoptRetainedAttachment";

   /*******************************************************************************
    ** Metadata utility.
    *******************************************************************************/
   private AdoptRetainedAttachmentProcess()
   {
   }

   /*******************************************************************************
    ** Standard QQQ confirmation and result screens, attached to the attachment table.
    *******************************************************************************/
   public static QProcessMetaData metadata()
   {
      return (new QProcessMetaData().withName(NAME).withTableName(CollaborationTables.ATTACHMENT).withLabel("Retain existing original")
         .withMinInputRecords(1).withMaxInputRecords(1).withPermissionRules(CollaborationPermissions.permissionRules())
         .withStep(new com.kingsrook.qqq.backend.core.model.metadata.processes.QBackendStepMetaData().withName("prepare").withCode(new QCodeReference(Prepare.class))
            .withInputData(new com.kingsrook.qqq.backend.core.model.metadata.processes.QFunctionInputMetaData()
               .withRecordListMetaData(new com.kingsrook.qqq.backend.core.model.metadata.processes.QRecordListMetaData().withTableName(CollaborationTables.ATTACHMENT))))
         .withStep(new com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendStepMetaData().withName("confirm").withLabel("Retain this original")
            .withComponent(new com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendComponentMetaData()
               .withType(com.kingsrook.qqq.backend.core.model.metadata.processes.QComponentType.EDIT_FORM))
            .withFormField(new QFieldMetaData("confirmAdoption", QFieldType.BOOLEAN).withLabel("Verify and retain the existing original without copying bytes").withDefaultValue(false).withIsRequired(true)))
         .withStep(new com.kingsrook.qqq.backend.core.model.metadata.processes.QBackendStepMetaData().withName("adopt").withCode(new QCodeReference(Adopt.class)))
         .withStep(new com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendStepMetaData().withName("result").withLabel("Original retained")
            .withComponent(new com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendComponentMetaData()
               .withType(com.kingsrook.qqq.backend.core.model.metadata.processes.QComponentType.VIEW_FORM))
            .withViewField(new QFieldMetaData("attachmentId", QFieldType.INTEGER).withLabel("Retained attachment")
               .withPossibleValueSourceName(com.kingsrook.qbits.collaboration.model.CollaborationTables.ATTACHMENT))));
   }

   /*******************************************************************************
    ** Resolve one visible attachment selection before displaying confirmation.
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
            throw (new QException("Select exactly one attachment."));
         }
         var row = new AttachmentsService().requireAdoptable(input.getRecords().getFirst().getValueInteger("id"));
         if(row == null)
         {
            throw (new QException("Attachment is unavailable."));
         }
         if(row.getValueString("intakeKey") != null)
         {
            throw (new QException("Retry this upload through its original intake request to preserve its context."));
         }
         output.addValue("attachmentId", row.getValue("id"));
         output.addValue("confirmAdoption", false);
      }
   }

   /*******************************************************************************
    ** Execution reauthorizes independently of the earlier preview.
    *******************************************************************************/
   public static final class Adopt implements BackendStep
   {
      /*******************************************************************************
       ** The owner returns only after mandatory lifecycle registration completes.
       *******************************************************************************/
      @Override
      public void run(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         if(!Boolean.TRUE.equals(input.getValueBoolean("confirmAdoption")))
         {
            throw (new QException("Confirm adoption of this existing original to continue."));
         }
         output.addValue("attachmentId", new AttachmentsService().adoptRetainedAttachment(input.getValueInteger("attachmentId")).id());
      }
   }
}
