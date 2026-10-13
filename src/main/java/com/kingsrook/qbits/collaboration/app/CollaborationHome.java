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
import java.util.List;
import com.kingsrook.qbits.collaboration.CollaborationQBitConfig;
import com.kingsrook.qbits.collaboration.CollaborationSecurity;
import com.kingsrook.qbits.collaboration.model.CollaborationTables;
import com.kingsrook.qbits.collaboration.permissions.CollaborationPermissions;
import com.kingsrook.qqq.backend.core.actions.dashboard.widgets.AbstractWidgetRenderer;
import com.kingsrook.qqq.backend.core.actions.permissions.PermissionEnforcement;
import com.kingsrook.qqq.backend.core.actions.tables.AggregateAction;
import com.kingsrook.qqq.backend.core.actions.tables.CountAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.aggregate.Aggregate;
import com.kingsrook.qqq.backend.core.model.actions.tables.aggregate.AggregateInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.aggregate.AggregateOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.aggregate.AggregateResult;
import com.kingsrook.qqq.backend.core.model.actions.tables.count.CountInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.widgets.RenderWidgetInput;
import com.kingsrook.qqq.backend.core.model.actions.widgets.RenderWidgetOutput;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.RawHTML;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.WidgetType;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.dashboard.QWidgetMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppSection;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QIcon;


/*******************************************************************************
 ** The Collaboration app and its home. Counts use QQQ actions with the current
 ** QContext session, preserving permissions and record security.
 *******************************************************************************/
