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

import java.util.Locale;
import java.util.Set;

/*******************************************************************************
 ** What an uploaded object is allowed to tell a browser. The declared media type
 ** is client-controlled: it is kept as display metadata, but the object itself is
 ** stored with an inert type and an inline disposition only when the type is a
 ** known passive image, PDF, plain-text, audio or video format. Anything else (HTML, SVG,
 ** XML, script, unknown) is stored as an opaque attachment, so a signed storage
 ** link can never render or execute uploaded active content inline.
 *******************************************************************************/
final class AttachmentMediaPolicy
{
   static final String OPAQUE = "application/octet-stream";

   private static final Set<String> PASSIVE_INLINE = Set.of(
      "image/png", "image/jpeg", "image/gif", "image/webp", "application/pdf", "text/plain",
      "audio/mpeg", "audio/mp4", "audio/ogg", "audio/wav", "audio/webm",
      "video/mp4", "video/webm", "video/ogg");

   /***************************************************************************
    ** Static policy only.
    ***************************************************************************/
   private AttachmentMediaPolicy()
   {
   }



   /***************************************************************************
    ** The base media type of a declared value: lowercase, without parameters.
    ** Anything that is not a plain type/subtype token is treated as unknown.
    ***************************************************************************/
   static String baseType(String declared)
   {
      if(declared == null)
      {
         return (OPAQUE);
      }
      String base = declared.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
      return (base.matches("[a-z0-9][a-z0-9!#$&^_.+-]{0,126}/[a-z0-9][a-z0-9!#$&^_.+-]{0,126}") ? base : OPAQUE);
   }



   /***************************************************************************
    ** The media type the stored object will be served with.
    ***************************************************************************/
   static String storedType(String declared)
   {
      String base = baseType(declared);
      return (PASSIVE_INLINE.contains(base) ? base : OPAQUE);
   }



   /***************************************************************************
    ** Inline only for passive types; every other object is an attachment.
    ***************************************************************************/
   static String disposition(String declared)
   {
      return (PASSIVE_INLINE.contains(baseType(declared)) ? "inline" : "attachment");
   }
}
