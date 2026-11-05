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

import com.kingsrook.qbits.redis.api.RedisNamespace;
import com.kingsrook.qqq.backend.core.actions.customizers.QCodeLoader;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.realtime.RealtimeAudienceProvider;

/*******************************************************************************
 ** Presence hints are visible only within the authenticated storage namespace.
 *******************************************************************************/
public class RedisPresenceAudience implements RealtimeAudienceProvider
{
   public static final String EVENT = "presence.invalidated";

   /***************************************************************************
    ** Recompute recipient scope on every delivery and replay; never trust the hint.
    ***************************************************************************/
   @Override
   public boolean allows(String audience) throws Exception
   {
      var config = RedisPresenceConfig.forInstance(QContext.getQInstance());
      return (config != null && namespace(config).key("audience").equals(audience));
   }

   /***************************************************************************
    ** Storage and delivery must derive exactly the same host-owned tenant scope.
    ***************************************************************************/
   public static RedisNamespace namespace(RedisPresenceConfig config) throws Exception
   {
      var scope = QCodeLoader.getAdHoc(PresenceScopeProvider.class, config.getScopeProvider()).tenantScope();
      return (new RedisNamespace(config.getDeployment(), config.getApplication(), scope, "presence:" + config.getRecoveryGeneration()));
   }
}
