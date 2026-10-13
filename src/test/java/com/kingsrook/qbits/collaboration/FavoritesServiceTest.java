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


import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.collaboration.favorites.FavoriteEntry;
import com.kingsrook.qbits.collaboration.favorites.FavoritesService;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.permissions.CollaborationPermissions;
import com.kingsrook.qqq.backend.core.actions.customizers.TableCustomizerInterface;
import com.kingsrook.qqq.backend.core.actions.customizers.TableCustomizers;
import com.kingsrook.qqq.backend.core.actions.tables.CountAction;
import com.kingsrook.qqq.backend.core.actions.tables.GetAction;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.actions.tables.UpdateAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.QueryOrGetInputInterface;
import com.kingsrook.qqq.backend.core.model.actions.tables.count.CountInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.get.GetInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.PermissionLevel;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.QPermissionRules;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.reporting.QReportMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** Behavioral and security acceptance for favorites, including label batching.
 *******************************************************************************/
class FavoritesServiceTest extends PersonalServiceFixture
{
   /*******************************************************************************
    ** Both record and null-record-id targets are idempotent and append in order.
    *******************************************************************************/
   @Test
   void starAndUnstarAreIdempotent() throws Exception
   {
      FavoritesService.star("RECORD", "document", "1");
      FavoritesService.star("RECORD", "document", "1");
      FavoritesService.star("TABLE", "document", null);
      FavoritesService.star("TABLE", "document", null);
      assertThat(FavoritesService.list()).extracting(FavoriteEntry::label).containsExactly("First document", "Documents");
      assertThat(FavoritesService.list()).extracting(FavoriteEntry::sortOrder).containsExactly(0, 1);
      assertThat(FavoritesService.list()).extracting(FavoriteEntry::href).containsExactly("/app/document/1/", "/app/document/");
      FavoritesService.unstar("RECORD", "document", "1");
      FavoritesService.unstar("RECORD", "document", "1");
      FavoritesService.unstar("TABLE", "document", null);
      FavoritesService.unstar("TABLE", "document", null);
      assertThat(FavoritesService.list()).isEmpty();
   }



   /*******************************************************************************
    ** Missing, locked and table-denied records expose exactly the same error.
    *******************************************************************************/
   @Test
   void invisibleAndMissingAreIndistinguishable() throws Exception
   {
      for(String id : List.of("3", "999"))
      {
         assertThatThrownBy(() -> FavoritesService.star("RECORD", "document", id)).isInstanceOf(QException.class).hasMessage("Not found");
      }
      assertThatThrownBy(() -> FavoritesService.star("TABLE", "missing", null)).hasMessage("Not found");
      instance.setEnforcePermissionsInCoreActions(false);
      QContext.getQSession().getPermissions().remove("document.read");
      assertThatThrownBy(() -> FavoritesService.star("RECORD", "document", "1")).hasMessage("Not found");
      assertThatThrownBy(() -> FavoritesService.star("TABLE", "document", null)).hasMessage("Not found");
      assertThat(FavoritesService.list()).isEmpty();
   }



   /*******************************************************************************
    ** Refresh uses one query, no gets; lock changes redact both label and href.
    *******************************************************************************/
   @Test
   void refreshesLabelsInOneQueryAndRedactsRevokedRecords() throws Exception
   {
      FavoritesService.star("RECORD", "document", "1");
      FavoritesService.star("RECORD", "document", "2");
      new UpdateAction().execute(new UpdateInput("document").withRecords(List.of(new QRecord().withValue("id", 1).withValue("title", "Renamed"))));
      ReadCounter.queries = 0;
      ReadCounter.gets = 0;
      instance.getTable("document").withCustomizer(TableCustomizers.POST_QUERY_RECORD, new QCodeReference(ReadCounter.class));
      assertThat(FavoritesService.list()).extracting(FavoriteEntry::label).containsExactly("Renamed", "Second document");
      assertThat(ReadCounter.queries).isEqualTo(1);
      assertThat(ReadCounter.gets).isZero();
      QContext.getQSession().getSecurityKeyValues().put("tenant", List.of("b"));
      assertThat(FavoritesService.list()).allSatisfy(entry ->
      {
         assertThat(entry.available()).isFalse();
         assertThat(entry.label()).isEqualTo("Unavailable");
         assertThat(entry.href()).isNull();
      });
      FavoritesService.unstar("RECORD", "document", "1");
      assertThat(FavoritesService.list()).hasSize(1);
   }



