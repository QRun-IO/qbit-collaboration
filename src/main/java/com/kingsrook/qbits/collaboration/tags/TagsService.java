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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import com.kingsrook.qbits.collaboration.CollaborationRecords;
import com.kingsrook.qbits.collaboration.PersonalRecords;
import com.kingsrook.qbits.collaboration.activity.ActivityWriter;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.security.ParentRecordAccess;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QBadRequestException;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QNotFoundException;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;

/*******************************************************************************
 ** Tag vocabulary and assignments. Parent authorization precedes trusted storage.
 ** Mutations join the caller's backend transaction; the config lock serializes
 ** service calls within this instance, as in the existing collaboration services.
 *******************************************************************************/
public class TagsService
{
   public static final String MANAGE = com.kingsrook.qbits.collaboration.permissions.CollaborationPermissions.TAGS_MANAGE;

   /*******************************************************************************
    ** Case-independent natural key; labels retain the user's capitalization.
    *******************************************************************************/
   public static String normalize(String label) throws QException
   {
      String name = label == null ? "" : label.strip().toLowerCase(Locale.ROOT).replaceAll("(?U)\\s+", " ").strip();
      if(name.isEmpty() || name.length() > 60)
      {
         throw (new QBadRequestException("Tag names must contain 1 to 60 characters."));
      }
      return (name);
   }

   /*******************************************************************************
    ** Global and table vocabulary; pending suggestions are private to their author.
    *******************************************************************************/
   public List<TagEntry> vocabulary(String table, String q, Integer limit) throws QException
   {
      requireTable(table);
      QQueryFilter filter = vocabularyFilter(table).withSubFilter(new QQueryFilter().withBooleanOperator(QQueryFilter.BooleanOperator.OR)
         .withCriteria("approved", QCriteriaOperator.EQUALS, true)
         .withCriteria("createdByUserId", QCriteriaOperator.EQUALS, PersonalRecords.currentUserId()));
      if(q != null && !q.isBlank())
      {
         filter.withCriteria("name", QCriteriaOperator.CONTAINS, q.strip().toLowerCase(Locale.ROOT));
      }
      filter.withOrderBy(new QFilterOrderBy("usageCount", false)).withOrderBy(new QFilterOrderBy("label")).withLimit(limit == null ? 20 : Math.max(1, Math.min(100, limit)));
      return (CollaborationRecords.query(CollaborationTables.TAG, filter).stream().map(TagsService::entry).toList());
   }

   /*******************************************************************************
    ** Shared picker filter, also enforced when submitted values reach the service.
    *******************************************************************************/
   public static QQueryFilter vocabularyFilter(String table)
   {
      QQueryFilter scope = new QQueryFilter().withBooleanOperator(QQueryFilter.BooleanOperator.OR).withCriteria("tableName", QCriteriaOperator.IS_BLANK);
      if(table != null)
      {
         scope.withCriteria("tableName", QCriteriaOperator.EQUALS, table);
      }
      return (new QQueryFilter().withCriteria("mergedIntoTagId", QCriteriaOperator.IS_BLANK).withSubFilter(scope));
   }

   /*******************************************************************************
    ** Read core's committed policy with instance/app/table resolution and hard limits.
    *******************************************************************************/
   public String mode(String table) throws QException
   {
      Object setting = com.kingsrook.qqq.backend.core.actions.collaboration.RecordFeaturesResolver.resolve(QContext.getQInstance(), QContext.getQInstance().getTable(table))
         .getFeatures().get(com.kingsrook.qqq.backend.core.model.metadata.collaboration.RecordFeature.TAGS).getSettings().get("mode");
      String mode = setting == null ? CollaborationRecords.config().getDefaultTagMode() : setting.toString();
      if(!List.of("FREE", "APPROVED_ONLY").contains(mode))
      {
         throw (new QException("Invalid TAGS mode: " + mode));
      }
      return (mode);
   }

