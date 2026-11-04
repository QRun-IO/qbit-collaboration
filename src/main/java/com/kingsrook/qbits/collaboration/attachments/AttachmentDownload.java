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

import java.net.URL;
import java.util.Objects;

/*******************************************************************************
 ** A download destination may require the application's authenticated transport.
 ** The URL is sensitive and never attests that bytes were served.
 *******************************************************************************/
public record AttachmentDownload(URL url, boolean authenticated)
{
   /***************************************************************************
    ** Only ordinary HTTP destinations are eligible for attachment delivery.
    ***************************************************************************/
   public AttachmentDownload
   {
      Objects.requireNonNull(url, "url");
      if((!"https".equalsIgnoreCase(url.getProtocol()) && !"http".equalsIgnoreCase(url.getProtocol()))
         || url.getUserInfo() != null || url.getRef() != null || url.getHost() == null || url.getHost().isBlank())
      {
         throw new IllegalArgumentException("Attachment download requires HTTP or HTTPS");
      }
   }

   /***************************************************************************
    ** Never disclose storage signatures through ordinary object logging.
    ***************************************************************************/
   @Override
   public String toString()
   {
      return ("AttachmentDownload[authenticated=" + authenticated + ", url=<redacted>]");
   }
}
