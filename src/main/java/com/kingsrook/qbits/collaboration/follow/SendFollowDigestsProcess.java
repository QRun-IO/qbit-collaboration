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


import com.kingsrook.qbits.collaboration.CollaborationQBitConfig;
import com.kingsrook.qbits.collaboration.permissions.CollaborationPermissions;
import com.kingsrook.qqq.backend.core.actions.permissions.CustomPermissionChecker;
import com.kingsrook.qqq.backend.core.actions.processes.BackendStep;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.actions.AbstractActionInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QIcon;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.MetaDataWithPermissionRules;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.PermissionLevel;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.QPermissionRules;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QBackendStepMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.scheduleing.QScheduleMetaData;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.model.session.QSystemUserSession;


/*******************************************************************************
 ** Hidden minute-by-minute digest job. Runs with its caller's session, which must be a
 ** scheduler system session or collaboration admin; never elevates a user.
 *******************************************************************************/
public class SendFollowDigestsProcess implements BackendStep, CustomPermissionChecker
{
   public static final String NAME = "collaborationSendFollowDigests";



   /*******************************************************************************
    ** Always register the backend-only process; scheduling is opt-in.
    *******************************************************************************/
   public static QProcessMetaData metadata(CollaborationQBitConfig config)
   {
      QProcessMetaData process = new QProcessMetaData().withName(NAME).withLabel("Send follow digests").withIsHidden(true).withIcon(new QIcon("history"))
         .withPermissionRules(new QPermissionRules().withLevel(PermissionLevel.HAS_ACCESS_PERMISSION)
            .withPermissionBaseName("collaboration").withCustomPermissionChecker(new QCodeReference(SendFollowDigestsProcess.class)))
         .withStep(new QBackendStepMetaData().withName("run").withCode(new QCodeReference(SendFollowDigestsProcess.class)));
      if(config.getSchedulerName() != null)
      {
         process.withSchedule(new QScheduleMetaData().withSchedulerName(config.getSchedulerName()).withRepeatSeconds(60).withInitialDelaySeconds(60));
      }
      return (process);
   }



   /*******************************************************************************
    ** Hidden is a presentation setting, so explicitly guard execution as well.
    *******************************************************************************/
   @Override
   public void checkPermissionsThrowing(AbstractActionInput input, MetaDataWithPermissionRules metadata) throws QPermissionDeniedException
   {
      QSession session = QContext.getQSession();
      if(!(session instanceof QSystemUserSession) && (session == null || !session.hasPermission(CollaborationPermissions.ADMIN)))
      {
         throw (new QPermissionDeniedException("This needs the collaboration.admin permission."));
      }
   }



   /*******************************************************************************
    ** Deliver due batches using fresh recipient permissions.
    *******************************************************************************/
   @Override
   public void run(RunBackendStepInput input, RunBackendStepOutput output) throws QException
   {
      checkPermissionsThrowing(input, null);
      new FollowChangeBatcher().sendDue();
   }
}
