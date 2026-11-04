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

/*******************************************************************************
 ** Verified bytes returned only after authorization, integrity and audit checks.
 ** Storage coordinates never cross this boundary. Content is bounded to 20 MiB.
 *******************************************************************************/
public record AttachmentContent(Integer attachmentId, String fileName, String contentType, String sha256, byte[] bytes)
{
   /*******************************************************************************
    ** Own the byte array so a caller cannot change a retained receipt's content.
    *******************************************************************************/
   public AttachmentContent
   {
      if(bytes == null || bytes.length > 20 * 1024 * 1024)
      {
         throw (new IllegalArgumentException("Attachment content is missing or exceeds the supported limit."));
      }
      bytes = bytes.clone();
   }

   /*******************************************************************************
    ** Return caller-owned bytes without exposing the immutable content buffer.
    *******************************************************************************/
   @Override
   public byte[] bytes()
   {
      return (bytes.clone());
   }
}