   /*******************************************************************************
    ** Create an approved free tag or a pending suggestion. Globals require manage.
    *******************************************************************************/
   public TagEntry create(String table, String label, String color) throws QException
   {
      if(table == null)
      {
         requireManage();
      }
      else
      {
         ParentRecordAccess.requireTableFeature(table, "tag");
      }
      synchronized(CollaborationRecords.config())
      {
         String name = normalize(label);
         QQueryFilter filter = new QQueryFilter().withCriteria("name", QCriteriaOperator.EQUALS, name);
         filter.withCriteria("tableName", table == null ? QCriteriaOperator.IS_BLANK : QCriteriaOperator.EQUALS, table);
         List<QRecord> existing = CollaborationRecords.query(CollaborationTables.TAG, filter);
         if(!existing.isEmpty())
         {
            QRecord row = existing.getFirst();
            if(row.getValue("mergedIntoTagId") != null || (!Boolean.TRUE.equals(row.getValueBoolean("approved"))
               && !PersonalRecords.currentUserId().equals(row.getValueString("createdByUserId")) && !canManage()))
            {
               throw (new QBadRequestException("Tag name is already reserved."));
            }
            return (entry(row));
         }
         boolean approved = table == null || canManage() || mode(table).equals("FREE");
         return (entry(CollaborationRecords.insert(CollaborationTables.TAG, new QRecord().withValue("name", name)
            .withValue("label", label.strip()).withValue("color", color).withValue("tableName", table).withValue("approved", approved)
            .withValue("createdByUserId", PersonalRecords.currentUserId()).withValue("usageCount", 0))));
      }
   }

   /*******************************************************************************
    ** Read chips only after resolving the canonical, visible parent key.
    *******************************************************************************/
   public List<TagEntry> tagsFor(String table, Serializable recordId) throws QException
   {
      String id = parentId(table, recordId);
      List<Integer> ids = assignments(table, id).stream().map(row -> row.getValueInteger("tagId")).toList();
      if(ids.isEmpty())
      {
         return (List.of());
      }
      return (CollaborationRecords.query(CollaborationTables.TAG, vocabularyFilter(table).withCriteria("id", QCriteriaOperator.IN, ids)
         .withCriteria("approved", QCriteriaOperator.EQUALS, true).withOrderBy(new QFilterOrderBy("label"))).stream().map(TagsService::entry).toList());
   }

   /*******************************************************************************
    ** Resolve and validate all inputs before changing assignments. Numbers are ids;
    ** strings are labels (including numeric labels), with global matches preferred.
    *******************************************************************************/
   List<QRecord> resolve(String table, Collection<? extends Serializable> values, boolean createMissing) throws QException
   {
      Map<Integer, QRecord> result = new LinkedHashMap<>();
      for(Serializable value : values == null ? List.<Serializable>of() : values)
      {
         QQueryFilter filter = vocabularyFilter(table);
         if(value instanceof Number)
         {
            filter.withCriteria("id", QCriteriaOperator.EQUALS, value);
         }
         else
         {
            filter.withCriteria("name", QCriteriaOperator.EQUALS, normalize(Objects.toString(value, null)));
         }
         List<QRecord> matches = CollaborationRecords.query(CollaborationTables.TAG, filter.withOrderBy(new QFilterOrderBy("id")));
         QRecord row;
         if(matches.isEmpty())
         {
            if(!createMissing || value instanceof Number || !mode(table).equals("FREE"))
            {
               throw (new QBadRequestException("Pick an approved tag (or suggest one)"));
            }
            row = requireTag(create(table, value.toString(), null).id());
         }
         else
         {
            row = matches.stream().filter(tag -> tag.getValue("tableName") == null).findFirst().orElse(matches.getFirst());
         }
         if(!Boolean.TRUE.equals(row.getValueBoolean("approved")))
         {
            throw (new QBadRequestException("Pick an approved tag (or suggest one)"));
         }
         result.put(row.getValueInteger("id"), row);
      }
      return (new ArrayList<>(result.values()));
   }

