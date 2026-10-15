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


import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import com.kingsrook.qqq.backend.core.model.metadata.help.bundles.HelpBundleEntry;
import com.kingsrook.qqq.backend.core.model.metadata.help.bundles.HelpBundles;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** Guardrail (owner requirement 2026-10-14: "all tables should be populated and
 ** the qbit should be fully using them"). For every collaboration_* table:
 ** - runtime code (not just its model, metadata or home screen) reads or writes
 **   it, so no table exists only on paper;
 ** - it is either seeded (feature settings: FeatureSettingsDefaults, from the
 **   configuration at run time) or listed here with why it starts empty;
 ** - it has bundle help that says what it is for and how its rows get created.
 ** A new table fails this test until it is wired, seeded or explained.
 *******************************************************************************/
class CollaborationTablesGuardrailTest extends PersonalServiceFixture
{
   private static final Path MAIN = Path.of("src", "main", "java", "com", "kingsrook", "qbits", "collaboration");

   /*******************************************************************************
    ** Seeded from the code configuration by "Create rows from configuration".
    *******************************************************************************/
   static final List<String> SEEDED = List.of("collaboration_feature_setting");

   /*******************************************************************************
    ** Tables that legitimately start empty, and where their rows come from.
    *******************************************************************************/
   static final Map<String, String> STARTS_EMPTY = Map.ofEntries(
      Map.entry("collaboration_user", "runtime: filled as signed-in people use collaboration services"),
      Map.entry("collaboration_favorite", "user content: a person stars something"),
      Map.entry("collaboration_recent", "runtime: a person opens a record"),
      Map.entry("collaboration_attachment", "user content: a person uploads a file"),
      Map.entry("collaboration_comment", "user content: a person comments"),
      Map.entry("collaboration_comment_revision", "runtime: a comment is edited"),
      Map.entry("collaboration_mention", "runtime: a comment mentions someone"),
      Map.entry("collaboration_follow", "user content: a person follows a record"),
      Map.entry("collaboration_follow_digest", "runtime: a followed record changes"),
      Map.entry("collaboration_activity", "runtime: collaboration features are used"),
      Map.entry("collaboration_tag", "user content: a person or administrator adds a tag"),
      Map.entry("collaboration_record_tag", "user content: a person tags a record"),
      Map.entry("collaboration_field_definition", "administrator content: a field manager defines a custom field"),
      Map.entry("collaboration_field_value", "user content: a person fills in a custom field"),
      Map.entry("collaboration_share", "user content: a person shares a record"),
      Map.entry("collaboration_access_request", "user content: a person asks for access"),
      Map.entry("collaboration_task", "user content: a person adds a task"),
      Map.entry("collaboration_record_assignment", "user content: a person assigns a record"));



   /*******************************************************************************
    ** Every table is used by runtime code.
    *******************************************************************************/
   @Test
   void everyTableIsUsedAtRuntime() throws Exception
   {
      Map<String, String> constantByTable = constantByTable();
      List<String>        runtimeSource;
      try(Stream<Path> files = Files.walk(MAIN))
      {
         runtimeSource = files.filter(p -> p.toString().endsWith(".java"))
            .filter(p -> !p.startsWith(MAIN.resolve("model")) && !p.startsWith(MAIN.resolve("metadata")) && !p.startsWith(MAIN.resolve("app"))
               && !p.getFileName().toString().equals("CoreCollaboration.java") && !p.getFileName().toString().equals("CollaborationSchema.java"))
            .map(CollaborationTablesGuardrailTest::read).toList();
      }
      for(String table : collaborationTables())
      {
         String constant = constantByTable.get(table);
         assertThat(constant).as("constant for " + table).isNotNull();
         Pattern use = Pattern.compile("CollaborationTables\\." + constant + "\\b");
         assertThat(runtimeSource.stream().anyMatch(source -> use.matcher(source).find()))
            .as(table + " (" + constant + ") must be read or written by runtime code, not only declared").isTrue();
      }
   }



   /*******************************************************************************
    ** Every table is seeded or explained.
    *******************************************************************************/
   @Test
   void everyTableIsSeededOrExplained() throws Exception
   {
      for(String table : collaborationTables())
      {
         boolean seeded = SEEDED.contains(table);
         assertThat(seeded || STARTS_EMPTY.containsKey(table)).as(table + " must be seeded or listed in STARTS_EMPTY").isTrue();
         assertThat(seeded && STARTS_EMPTY.containsKey(table)).as(table + " cannot be both seeded and listed as starting empty").isFalse();
      }
      assertThat(STARTS_EMPTY.keySet()).allMatch(table -> instance.getTable(table) != null, "STARTS_EMPTY names only real tables");

      ///////////////////////////////////////////////////////////////////////////////////////
      // the seeded table has a real action that writes its rows, and it is named in help //
      ///////////////////////////////////////////////////////////////////////////////////////
      assertThat(instance.getProcess(FeatureSettingsDefaultsProcess.NAME).getTableName()).isEqualTo("collaboration_feature_setting");
      assertThat(FeatureSettingsDefaults.configuredRows(instance, config)).isNotEmpty();
      assertThat(help("collaboration_feature_setting").getContent()).contains(instance.getProcess(FeatureSettingsDefaultsProcess.NAME).getLabel());
   }



   /*******************************************************************************
    ** Every table and the app have bundle help that says how rows get created.
    *******************************************************************************/
   @Test
   void everyTableHasEmptyStateHelp() throws Exception
   {
      for(String table : collaborationTables())
      {
         assertThat(help(table).getContent()).as(table + " help says how rows get created").contains("How rows get created");
      }
      assertThat(HelpBundles.of(instance).getBundles()).singleElement().satisfies(bundle ->
      {
         assertThat(bundle.getId()).isEqualTo("qbit-collaboration");
         assertThat(bundle.getVersion()).isEqualTo("0.1.0-SNAPSHOT");
         assertThat(bundle.getEntries()).anyMatch(entry -> entry.getKey().equals("app:collaboration;slot:help"));
      });
   }



   /*******************************************************************************
    ** Registration works without a help table; core owns the guarded startup sync.
    *******************************************************************************/
   @Test
   void registrationWithoutHelpTable()
   {
      assertThat(instance.getTable("helpContent")).isNull();
      HelpBundles.syncOnStartup(instance);
      assertThat(HelpBundles.of(instance).getBundles()).hasSize(1);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private HelpBundleEntry help(String table)
   {
      return (HelpBundles.of(instance).getBundles().getFirst().getEntries().stream()
         .filter(entry -> entry.getKey().equals("table:" + table + ";slot:help")).findFirst().orElseThrow());
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private List<String> collaborationTables()
   {
      List<String> tables = instance.getTables().values().stream().map(QTableMetaData::getName).filter(n -> n.startsWith("collaboration_")).sorted().toList();
      assertThat(tables).hasSize(19);
      return (tables);
   }



   /*******************************************************************************
    ** CollaborationTables declares X = "collaboration_x".
    *******************************************************************************/
   private static Map<String, String> constantByTable() throws Exception
   {
      Map<String, String> rs = new HashMap<>();
      Matcher             m  = Pattern.compile("String\\s+([A-Z_]+)\\s*=\\s*\"(collaboration_[a-z_]+)\"").matcher(read(MAIN.resolve("model").resolve("CollaborationTables.java")));
      while(m.find())
      {
         rs.put(m.group(2), m.group(1));
      }
      return (rs);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static String read(Path path)
   {
      try
      {
         return (Files.readString(path, StandardCharsets.UTF_8));
      }
      catch(Exception e)
      {
         throw (new IllegalStateException(e));
      }
   }

}