   /*******************************************************************************
    ** Direct table reads/writes initialize keys and enforce both sides of a lock.
    *******************************************************************************/
   @Test
   void ownershipAndAdminAccess() throws Exception
   {
      FavoritesService.star("TABLE", "document", null);
      Integer aliceId = FavoritesService.list().getFirst().id();
      login("bob");
      assertThat(new QueryAction().execute(new QueryInput(CollaborationTables.FAVORITE)).getRecords()).isEmpty();
      assertThat(new GetAction().execute(new GetInput(CollaborationTables.FAVORITE).withPrimaryKey(aliceId)).getRecord()).isNull();
      assertThat(new CountAction().execute(new CountInput(CollaborationTables.FAVORITE)).getCount()).isZero();
      FavoritesService.star("TABLE", "document", null);
      QRecord spoof = new QRecord().withValue("userId", "alice").withValue("targetType", "TABLE").withValue("targetName", "other").withValue("sortOrder", 0);
      assertThat(new InsertAction().execute(new InsertInput(CollaborationTables.FAVORITE).withRecords(List.of(spoof))).getRecords().getFirst().getErrors()).isNotEmpty();
      Integer bobId = FavoritesService.list().getFirst().id();
      assertThat(new UpdateAction().execute(new UpdateInput(CollaborationTables.FAVORITE).withRecords(List.of(new QRecord().withValue("id", bobId).withValue("userId", "alice"))))
         .getRecords().getFirst().getErrors()).isNotEmpty();
      admin();
      assertThat(new QueryAction().execute(new QueryInput(CollaborationTables.FAVORITE)).getRecords()).hasSize(2);
      assertThat(FavoritesService.list()).isEmpty();
      QContext.getQSession().getPermissions().remove(CollaborationPermissions.ADMIN);
      assertThat(new QueryAction().execute(new QueryInput(CollaborationTables.FAVORITE)).getRecords()).isEmpty();
      assertThat(QContext.getQSession().getSecurityKeyValues()).doesNotContainKey(CollaborationSecurity.KEY_USER_ALL_ACCESS);
   }



   /*******************************************************************************
    ** Reordering validates all ownership before writing and can clear a folder.
    *******************************************************************************/
   @Test
   void reorderAndFolders() throws Exception
   {
      FavoritesService.star("RECORD", "document", "1");
      FavoritesService.star("RECORD", "document", "2");
      List<Integer> ids = FavoritesService.list().stream().map(FavoriteEntry::id).toList();
      FavoritesService.reorder(List.of(ids.get(1)), Map.of(ids.get(1), "Work"));
      assertThat(FavoritesService.list()).extracting(FavoriteEntry::id).containsExactly(ids.get(1), ids.get(0));
      assertThat(FavoritesService.list().getFirst().folder()).isEqualTo("Work");
      Map<Integer, String> clear = new HashMap<>();
      clear.put(ids.get(1), null);
      FavoritesService.reorder(List.of(), clear);
      assertThat(FavoritesService.list().getFirst().folder()).isNull();
      assertThatThrownBy(() -> FavoritesService.reorder(List.of(ids.get(0), ids.get(0)), Map.of())).hasMessage("Invalid favorite ids");
      assertThatThrownBy(() -> FavoritesService.reorder(List.of(), Map.of(ids.get(0), "x".repeat(256)))).hasMessage("Folder is too long");
      login("bob");
      assertThatThrownBy(() -> FavoritesService.reorder(ids, Map.of())).hasMessage("Invalid favorite ids");
      assertThatThrownBy(() -> FavoritesService.reorder(List.of(), Map.of(ids.get(0), "stolen"))).hasMessage("Invalid favorite ids");
   }



