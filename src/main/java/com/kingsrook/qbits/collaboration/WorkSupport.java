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
import com.kingsrook.qbits.collaboration.people.PeopleService;
import com.kingsrook.qbits.collaboration.security.OtherUserAccess;
import com.kingsrook.qbits.collaboration.security.ParentRecordAccess;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QNotFoundException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;

/*******************************************************************************
 ** Shared validation for service-managed work records.
 *******************************************************************************/
public final class WorkSupport
{
   /*******************************************************************************
    ** Static helpers only.
    *******************************************************************************/
   private WorkSupport()
   {
   }

   /*******************************************************************************
    ** Look up an extension row and enforce its parent before returning data.
    *******************************************************************************/
   public static QRecord visible(String table, Integer id) throws QException
   {
      QRecord row = row(table, id);
      if(Boolean.TRUE.equals(row.getValueBoolean("parentDeleted")))
      {
         throw (new QNotFoundException("Not found"));
      }
      ParentRecordAccess.requireVisibleParent(row.getValueString("tableName"), row.getValueString("recordId"));
      return (row);
   }

   /*******************************************************************************
    ** Trusted lookup: callers must authorize ownership or parent access themselves.
    *******************************************************************************/
   public static QRecord row(String table, Integer id) throws QException
   {
      List<QRecord> rows = CollaborationRecords.query(table, new QQueryFilter().withCriteria("id", QCriteriaOperator.EQUALS, id));
      if(rows.isEmpty())
      {
         throw (new QNotFoundException("Not found"));
      }
      return (rows.getFirst());
   }

   /*******************************************************************************
    ** Self passes after the caller's parent check; unknown others fail closed.
    *******************************************************************************/
   public static void requireReader(String user, String table, Serializable id) throws QException
   {
      if(user != null && (user.isBlank() || (!user.equals(PersonalRecords.currentUserId())
         && !new OtherUserAccess().canRead(user, table, id).orElse(false))))
      {
         throw (new QException(new PeopleService().displayName(user) + " can't open this record."));
      }
   }

   /*******************************************************************************
    ** Bounded text validation before any write or notification.
    *******************************************************************************/
   public static void text(String value, int max, boolean required) throws QException
   {
      if((required && (value == null || value.isBlank())) || (value != null && value.length() > max))
      {
         throw (new QException("Text must contain " + (required ? "1" : "0") + " to " + max + " characters."));
      }
   }
}
