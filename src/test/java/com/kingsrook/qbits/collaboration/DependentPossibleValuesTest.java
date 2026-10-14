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
import java.util.Map;
import com.kingsrook.qbits.collaboration.comments.CommentsService;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.values.FeatureScopeNamePossibleValueProvider;
import com.kingsrook.qbits.collaboration.values.RecordFieldNamePossibleValueProvider;
import com.kingsrook.qqq.backend.core.actions.values.SearchPossibleValueSourceAction;
import com.kingsrook.qqq.backend.core.model.actions.values.SearchPossibleValueSourceInput;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.PossibleValueSearchFilterUseCase;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValue;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.security.FieldSecurityLock;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/*******************************************************************************
 ** Exercise registered dependent sources through the same core action as forms.
 *******************************************************************************/
class DependentPossibleValuesTest extends PersonalServiceFixture
{
   /*******************************************************************************
    ** Roots and replies from another thread must never leak into this picker.
    *******************************************************************************/
   @Test
   void parentsAreRestrictedToTheSelectedThread() throws Exception
   {
      var comments = new CommentsService();
      var root = comments.post("document", 1, "First root", null, null);
      var reply = comments.post("document", 1, "First reply", root.id(), null);
      var other = comments.post("document", 1, "Other root", null, null);
      comments.post("document", 1, "Other reply", other.id(), null);
      admin();
      assertThat(search(CollaborationTables.COMMENT, "parentCommentId", Map.of("threadId", root.id()), null, null))
         .extracting(value -> (Object) value.getId()).containsExactlyInAnyOrder(root.id(), reply.id());
      assertThat(search(CollaborationTables.COMMENT, "parentCommentId", Map.of("threadId", other.id()), null, null))
         .extracting(value -> (Object) value.getId()).doesNotContain(root.id(), reply.id());
      assertThat(search(CollaborationTables.COMMENT, "parentCommentId", Map.of(), null, null)).isEmpty();
   }

   /*******************************************************************************
    ** Scope determines eligible metadata, and searches preserve names and labels.
    *******************************************************************************/
   @Test
   void scopeNamesFollowScopeType() throws Exception
   {
      instance.addApp(new QAppMetaData().withName("sales").withLabel("Sales Office"));
      instance.addTable(new QTableMetaData().withName("hidden").withIsHidden(true).withPrimaryKeyField("id"));
      instance.addTable(new QTableMetaData().withName("no_key"));
      assertThat(search(CollaborationTables.FEATURE_SETTING, "scopeName", Map.of("scopeType", "APP"), "OFFICE", null))
         .extracting(QPossibleValue::getLabel).containsExactly("Sales Office");
      assertThat(search(CollaborationTables.FEATURE_SETTING, "scopeName", Map.of("scopeType", "APP"), "no match", List.of("sales")))
         .extracting(value -> (Object) value.getId()).containsExactly("sales");
      assertThat(search(CollaborationTables.FEATURE_SETTING, "scopeName", Map.of("scopeType", "TABLE"), null, null))
         .extracting(value -> (Object) value.getId()).containsExactly("document");
      assertThat(search(CollaborationTables.FEATURE_SETTING, "scopeName", Map.of(), null, null)).isEmpty();
      assertThat(search(CollaborationTables.FEATURE_SETTING, "scopeName", null, null, null)).isEmpty();
      assertThat(search(CollaborationTables.FEATURE_SETTING, "scopeName", Map.of("scopeType", "INSTANCE"), null, null))
         .extracting(value -> (Object) value.getId()).containsExactly("instance");
      var provider = new FeatureScopeNamePossibleValueProvider();
      assertThat(provider.getPossibleValue("sales").getLabel()).isEqualTo("Sales Office");
      assertThat(provider.getPossibleValue("document").getLabel()).isEqualTo("Documents");
      assertThat(provider.getPossibleValue("unknown").getLabel()).isEqualTo("unknown");
      assertThat(provider.getPossibleValue(null)).isNull();
   }

   /*******************************************************************************
    ** Table fields are labeled and hidden fields are excluded; stored rows resolve too.
    *******************************************************************************/
   @Test
   void fieldNamesFollowTableAndPersistedComment() throws Exception
   {
      instance.addTable(new QTableMetaData().withName("person").withLabel("People").withBackendName("memory").withPrimaryKeyField("id")
         .withField(new QFieldMetaData("id", QFieldType.INTEGER).withLabel("ID"))
         .withField(new QFieldMetaData("name", QFieldType.STRING).withLabel("Full Name"))
         .withField(new QFieldMetaData("secret", QFieldType.STRING).withIsHidden(true))
         .withField(new QFieldMetaData("restricted", QFieldType.STRING).withFieldSecurityLock(new FieldSecurityLock()
            .withSecurityKeyType("tenant").withOverrideValues(List.of("b")))));
      assertThat(search(CollaborationTables.COMMENT, "fieldName", Map.of("tableName", "person"), null, null))
         .extracting(QPossibleValue::getLabel).containsExactly("ID (id)", "Full Name (name)");
      assertThat(search(CollaborationTables.COMMENT, "fieldName", Map.of("tableName", "person"), "FULL", null))
         .extracting(value -> (Object) value.getId()).containsExactly("name");
      assertThat(search(CollaborationTables.COMMENT, "fieldName", Map.of("tableName", "person"), "absent", List.of("id")))
         .extracting(value -> (Object) value.getId()).containsExactly("id");
      assertThat(search(CollaborationTables.COMMENT, "fieldName", Map.of(), null, null)).isEmpty();
      assertThat(search(CollaborationTables.COMMENT, "fieldName", null, null, null)).isEmpty();
      assertThat(search(CollaborationTables.COMMENT, "fieldName", Map.of("tableName", "unknown"), null, null)).isEmpty();
      var root = new CommentsService().post("document", 1, "Root", null, null);
      admin();
      assertThat(search(CollaborationTables.COMMENT, "fieldName", Map.of("id", root.id()), null, null))
         .extracting(value -> (Object) value.getId()).contains("title");
      var provider = new RecordFieldNamePossibleValueProvider();
      assertThat(provider.getPossibleValue("name").getLabel()).isEqualTo("name");
      assertThat(provider.getPossibleValue(null)).isNull();
   }

   /*******************************************************************************
    ** Mirror TablePossibleValuesSpecV1: clone and interpret metadata, then search.
    *******************************************************************************/
   private List<QPossibleValue<?>> search(String table, String field, Map<String, Serializable> values, String term, List<Serializable> ids) throws Exception
   {
      var metadata = instance.getTable(table).getField(field);
      var input = new SearchPossibleValueSourceInput().withPossibleValueSourceName(metadata.getPossibleValueSourceName())
         .withOtherValues(values).withSearchTerm(term).withIdList(ids);
      if(metadata.getPossibleValueSourceFilter() != null)
      {
         var filter = metadata.getPossibleValueSourceFilter().clone();
         filter.interpretValues(Map.of("input", values), PossibleValueSearchFilterUseCase.FORM);
         input.setDefaultQueryFilter(filter);
      }
      return (new SearchPossibleValueSourceAction().execute(input).getResults());
   }
}