   /*******************************************************************************
    ** All metadata types use their core permissions, refresh labels and redact.
    *******************************************************************************/
   @Test
   void metadataTargetsAndRevocation() throws Exception
   {
      QPermissionRules rules = new QPermissionRules().withLevel(PermissionLevel.HAS_ACCESS_PERMISSION).withPermissionBaseName("targets");
      instance.addApp(new QAppMetaData().withName("work").withLabel("Work").withPermissionRules(rules));
      instance.addProcess(new QProcessMetaData().withName("document.export").withLabel("Export").withPermissionRules(rules));
      instance.addReport(new QReportMetaData().withName("report").withLabel("Report").withPermissionRules(rules));
      for(String type : List.of("APP", "PROCESS", "REPORT"))
      {
         String name = type.equals("APP") ? "work" : type.equals("PROCESS") ? "document.export" : "report";
         assertThatThrownBy(() -> FavoritesService.star(type, name, null)).hasMessage("Not found");
         assertThatThrownBy(() -> FavoritesService.star(type, "missing", null)).hasMessage("Not found");
      }
      QContext.getQSession().withPermissions("targets.hasAccess");
      FavoritesService.star("APP", "work", null);
      FavoritesService.star("PROCESS", "document.export", null);
      FavoritesService.star("REPORT", "report", null);
      FavoritesService.star("TABLE", "document", null);
      assertThat(FavoritesService.list()).extracting(FavoriteEntry::label).containsExactly("Work", "Export", "Report", "Documents");
      assertThat(FavoritesService.list().get(1).href()).isEqualTo("/app/document.export/");
      instance.getApp("work").setLabel("Renamed app");
      assertThat(FavoritesService.list().getFirst().label()).isEqualTo("Renamed app");
      QContext.getQSession().getPermissions().clear();
      assertThat(FavoritesService.list()).allSatisfy(entry -> assertThat(entry.label()).isEqualTo("Unavailable"));
   }



   /*******************************************************************************
    ** Saved views are conditional on the core table and respect its row locks.
    *******************************************************************************/
   @Test
   void savedViews() throws Exception
   {
      assertThatThrownBy(() -> FavoritesService.star("SAVED_VIEW", "7", null)).hasMessage("Not found");
      instance.addTable(new QTableMetaData().withName("savedView").withBackendName("memory").withPrimaryKeyField("id")
         .withPermissionRules(new QPermissionRules().withLevel(PermissionLevel.NOT_PROTECTED))
         .withField(new QFieldMetaData("id", QFieldType.INTEGER)).withField(new QFieldMetaData("label", QFieldType.STRING))
         .withField(new QFieldMetaData("tableName", QFieldType.STRING)).withField(new QFieldMetaData("userId", QFieldType.STRING))
         .withRecordLabelFormatAndFields("%s", "label").withRecordSecurityLock(CollaborationSecurity.ownerLock("userId")));
      CollaborationSecurity.ensureSessionKeys(instance, QContext.getQSession());
      new InsertAction().execute(new InsertInput("savedView").withRecords(List.of(new QRecord().withValue("id", 7).withValue("label", "My view")
         .withValue("tableName", "document").withValue("userId", "alice"))));
      FavoritesService.star("SAVED_VIEW", "7", null);
      assertThat(FavoritesService.list().getFirst().label()).isEqualTo("My view");
      assertThat(FavoritesService.list().getFirst().href()).isEqualTo("/app/document/savedView/7/");
      login("bob");
      assertThatThrownBy(() -> FavoritesService.star("SAVED_VIEW", "7", null)).hasMessage("Not found");
      login("alice");
      QContext.getQSession().getPermissions().remove("document.read");
      assertThat(FavoritesService.list().getFirst().label()).isEqualTo("Unavailable");
   }



