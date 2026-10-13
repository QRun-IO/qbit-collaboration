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


package com.kingsrook.qbits.collaboration.api;


import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.kingsrook.qbits.collaboration.api.CollaborationSchemas.CollaborationAttachmentV1;
import com.kingsrook.qbits.collaboration.attachments.AttachmentsService;
import com.kingsrook.qbits.collaboration.security.ParentRecordAccess;
import com.kingsrook.qqq.backend.core.exceptions.QBadRequestException;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.utils.JsonUtils;
import com.kingsrook.qqq.middleware.javalin.executors.AbstractMiddlewareExecutor;
import com.kingsrook.qqq.middleware.javalin.executors.io.AbstractMiddlewareInput;
import com.kingsrook.qqq.middleware.javalin.executors.io.AbstractMiddlewareOutputInterface;
import com.kingsrook.qqq.middleware.javalin.specs.AbstractEndpointSpec;
import com.kingsrook.qqq.middleware.javalin.specs.BasicOperation;
import com.kingsrook.qqq.middleware.javalin.specs.BasicResponse;
import com.kingsrook.qqq.openapi.model.Content;
import com.kingsrook.qqq.openapi.model.HttpMethod;
import com.kingsrook.qqq.openapi.model.Parameter;
import com.kingsrook.qqq.openapi.model.RequestBody;
import com.kingsrook.qqq.openapi.model.Schema;
import io.javalin.http.Context;
import io.javalin.http.UploadedFile;


/*******************************************************************************
 ** Multipart adapter: exactly one file, streamed through the bounded attachment service.
 *******************************************************************************/
public class UploadAttachmentSpec extends AbstractEndpointSpec<UploadAttachmentSpec.UploadInput, UploadAttachmentSpec.UploadOutput, UploadAttachmentSpec.UploadExecutor>
{
   /*******************************************************************************
    ** Describe the upload operation.
    *******************************************************************************/
   @Override
   public BasicOperation defineBasicOperation()
   {
      return (CollaborationJsonSpec.operation(HttpMethod.POST, "/records/{table}/{pk}/attachments", "Upload an attachment"));
   }

   /*******************************************************************************
    ** Parent coordinates.
    *******************************************************************************/
   @Override
   public List<Parameter> defineRequestParameters()
   {
      return (CollaborationJsonSpec.parametersFor(defineBasicOperation().getPath()));
   }

   /*******************************************************************************
    ** A named binary file and optional description, not a JSON upload.
    *******************************************************************************/
   @Override
   public RequestBody defineRequestBody()
   {
      return (new RequestBody().withRequired(true).withContent(Map.of("multipart/form-data",
         new Content().withSchema(new Schema().withRefToSchema("CollaborationAttachmentUploadRequestV1")))));
   }

   /*******************************************************************************
    ** Use the same typed attachment as JSON operations.
    *******************************************************************************/
   @Override
   public BasicResponse defineBasicSuccessResponse()
   {
      return (new BasicResponse("Uploaded attachment", "CollaborationAttachmentV1"));
   }

   /*******************************************************************************
    ** Standard framework errors.
    *******************************************************************************/
   @Override
   public List<BasicResponse> defineAdditionalBasicResponses()
   {
      return (CollaborationJsonSpec.errors());
   }

   /*******************************************************************************
    ** Authenticate and check the parent before asking Javalin for uploaded parts.
    *******************************************************************************/
   @Override
   public UploadInput buildInput(Context context) throws Exception
   {
      ParentRecordAccess.requireFeature(context.pathParam("table"), context.pathParam("pk"), "attach");
      if(context.contentType() == null || !context.contentType().toLowerCase(Locale.ROOT).startsWith("multipart/form-data"))
      {
         throw (new QBadRequestException("Use multipart/form-data with one file part named file"));
      }
      List<UploadedFile> files;
      try
      {
         files = context.uploadedFiles();
      }
      catch(RuntimeException e)
      {
         throw (new QBadRequestException("Malformed multipart upload", e));
      }
      if(files.size() != 1 || context.uploadedFiles("file").size() != 1)
      {
         throw (new QBadRequestException("Exactly one file part named file is required"));
      }
      UploadInput input = new UploadInput();
      input.table = context.pathParam("table");
      input.pk = context.pathParam("pk");
      input.file = files.getFirst();
      input.description = context.formParam("description");
      return (input);
   }

   /*******************************************************************************
    ** Serialize the wire record, keeping nullable display fields.
    *******************************************************************************/
   @Override
   public void handleOutput(Context context, UploadOutput output)
   {
      context.contentType("application/json").result(JsonUtils.toJsonCustomized(output.attachment,
         builder -> builder.serializationInclusion(JsonInclude.Include.ALWAYS)));
   }

   /*******************************************************************************
    ** Request parts remain scoped to this request.
    *******************************************************************************/
   public static class UploadInput extends AbstractMiddlewareInput
   {
      private String table;
      private String pk;
      private UploadedFile file;
      private String description;
   }

   /*******************************************************************************
    ** The executor output contains only the public wire DTO.
    *******************************************************************************/
   public static class UploadOutput implements AbstractMiddlewareOutputInterface
   {
      private CollaborationAttachmentV1 attachment;
   }

   /*******************************************************************************
    ** The service enforces size, filename policy, permission, storage and cleanup.
    *******************************************************************************/
   public static class UploadExecutor extends AbstractMiddlewareExecutor<UploadInput, UploadOutput>
   {
      /*******************************************************************************
       ** Close the uploaded stream after the synchronous service call.
       *******************************************************************************/
      @Override
      public void execute(UploadInput input, UploadOutput output) throws QException
      {
         try(InputStream stream = input.file.content())
         {
            output.attachment = CollaborationViews.attachment(new AttachmentsService().upload(input.table, input.pk,
               input.file.filename(), input.file.contentType(), stream, input.description));
         }
         catch(IOException e)
         {
            throw (new QException("Could not read attachment upload", e));
         }
      }
   }
}
