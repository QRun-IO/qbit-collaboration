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
import java.util.UUID;
import com.kingsrook.qbits.objectstorage.api.DeleteMode;
import com.kingsrook.qbits.objectstorage.api.DeleteRequest;
import com.kingsrook.qbits.objectstorage.api.ObjectStorage;
import com.kingsrook.qbits.objectstorage.api.PresignGet;
import com.kingsrook.qbits.objectstorage.api.PutRequest;
import com.kingsrook.qbits.objectstorage.api.StorageBucket;

/*******************************************************************************
 ** Narrow storage boundary. The caller must check collaboration access first.
 ** Tests can substitute an in-memory implementation without an S3 service.
 *******************************************************************************/
public class AttachmentStorage
{
   /*******************************************************************************
    ** Tag storage operations as the calling qbit, retaining the initiating user.
    *******************************************************************************/
   private StorageBucket bucket(String name)
   {
      return (ObjectStorage.asSystem("collaboration", UUID.randomUUID().toString()).bucket(name));
   }

   /*******************************************************************************
    ** The bucket's own upload cap, from object storage's api package; Long.MAX_VALUE
    ** when the bucket has no limit.  Collaboration also enforces its per-table cap.
    *******************************************************************************/
   public long maxBytes(String name)
   {
      Long max = bucket(name).maxUploadBytes();
      return (max == null ? Long.MAX_VALUE : max);
   }

   /*******************************************************************************
    ** Put a bounded stream through object storage's multipart implementation.
    *******************************************************************************/
   public void put(String name, PutRequest request)
   {
      bucket(name).put(request);
   }

   /*******************************************************************************
    ** Prefer soft deletion; allow permanent deletion only where no soft mode exists.
    *******************************************************************************/
   public void delete(String name, String key)
   {
      if(bucket(name).delete(DeleteRequest.of(key).withAllowPermanentWhenNoSoftDelete(true)).getMode() == DeleteMode.SKIPPED_OBJECT_LOCK)
      {
         throw (new IllegalStateException("Object retention prevents attachment deletion."));
      }
   }

   /*******************************************************************************
    ** Create a temporary link, never a persistent table value.
    *******************************************************************************/
   public URL presign(String name, PresignGet request)
   {
      return (bucket(name).presignGet(request));
   }
}
