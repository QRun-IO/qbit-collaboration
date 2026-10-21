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


package com.kingsrook.qbits.collaboration.app;


import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.kingsrook.qbits.collaboration.CollaborationQBitConfig;
import com.kingsrook.qbits.collaboration.CollaborationRecords;
import com.kingsrook.qbits.collaboration.CollaborationSecurity;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.permissions.CollaborationPermissions;
import com.kingsrook.qbits.collaboration.presence.PresenceRegistry;
import com.kingsrook.qqq.backend.core.actions.dashboard.widgets.AbstractWidgetRenderer;
import com.kingsrook.qqq.backend.core.actions.permissions.PermissionEnforcement;
import com.kingsrook.qqq.backend.core.actions.tables.AggregateAction;
import com.kingsrook.qqq.backend.core.actions.tables.CountAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.aggregate.Aggregate;
import com.kingsrook.qqq.backend.core.model.actions.tables.aggregate.AggregateInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.aggregate.AggregateOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.aggregate.AggregateResult;
import com.kingsrook.qqq.backend.core.model.actions.tables.count.CountInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.actions.widgets.RenderWidgetInput;
import com.kingsrook.qqq.backend.core.model.actions.widgets.RenderWidgetOutput;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.MultiStatisticsData;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.MultiStatisticsData.StatisticsGroupData;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.MultiStatisticsData.StatisticsGroupData.Statistic;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.TableData;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.WidgetType;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.dashboard.QWidgetMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppSection;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QIcon;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;


/*******************************************************************************
 ** The Collaboration app and its home, built from standard QQQ widgets: a KPI
 ** block and three full-width tables (tags, custom fields, who's here). Counts
 ** use QQQ actions with the current QContext session, so permissions and record
 ** security apply, and every block carries a real empty-state message. No colors
 ** are specified, so the frontend theme (including dark mode) decides.
 *******************************************************************************/
public final class CollaborationHome
{
   public static final String SETUP_WIDGET = "collaborationSetup";
   public static final String KPI_WIDGET = "collaborationKpis";
   public static final String TAGS_WIDGET = "collaborationTags";
   public static final String FIELDS_WIDGET = "collaborationCustomFields";
   public static final String PRESENCE_WIDGET = "collaborationWhosHere";
   public static final List<String> HOME_WIDGETS = List.of(SETUP_WIDGET, KPI_WIDGET, TAGS_WIDGET, FIELDS_WIDGET, PRESENCE_WIDGET);

   public static final String NOBODY_HERE = "Nobody else is here right now.";
   public static final String NO_TAGS = "No tags yet. Tags appear here once someone creates one on a record.";
   public static final String NO_FIELDS = "No custom fields yet. Define one under Custom fields.";
   public static final String PRESENCE_ADMIN_ONLY = "Who's here is visible to collaboration administrators.";



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private CollaborationHome()
   {
   }



   /*******************************************************************************
    ** Main tables only; processes never appear in navigation.
    *******************************************************************************/
   public static QAppMetaData app(CollaborationQBitConfig config)
   {
      QAppMetaData app = new QAppMetaData().withName(config.getAppName()).withLabel("Collaboration").withIcon(new QIcon("forum"))
         .withPermissionRules(CollaborationPermissions.permissionRules())
         .withWidgets(HOME_WIDGETS)
         .withSection(new QAppSection().withName("collaboration").withLabel("Collaboration").withIcon(new QIcon("forum"))
            .withTables(List.of(CollaborationTables.COMMENT, CollaborationTables.ATTACHMENT, CollaborationTables.TAG, CollaborationTables.FIELD_DEFINITION, CollaborationTables.TASK, CollaborationTables.SHARE, CollaborationTables.ACCESS_REQUEST, CollaborationTables.RECORD_ASSIGNMENT, CollaborationTables.ACTIVITY, CollaborationTables.FEATURE_SETTING, CollaborationTables.USER)));
      app.setParentAppName(config.getParentAppName());
      return (app);
   }



