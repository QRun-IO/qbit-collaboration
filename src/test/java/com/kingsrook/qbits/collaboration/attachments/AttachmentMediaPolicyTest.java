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

package com.kingsrook.qbits.collaboration.attachments;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/*******************************************************************************
 ** Declared media types are client-controlled; only passive formats may be
 ** served inline, everything else is an opaque attachment.
 *******************************************************************************/
class AttachmentMediaPolicyTest
{
   /***************************************************************************
    ** Active, malformed and missing declarations are opaque attachments.
    ***************************************************************************/
   @Test
   void activeMalformedAndMissingTypesAreOpaqueAttachments()
   {
      for(String declared : new String[] { "text/html", "TEXT/HTML; charset=utf-8", "image/svg+xml", "application/xhtml+xml", "text/xml", "application/xml",
         "text/javascript", "application/javascript", "weird", "", " ", "a/b/c", "text/html\r\nX-Injected: 1", null })
      {
         assertThat(AttachmentMediaPolicy.storedType(declared)).as(String.valueOf(declared)).isEqualTo("application/octet-stream");
         assertThat(AttachmentMediaPolicy.disposition(declared)).as(String.valueOf(declared)).isEqualTo("attachment");
      }
   }

   /***************************************************************************
    ** Passive raster, PDF, text and media types keep their normalized type inline.
    ***************************************************************************/
   @Test
   void passiveTypesAreNormalizedAndInline()
   {
      assertThat(AttachmentMediaPolicy.storedType("IMAGE/PNG; q=1")).isEqualTo("image/png");
      assertThat(AttachmentMediaPolicy.disposition("IMAGE/PNG; q=1")).isEqualTo("inline");
      for(String declared : new String[] { "image/jpeg", "image/gif", "image/webp", "application/pdf", "text/plain", "audio/mpeg", "video/mp4" })
      {
         assertThat(AttachmentMediaPolicy.storedType(declared)).isEqualTo(declared);
         assertThat(AttachmentMediaPolicy.disposition(declared)).isEqualTo("inline");
      }
   }
}
