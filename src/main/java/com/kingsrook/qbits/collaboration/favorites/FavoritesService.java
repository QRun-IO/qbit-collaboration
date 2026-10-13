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


package com.kingsrook.qbits.collaboration.favorites;


import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.kingsrook.qbits.collaboration.PersonalRecords;
import com.kingsrook.qbits.collaboration.TargetResolver;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;


/*******************************************************************************
 ** Current-user favorites. All actions preserve QContext; administrators use the
 ** standard table screens for cross-user management, not these personal methods.
 *******************************************************************************/
public final class FavoritesService
{
   /*******************************************************************************
    ** Static service.
    *******************************************************************************/
   private FavoritesService()
   {
   }



   /*******************************************************************************
    ** Idempotently star a visible target, appending it after existing favorites.
    *******************************************************************************/
   public static void star(String targetType, String targetName, Serializable recordId) throws QException
   {
      String userId = PersonalRecords.currentUserId();
      FavoriteEntry target = TargetResolver.require(targetType, targetName, recordId);
      QQueryFilter filter = PersonalRecords.targetFilter(targetType, targetName, target.recordId());
      if(!PersonalRecords.query(CollaborationTables.FAVORITE, filter).isEmpty())
      {
         return;
      }
      List<QRecord> last = PersonalRecords.query(CollaborationTables.FAVORITE, PersonalRecords.ownFilter()
         .withOrderBy(new QFilterOrderBy("sortOrder", false)).withLimit(1));
      Integer order = last.isEmpty() ? 0 : Math.addExact(last.getFirst().getValueInteger("sortOrder"), 1);
      QRecord row = new QRecord().withValue("userId", userId).withValue("targetType", targetType).withValue("targetName", targetName)
         .withValue("recordId", target.recordId()).withValue("label", target.label()).withValue("sortOrder", order);
      try
      {
         PersonalRecords.insert(CollaborationTables.FAVORITE, row);
      }
      catch(QException e)
      {
         if(PersonalRecords.query(CollaborationTables.FAVORITE, filter).isEmpty())
         {
            throw (e);
         }
         // A concurrent star won the unique-key race; the requested state exists.
      }
   }



   /*******************************************************************************
    ** Removing a star does not require continued access to its target.
    *******************************************************************************/
   public static void unstar(String targetType, String targetName, Serializable recordId) throws QException
   {
      String id = TargetResolver.recordId(targetType, targetName, recordId);
      List<QRecord> rows = PersonalRecords.query(CollaborationTables.FAVORITE, PersonalRecords.targetFilter(targetType, targetName, id));
      PersonalRecords.delete(CollaborationTables.FAVORITE, rows.stream().map(row -> row.getValue("id")).toList());
   }



   /*******************************************************************************
    ** Ordered favorites with freshly checked labels and availability.
    *******************************************************************************/
   public static List<FavoriteEntry> list() throws QException
   {
      return (TargetResolver.refresh(PersonalRecords.query(CollaborationTables.FAVORITE, PersonalRecords.ownFilter()
         .withOrderBy(new QFilterOrderBy("sortOrder")).withOrderBy(new QFilterOrderBy("id")))));
   }



   /*******************************************************************************
    ** Move the supplied ids first, preserving order of omitted favorites. Folder
    ** values may be null to clear them. Validate all ids before writing anything.
    *******************************************************************************/
   public static void reorder(List<Integer> ids, Map<Integer, String> folders) throws QException
   {
      List<QRecord> rows = PersonalRecords.query(CollaborationTables.FAVORITE, PersonalRecords.ownFilter()
         .withOrderBy(new QFilterOrderBy("sortOrder")).withOrderBy(new QFilterOrderBy("id")));
      Set<Integer> owned = new HashSet<>(rows.stream().map(row -> row.getValueInteger("id")).toList());
      if(ids == null || folders == null || new HashSet<>(ids).size() != ids.size() || !owned.containsAll(ids) || !owned.containsAll(folders.keySet()))
      {
         throw (new QException("Invalid favorite ids"));
      }
      for(String folder : folders.values())
      {
         if(folder != null && folder.length() > 255)
         {
            throw (new QException("Folder is too long"));
         }
      }
      List<Integer> order = new ArrayList<>(ids);
      for(QRecord row : rows)
      {
         Integer id = row.getValueInteger("id");
         if(!ids.contains(id))
         {
            order.add(id);
         }
      }
      List<QRecord> changes = new ArrayList<>();
      for(int i = 0; i < order.size(); i++)
      {
         Integer id = order.get(i);
         QRecord change = new QRecord().withValue("id", id).withValue("sortOrder", i);
         if(folders.containsKey(id))
         {
            change.setValue("folder", folders.get(id));
         }
         changes.add(change);
      }
      PersonalRecords.update(CollaborationTables.FAVORITE, changes);
   }
}