public final class CollaborationHome
{
   public static final String HOME_WIDGET = "collaborationHome";



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
         .withWidgets(List.of(HOME_WIDGET))
         .withSection(new QAppSection().withName("collaboration").withLabel("Collaboration").withIcon(new QIcon("forum"))
            .withTables(List.of(CollaborationTables.COMMENT, CollaborationTables.ATTACHMENT, CollaborationTables.ACTIVITY, CollaborationTables.FEATURE_SETTING, CollaborationTables.USER)));
      app.setParentAppName(config.getParentAppName());
      return (app);
   }



   /*******************************************************************************
    ** Full-width HTML widget, following the reference Storage home renderer.
    *******************************************************************************/
   public static QWidgetMetaData widget()
   {
      return (new QWidgetMetaData().withName(HOME_WIDGET).withLabel("Overview").withType(WidgetType.HTML.getType())
         .withCodeReference(new QCodeReference(HomeRenderer.class)).withGridColumns(12).withIsCard(false)
         .withPermissionRules(CollaborationPermissions.permissionRules()));
   }



   /*******************************************************************************
    ** Show persisted enabled overrides by scope type, and recent directory use.
    ** These are settings counts, not a claim about resolved host-table features.
    *******************************************************************************/
   public static String render(Instant now) throws QException
   {
      CollaborationSecurity.ensureSessionKeys(QContext.getQInstance(), QContext.getQSession());
      StringBuilder html = new StringBuilder("<div style=\"display:flex;flex-direction:column;gap:1rem;width:100%;overflow-wrap:anywhere;\"><section><h3>Feature settings</h3>");
      long settings = count(CollaborationTables.FEATURE_SETTING, new QQueryFilter());
      if(settings == 0)
      {
         html.append("<p>No feature settings yet: features follow the code defaults</p>");
      }
      else
      {
         html.append("<p>Enabled feature settings per scope:</p><ul>");
         for(String scope : List.of("INSTANCE", "APP", "TABLE"))
         {
            long enabled = count(CollaborationTables.FEATURE_SETTING, new QQueryFilter()
               .withCriteria(new QFilterCriteria("scopeType", QCriteriaOperator.EQUALS, scope))
               .withCriteria(new QFilterCriteria("enabled", QCriteriaOperator.EQUALS, true)));
            html.append("<li>").append(scope).append(": ").append(enabled).append("</li>");
         }
         html.append("</ul><p>Stored overrides; applying them awaits the core collaboration contracts.</p>");
      }
      long people = count(CollaborationTables.USER, new QQueryFilter());
      long recent = count(CollaborationTables.USER, new QQueryFilter()
         .withCriteria(new QFilterCriteria("lastSeenAt", QCriteriaOperator.GREATER_THAN_OR_EQUALS, now.minus(7, ChronoUnit.DAYS)))
         .withCriteria(new QFilterCriteria("lastSeenAt", QCriteriaOperator.LESS_THAN_OR_EQUALS, now)));
      html.append("</section><section><h3>People</h3><p>People known: ").append(people)
         .append("</p><p>Active in the last 7 days: ").append(recent).append("</p>");
      if(people == 0)
      {
         html.append("<p>No people yet: the directory will be filled by collaboration calls when the feature services are installed.</p>");
      }
      html.append("</section>");
      personalSummary(html, CollaborationTables.FAVORITE, "Favorites");
      personalSummary(html, CollaborationTables.RECENT, "Recently viewed");
      attachmentSummary(html);
      html.append("<section><h3>Comments this week</h3><p>").append(count(CollaborationTables.COMMENT, new QQueryFilter()
         .withCriteria("createDate", QCriteriaOperator.GREATER_THAN_OR_EQUALS, now.minus(7, ChronoUnit.DAYS))
         .withCriteria("createDate", QCriteriaOperator.LESS_THAN_OR_EQUALS, now).withCriteria("deletedAt", QCriteriaOperator.IS_BLANK))).append("</p></section>");
      html.append("<section><h3>Open threads</h3><p>").append(count(CollaborationTables.COMMENT, new QQueryFilter()
         .withCriteria("threadId", QCriteriaOperator.IS_BLANK).withCriteria("resolvedAt", QCriteriaOperator.IS_BLANK)
         .withCriteria("deletedAt", QCriteriaOperator.IS_BLANK))).append("</p></section>");
      if(QContext.getQSession().hasPermission(CollaborationPermissions.ADMIN))
      {
         html.append("<section><h3>Followers</h3><p>").append(count(CollaborationTables.FOLLOW, new QQueryFilter())).append("</p></section>");
      }
      html.append("<p>Conversation totals are approximate where parent record locks require post-filtering.</p>");
      return (html.append("</div>").toString());
   }



   /*******************************************************************************
    ** Real table aggregates, restricted to readable parent tables. Parent locks
    ** are post-filtered on rows, so these aggregate totals are approximate.
    *******************************************************************************/
   private static void attachmentSummary(StringBuilder html) throws QException
   {
      Aggregate count = new Aggregate().withFieldName("id").withOperator(AggregateOperator.COUNT);
      Aggregate bytes = new Aggregate().withFieldName("sizeBytes").withOperator(AggregateOperator.SUM);
      AggregateInput input = new AggregateInput(CollaborationTables.ATTACHMENT).withAggregates(List.of(count, bytes));
      input.setPermissionEnforcement(PermissionEnforcement.ENFORCE);
      List<AggregateResult> results = new AggregateAction().execute(input).getResults();
      Object rowCount = results.isEmpty() ? 0 : results.getFirst().getAggregateValue(count);
      Object totalBytes = results.isEmpty() ? 0 : results.getFirst().getAggregateValue(bytes);
      html.append("<section><h3>Attachments</h3><p>Attachments: ").append(rowCount)
         .append("</p><p>Total size (bytes): ").append(totalBytes == null ? 0 : totalBytes)
         .append("</p><p>Approximate totals: parent record locks are applied when browsing attachments.</p>");
      if("0".equals(String.valueOf(rowCount)))
      {
         html.append("<p>No attachments yet.</p>");
      }
      html.append("</section>");
   }



   /*******************************************************************************
    ** Aggregate rows and distinct owners without loading high-volume personal data.
    *******************************************************************************/
   private static void personalSummary(StringBuilder html, String table, String label) throws QException
   {
      Aggregate rows = new Aggregate().withFieldName("id").withOperator(AggregateOperator.COUNT);
      Aggregate users = new Aggregate().withFieldName("userId").withOperator(AggregateOperator.COUNT_DISTINCT);
      AggregateInput input = new AggregateInput(table).withAggregates(List.of(rows, users));
      input.setPermissionEnforcement(PermissionEnforcement.ENFORCE);
      List<AggregateResult> results = new AggregateAction().execute(input).getResults();
      html.append("<section><h3>").append(label).append("</h3><p>Rows: ").append(results.isEmpty() ? 0 : results.getFirst().getAggregateValue(rows))
         .append("</p><p>Distinct users: ").append(results.isEmpty() ? 0 : results.getFirst().getAggregateValue(users)).append("</p></section>");
   }



   /*******************************************************************************
    ** Count without materializing rows or substituting a privileged session.
    *******************************************************************************/
   private static long count(String table, QQueryFilter filter) throws QException
   {
      CountInput input = new CountInput(table).withFilter(filter);
      input.setPermissionEnforcement(PermissionEnforcement.ENFORCE);
      return (new CountAction().execute(input).getCount());
   }



   /*******************************************************************************
    ** Widget renderer instantiated by QQQ.
    *******************************************************************************/
   public static class HomeRenderer extends AbstractWidgetRenderer
   {
      /*******************************************************************************
       ** Render using the current session and time.
       *******************************************************************************/
      @Override
      public RenderWidgetOutput render(RenderWidgetInput input) throws QException
      {
         return (new RenderWidgetOutput(new RawHTML(null, CollaborationHome.render(Instant.now()))));
      }
   }
}
