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


package com.kingsrook.qbits.collaboration.attachments.uploadjournal;


import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;


/*******************************************************************************
 ** Immutable, bounded manifest of a retained upload, assembled by the server
 ** (AttachmentsService) from the authorized source, the actual session actor,
 ** the measured size/hash and the chosen storage coordinates. Nothing here is a
 ** client-supplied path or journal field. All members are immutable values.
 **
 ** Only retained uploads are journaled; a non-retained candidate is rejected.
 ** The bucket and key are needed for recovery but are deliberately absent from
 ** toString and from the QQQ table metadata; the journal exposes only their hash.
 *******************************************************************************/
public record UploadCandidate(String tableName, String recordId, String actorId, String fileName, String contentType, String description,
                              Long sizeBytes, String sha256, String bucketName, String storageKey, Boolean retained,
                              String intakeKey, String intakeFingerprint)
{
   public static final long MAX_SIZE_BYTES = 1L << 40;
   private static final String HEX_64 = "[0-9a-f]{64}";



   /*******************************************************************************
    ** Validate every member. Failures are programming errors in the trusted
    ** caller, so they are unchecked and never echo the offending value.
    *******************************************************************************/
   public UploadCandidate
   {
      tableName = text("tableName", tableName, 128, true, false);
      recordId = text("recordId", recordId, 64, true, false);
      actorId = text("actorId", actorId, 255, true, false);
      fileName = text("fileName", fileName, 255, true, false);
      contentType = text("contentType", contentType, 255, true, false);
      description = text("description", description, 4096, false, true);
      bucketName = text("bucketName", bucketName, 255, true, false);
      storageKey = text("storageKey", storageKey, 1024, true, false);
      if(sizeBytes == null || sizeBytes < 0 || sizeBytes > MAX_SIZE_BYTES)
      {
         throw (new IllegalArgumentException("sizeBytes is out of range."));
      }
      if(sha256 == null || !sha256.matches(HEX_64))
      {
         throw (new IllegalArgumentException("sha256 must be a lowercase SHA-256 hex digest."));
      }
      if(!Boolean.TRUE.equals(retained))
      {
         throw (new IllegalArgumentException("Only retained uploads are journaled."));
      }
      if((intakeKey == null) != (intakeFingerprint == null)
         || (intakeKey != null && (!intakeKey.matches(HEX_64) || !intakeFingerprint.matches(HEX_64))))
      {
         throw (new IllegalArgumentException("intakeKey and intakeFingerprint must be supplied together as SHA-256 identifiers."));
      }
   }



   /*******************************************************************************
    ** SHA-256 of the length-prefixed bucket and key; the journal's unique identity
    ** for one object location.
    *******************************************************************************/
   public String locatorHash()
   {
      return (locatorHash(bucketName, storageKey));
   }



   /*******************************************************************************
    ** SHA-256 of the length-prefixed bucket and key.
    *******************************************************************************/
   public static String locatorHash(String bucketName, String storageKey)
   {
      return (tupleHash("upload-journal-locator-v1", bucketName, storageKey));
   }



   /*******************************************************************************
    ** SHA-256 over the complete manifest. A row that shares a locator or intake
    ** key but not this hash is an immutable conflict.
    *******************************************************************************/
   public String manifestHash()
   {
      return (tupleHash("upload-journal-manifest-v1", tableName, recordId, actorId, fileName, contentType, description,
         String.valueOf(sizeBytes), sha256, bucketName, storageKey, String.valueOf(retained), intakeKey, intakeFingerprint));
   }



   /*******************************************************************************
    ** Never prints the bucket, key, description or actor.
    *******************************************************************************/
   @Override
   public String toString()
   {
      return ("UploadCandidate[source=" + tableName + "/" + recordId + ", sizeBytes=" + sizeBytes + ", locatorHash=" + locatorHash() + "]");
   }



   /*******************************************************************************
    ** Bounded text without control characters (multi-line allows CR, LF, tab).
    *******************************************************************************/
   private static String text(String name, String value, int maximumLength, boolean required, boolean multiLine)
   {
      if(value == null)
      {
         if(required)
         {
            throw (new IllegalArgumentException(name + " is required."));
         }
         return (null);
      }
      if((required && value.isBlank()) || value.length() > maximumLength)
      {
         throw (new IllegalArgumentException(name + " is blank or exceeds " + maximumLength + " characters."));
      }
      for(int i = 0; i < value.length(); i++)
      {
         char c = value.charAt(i);
         if(Character.isHighSurrogate(c))
         {
            if(i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++i)))
            {
               throw (new IllegalArgumentException(name + " contains malformed Unicode."));
            }
            continue;
         }
         if(Character.isLowSurrogate(c))
         {
            throw (new IllegalArgumentException(name + " contains malformed Unicode."));
         }
         if(Character.isISOControl(c) && !(multiLine && (c == '\n' || c == '\r' || c == '\t')))
         {
            throw (new IllegalArgumentException(name + " contains a control character."));
         }
      }
      return (value);
   }



   /*******************************************************************************
    ** Length-prefix every member, including null, so delimiters cannot collide.
    *******************************************************************************/
   private static String tupleHash(String... values)
   {
      try
      {
         ByteArrayOutputStream buffer = new ByteArrayOutputStream();
         try(DataOutputStream output = new DataOutputStream(buffer))
         {
            for(String value : values)
            {
               byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
               output.writeInt(value == null ? -1 : bytes.length);
               output.write(bytes);
            }
         }
         return (HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(buffer.toByteArray())));
      }
      catch(IOException | NoSuchAlgorithmException e)
      {
         throw (new IllegalStateException("Could not hash the upload manifest.", e));
      }
   }
}
