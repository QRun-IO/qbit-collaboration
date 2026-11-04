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


package com.kingsrook.qbits.collaboration.attachments.uploadjournal;


import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.expressions.CurrentUser;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordFeature;
import com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordFeaturesMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;


/*******************************************************************************
 ** Small metadata contract for native recovery navigation from a source's Files
 ** panel. The client adds canonical source table/id pins to this server-owned view.
 ** Filters aid discovery only; the journal still authorizes every row and action.
 *******************************************************************************/
public final class UploadRecoveryNavigation
{
   /*******************************************************************************
    ** Metadata utility.
    *******************************************************************************/
   private UploadRecoveryNavigation()
   {
   }

   /*******************************************************************************
    ** Retention owners opt in their managed source tables after producing them.
    ** No client knows or guesses the journal table or recovery process name.
    *******************************************************************************/
   public static void configure(QTableMetaData source)
   {
      Map<String, Serializable> view = new LinkedHashMap<>();
      view.put("tableName", UploadJournalTable.NAME);
      view.put("label", "Review interrupted uploads");
      view.put("filter", new QQueryFilter().withCriteria("createdByUserId", QCriteriaOperator.EQUALS, new CurrentUser())
         .withCriteria("state", QCriteriaOperator.IN, "PREPARED", "REGISTERED"));
      RecordFeaturesMetaData.ofOrWithNew(source).withSetting(RecordFeature.ATTACHMENTS, "recoveryView", (Serializable) view);
   }
}