   /*******************************************************************************
    ** The four full-width home widgets, in display order.
    *******************************************************************************/
   public static List<QWidgetMetaData> widgets()
   {
      return (List.of(
         widget(SETUP_WIDGET, "Setup", WidgetType.TABLE, SetupRenderer.class),
         widget(KPI_WIDGET, "Overview", WidgetType.MULTI_STATISTICS, KpiRenderer.class),
         widget(TAGS_WIDGET, "Tags", WidgetType.TABLE, TagsRenderer.class),
         widget(FIELDS_WIDGET, "Custom fields", WidgetType.TABLE, FieldsRenderer.class),
         widget(PRESENCE_WIDGET, "Who's here", WidgetType.TABLE, PresenceRenderer.class)));
   }



   /*******************************************************************************
    ** One full-width card-less widget guarded by the shared permission rules.
    *******************************************************************************/
   private static QWidgetMetaData widget(String name, String label, WidgetType type, Class<? extends AbstractWidgetRenderer> renderer)
   {
      return (new QWidgetMetaData().withName(name).withLabel(label).withType(type.getType())
         .withCodeReference(new QCodeReference(renderer)).withGridColumns(12).withIsCard(false)
         .withPermissionRules(CollaborationPermissions.permissionRules()));
   }



   /*******************************************************************************
    ** The Setup card: each record feature, whether it is on, for which tables,
    ** where that comes from, and the next step.
    *******************************************************************************/
   public static TableData setup() throws QException
   {
      CollaborationSecurity.ensureSessionKeys(QContext.getQInstance(), QContext.getQSession());
      List<Map<String, Object>> rows = new ArrayList<>();
      for(CollaborationSetupStatus.Item item : CollaborationSetupStatus.load(QContext.getQInstance()))
      {
         Map<String, Object> row = new LinkedHashMap<>();
         row.put("feature", item.feature());
         row.put("state", item.state());
         row.put("where", item.where());
         row.put("source", item.source());
         row.put("next", item.nextStep() == null ? "" : item.nextStep());
         rows.add(row);
      }
      return (table("Setup", "Nothing to set up.", rows, column("Feature", "feature", "left"), column("State", "state", "left"), column("Where", "where", "left"),
         column("From", "source", "left"), column("Next step", "next", "left")));
   }



