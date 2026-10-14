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
import java.util.function.Function;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.qbits.QBitConfig;
import com.kingsrook.qqq.backend.core.utils.StringUtils;


/*******************************************************************************
 ** Host configuration for the Collaboration qbit.
 *******************************************************************************/
public class CollaborationQBitConfig implements QBitConfig
{
   public static final String TABLE_NAME_PREFIX = "collaboration_";
   public static final String PERMISSION_BASE_NAME = "collaboration";

   private QCodeReference notifier = null;
   private Function<String, String> recipientEmailResolver = null;
   private QCodeReference userSessionSupplier = null;
   private String defaultTagMode = "FREE";
   private List<String> tagActionTables = List.of();
   private List<String> followActionTables = List.of();
   private List<String> followChangeTables = List.of();
   private String frontendBaseUrl;
   private QCodeReference permissionHolderDirectory;
   private List<String> assignActionTables = List.of();
   private String backendName;
   private String schedulerName;
   private String appName = "collaboration";
   private String parentAppName;
   private Boolean adminApp = true;
   private Integer recentsPerUser = 200;
   private String attachmentsBucketName = "collaboration";
   private Long defaultMaxBytes = 100L * 1024 * 1024;
   private List<String> blockedExtensions = List.of();



   /*******************************************************************************
    ** Getter for backendName.
    *******************************************************************************/
   public String getBackendName()
   {
      return (backendName);
   }



   /*******************************************************************************
    ** Setter for backendName.
    *******************************************************************************/
   public void setBackendName(String backendName)
   {
      this.backendName = backendName;
   }



   /*******************************************************************************
    ** Fluent setter for backendName.
    *******************************************************************************/
   public CollaborationQBitConfig withBackendName(String backendName)
   {
      this.backendName = backendName;
      return (this);
   }



   /*******************************************************************************
    ** Getter for schedulerName.
    *******************************************************************************/
   public String getSchedulerName()
   {
      return (schedulerName);
   }



   /*******************************************************************************
    ** Setter for schedulerName.
    *******************************************************************************/
   public void setSchedulerName(String schedulerName)
   {
      this.schedulerName = schedulerName;
   }



   /*******************************************************************************
    ** Fluent setter for schedulerName.
    *******************************************************************************/
   public CollaborationQBitConfig withSchedulerName(String schedulerName)
   {
      this.schedulerName = schedulerName;
      return (this);
   }



   /*******************************************************************************
    ** Getter for appName.
    *******************************************************************************/
   public String getAppName()
   {
      return (appName);
   }



   /*******************************************************************************
    ** Setter for appName.
    *******************************************************************************/
   public void setAppName(String appName)
   {
      this.appName = appName;
   }



   /*******************************************************************************
    ** Fluent setter for appName.
    *******************************************************************************/
   public CollaborationQBitConfig withAppName(String appName)
   {
      this.appName = appName;
      return (this);
   }



   /*******************************************************************************
    ** Getter for parentAppName.
    *******************************************************************************/
   public String getParentAppName()
   {
      return (parentAppName);
   }



   /*******************************************************************************
    ** Setter for parentAppName.
    *******************************************************************************/
   public void setParentAppName(String parentAppName)
   {
      this.parentAppName = parentAppName;
   }



   /*******************************************************************************
    ** Fluent setter for parentAppName.
    *******************************************************************************/
   public CollaborationQBitConfig withParentAppName(String parentAppName)
   {
      this.parentAppName = parentAppName;
      return (this);
   }



   /*******************************************************************************
    ** Getter for adminApp.
    *******************************************************************************/
   public Boolean getAdminApp()
   {
      return (adminApp);
   }



   /*******************************************************************************
    ** Setter for adminApp.
    *******************************************************************************/
   public void setAdminApp(Boolean adminApp)
   {
      this.adminApp = adminApp;
   }



   /*******************************************************************************
    ** Fluent setter for adminApp.
    *******************************************************************************/
   public CollaborationQBitConfig withAdminApp(Boolean adminApp)
   {
      this.adminApp = adminApp;
      return (this);
   }



   /*******************************************************************************
    ** Maximum retained recent targets per user.
    *******************************************************************************/
   public Integer getRecentsPerUser()
   {
      return (recentsPerUser);
   }



   /*******************************************************************************
    ** Configure retention; validated when the producer is installed.
    *******************************************************************************/
   public void setRecentsPerUser(Integer recentsPerUser)
   {
      this.recentsPerUser = recentsPerUser;
   }



