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


import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import com.kingsrook.qbits.collaboration.app.CollaborationHome;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.MultiStatisticsData;


/*******************************************************************************
 ** Test view of the home KPI widget: statistic label to value, and group subheaders.
 *******************************************************************************/
public final class HomeWidgetData
{
   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private HomeWidgetData()
   {
   }



   /*******************************************************************************
    ** Every statistic on the KPI widget, by label.
    *******************************************************************************/
   public static Map<String, Integer> stats(Instant now) throws QException
   {
      Map<String, Integer> result = new LinkedHashMap<>();
      CollaborationHome.kpis(now).getStatisticsGroupData().forEach(group -> group.getStatisticList().forEach(stat -> result.put(stat.getLabel(), stat.getValue())));
      return (result);
   }



   /*******************************************************************************
    ** The subheader of the named KPI group.
    *******************************************************************************/
   public static String subheader(MultiStatisticsData data, String header)
   {
      return (data.getStatisticsGroupData().stream().filter(group -> header.equals(group.getHeader())).findFirst().orElseThrow().getSubheader());
   }
}
