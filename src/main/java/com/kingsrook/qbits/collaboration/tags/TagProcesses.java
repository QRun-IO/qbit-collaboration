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


package com.kingsrook.qbits.collaboration.tags;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.security.ParentRecordAccess;
import com.kingsrook.qqq.backend.core.actions.permissions.TablePermissionSubType;
import com.kingsrook.qqq.backend.core.actions.permissions.UseTablePermissionCustomPermissionChecker;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLine;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLineInterface;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.actions.processes.Status;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QIcon;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.PermissionLevel;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.QPermissionRules;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QComponentType;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendComponentMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendStepMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractLoadStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractTransformStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.ExtractViaQueryStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.ProcessSummaryProviderInterface;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.StreamedETLWithFrontendProcess;

/*******************************************************************************
 ** Managed vocabulary and host bulk actions using the standard streamed ETL flow.
 *******************************************************************************/
public final class TagProcesses
{
   public static final String TAGS = "tags";
   public static final String TARGET = "targetTagId";

   /*******************************************************************************
    ** Metadata factory.
    *******************************************************************************/
   private TagProcesses()
   {
   }

   /*******************************************************************************
    ** Vocabulary record actions, never navigation entries.
    *******************************************************************************/
   public static List<QProcessMetaData> management()
   {
      return (List.of(process(CollaborationTables.TAG, "Approve"), process(CollaborationTables.TAG, "Merge"), process(CollaborationTables.TAG, "Delete")));
   }

   /*******************************************************************************
    ** Host record actions configured by tagActionTables.
    *******************************************************************************/
   public static List<QProcessMetaData> processesFor(String table)
   {
      return (List.of(process(table, "Add"), process(table, "Remove")));
   }

   /*******************************************************************************
    ** Approved scoped PVS inputs and explicit selection cardinality for clients.
    *******************************************************************************/
   private static QProcessMetaData process(String table, String operation)
   {
      boolean bulk = !table.equals(CollaborationTables.TAG);
      Map<String, Serializable> values = new HashMap<>();
      values.put(StreamedETLWithFrontendProcess.FIELD_SOURCE_TABLE, table);
      values.put(StreamedETLWithFrontendProcess.FIELD_DESTINATION_TABLE, table);
      values.put(StreamedETLWithFrontendProcess.FIELD_SUPPORTS_FULL_VALIDATION, true);
      values.put(StreamedETLWithFrontendProcess.FIELD_DO_FULL_VALIDATION, true);
      values.put(StreamedETLWithFrontendProcess.FIELD_PREVIEW_MESSAGE, operation + " tags on the selected records.");
      QProcessMetaData process = StreamedETLWithFrontendProcess.defineProcessMetaData(ExtractViaQueryStep.class, Review.class, Apply.class, values)
         .withName("collaboration" + operation + "Tags" + (bulk ? "_" + table : "")).withLabel(operation + " tags").withTableName(table).withIcon(new QIcon("label"))
         .withPermissionRules(new QPermissionRules().withLevel(PermissionLevel.NOT_PROTECTED).withCustomPermissionChecker(bulk
            ? UseTablePermissionCustomPermissionChecker.build(table, TablePermissionSubType.READ) : new QCodeReference(TagSecurity.class)));
      if(bulk || operation.equals("Merge"))
      {
         QQueryFilter filter = bulk ? TagsService.vocabularyFilter(table) : new QQueryFilter().withCriteria("mergedIntoTagId", QCriteriaOperator.IS_BLANK);
         filter.withCriteria("approved", QCriteriaOperator.EQUALS, true).withOrderBy(new QFilterOrderBy("usageCount", false)).withOrderBy(new QFilterOrderBy("label"));
         QFieldMetaData field = new QFieldMetaData(bulk ? TAGS : TARGET, QFieldType.INTEGER).withLabel(bulk ? "Tags" : "Target tag")
            .withIsRequired(true).withPossibleValueSourceName(CollaborationTables.TAG).withPossibleValueSourceFilter(filter);
         process.withStep(0, new QFrontendStepMetaData().withName("tags").withLabel(operation + " tags").withFormField(field)
            .withComponent(new QFrontendComponentMetaData().withType(QComponentType.EDIT_FORM)
               .withValue("multiSelectFields", new ArrayList<>(bulk ? List.of(TAGS) : List.of()))));
      }
      return (process);
   }

   /*******************************************************************************
    ** Operation comes from installed metadata, never caller-supplied values.
    *******************************************************************************/
   private static String operation(RunBackendStepInput input)
   {
      return (input.getProcess().getName().substring("collaboration".length()).split("Tags")[0]);
   }

   /*******************************************************************************
    ** PVS submissions are ids; accept serialized comma lists as well as arrays.
    *******************************************************************************/
   private static List<Integer> tagIds(RunBackendStepInput input) throws QException
   {
      Serializable value = input.getValue(TAGS);
      Collection<?> values = value instanceof Collection<?> collection ? collection : java.util.Arrays.asList(value == null ? new String[0] : value.toString().split(","));
      List<Integer> ids = new ArrayList<>();
      try
      {
         for(Object id : values)
         {
            ids.add(Integer.valueOf(id.toString().strip()));
         }
      }
      catch(NumberFormatException expected)
      {
         throw (new QException("Pick at least one approved tag."));
      }
      if(ids.isEmpty())
      {
         throw (new QException("Pick at least one approved tag."));
      }
      return (ids.stream().distinct().toList());
   }

