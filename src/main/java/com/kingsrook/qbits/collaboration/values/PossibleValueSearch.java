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


package com.kingsrook.qbits.collaboration.values;


import java.util.Locale;
import com.kingsrook.qqq.backend.core.model.actions.values.SearchPossibleValueSourceInput;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValue;
import com.kingsrook.qqq.backend.core.utils.ValueUtils;


/*******************************************************************************
 ** Shared matching semantics for metadata-backed possible values.
 *******************************************************************************/
final class PossibleValueSearch
{
   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private PossibleValueSearch()
   {
   }



   /*******************************************************************************
    ** Explicit ids take precedence; otherwise match label or name, ignoring case.
    *******************************************************************************/
   static boolean matches(SearchPossibleValueSourceInput input, QPossibleValue<String> value)
   {
      if(input.getIdList() != null && !input.getIdList().isEmpty())
      {
         return (input.getIdList().stream().anyMatch(id -> value.getId().equals(ValueUtils.getValueAsString(id))));
      }
      String term = input.getSearchTerm();
      return (term == null || term.isBlank() || value.getId().toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT))
         || value.getLabel().toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT)));
   }
}
