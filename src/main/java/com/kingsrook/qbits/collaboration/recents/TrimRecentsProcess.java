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


package com.kingsrook.qbits.collaboration.recents;


import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import com.kingsrook.qbits.collaboration.CollaborationQBitConfig;
import com.kingsrook.qbits.collaboration.CollaborationSecurity;
import com.kingsrook.qbits.collaboration.PersonalRecords;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.permissions.CollaborationPermissions;
import com.kingsrook.qqq.backend.core.actions.permissions.CustomPermissionChecker;
import com.kingsrook.qqq.backend.core.actions.processes.BackendStep;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.actions.AbstractActionInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
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
 ** Hidden daily retention job. Runs with its caller's session, which must be a
 ** scheduler system session or collaboration admin; never elevates a user.
 *******************************************************************************/
public class TrimRecentsProcess implements BackendStep, CustomPermissionChecker
{
   public static final String NAME = "collaborationTrimRecents";



   /*******************************************************************************
    ** Always register the backend-only process; scheduling is opt-in.
    *******************************************************************************/
   public static QProcessMetaData metadata(CollaborationQBitConfig config)
   {
      QProcessMetaData process = new QProcessMetaData().withName(NAME).withLabel("Trim recents").withIsHidden(true).withIcon(new QIcon("history"))
         .withPermissionRules(new QPermissionRules().withLevel(PermissionLevel.HAS_ACCESS_PERMISSION)
            .withPermissionBaseName("collaboration").withCustomPermissionChecker(new QCodeReference(TrimRecentsProcess.class)))
         .withStep(new QBackendStepMetaData().withName("run").withCode(new QCodeReference(TrimRecentsProcess.class)));
      if(config.getSchedulerName() != null)
      {
         process.withSchedule(new QScheduleMetaData().withSchedulerName(config.getSchedulerName()).withRepeatSeconds(86400).withInitialDelaySeconds(86400));
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
    ** Keyset-page users, then delete overflow in bounded batches. Re-query each
    ** user's newest N before every batch, avoiding offsets over deleted rows.
    *******************************************************************************/
   @Override
   public void run(RunBackendStepInput input, RunBackendStepOutput output) throws QException
   {
      checkPermissionsThrowing(input, null);
      CollaborationSecurity.ensureSessionKeys(QContext.getQInstance(), QContext.getQSession());
      CollaborationQBitConfig config = (CollaborationQBitConfig) QContext.getQInstance().getQBits().values().stream()
         .filter(qbit -> qbit.getConfig() instanceof CollaborationQBitConfig).findFirst().orElseThrow().getConfig();
      String previousUser = null;
      while(true)
      {
         QQueryFilter nextUser = new QQueryFilter().withOrderBy(new QFilterOrderBy("userId")).withLimit(1);
         if(previousUser != null)
         {
            nextUser.withCriteria("userId", QCriteriaOperator.GREATER_THAN, previousUser);
         }
         List<QRecord> next = PersonalRecords.query(CollaborationTables.RECENT, nextUser);
         if(next.isEmpty())
         {
            break;
         }
         previousUser = next.getFirst().getValueString("userId");
         while(true)
         {
            List<QRecord> overflow = PersonalRecords.query(CollaborationTables.RECENT, new QQueryFilter()
               .withCriteria("userId", QCriteriaOperator.EQUALS, previousUser)
               .withOrderBy(new QFilterOrderBy("lastViewedAt", false)).withOrderBy(new QFilterOrderBy("id", false))
               .withSkip(config.getRecentsPerUser()).withLimit(500));
            if(overflow.isEmpty())
            {
               break;
            }
            List<Serializable> ids = new ArrayList<>(overflow.stream().map(row -> row.getValue("id")).toList());
            PersonalRecords.delete(CollaborationTables.RECENT, ids);
         }
      }
   }
}
