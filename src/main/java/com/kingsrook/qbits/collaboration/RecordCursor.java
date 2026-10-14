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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import com.kingsrook.qqq.backend.core.exceptions.QBadRequestException;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;

/*******************************************************************************
 ** Versioned opaque keyset cursor shared by comments and timeline.
 *******************************************************************************/
public record RecordCursor(Instant at, String kind, Long id)
{
   /*******************************************************************************
    ** No record data is embedded in a cursor.
    *******************************************************************************/
   public String encode()
   {
      return (Base64.getUrlEncoder().withoutPadding().encodeToString(("1\n" + at + "\n" + kind + "\n" + id).getBytes(StandardCharsets.UTF_8)));
   }

   /*******************************************************************************
    ** Reject malformed cursors with a consistent service error.
    *******************************************************************************/
   public static RecordCursor decode(String cursor) throws QException
   {
      try
      {
         String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\n", -1);
         if(parts.length != 4 || !parts[0].equals("1") || !java.util.List.of("COMMENT", "ACTIVITY", "AUDIT").contains(parts[2]))
         {
            throw (new IllegalArgumentException("Invalid cursor"));
         }
         return (new RecordCursor(Instant.parse(parts[1]), parts[2], Long.valueOf(parts[3])));
      }
      catch(RuntimeException e)
      {
         throw (new QBadRequestException("Invalid cursor", e));
      }
   }

   /*******************************************************************************
    ** Strict ordering for descending (time, kind, id) pagination.
    *******************************************************************************/
   public boolean isBefore(RecordCursor other)
   {
      int time = at.compareTo(other.at);
      int type = kind.compareTo(other.kind);
      return (time < 0 || (time == 0 && (type < 0 || (type == 0 && id < other.id))));
   }

   /*******************************************************************************
    ** Apply the same tuple ordering in backend queries, including equal timestamps.
    *******************************************************************************/
   public QQueryFilter beforeFilter(String atField, String streamKind)
   {
      QQueryFilter filter = new QQueryFilter().withBooleanOperator(QQueryFilter.BooleanOperator.OR)
         .withSubFilter(new QQueryFilter().withCriteria(atField, QCriteriaOperator.LESS_THAN, at));
      if(streamKind.compareTo(kind) <= 0)
      {
         QQueryFilter equal = new QQueryFilter().withCriteria(atField, QCriteriaOperator.EQUALS, at);
         if(streamKind.equals(kind))
         {
            equal.withCriteria("id", QCriteriaOperator.LESS_THAN, id.toString());
         }
         filter.withSubFilter(equal);
      }
      return (filter);
   }
}
