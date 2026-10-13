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


import java.util.List;
import com.kingsrook.qbits.collaboration.permissions.CollaborationPermissions;
import com.kingsrook.qqq.backend.core.actions.permissions.CustomPermissionChecker;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.actions.AbstractActionInput;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.MetaDataWithPermissionRules;
import com.kingsrook.qqq.backend.core.model.metadata.security.QSecurityKeyType;
import com.kingsrook.qqq.backend.core.model.metadata.security.RecordSecurityLock;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.model.session.QSystemUserSession;


/*******************************************************************************
 ** Personal tables require no collaboration.view grant: NOT_PROTECTED plus a
 ** READ_AND_WRITE owner lock lets every signed-in user manage their own rows.
 ** Administrators receive the all-access key. The permission checker initializes
 ** keys before enforced core actions (RC.5 has no pre-query customizer); services
 ** and the home also ensure keys, like Alerts. Hosts may call applyToSession
 ** after authentication. System sessions are left untouched.
 *******************************************************************************/
public class CollaborationSecurity implements CustomPermissionChecker
{
   public static final String KEY_USER = "collaboration_user";
   public static final String KEY_USER_ALL_ACCESS = "collaboration_user_all_access";



   /*******************************************************************************
    ** Register the owner key and its administrator override.
    *******************************************************************************/
   public static List<QSecurityKeyType> buildSecurityKeyTypes()
   {
      return (List.of(new QSecurityKeyType().withName(KEY_USER).withAllAccessKeyName(KEY_USER_ALL_ACCESS)));
   }



   /*******************************************************************************
    ** Apply ownership to reads and writes, including changes to the owner field.
    *******************************************************************************/
   public static RecordSecurityLock ownerLock(String fieldName)
   {
      return (new RecordSecurityLock().withSecurityKeyType(KEY_USER).withFieldName(fieldName)
         .withLockScope(RecordSecurityLock.LockScope.READ_AND_WRITE));
   }



   /*******************************************************************************
    ** Replace our keys so a changed user or revoked admin permission cannot linger.
    *******************************************************************************/
   public static void applyToSession(QInstance instance, QSession session)
   {
      if(instance == null || session == null || session instanceof QSystemUserSession)
      {
         return;
      }
      if(session.getSecurityKeyValues() != null)
      {
         session.getSecurityKeyValues().remove(KEY_USER);
         session.getSecurityKeyValues().remove(KEY_USER_ALL_ACCESS);
      }
      String userId = userId(session);
      if(userId != null && !userId.isBlank())
      {
         session.withSecurityKeyValue(KEY_USER, userId);
         if(session.hasPermission(CollaborationPermissions.ADMIN))
         {
            session.withSecurityKeyValue(KEY_USER_ALL_ACCESS, true);
         }
      }
   }



   /*******************************************************************************
    ** Cheaply refresh the two keys on every entry point; no metadata scan needed.
    *******************************************************************************/
   public static void ensureSessionKeys(QInstance instance, QSession session)
   {
      applyToSession(instance, session);
   }



   /*******************************************************************************
    ** Authentication's stable user reference, never a display name or session id.
    *******************************************************************************/
   public static String userId(QSession session)
   {
      return (session == null || session.getUser() == null ? null : session.getUser().getIdReference());
   }



   /*******************************************************************************
    ** Initialize before direct query/get/count/aggregate/insert/update/delete.
    *******************************************************************************/
   @Override
   public void checkPermissionsThrowing(AbstractActionInput input, MetaDataWithPermissionRules metadata) throws QPermissionDeniedException
   {
      QSession session = QContext.getQSession();
      ensureSessionKeys(QContext.getQInstance(), session);
      if(!(session instanceof QSystemUserSession) && (userId(session) == null || userId(session).isBlank()))
      {
         throw (new QPermissionDeniedException("Sign in to manage favorites and recents."));
      }
   }
}