   /*******************************************************************************
    ** The KPI groups: conversations, work, files and custom fields.
    *******************************************************************************/
   public static MultiStatisticsData kpis(Instant now) throws QException
   {
      CollaborationSecurity.ensureSessionKeys(QContext.getQInstance(), QContext.getQSession());
      Instant weekAgo = now.minus(7, ChronoUnit.DAYS);
      int commentsThisWeek = count(CollaborationTables.COMMENT, new QQueryFilter()
         .withCriteria("createDate", QCriteriaOperator.GREATER_THAN_OR_EQUALS, weekAgo)
         .withCriteria("createDate", QCriteriaOperator.LESS_THAN_OR_EQUALS, now).withCriteria("deletedAt", QCriteriaOperator.IS_BLANK));
      int openThreads = count(CollaborationTables.COMMENT, new QQueryFilter().withCriteria("threadId", QCriteriaOperator.IS_BLANK)
         .withCriteria("resolvedAt", QCriteriaOperator.IS_BLANK).withCriteria("deletedAt", QCriteriaOperator.IS_BLANK));
      int openTasks = count(CollaborationTables.TASK, new QQueryFilter().withCriteria("status", QCriteriaOperator.EQUALS, "OPEN"));
      int overdue = count(CollaborationTables.TASK, new QQueryFilter().withCriteria("status", QCriteriaOperator.EQUALS, "OPEN")
         .withCriteria("dueAt", QCriteriaOperator.LESS_THAN, now));
      int openRequests = count(CollaborationTables.ACCESS_REQUEST, new QQueryFilter().withCriteria("status", QCriteriaOperator.EQUALS, "OPEN"));
      int activeFields = count(CollaborationTables.FIELD_DEFINITION, new QQueryFilter().withCriteria("status", QCriteriaOperator.EQUALS, "ACTIVE"));
      Aggregate files = new Aggregate().withFieldName("id").withOperator(AggregateOperator.COUNT);
      Aggregate bytes = new Aggregate().withFieldName("sizeBytes").withOperator(AggregateOperator.SUM);
      AggregateInput aggregateInput = new AggregateInput(CollaborationTables.ATTACHMENT).withAggregates(List.of(files, bytes));
      aggregateInput.setPermissionEnforcement(PermissionEnforcement.ENFORCE);
      List<AggregateResult> results = new AggregateAction().execute(aggregateInput).getResults();
      int attachments = results.isEmpty() ? 0 : numberOf(results.getFirst().getAggregateValue(files)).intValue();
      long totalBytes = results.isEmpty() ? 0 : numberOf(results.getFirst().getAggregateValue(bytes)).longValue();
      String fileSubheader = attachments == 0 ? "No attachments yet" : humanSize(totalBytes) + " in total";

      return (new MultiStatisticsData("Collaboration", List.of(
         new StatisticsGroupData("forum", null, "Conversations", "Last 7 days and open", List.of(
            new Statistic("Comments this week", commentsThisWeek, path(CollaborationTables.COMMENT)),
            new Statistic("Open threads", openThreads, path(CollaborationTables.COMMENT)))),
         new StatisticsGroupData("task_alt", null, "Work", openTasks == 0 && openRequests == 0 ? "Nothing waiting" : "Waiting on someone", List.of(
            new Statistic("Open tasks", openTasks, path(CollaborationTables.TASK)),
            new Statistic("Overdue tasks", overdue, path(CollaborationTables.TASK)),
            new Statistic("Open access requests", openRequests, path(CollaborationTables.ACCESS_REQUEST)))),
         new StatisticsGroupData("attach_file", null, "Attachments", fileSubheader, List.of(
            new Statistic("Attachments", attachments, path(CollaborationTables.ATTACHMENT)))),
         new StatisticsGroupData("tune", null, "Custom fields", activeFields == 0 ? "None active yet" : "Defined by administrators", List.of(
            new Statistic("Active custom fields", activeFields, path(CollaborationTables.FIELD_DEFINITION)))))));
   }



   /*******************************************************************************
    ** Approved and pending tags, most used first: label, table label, records.
    *******************************************************************************/
   public static TableData tags() throws QException
   {
      CollaborationSecurity.ensureSessionKeys(QContext.getQInstance(), QContext.getQSession());
      QueryInput input = new QueryInput(CollaborationTables.TAG).withFilter(new QQueryFilter().withCriteria("mergedIntoTagId", QCriteriaOperator.IS_BLANK)
         .withOrderBy(new QFilterOrderBy("usageCount", false)).withOrderBy(new QFilterOrderBy("label")).withLimit(200));
      input.setPermissionEnforcement(PermissionEnforcement.ENFORCE);
      List<Map<String, Object>> rows = new ArrayList<>();
      for(QRecord tag : new QueryAction().execute(input).getRecords())
      {
         Map<String, Object> row = new LinkedHashMap<>();
         row.put("label", tag.getValueString("label"));
         row.put("table", tableLabel(tag.getValueString("tableName")));
         row.put("records", tag.getValueInteger("usageCount") == null ? 0 : tag.getValueInteger("usageCount"));
         rows.add(row);
      }
      return (table("Tags", NO_TAGS, rows, column("Label", "label", "left"), column("Table", "table", "left"), column("Records", "records", "right")));
   }



