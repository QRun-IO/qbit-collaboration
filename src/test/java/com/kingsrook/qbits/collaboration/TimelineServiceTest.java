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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.timeline.TimelineDetail;
import com.kingsrook.qbits.collaboration.timeline.TimelineItem;
import com.kingsrook.qbits.collaboration.timeline.TimelinePage;
import com.kingsrook.qbits.collaboration.timeline.TimelineService;
import com.kingsrook.qqq.backend.core.actions.metadata.personalization.TableMetaDataPersonalizerInterface;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.instances.QInstanceValidator;
import com.kingsrook.qqq.backend.core.model.actions.metadata.personalization.TableMetaDataPersonalizerInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.audits.AuditsMetaDataProvider;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.PermissionLevel;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.QPermissionRules;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.modules.backend.implementations.memory.MemoryRecordStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*******************************************************************************
 ** Core audit schemas from RC.5, merged with real activity and permission checks.
 *******************************************************************************/
public class TimelineServiceTest extends PersonalServiceFixture
{
   private final TimelineService timeline = new TimelineService();
   private final Instant at = Instant.parse("2026-01-01T00:00:00Z");

   /*******************************************************************************
    ** Audits.
    *******************************************************************************/
   @BeforeEach
   void audits() throws Exception
   {
      new AuditsMetaDataProvider().withRecordIdType(QFieldType.STRING).defineAll(instance, "memory", table ->
         table.withPermissionRules(new QPermissionRules().withLevel(PermissionLevel.READ_WRITE_PERMISSIONS)));
      new QInstanceValidator().validate(instance);
      seed("auditTable", new QRecord().withValue("id", 1).withValue("name", "document").withValue("label", "Documents"));
      seed("auditUser", new QRecord().withValue("id", 1).withValue("name", "Audit Actor"));
      for(long id = 1; id <= 3; id++)
      {
         seed("audit", new QRecord().withValue("id", id).withValue("auditTableId", 1).withValue("auditUserId", 1)
            .withValue("recordId", "1").withValue("timestamp", id == 3 ? at.plusSeconds(10) : at).withValue("message", "Record changed"));
      }
      seed("auditDetail", new QRecord().withValue("id", 1L).withValue("auditId", 2L).withValue("fieldName", "title").withValue("oldValue", "Old").withValue("newValue", "New"));
      seed("auditDetail", new QRecord().withValue("id", 2L).withValue("auditId", 2L).withValue("fieldName", "tenant").withValue("oldValue", "sensitive old").withValue("newValue", "sensitive new"));
      for(int id = 1; id <= 3; id++)
      {
         CollaborationRecords.insert(CollaborationTables.ACTIVITY, new QRecord().withValue("id", id).withValue("tableName", "document").withValue("recordId", "1")
            .withValue("at", id == 3 ? at.minusSeconds(10) : at).withValue("actorUserId", "alice").withValue("type", "COMMENTED")
            .withValue("summary", "Comment added").withValue("refTableName", CollaborationTables.COMMENT).withValue("refId", "1").withValue("parentDeleted", false));
      }
      QContext.getQSession().withPermissions("audit.read", "auditTable.read", "auditDetail.read", "auditUser.read");
   }

   /*******************************************************************************
    ** Seed audit-only fixture data through the memory backend, which core owns.
    *******************************************************************************/
   private void seed(String table, QRecord record) throws Exception
   {
      MemoryRecordStore.getInstance().insert(new InsertInput(table).withRecords(List.of(record)), true);
   }

   /*******************************************************************************
    ** Merges ties and keyset pages without gaps or duplicates.
    *******************************************************************************/
   @Test
   void mergesTiesAndKeysetPagesWithoutGapsOrDuplicates() throws Exception
   {
      TimelinePage first = timeline.timeline("document", 1, null, 2, null);
      assertThat(first.items()).extracting(TimelineItem::kind).containsExactly("AUDIT", "AUDIT");
      assertThat(first.items().get(1).details()).hasSize(2);
      assertThat(first.items().get(1).actorName()).isEqualTo("Audit Actor");
      assertThat(first.items().get(1).actorUserId()).isNull();
      List<String> seen = new ArrayList<>();
      String cursor = null;
      do
      {
         TimelinePage page = timeline.timeline("document", 1, cursor, 1, Set.of());
         page.items().forEach(item -> seen.add(item.kind() + ":" + item.id()));
         cursor = page.nextCursor();
      }
      while(cursor != null && seen.size() < 10);
      assertThat(seen).containsExactly("AUDIT:3", "AUDIT:2", "AUDIT:1", "ACTIVITY:2", "ACTIVITY:1", "ACTIVITY:3");
      assertThat(timeline.timeline("document", 1, null, 50, Set.of("COMMENTED")).items()).hasSize(3);
      assertThat(timeline.timeline("document", 1, null, 50, Set.of("AUDIT")).items()).hasSize(3);
      assertThat(timeline.timeline("document", 2, null, 50, null).items()).isEmpty();
   }