   /*******************************************************************************
    ** Shared renderer for Review and Result language.
    *******************************************************************************/
   private static ProcessSummaryLine line(Status status, String future, String past)
   {
      ProcessSummaryLine line = new ProcessSummaryLine(status, 0, null);
      line.setSingularFutureMessage(future);
      line.setPluralFutureMessage(future);
      line.setSingularPastMessage(past);
      line.setPluralPastMessage(past);
      return (line);
   }

   /*******************************************************************************
    ** Validate each record without mutation, reporting affected and unchanged rows.
    *******************************************************************************/
   public static class Review extends AbstractTransformStep implements ProcessSummaryProviderInterface
   {
      private ProcessSummaryLine selected;
      private final ProcessSummaryLine skipped = line(Status.WARNING, "records cannot be processed (skipped)", "records skipped");
      private final ProcessSummaryLine unchanged = line(Status.INFO, "records already in the requested tag state", "records already in the requested tag state");
      private final ProcessSummaryLine affected = line(Status.INFO, "records will lose tag assignments", "records affected by tag deletion");
      private final Set<String> affectedParents = new HashSet<>();

      /*******************************************************************************
       ** Parent and feature checks are made independently for every selected row.
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         String table = input.getProcess().getTableName();
         String op = operation(input);
         boolean bulk = !table.equals(CollaborationTables.TAG);
         List<Integer> ids = bulk ? tagIds(input) : List.of();
         if(selected == null)
         {
            selected = line(Status.OK, "records: " + op + " " + (bulk ? ids.size() + " " : "") + "tags", "records: " + op + " tags completed");
         }
         String key = QContext.getQInstance().getTable(table).getPrimaryKeyField();
         TagsService service = new TagsService();
         for(QRecord row : input.getRecords())
         {
            Serializable id = row.getValue(key);
            try
            {
               if(bulk)
               {
                  ParentRecordAccess.requireFeature(table, id, "tag");
                  service.resolve(table, ids, false);
                  Set<Integer> present = new HashSet<>(service.tagsFor(table, id).stream().map(TagEntry::id).toList());
                  if(op.equals("Add") ? present.containsAll(ids) : ids.stream().noneMatch(present::contains))
                  {
                     unchanged.incrementCountAndAddPrimaryKey(id);
                  }
               }
               else
               {
                  TagsService.requireManage();
                  TagsService.requireTag(id);
                  if(op.equals("Merge"))
                  {
                     service.mergeTarget(id, input.getValue(TARGET));
                  }
                  if(op.equals("Delete"))
                  {
                     for(QRecord link : TagsService.links(id))
                     {
                        String parent = link.getValueString("tableName") + ":" + link.getValueString("recordId");
                        if(affectedParents.add(parent))
                        {
                           affected.incrementCountAndAddPrimaryKey(parent);
                        }
                     }
                  }
               }
               output.getRecords().add(row);
               selected.incrementCountAndAddPrimaryKey(id);
            }
            catch(QException expected)
            {
               skipped.incrementCountAndAddPrimaryKey(id);
            }
         }
      }

      /*******************************************************************************
       ** Review totals are cumulative over streamed pages.
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         if(selected != null)
         {
            selected.addSelfToListIfAnyCount(lines);
         }
         skipped.addSelfToListIfAnyCount(lines);
         unchanged.addSelfToListIfAnyCount(lines);
         affected.addSelfToListIfAnyCount(lines);
         return (lines);
      }
   }

   /*******************************************************************************
    ** Execute with fresh authorization; report actual successes and failures.
    *******************************************************************************/
   public static class Apply extends AbstractLoadStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine done = line(Status.OK, "records will be processed", "records processed");
      private final ProcessSummaryLine failed = line(Status.ERROR, "records cannot be processed", "records failed (retry required)");

      /*******************************************************************************
       ** The management service and parent service both recheck access at load time.
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         String table = input.getProcess().getTableName();
         String key = QContext.getQInstance().getTable(table).getPrimaryKeyField();
         TagsService service = new TagsService();
         for(QRecord row : input.getRecords())
         {
            Serializable id = row.getValue(key);
            try
            {
               switch(operation(input))
               {
                  case "Approve" -> service.approve(id);
                  case "Merge" -> service.merge(id, input.getValue(TARGET));
                  case "Delete" -> service.delete(id);
                  case "Add" -> service.apply(table, id, tagIds(input), List.of());
                  case "Remove" -> service.apply(table, id, List.of(), tagIds(input));
                  default -> throw new QException("Unknown tag action.");
               }
               done.incrementCountAndAddPrimaryKey(id);
            }
            catch(QException expected)
            {
               failed.incrementCountAndAddPrimaryKey(id);
            }
         }
      }

      /*******************************************************************************
       ** Result retains review skips and reports load failures separately.
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         done.addSelfToListIfAnyCount(lines);
         failed.addSelfToListIfAnyCount(lines);
         if(getTransformStep() instanceof Review review)
         {
            review.skipped.addSelfToListIfAnyCount(lines);
         }
         return (lines);
      }
   }
}
