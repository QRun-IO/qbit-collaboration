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


package com.kingsrook.qbits.collaboration.permissions;


import com.kingsrook.qbits.collaboration.CollaborationQBitConfig;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.PermissionLevel;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.QPermissionRules;


/*******************************************************************************
 ** Full permission names; fields management is reserved for a later phase.
 *******************************************************************************/
public final class CollaborationPermissions
{
   public static final String VIEW = "collaboration.view";
   public static final String ADMIN = "collaboration.admin";
   public static final String FIELDS_MANAGE = "collaboration.fields.manage";
   public static final String TAGS_MANAGE = "collaboration.tags.manage";



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private CollaborationPermissions()
   {
   }



   /*******************************************************************************
    ** Shared permission checker for tables, app and home.
    *******************************************************************************/
   public static QPermissionRules permissionRules()
   {
      return (new QPermissionRules().withLevel(PermissionLevel.HAS_ACCESS_PERMISSION)
         .withPermissionBaseName(CollaborationQBitConfig.PERMISSION_BASE_NAME)
         .withCustomPermissionChecker(new QCodeReference(CollaborationPermissionChecker.class)));
   }



}