   /*******************************************************************************
    ** Core wording becomes who-did-what; detail rows and unknown messages are untouched.
    *******************************************************************************/
   @Test
   void auditSummariesSayWhoDidWhat() throws Exception
   {
      seedAudit(4, "Record was Edited", 100);
      seedAudit(5, "Record was Inserted", 101);
      seedAudit(6, "Record was Edited", 102);
      seedAudit(7, "Record was Deleted", 103);
      seed("auditDetail", new QRecord().withValue("id", 10L).withValue("auditId", 4L).withValue("fieldName", "title").withValue("oldValue", "Old").withValue("newValue", "New"));
      seed("auditDetail", new QRecord().withValue("id", 11L).withValue("auditId", 4L).withValue("fieldName", "tenant").withValue("oldValue", "a").withValue("newValue", "b"));
      seed("auditDetail", new QRecord().withValue("id", 12L).withValue("auditId", 4L).withValue("fieldName", "title").withValue("oldValue", "New").withValue("newValue", "Newer"));
      seed("auditDetail", new QRecord().withValue("id", 13L).withValue("auditId", 5L).withValue("fieldName", "title").withValue("newValue", "Created title"));
      var items = timeline.timeline("document", 1, null, 50, Set.of("AUDIT")).items();
      java.util.function.Function<Long, TimelineItem> byId = id -> items.stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
      assertThat(byId.apply(4L).summary()).isEqualTo("Edited by Audit Actor: Title, Tenant");
      assertThat(byId.apply(4L).details()).hasSize(3);
      assertThat(byId.apply(5L).summary()).isEqualTo("Created by Audit Actor");
      assertThat(byId.apply(5L).details()).hasSize(1);
      assertThat(byId.apply(6L).summary()).isEqualTo("Edited by Audit Actor");
      assertThat(byId.apply(7L).summary()).isEqualTo("Deleted by Audit Actor");
      assertThat(byId.apply(1L).summary()).as("unrecognized messages are kept").isEqualTo("Record changed");
      assertThat(items).extracting(TimelineItem::summary).noneMatch(summary -> summary.startsWith("Record was"));

      assertThat(TimelineService.friendlySummary("Record was Inserted", null, List.of(), null)).isEqualTo("Created");
      assertThat(TimelineService.friendlySummary("Record was Inserted via import", " ", List.of(), null)).isEqualTo("Created");
      assertThat(TimelineService.friendlySummary(null, "A", List.of(), null)).isNull();
      List<TimelineDetail> many = java.util.stream.IntStream.rangeClosed(1, 6).mapToObj(n -> new TimelineDetail("f" + n, "a", "b", null)).toList();
      assertThat(TimelineService.friendlySummary("Record was Edited", "A", many, null)).isEqualTo("Edited by A: f1, f2, f3, f4 and 2 more");
      assertThat(TimelineService.friendlySummary("Record was Edited", "A", List.of(new TimelineDetail(null, null, null, "note"), new TimelineDetail(" ", null, null, null)), null)).isEqualTo("Edited by A");
   }

   /*******************************************************************************
    ** One audit header for the first document, as a different time.
    *******************************************************************************/
   private void seedAudit(long id, String message, long secondsAfter) throws Exception
   {
      seed("audit", new QRecord().withValue("id", id).withValue("auditTableId", 1).withValue("auditUserId", 1)
         .withValue("recordId", "1").withValue("timestamp", at.plusSeconds(secondsAfter)).withValue("message", message));
   }

   /*******************************************************************************
    ** Audit read permission and hidden fields apply.
    *******************************************************************************/
   @Test
   void auditReadPermissionAndHiddenFieldsApply() throws Exception
   {
      instance.addSupplementalCustomizer(TableMetaDataPersonalizerInterface.CUSTOMIZER_TYPE, new QCodeReference(HideTenant.class));
      TimelinePage page = timeline.timeline("document", 1, null, 50, null);
      assertThat(page.items().stream().flatMap(item -> item.details().stream())).singleElement().satisfies(detail ->
      {
         assertThat(detail.fieldName()).isEqualTo("title");
         assertThat(detail.oldValue()).isEqualTo("Old");
      });
      QContext.getQSession().getPermissions().remove("audit.read");
      instance.setEnforcePermissionsInCoreActions(false);
      assertThat(timeline.timeline("document", 1, null, 50, null).items()).hasSize(3).allSatisfy(item -> assertThat(item.kind()).isEqualTo("ACTIVITY"));
      assertThatThrownBy(() -> timeline.timeline("document", 3, null, 50, null)).hasMessage("Not found");
      assertThatThrownBy(() -> timeline.timeline("document", 1, "invalid", 50, null)).hasMessage("Invalid cursor");
      assertThatThrownBy(() -> timeline.timeline("document", 1, null, 101, null)).hasMessageContaining("Limit");
      instance.getTables().remove("audit");
      assertThat(timeline.timeline("document", 1, null, 50, Set.of("AUDIT")).items()).isEmpty();
   }

   /*******************************************************************************
    ** Real personalizer boundary, like core's audit screen.
    *******************************************************************************/
   public static class HideTenant implements TableMetaDataPersonalizerInterface
   {
      @Override
      public QTableMetaData execute(TableMetaDataPersonalizerInput input)
      {
         QTableMetaData result = input.getTable().clone();
         if(result.getName().equals("document"))
         {
            result.getFields().remove("tenant");
         }
         return (result);
      }
   }
}
