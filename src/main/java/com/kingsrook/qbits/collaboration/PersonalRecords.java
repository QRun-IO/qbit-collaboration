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
import java.util.List;
import com.kingsrook.qqq.backend.core.actions.permissions.PermissionEnforcement;
import com.kingsrook.qqq.backend.core.actions.tables.DeleteAction;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.actions.tables.UpdateAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.actions.tables.delete.DeleteInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;


/*******************************************************************************
 ** Shared current-session persistence. Explicit user filters keep personal service
 ** calls scoped to the caller even when administrators hold the all-access key.
 *******************************************************************************/
public final class PersonalRecords
{
   /*******************************************************************************
    ** Static operations only.
    *******************************************************************************/
   private PersonalRecords()
   {
   }



   /*******************************************************************************
    ** Require an authenticated owner before accepting a personal operation.
    *******************************************************************************/
   public static String currentUserId() throws QPermissionDeniedException
   {
      CollaborationSecurity.ensureSessionKeys(QContext.getQInstance(), QContext.getQSession());
      String userId = CollaborationSecurity.userId(QContext.getQSession());
      if(userId == null || userId.isBlank())
      {
         throw (new QPermissionDeniedException("Sign in to manage favorites and recents."));
      }
      return (userId);
   }



   /*******************************************************************************
    ** Always constrain personal services to the current owner.
    *******************************************************************************/
   public static QQueryFilter ownFilter() throws QPermissionDeniedException
   {
      return (new QQueryFilter().withCriteria("userId", QCriteriaOperator.EQUALS, currentUserId()));
   }



   /*******************************************************************************
    ** Null record ids use IS_BLANK, not SQL equality with NULL.
    *******************************************************************************/
   public static QQueryFilter targetFilter(String type, String name, String recordId) throws QPermissionDeniedException
   {
      QQueryFilter filter = ownFilter().withCriteria("targetType", QCriteriaOperator.EQUALS, type)
         .withCriteria("targetName", QCriteriaOperator.EQUALS, name);
      if(recordId == null)
      {
         filter.withCriteria("recordId", QCriteriaOperator.IS_BLANK);
      }
      else
      {
         filter.withCriteria("recordId", QCriteriaOperator.EQUALS, recordId);
      }
      return (filter);
   }



   /*******************************************************************************
    ** Enforce permissions independently of the host's core-action default.
    *******************************************************************************/
   public static List<QRecord> query(String table, QQueryFilter filter) throws QException
   {
      QueryInput input = new QueryInput(table).withFilter(filter);
      input.setPermissionEnforcement(PermissionEnforcement.ENFORCE);
      return (new QueryAction().execute(input).getRecords());
   }



   /*******************************************************************************
    ** Propagate per-record validation failures instead of reporting success.
    *******************************************************************************/
   public static QRecord insert(String table, QRecord record) throws QException
   {
      InsertInput input = new InsertInput(table).withRecords(List.of(record));
      input.setPermissionEnforcement(PermissionEnforcement.ENFORCE);
      QRecord result = new InsertAction().execute(input).getRecords().getFirst();
      checkErrors(List.of(result));
      return (result);
   }



   /*******************************************************************************
    ** Update only fields supplied by the service, using the current session.
    *******************************************************************************/
   public static void update(String table, List<QRecord> records) throws QException
   {
      if(records.isEmpty())
      {
         return;
      }
      UpdateInput input = new UpdateInput(table).withRecords(records);
      input.setPermissionEnforcement(PermissionEnforcement.ENFORCE);
      checkErrors(new UpdateAction().execute(input).getRecords());
   }



   /*******************************************************************************
    ** Delete already selected ids; row locks are still enforced by DeleteAction.
    *******************************************************************************/
   public static void delete(String table, List<Serializable> ids) throws QException
   {
      if(ids.isEmpty())
      {
         return;
      }
      DeleteInput input = new DeleteInput(table).withPrimaryKeys(ids);
      input.setPermissionEnforcement(PermissionEnforcement.ENFORCE);
      checkErrors(new DeleteAction().execute(input).getRecordsWithErrors());
   }



   /*******************************************************************************
    ** QQQ actions can return record errors without throwing.
    *******************************************************************************/
   private static void checkErrors(List<QRecord> records) throws QException
   {
      if(records != null)
      {
         for(QRecord record : records)
         {
            if(record.getErrors() != null && !record.getErrors().isEmpty())
            {
               throw (new QException("Could not save personal target: " + record.getErrors()));
            }
         }
      }
   }
}
