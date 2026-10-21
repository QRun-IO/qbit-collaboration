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


package com.kingsrook.qbits.collaboration.presence;

import java.io.Serializable;
import java.util.List;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;

/*******************************************************************************
 ** Host configuration for shared presence, including authoritative tenant scope.
 *******************************************************************************/
public class RedisPresenceConfig implements Serializable
{
   private String connectionName = null;
   private String deployment = null;
   private String application = null;
   private QCodeReference scopeProvider = null;
   private Integer capacity = 2000;

   /***************************************************************************
    ** Inspect configuration without creating a runtime pool or local registry.
    ***************************************************************************/
   public static RedisPresenceConfig forInstance(com.kingsrook.qqq.backend.core.model.metadata.QInstance instance)
   {
      var config = instance.getQBits().values().stream().map(bit -> bit.getConfig())
         .filter(com.kingsrook.qbits.collaboration.CollaborationQBitConfig.class::isInstance)
         .map(com.kingsrook.qbits.collaboration.CollaborationQBitConfig.class::cast).findFirst().orElse(null);
      return (config == null ? null : config.getRedisPresence());
   }

   /***************************************************************************
    ** Explicit scope prevents accidental sharing between deployments or tenants.
    ***************************************************************************/
   public void validate(List<String> errors)
   {
      if(connectionName == null || connectionName.isBlank() || deployment == null || deployment.isBlank()
         || application == null || application.isBlank() || scopeProvider == null)
      {
         errors.add("Redis presence requires a named connection, deployment, application and tenant scope provider");
      }
      if(capacity == null || capacity < 1 || capacity > 10000)
      {
         errors.add("Redis presence capacity must be between 1 and 10000");
      }
   }

   /***************************************************************************
    ** Return connectionName.
    ***************************************************************************/
   public String getConnectionName()
   {
      return (connectionName);
   }

   /***************************************************************************
    ** Configure connectionName.
    ***************************************************************************/
   public RedisPresenceConfig withConnectionName(String value)
   {
      connectionName = value;
      return (this);
   }

   /***************************************************************************
    ** Return deployment.
    ***************************************************************************/
   public String getDeployment()
   {
      return (deployment);
   }

   /***************************************************************************
    ** Configure deployment.
    ***************************************************************************/
   public RedisPresenceConfig withDeployment(String value)
   {
      deployment = value;
      return (this);
   }

   /***************************************************************************
    ** Return application.
    ***************************************************************************/
   public String getApplication()
   {
      return (application);
   }

   /***************************************************************************
    ** Configure application.
    ***************************************************************************/
   public RedisPresenceConfig withApplication(String value)
   {
      application = value;
      return (this);
   }

   /***************************************************************************
    ** Return scopeProvider.
    ***************************************************************************/
   public QCodeReference getScopeProvider()
   {
      return (scopeProvider);
   }

   /***************************************************************************
    ** Configure scopeProvider.
    ***************************************************************************/
   public RedisPresenceConfig withScopeProvider(QCodeReference value)
   {
      scopeProvider = value;
      return (this);
   }

   /***************************************************************************
    ** Return capacity.
    ***************************************************************************/
   public Integer getCapacity()
   {
      return (capacity);
   }

   /***************************************************************************
    ** Configure capacity.
    ***************************************************************************/
   public RedisPresenceConfig withCapacity(Integer value)
   {
      capacity = value;
      return (this);
   }
}