   /*******************************************************************************
    ** Idempotent assignment changes, with activity only for actual changes.
    ** Tags intentionally do not notify followers.
    *******************************************************************************/
   public void apply(String table, Serializable recordId, Collection<? extends Serializable> add, Collection<? extends Serializable> remove) throws QException
   {
      ParentRecordAccess.requireFeature(table, recordId, "tag");
      String id = parentId(table, recordId);
      synchronized(CollaborationRecords.config())
      {
         List<QRecord> removals = resolve(table, remove, false);
         List<QRecord> additions = resolve(table, add, true);
         for(QRecord tag : removals)
         {
            List<QRecord> links = assignments(table, id).stream().filter(row -> Objects.equals(row.getValue("tagId"), tag.getValue("id"))).toList();
            for(QRecord link : links)
            {
               CollaborationRecords.delete(CollaborationTables.RECORD_TAG, link.getValue("id"));
               ActivityWriter.write(table, id, "UNTAGGED", "Tag removed", CollaborationTables.TAG, tag.getValueString("id"));
            }
            recompute(tag.getValueInteger("id"));
         }
         for(QRecord tag : additions)
         {
            if(assignments(table, id).stream().noneMatch(row -> Objects.equals(row.getValue("tagId"), tag.getValue("id"))))
            {
               CollaborationRecords.insert(CollaborationTables.RECORD_TAG, new QRecord().withValue("tableName", table).withValue("recordId", id)
                  .withValue("tagId", tag.getValue("id")).withValue("createdByUserId", PersonalRecords.currentUserId()).withValue("parentDeleted", false));
               ActivityWriter.write(table, id, "TAGGED", "Tag added", CollaborationTables.TAG, tag.getValueString("id"));
            }
            recompute(tag.getValueInteger("id"));
         }
      }
   }

   /*******************************************************************************
    ** Approval never changes an already merged alias.
    *******************************************************************************/
   public void approve(Serializable id) throws QException
   {
      requireManage();
      synchronized(CollaborationRecords.config())
      {
         requireTag(id);
         CollaborationRecords.update(CollaborationTables.TAG, new QRecord().withValue("id", id).withValue("approved", true));
      }
   }

   /*******************************************************************************
    ** Validate a merge before review and again before load; prevent scope loss.
    *******************************************************************************/
   public QRecord mergeTarget(Serializable sourceId, Serializable targetId) throws QException
   {
      requireManage();
      QRecord source = requireTag(sourceId);
      QRecord target = requireTag(targetId);
      if(Objects.equals(source.getValue("id"), target.getValue("id")) || !Boolean.TRUE.equals(target.getValueBoolean("approved"))
         || (target.getValue("tableName") != null && !Objects.equals(source.getValue("tableName"), target.getValue("tableName"))))
      {
         throw (new QException("Choose a different approved tag in the same scope or global vocabulary."));
      }
      return (target);
   }

   /*******************************************************************************
    ** Move references with deduplication and retain the source as an audited alias.
    *******************************************************************************/
   public void merge(Serializable sourceId, Serializable targetId) throws QException
   {
      requireManage();
      synchronized(CollaborationRecords.config())
      {
         QRecord target = mergeTarget(sourceId, targetId);
         for(QRecord link : links(sourceId))
         {
            boolean duplicate = assignments(link.getValueString("tableName"), link.getValueString("recordId")).stream()
               .anyMatch(row -> Objects.equals(row.getValue("tagId"), target.getValue("id")));
            if(duplicate)
            {
               CollaborationRecords.delete(CollaborationTables.RECORD_TAG, link.getValue("id"));
            }
            else
            {
               CollaborationRecords.update(CollaborationTables.RECORD_TAG, new QRecord().withValue("id", link.getValue("id")).withValue("tagId", target.getValue("id")));
            }
         }
         CollaborationRecords.update(CollaborationTables.TAG, new QRecord().withValue("id", sourceId).withValue("mergedIntoTagId", target.getValue("id")));
         recompute(sourceId);
         recompute(targetId);
      }
   }

   /*******************************************************************************
    ** Delete the vocabulary entry and its assignments through the managed action.
    *******************************************************************************/
   public void delete(Serializable id) throws QException
   {
      requireManage();
      synchronized(CollaborationRecords.config())
      {
         for(QRecord link : links(id))
         {
            CollaborationRecords.delete(CollaborationTables.RECORD_TAG, link.getValue("id"));
         }
         CollaborationRecords.delete(CollaborationTables.TAG, id);
      }
   }

