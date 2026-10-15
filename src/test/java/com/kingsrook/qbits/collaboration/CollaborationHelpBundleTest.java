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


import com.kingsrook.qqq.backend.core.model.metadata.QBackendMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.help.bundles.HelpBundles;
import com.kingsrook.qqq.backend.core.modules.backend.implementations.memory.MemoryBackendModule;
import com.kingsrook.qqq.backend.core.testing.HelpBundleAssertions;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** Coverage of metadata produced by this qbit, before core adds its own bulk actions.
 *******************************************************************************/
class CollaborationHelpBundleTest
{
   /*******************************************************************************
    ** Every owned object resolves and has help, without host-specific providers.
    *******************************************************************************/
   @Test
   void bundleCoversProducedMetadata() throws Exception
   {
      QInstance instance = new QInstance();
      instance.addBackend(new QBackendMetaData().withName("memory").withBackendType(MemoryBackendModule.class));
      new CollaborationQBitProducer().withQBitConfig(new CollaborationQBitConfig().withBackendName("memory")).produce(instance);

      HelpBundleAssertions.forInstance(instance)
         .bundles("help/qbit-collaboration.yaml")
         .owning(target -> target.name().startsWith("collaboration"))
         .assertComplete();

      var entries = HelpBundles.of(instance).getBundles().getFirst().getEntries();
      assertThat(entries).extracting(entry -> entry.getKey()).doesNotHaveDuplicates();
      // Core's gate requires tables, fields, apps and processes; also cover our widgets and selectors.
      instance.getWidgets().keySet().forEach(name -> assertThat(entries)
         .anyMatch(entry -> entry.getKey().equals("widget:" + name + ";slot:label")));
      instance.getPossibleValueSources().keySet().stream().filter(name -> name.startsWith("collaboration")).forEach(name -> assertThat(entries)
         .anyMatch(entry -> entry.getKey().equals("possibleValueSource:" + name + ";slot:help")));
   }
}