   /*******************************************************************************
    ** Integer aliases refer to one target; arbitrary string keys are URL-encoded.
    *******************************************************************************/
   @Test
   void canonicalRecordIdsAndEncodedPaths() throws Exception
   {
      FavoritesService.star("RECORD", "document", "01");
      FavoritesService.star("RECORD", "document", 1);
      assertThat(FavoritesService.list()).extracting(FavoriteEntry::recordId).containsExactly("1");
      assertThat(FavoritesService.list().getFirst().available()).isTrue();
      FavoritesService.unstar("RECORD", "document", "001");
      assertThat(FavoritesService.list()).isEmpty();
      assertThatThrownBy(() -> FavoritesService.star("RECORD", "document", "invalid")).hasMessage("Not found");
      instance.addTable(new QTableMetaData().withName("named").withLabel("Named").withBackendName("memory").withPrimaryKeyField("id")
         .withPermissionRules(new QPermissionRules().withLevel(PermissionLevel.NOT_PROTECTED))
         .withField(new QFieldMetaData("id", QFieldType.STRING)).withRecordLabelFormatAndFields("%s", "id"));
      new InsertAction().execute(new InsertInput("named").withRecords(List.of(new QRecord().withValue("id", "a/b #?"))));
      FavoritesService.star("RECORD", "named", "a/b #?");
      assertThat(FavoritesService.list().getFirst().href()).isEqualTo("/app/named/a%2Fb%20%23%3F/");
   }



   /*******************************************************************************
    ** Deletion has the same unavailable presentation as losing access.
    *******************************************************************************/
   @Test
   void deletedTargetsAndCrossUserWrites() throws Exception
   {
      FavoritesService.star("RECORD", "document", "1");
      FavoritesService.star("TABLE", "document", null);
      Integer favoriteId = FavoritesService.list().getFirst().id();
      login("bob");
      assertThat(new UpdateAction().execute(new UpdateInput(CollaborationTables.FAVORITE)
         .withRecords(List.of(new QRecord().withValue("id", favoriteId).withValue("folder", "stolen"))))
         .getRecords().getFirst().getErrors()).isNotEmpty();
      assertThat(new com.kingsrook.qqq.backend.core.actions.tables.DeleteAction().execute(
         new com.kingsrook.qqq.backend.core.model.actions.tables.delete.DeleteInput(CollaborationTables.FAVORITE).withPrimaryKeys(List.of(favoriteId))).getDeletedRecordCount()).isZero();
      admin();
      assertThatThrownBy(() -> FavoritesService.star("RECORD", "document", "3")).hasMessage("Not found");
      login("alice");
      PersonalRecords.delete("document", List.of(1));
      assertThat(FavoritesService.list().getFirst().label()).isEqualTo("Unavailable");
      instance.getTables().remove("document");
      assertThat(FavoritesService.list()).allSatisfy(entry ->
      {
         assertThat(entry.available()).isFalse();
         assertThat(entry.label()).isEqualTo("Unavailable");
         assertThat(entry.href()).isNull();
      });
   }



   /*******************************************************************************
    ** Bad target shapes and anonymous callers fail before storing data.
    *******************************************************************************/
   @Test
   void rejectsInvalidTargetsAndAnonymousSessions()
   {
      assertThatThrownBy(() -> FavoritesService.star("OTHER", "document", null)).hasMessage("Invalid target");
      assertThatThrownBy(() -> FavoritesService.star("RECORD", "document", null)).hasMessage("Invalid target");
      assertThatThrownBy(() -> FavoritesService.star("TABLE", "document", "1")).hasMessage("Invalid target");
      assertThatThrownBy(() -> FavoritesService.star("TABLE", " ", null)).hasMessage("Invalid target");
      QContext.init(instance, new QSession());
      assertThatThrownBy(() -> FavoritesService.list()).hasMessageContaining("Sign in");
      assertThatThrownBy(() -> new QueryAction().execute(new QueryInput(CollaborationTables.FAVORITE))).hasMessageContaining("Sign in");
   }



   /*******************************************************************************
    ** Count actual core reads rather than assuming an implementation batches.
    *******************************************************************************/
   public static class ReadCounter implements TableCustomizerInterface
   {
      static int queries;
      static int gets;



      /*******************************************************************************
       ** Core calls this for both get and query, after enforcing record locks.
       *******************************************************************************/
      @Override
      public List<QRecord> postQuery(QueryOrGetInputInterface input, List<QRecord> rows)
      {
         if(input instanceof QueryInput)
         {
            queries++;
         }
         else
         {
            gets++;
         }
         return (rows);
      }
   }
}