   /*******************************************************************************
    ** Fluent retention configuration.
    *******************************************************************************/
   public CollaborationQBitConfig withRecentsPerUser(Integer recentsPerUser)
   {
      setRecentsPerUser(recentsPerUser);
      return (this);
   }



   /*******************************************************************************
    ** Validate the configured backend and app placement.
    *******************************************************************************/
   @Override
   public void validate(QInstance instance, List<String> errors)
   {
      if(assertCondition(StringUtils.hasContent(backendName), "backendName is required for Collaboration", errors))
      {
         assertCondition(instance.getBackend(backendName) != null, "Unknown Collaboration backend: " + backendName, errors);
      }
      assertCondition(List.of("FREE", "APPROVED_ONLY").contains(defaultTagMode == null ? "" : defaultTagMode), "defaultTagMode must be FREE or APPROVED_ONLY", errors);
      for(List<String> tables : List.of(followActionTables, followChangeTables, tagActionTables, assignActionTables))
      {
         for(String table : tables)
         {
            assertCondition(table != null && !table.startsWith(TABLE_NAME_PREFIX) && instance.getTable(table) != null
               && instance.getTable(table).getPrimaryKeyField() != null, "Follow configuration needs a host table with a primary key: " + table, errors);
         }
      }
      assertCondition(StringUtils.hasContent(attachmentsBucketName), "attachmentsBucketName is required", errors);
      assertCondition(defaultMaxBytes != null && defaultMaxBytes > 0, "defaultMaxBytes must be positive", errors);
      assertCondition(blockedExtensions != null && blockedExtensions.stream().allMatch(value -> value != null && !value.isBlank()), "blockedExtensions must contain nonblank extensions", errors);
      assertCondition(recentsPerUser != null && recentsPerUser > 0, "recentsPerUser must be positive", errors);
      if(schedulerName != null)
      {
         assertCondition(instance.getScheduler(schedulerName) != null, "Unknown Collaboration scheduler: " + schedulerName, errors);
      }
      if(Boolean.TRUE.equals(adminApp))
      {
         assertCondition(StringUtils.hasContent(appName), "appName is required when adminApp is enabled", errors);
         if(parentAppName != null)
         {
            assertCondition(instance.getApp(parentAppName) != null, "Unknown Collaboration parent app: " + parentAppName, errors);
         }
      }
   }



   /*******************************************************************************
    ** Default backend for the qbit tables.
    *******************************************************************************/
   @Override
   public String getDefaultBackendNameForTables()
   {
      return (backendName);
   }


   /*******************************************************************************
    ** Getter for attachmentsBucketName.
    *******************************************************************************/
   public String getAttachmentsBucketName()
   {
      return (attachmentsBucketName);
   }


   /*******************************************************************************
    ** Setter for attachmentsBucketName.
    *******************************************************************************/
   public void setAttachmentsBucketName(String attachmentsBucketName)
   {
      this.attachmentsBucketName = attachmentsBucketName;
   }


   /*******************************************************************************
    ** Fluent setter for attachmentsBucketName.
    *******************************************************************************/
   public CollaborationQBitConfig withAttachmentsBucketName(String attachmentsBucketName)
   {
      setAttachmentsBucketName(attachmentsBucketName);
      return (this);
   }


   /*******************************************************************************
    ** Getter for defaultMaxBytes.
    *******************************************************************************/
   public Long getDefaultMaxBytes()
   {
      return (defaultMaxBytes);
   }


   /*******************************************************************************
    ** Setter for defaultMaxBytes.
    *******************************************************************************/
   public void setDefaultMaxBytes(Long defaultMaxBytes)
   {
      this.defaultMaxBytes = defaultMaxBytes;
   }


   /*******************************************************************************
    ** Fluent setter for defaultMaxBytes.
    *******************************************************************************/
   public CollaborationQBitConfig withDefaultMaxBytes(Long defaultMaxBytes)
   {
      setDefaultMaxBytes(defaultMaxBytes);
      return (this);
   }


   /*******************************************************************************
    ** Getter for blockedExtensions.
    *******************************************************************************/
   public List<String> getBlockedExtensions()
   {
      return (blockedExtensions);
   }


   /*******************************************************************************
    ** Setter for blockedExtensions.
    *******************************************************************************/
   public void setBlockedExtensions(List<String> blockedExtensions)
   {
      this.blockedExtensions = blockedExtensions;
   }


   /*******************************************************************************
    ** Fluent setter for blockedExtensions.
    *******************************************************************************/
   public CollaborationQBitConfig withBlockedExtensions(List<String> blockedExtensions)
   {
      setBlockedExtensions(blockedExtensions);
      return (this);
   }