   /*******************************************************************************
    ** Custom field definitions: name, table, status and index state.
    *******************************************************************************/
   public static TableData fields() throws QException
   {
      CollaborationSecurity.ensureSessionKeys(QContext.getQInstance(), QContext.getQSession());
      QueryInput input = new QueryInput(CollaborationTables.FIELD_DEFINITION).withFilter(new QQueryFilter()
         .withOrderBy(new QFilterOrderBy("tableName")).withOrderBy(new QFilterOrderBy("name")).withLimit(200));
      input.setPermissionEnforcement(PermissionEnforcement.ENFORCE);
      List<Map<String, Object>> rows = new ArrayList<>();
      for(QRecord definition : new QueryAction().execute(input).getRecords())
      {
         Map<String, Object> row = new LinkedHashMap<>();
         row.put("name", definition.getValueString("name"));
         row.put("table", tableLabel(definition.getValueString("tableName")));
         row.put("status", friendly(definition.getValueString("status")));
         row.put("indexState", friendly(definition.getValueString("indexState")));
         rows.add(row);
      }
      return (table("Custom fields", NO_FIELDS, rows, column("Name", "name", "left"), column("Table", "table", "left"),
         column("Status", "status", "left"), column("Index state", "indexState", "left")));
   }



   /*******************************************************************************
    ** Live viewer counts per table. Only administrators see them (they cover
    ** records the viewer may not be able to open); others get an explanation.
    *******************************************************************************/
   public static TableData presence() throws QException
   {
      CollaborationSecurity.ensureSessionKeys(QContext.getQInstance(), QContext.getQSession());
      boolean admin = QContext.getQSession().hasPermission(CollaborationPermissions.ADMIN);
      List<Map<String, Object>> rows = new ArrayList<>();
      if(admin)
      {
         var instance = QContext.getQInstance();
         var redis = com.kingsrook.qbits.collaboration.presence.RedisPresenceConfig.forInstance(instance);
         Map<String, Integer> counts;
         if(redis == null)
         {
            counts = PresenceRegistry.get(instance).counts();
         }
         else
         {
            counts = new java.util.TreeMap<>();
            var backend = new com.kingsrook.qbits.collaboration.presence.RedisPresenceBackend(redis);
            // Read only known table rooms in the administrator's authenticated tenant.
            // Never scan Redis keys or silently substitute local counts on failure.
            for(String tableName : instance.getTables().keySet())
            {
               int viewers = backend.snapshot(tableName, null).size();
               if(viewers > 0)
               {
                  counts.put(tableName, viewers);
               }
            }
         }
         counts.forEach((table, viewers) ->
         {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("table", tableLabel(table));
            row.put("viewers", viewers);
            rows.add(row);
         });
      }
      return (table("Who's here", admin ? NOBODY_HERE : PRESENCE_ADMIN_ONLY, rows, column("Table", "table", "left"), column("People", "viewers", "right")));
   }



   /*******************************************************************************
    ** Assemble a table widget whose empty state is a sentence, not a blank grid.
    *******************************************************************************/
   private static TableData table(String label, String emptyMessage, List<Map<String, Object>> rows, TableData.Column... columns)
   {
      return (new TableData(label, List.of(columns), rows).withNoRowsFoundHTML(emptyMessage).withRowsPerPage(10));
   }



   /*******************************************************************************
    ** A plain column; the frontend styles it.
    *******************************************************************************/
   private static TableData.Column column(String header, String accessor, String align)
   {
      //////////////////////////////////////////////////////////////////
      // no width: frontends size the columns to fit narrow (390 px) cards //
      //////////////////////////////////////////////////////////////////
      return (new TableData.Column("default", header, accessor, null, align));
   }



   /*******************************************************************************
    ** A table's display label, "All tables" when there is none (global).
    *******************************************************************************/
   private static String tableLabel(String name)
   {
      if(name == null || name.isBlank())
      {
         return ("All tables");
      }
      QTableMetaData table = QContext.getQInstance().getTable(name);
      return (table == null || table.getLabel() == null ? name : table.getLabel());
   }



