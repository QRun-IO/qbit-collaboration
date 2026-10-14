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

import java.nio.file.Files;
import java.nio.file.Path;
import com.kingsrook.qbits.collaboration.api.CollaborationApi;
import com.kingsrook.qqq.backend.core.model.metadata.QBackendMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.modules.backend.implementations.memory.MemoryBackendModule;
import com.kingsrook.qqq.middleware.javalin.specs.extensions.MiddlewareSpecExtensionsMetaData;
import com.kingsrook.qqq.middleware.javalin.specs.v1.MiddlewareVersionV1;
import com.kingsrook.qqq.middleware.javalin.tools.UiApiContract;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/*******************************************************************************
 ** The committed extension fragment must describe exactly the shipped specs.
 *******************************************************************************/
class CollaborationApiContractTest
{
   /*******************************************************************************
    ** Opt-in regeneration uses the same deterministic generator as QQQ core.
    *******************************************************************************/
   @Test
   void contract() throws Exception
   {
      var extension = CollaborationApi.extension();
      var version = new MiddlewareVersionV1();
      String generated = UiApiContract.generateExtensionYaml(version, extension);
      Path path = Path.of("src/main/resources/openapi/qqq-v1/collaboration.yaml");
      if(Boolean.getBoolean("collaboration.openapi.update"))
      {
         Files.createDirectories(path.getParent());
         Files.writeString(path, generated);
      }
      assertThat(UiApiContract.check(generated, Files.readString(path), null, 1,
         "mvnw.cmd -o -q -Dtest=CollaborationApiContractTest -Dcollaboration.openapi.update=true test")).isEmpty();
      var model = version.generateExtensionOpenAPIModel("qqq", extension);
      assertThat(model.getComponents().getSchemas().keySet()).allMatch(name -> name.startsWith("Collaboration"));
      assertThat(model.getPaths().keySet()).allMatch(pathName -> pathName.startsWith("/qqq/v1/qbit/collaboration/"));
      assertThat(generated).contains("multipart/form-data", "format: \"binary\"", "clients follow url", "nextCursor");
      assertThat(extension.getSpecs()).hasSize(42);
   }

   /*******************************************************************************
    ** Hosts may opt out; default registration uses the default namespace.
    *******************************************************************************/
   @Test
   void registrationCanBeDisabled() throws Exception
   {
      QInstance disabled = new QInstance();
      disabled.addBackend(new QBackendMetaData().withName("memory").withBackendType(MemoryBackendModule.class));
      new CollaborationQBitProducer().withQBitConfig(new CollaborationQBitConfig().withBackendName("memory").withUiApi(false)).produce(disabled);
      assertThat(MiddlewareSpecExtensionsMetaData.ofOrWithNew(disabled).getExtensions()).isEmpty();
      QInstance enabled = new QInstance();
      enabled.addBackend(new QBackendMetaData().withName("memory").withBackendType(MemoryBackendModule.class));
      new CollaborationQBitProducer().withQBitConfig(new CollaborationQBitConfig().withBackendName("memory")).produce(enabled);
      assertThat(MiddlewareSpecExtensionsMetaData.ofOrWithNew(enabled).getExtensions()).hasSize(1);
      assertThat(CollaborationApi.extension().getEffectivePathPrefix()).isEqualTo("/qbit/collaboration");
   }
}