   /*******************************************************************************
    ** Get notifier. Null (the default) means: email through qbit-email when it is
    ** produced in the instance, otherwise no delivery transport.
    *******************************************************************************/
   public QCodeReference getNotifier()
   {
      return (notifier);
   }

   /*******************************************************************************
    ** Get the user id to email address lookup used by the email notifier.
    *******************************************************************************/
   public Function<String, String> getRecipientEmailResolver()
   {
      return (recipientEmailResolver);
   }

   /*******************************************************************************
    ** Configure how the email notifier finds a recipient address from a user id.
    ** Null (the default) uses the email stored in the collaboration people directory.
    *******************************************************************************/
   public CollaborationQBitConfig withRecipientEmailResolver(Function<String, String> recipientEmailResolver)
   {
      this.recipientEmailResolver = recipientEmailResolver;
      return (this);
   }

   /*******************************************************************************
    ** Configure notifier.
    *******************************************************************************/
   public CollaborationQBitConfig withNotifier(QCodeReference notifier)
   {
      this.notifier = notifier;
      return (this);
   }

   /*******************************************************************************
    ** Get userSessionSupplier.
    *******************************************************************************/
   public QCodeReference getUserSessionSupplier()
   {
      return (userSessionSupplier);
   }

   /*******************************************************************************
    ** Configure userSessionSupplier.
    *******************************************************************************/
   public CollaborationQBitConfig withUserSessionSupplier(QCodeReference userSessionSupplier)
   {
      this.userSessionSupplier = userSessionSupplier;
      return (this);
   }

   /*******************************************************************************
    ** Get followActionTables.
    *******************************************************************************/
   public List<String> getFollowActionTables()
   {
      return (followActionTables);
   }

   /*******************************************************************************
    ** Configure followActionTables.
    *******************************************************************************/
   public CollaborationQBitConfig withFollowActionTables(List<String> followActionTables)
   {
      this.followActionTables = followActionTables;
      return (this);
   }

   /*******************************************************************************
    ** Get followChangeTables.
    *******************************************************************************/
   public List<String> getFollowChangeTables()
   {
      return (followChangeTables);
   }

   /*******************************************************************************
    ** Configure followChangeTables.
    *******************************************************************************/
   public CollaborationQBitConfig withFollowChangeTables(List<String> followChangeTables)
   {
      this.followChangeTables = followChangeTables;
      return (this);
   }

   /*******************************************************************************
    ** Default vocabulary policy when no persisted override supplies a mode.
    *******************************************************************************/
   public String getDefaultTagMode()
   {
      return (defaultTagMode);
   }

   /*******************************************************************************
    ** Configure the default tag policy.
    *******************************************************************************/
   public CollaborationQBitConfig withDefaultTagMode(String mode)
   {
      this.defaultTagMode = mode;
      return (this);
   }

   /*******************************************************************************
    ** Host tables receiving Add tags and Remove tags actions.
    *******************************************************************************/
   public List<String> getTagActionTables()
   {
      return (tagActionTables);
   }

   /*******************************************************************************
    ** Configure host tag actions.
    *******************************************************************************/
   public CollaborationQBitConfig withTagActionTables(List<String> tables)
   {
      this.tagActionTables = tables;
      return (this);
   }

   /*******************************************************************************
    ** Host configuration for frontendBaseUrl.
    *******************************************************************************/
   public String getFrontendBaseUrl()
   {
      return (frontendBaseUrl);
   }

   /*******************************************************************************
    ** Configure frontendBaseUrl.
    *******************************************************************************/
   public CollaborationQBitConfig withFrontendBaseUrl(String value)
   {
      this.frontendBaseUrl = value;
      return (this);
   }

   /*******************************************************************************
    ** Host configuration for permissionHolderDirectory.
    *******************************************************************************/
   public QCodeReference getPermissionHolderDirectory()
   {
      return (permissionHolderDirectory);
   }

   /*******************************************************************************
    ** Configure permissionHolderDirectory.
    *******************************************************************************/
   public CollaborationQBitConfig withPermissionHolderDirectory(QCodeReference value)
   {
      this.permissionHolderDirectory = value;
      return (this);
   }

   /*******************************************************************************
    ** Host configuration for assignActionTables.
    *******************************************************************************/
   public List<String> getAssignActionTables()
   {
      return (assignActionTables);
   }

   /*******************************************************************************
    ** Configure assignActionTables.
    *******************************************************************************/
   public CollaborationQBitConfig withAssignActionTables(List<String> value)
   {
      this.assignActionTables = value;
      return (this);
   }
}