   /*******************************************************************************
    ** ACTIVE becomes Active; a missing value becomes a dash.
    *******************************************************************************/
   private static String friendly(String value)
   {
      if(value == null || value.isBlank())
      {
         return ("-");
      }
      String lower = value.toLowerCase(Locale.ROOT).replace('_', ' ');
      return (Character.toUpperCase(lower.charAt(0)) + lower.substring(1));
   }



   /*******************************************************************************
    ** Binary sizes with one decimal, like 4.7 KB; whole bytes below 1 KB.
    *******************************************************************************/
   public static String humanSize(long bytes)
   {
      if(bytes < 1024)
      {
         return (bytes + " B");
      }
      String[] units = { "KB", "MB", "GB", "TB", "PB" };
      double value = bytes;
      int unit = -1;
      while(value >= 1024 && unit < units.length - 1)
      {
         value /= 1024;
         unit++;
      }
      return (String.format(Locale.ROOT, "%.1f %s", value, units[unit]));
   }



   /*******************************************************************************
    ** Aggregate values come back as any numeric type, or null for empty sets.
    *******************************************************************************/
   private static Number numberOf(Object value)
   {
      return (value instanceof Number number ? number : 0);
   }



   /*******************************************************************************
    ** The path of a Collaboration table page, under the parent app when configured.
    *******************************************************************************/
   private static String path(String table)
   {
      CollaborationQBitConfig config = CollaborationRecords.config();
      String prefix = config.getParentAppName() == null ? "/" + config.getAppName() : "/" + config.getParentAppName() + "/" + config.getAppName();
      return (prefix + "/" + table);
   }



   /*******************************************************************************
    ** Count without materializing rows or substituting a privileged session.
    *******************************************************************************/
   private static int count(String table, QQueryFilter filter) throws QException
   {
      CountInput input = new CountInput(table).withFilter(filter);
      input.setPermissionEnforcement(PermissionEnforcement.ENFORCE);
      return (new CountAction().execute(input).getCount());
   }



   /*******************************************************************************
    ** Setup renderer instantiated by QQQ.
    *******************************************************************************/
   public static class SetupRenderer extends AbstractWidgetRenderer
   {
      /*******************************************************************************
       ** Render using the current session.
       *******************************************************************************/
      @Override
      public RenderWidgetOutput render(RenderWidgetInput input) throws QException
      {
         return (new RenderWidgetOutput(setup()));
      }
   }



   /*******************************************************************************
    ** KPI renderer instantiated by QQQ.
    *******************************************************************************/
   public static class KpiRenderer extends AbstractWidgetRenderer
   {
      /*******************************************************************************
       ** Render using the current session and time.
       *******************************************************************************/
      @Override
      public RenderWidgetOutput render(RenderWidgetInput input) throws QException
      {
         return (new RenderWidgetOutput(kpis(Instant.now())));
      }
   }



   /*******************************************************************************
    ** Tags renderer instantiated by QQQ.
    *******************************************************************************/
   public static class TagsRenderer extends AbstractWidgetRenderer
   {
      /*******************************************************************************
       ** Render using the current session.
       *******************************************************************************/
      @Override
      public RenderWidgetOutput render(RenderWidgetInput input) throws QException
      {
         return (new RenderWidgetOutput(tags()));
      }
   }



   /*******************************************************************************
    ** Custom fields renderer instantiated by QQQ.
    *******************************************************************************/
   public static class FieldsRenderer extends AbstractWidgetRenderer
   {
      /*******************************************************************************
       ** Render using the current session.
       *******************************************************************************/
      @Override
      public RenderWidgetOutput render(RenderWidgetInput input) throws QException
      {
         return (new RenderWidgetOutput(fields()));
      }
   }



   /*******************************************************************************
    ** Presence renderer instantiated by QQQ.
    *******************************************************************************/
   public static class PresenceRenderer extends AbstractWidgetRenderer
   {
      /*******************************************************************************
       ** Render using the current session.
       *******************************************************************************/
      @Override
      public RenderWidgetOutput render(RenderWidgetInput input) throws QException
      {
         return (new RenderWidgetOutput(presence()));
      }
   }
}