   /*******************************************************************************
    ** Trusted parent cleanup callback; the parent no longer exists to authorize.
    *******************************************************************************/
   public void onParentsDeleted(String table, List<? extends Serializable> ids) throws QException
   {
      synchronized(CollaborationRecords.config())
      {
         for(QRecord link : CollaborationRecords.cleanupQuery(CollaborationTables.RECORD_TAG, new QQueryFilter().withCriteria("tableName", QCriteriaOperator.EQUALS, table)
            .withCriteria("recordId", QCriteriaOperator.IN, ids.stream().map(Object::toString).toList())))
         {
            CollaborationRecords.cleanupDelete(CollaborationTables.RECORD_TAG, link.getValue("id"));
            recompute(link.getValue("tagId"));
         }
      }
   }

   /*******************************************************************************
    ** Recount instead of incrementing a potentially stale denormalized value.
    *******************************************************************************/
   private void recompute(Serializable id) throws QException
   {
      CollaborationRecords.update(CollaborationTables.TAG, new QRecord().withValue("id", id).withValue("usageCount", links(id).size()));
   }

   /*******************************************************************************
    ** Internal references used by management actions after checking manage.
    *******************************************************************************/
   static List<QRecord> links(Serializable id) throws QException
   {
      return (CollaborationRecords.query(CollaborationTables.RECORD_TAG, new QQueryFilter().withCriteria("tagId", QCriteriaOperator.EQUALS, id)));
   }

   /*******************************************************************************
    ** Canonical assignment list for an authorized parent.
    *******************************************************************************/
   private List<QRecord> assignments(String table, String id) throws QException
   {
      return (CollaborationRecords.query(CollaborationTables.RECORD_TAG, CollaborationRecords.parent(table, id)));
   }

   /*******************************************************************************
    ** Reject missing and merged vocabulary ids.
    *******************************************************************************/
   static QRecord requireTag(Serializable id) throws QException
   {
      List<QRecord> rows = CollaborationRecords.query(CollaborationTables.TAG, new QQueryFilter().withCriteria("id", QCriteriaOperator.EQUALS, id)
         .withCriteria("mergedIntoTagId", QCriteriaOperator.IS_BLANK));
      if(rows.isEmpty())
      {
         throw (new QNotFoundException("Tag not found"));
      }
      return (rows.getFirst());
   }

   /*******************************************************************************
    ** Table reads guard vocabulary calls without needing a particular record.
    *******************************************************************************/
   static void requireTable(String table) throws QException
   {
      if(!ParentRecordAccess.canReadTable(table))
      {
         throw (new QNotFoundException("Not found"));
      }
   }

   /*******************************************************************************
    ** Canonical keys prevent aliases such as 01 from creating duplicate assignments.
    *******************************************************************************/
   private String parentId(String table, Serializable id) throws QException
   {
      return (ParentRecordAccess.requireVisibleParent(table, id).getValueString(QContext.getQInstance().getTable(table).getPrimaryKeyField()));
   }

   /*******************************************************************************
    ** Management is an explicit permission independent of collaboration.admin.
    *******************************************************************************/
   static boolean canManage()
   {
      return (QContext.getQSession().hasPermission(MANAGE));
   }

   /*******************************************************************************
    ** Shared action and service permission check.
    *******************************************************************************/
   public static void requireManage() throws QPermissionDeniedException
   {
      if(!canManage())
      {
         throw (new QPermissionDeniedException("This needs " + MANAGE + "."));
      }
   }

   /*******************************************************************************
    ** Public vocabulary projection.
    *******************************************************************************/
   private static TagEntry entry(QRecord row)
   {
      return (new TagEntry(row.getValueInteger("id"), row.getValueString("name"), row.getValueString("label"), row.getValueString("color"),
         row.getValueString("tableName"), row.getValueBoolean("approved"), row.getValueInteger("usageCount")));
   }
}
