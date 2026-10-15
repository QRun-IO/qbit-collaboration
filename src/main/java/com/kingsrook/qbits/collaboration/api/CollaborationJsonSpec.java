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


import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.kingsrook.qbits.collaboration.api.CollaborationSchemas.CollaborationMutationResultV1;
import com.kingsrook.qbits.collaboration.security.ParentRecordAccess;
import com.kingsrook.qqq.backend.core.exceptions.QBadRequestException;
import com.kingsrook.qqq.middleware.javalin.specs.BasicOperation;
import com.kingsrook.qqq.middleware.javalin.specs.BasicResponse;
import com.kingsrook.qqq.middleware.javalin.specs.extensions.AbstractJsonEndpointSpec;
import com.kingsrook.qqq.middleware.javalin.specs.v1.responses.BasicErrorResponseV1;
import com.kingsrook.qqq.openapi.model.HttpMethod;
import com.kingsrook.qqq.openapi.model.In;
import com.kingsrook.qqq.openapi.model.Parameter;
import com.kingsrook.qqq.openapi.model.Schema;
import com.kingsrook.qqq.openapi.model.Type;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;


/*******************************************************************************
 ** Common JSON transport declarations and input validation; services own authorization.
 *******************************************************************************/
abstract class CollaborationJsonSpec extends AbstractJsonEndpointSpec
{
   private static final ObjectMapper MAPPER = mapper();

   /*******************************************************************************
    ** Jackson's scalar-coercion flag alone still permits numbers as text.
    *******************************************************************************/
   private static ObjectMapper mapper()
   {
      ObjectMapper mapper = JsonMapper.builder().disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
         .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).build();
      mapper.coercionConfigFor(LogicalType.Textual)
         .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
         .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
         .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
      return (mapper);
   }

   /*******************************************************************************
    ** Check record visibility before decoding a body or paging parameters.
    *******************************************************************************/
   @Override
   public JsonInput buildInput(Context context) throws Exception
   {
      if(context.pathParamMap().containsKey("table"))
      {
         ParentRecordAccess.requireVisibleParent(context.pathParam("table"), context.pathParam("pk"));
      }
      return (super.buildInput(context));
   }

   /*******************************************************************************
    ** Extension-relative operation with semantic client documentation.
    *******************************************************************************/
   static BasicOperation operation(HttpMethod method, String path, String summary)
   {
      return (new BasicOperation().withHttpMethod(method).withPath("/qbit/collaboration" + path)
         .withShortSummary(summary).withLongDescription(summary + ". Uses the signed-in caller's permissions and record visibility."));
   }

   /*******************************************************************************
    ** Declare the framework's standard error bodies without replacing its mapping.
    *******************************************************************************/
   @Override
   public List<BasicResponse> defineAdditionalBasicResponses()
   {
      return (errors());
   }

   /*******************************************************************************
    ** Shared error contract, including the multipart operation.
    *******************************************************************************/
   static List<BasicResponse> errors()
   {
      return (List.of(new BasicResponse(HttpStatus.BAD_REQUEST, "Invalid input", BasicErrorResponseV1.class.getSimpleName()),
         new BasicResponse(HttpStatus.UNAUTHORIZED, "Sign in required", BasicErrorResponseV1.class.getSimpleName()),
         new BasicResponse(HttpStatus.FORBIDDEN, "Permission denied", BasicErrorResponseV1.class.getSimpleName()),
         new BasicResponse(HttpStatus.NOT_FOUND, "Not found (including invisible parents)", BasicErrorResponseV1.class.getSimpleName())));
   }

   /*******************************************************************************
    ** Path parameters and explicitly supported query parameters.
    *******************************************************************************/
   protected List<Parameter> parameters(String... queries)
   {
      return (parametersFor(defineBasicOperation().getPath(), queries));
   }

   /*******************************************************************************
    ** Parameter descriptions are shared with multipart upload.
    *******************************************************************************/
   static List<Parameter> parametersFor(String path, String... queries)
   {
      List<Parameter> result = new ArrayList<>();
      Matcher matcher = Pattern.compile("\\{([^}]+)}").matcher(path);
      while(matcher.find())
      {
         String name = matcher.group(1);
         result.add(parameter(name, In.PATH, true));
      }
      for(String query : queries)
      {
         result.add(parameter(query.replace("!", ""), In.QUERY, query.endsWith("!")));
      }
      return (result);
   }

   /*******************************************************************************
    ** Semantic hints for clients and command-line help.
    *******************************************************************************/
   private static Parameter parameter(String name, In location, boolean required)
   {
      String description = switch(name)
      {
         case "table" -> "Parent table metadata name.";
         case "pk" -> "Parent record primary key.";
         case "id" -> "Collaboration object identifier.";
         case "userId" -> "Stable directory user identity.";
         case "limit" -> "Maximum items to return, from 1 to 100; default 20.";
         case "cursor" -> "Opaque nextCursor returned by the previous page; omit for the first page.";
         case "before" -> "Timeline continuation token; alias for cursor. Supply only one.";
         case "includeDone" -> "Include completed and cancelled tasks; default false.";
         case "status" -> "Task status: OPEN (default), DONE or CANCELLED.";
         case "types" -> "Comma-separated AUDIT, ACTIVITY or activity type names (for example COMMENTED).";
         case "q" -> "Text to search in display names or vocabulary.";
         default -> name;
      };
      return (new Parameter().withName(name).withIn(location).withRequired(required).withDescription(description)
         .withSchema(new Schema().withType(name.equals("limit") ? Type.INTEGER : name.equals("includeDone") ? Type.BOOLEAN : Type.STRING)));
   }

   /*******************************************************************************
    ** Reject malformed types instead of silently coercing client values.
    *******************************************************************************/
   protected static <T> T body(JsonInput input, Class<T> type) throws QBadRequestException
   {
      try
      {
         if(input.getBody() == null)
         {
            throw (new IllegalArgumentException("Object body required"));
         }
         return (MAPPER.convertValue(input.getBody(), type));
      }
      catch(IllegalArgumentException e)
      {
         throw (new QBadRequestException("Invalid " + type.getSimpleName() + " body: check the property names and types."));
      }
   }

   /*******************************************************************************
    ** Validate the shared page bound.
    *******************************************************************************/
   protected static int limit(JsonInput input) throws QBadRequestException
   {
      Integer limit = input.getQueryParamInteger("limit");
      if(limit == null)
      {
         return (20);
      }
      if(limit < 1 || limit > 100)
      {
         throw (new QBadRequestException("Limit must be between 1 and 100"));
      }
      return (limit);
   }

   /*******************************************************************************
    ** Page complete, permission-filtered service lists; recheck on every request.
    ** These cursors are offsets, not a snapshot across concurrent mutations.
    *******************************************************************************/
   protected static <T> Page<T> page(JsonInput input, List<T> values) throws QBadRequestException
   {
      int limit = limit(input);
      int offset = 0;
      String scope = input.getSpec().defineBasicOperation().getPath() + input.getPathParams().toString()
         + ":" + input.getQueryParam("table") + ":" + input.getQueryParam("pk") + ":" + input.getQueryParam("q");
      String prefix = "1:" + Integer.toHexString(scope.hashCode()) + ":";
      String cursor = input.getQueryParam("cursor");
      if(cursor != null)
      {
         try
         {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if(!decoded.startsWith(prefix))
            {
               throw (new IllegalArgumentException("Wrong cursor scope"));
            }
            offset = Integer.parseInt(decoded.substring(prefix.length()));
            if(offset < 0)
            {
               throw (new IllegalArgumentException("Negative offset"));
            }
         }
         catch(IllegalArgumentException e)
         {
            throw (new QBadRequestException("Invalid cursor"));
         }
      }
      int start = Math.min(offset, values.size());
      int end = start + Math.min(limit, values.size() - start);
      String next = end < values.size() ? Base64.getUrlEncoder().withoutPadding().encodeToString((prefix + end).getBytes(StandardCharsets.UTF_8)) : null;
      return (new Page<>(values.subList(start, end), next));
   }

   /*******************************************************************************
    ** Internal page shape before projecting the specific wire type.
    *******************************************************************************/
   protected record Page<T>(List<T> items, String nextCursor)
   {
   }

   /*******************************************************************************
    ** A required nonblank string.
    *******************************************************************************/
   protected static String required(String value, String name) throws QBadRequestException
   {
      if(value == null || value.isBlank())
      {
         throw (new QBadRequestException(name + " is required"));
      }
      return (value);
   }

   /*******************************************************************************
    ** Parent table name.
    *******************************************************************************/
   protected static String table(JsonInput input)
   {
      return (input.getPathParam("table"));
   }

   /*******************************************************************************
    ** Parent key remains a string until the service canonicalizes its field type.
    *******************************************************************************/
   protected static String pk(JsonInput input)
   {
      return (input.getPathParam("pk"));
   }

   /*******************************************************************************
    ** Collaboration tables use positive integer identifiers.
    *******************************************************************************/
   protected static Integer id(JsonInput input) throws QBadRequestException
   {
      return (positiveId(input.getPathParam("id")));
   }

   /*******************************************************************************
    ** Strict identifier parsing, also used for folder-map keys.
    *******************************************************************************/
   private static Integer positiveId(String value) throws QBadRequestException
   {
      try
      {
         int id = Integer.parseInt(value);
         if(id < 1)
         {
            throw (new NumberFormatException());
         }
         return (id);
      }
      catch(NumberFormatException e)
      {
         throw (new QBadRequestException("An identifier must be a positive integer"));
      }
   }

   /*******************************************************************************
    ** Optional add/remove lists may be empty, but never contain null identifiers.
    *******************************************************************************/
   protected static List<Integer> ids(List<Integer> values) throws QBadRequestException
   {
      if(values == null)
      {
         return (List.of());
      }
      if(values.stream().anyMatch(id -> id == null || id < 1))
      {
         throw (new QBadRequestException("Tag identifiers must be positive integers"));
      }
      return (values);
   }

   /*******************************************************************************
    ** Convert JSON object keys without trusting arbitrary numeric coercion.
    *******************************************************************************/
   protected static Map<Integer, String> folders(Map<String, String> values) throws QBadRequestException
   {
      Map<Integer, String> result = new LinkedHashMap<>();
      if(values != null)
      {
         for(Map.Entry<String, String> entry : values.entrySet())
         {
            result.put(positiveId(entry.getKey()), entry.getValue());
         }
      }
      return (result);
   }

   /*******************************************************************************
    ** Preserve the timeline's before name and the common cursor convention.
    *******************************************************************************/
   protected static String timelineCursor(JsonInput input) throws QBadRequestException
   {
      if(input.getQueryParam("before") != null && input.getQueryParam("cursor") != null)
      {
         throw (new QBadRequestException("Supply before or cursor, not both"));
      }
      return (input.getQueryParam("before") == null ? input.getQueryParam("cursor") : input.getQueryParam("before"));
   }

   /*******************************************************************************
    ** Timeline accepts source names or collaboration activity names.
    *******************************************************************************/
   protected static Set<String> types(JsonInput input)
   {
      String types = input.getQueryParam("types");
      return (types == null || types.isBlank() ? Set.of() : Arrays.stream(types.split(",")).map(String::strip).collect(Collectors.toSet()));
   }

   /*******************************************************************************
    ** Consistent acknowledgement for operations with no service return value.
    *******************************************************************************/
   protected static CollaborationMutationResultV1 success()
   {
      return (new CollaborationMutationResultV1(true));
   }

   /*******************************************************************************
    ** Parse a nullable ISO-8601 timestamp.
    *******************************************************************************/
   protected static java.time.Instant instant(String value) throws QBadRequestException
   {
      try
      {
         return (value == null ? null : java.time.Instant.parse(value));
      }
      catch(java.time.format.DateTimeParseException e)
      {
         throw (new QBadRequestException("Expected an ISO-8601 timestamp"));
      }
   }

   /*******************************************************************************
    ** Recipient lists are bounded and contain only nonblank identities.
    *******************************************************************************/
   protected static List<String> recipients(List<String> values) throws QBadRequestException
   {
      if(values == null || values.isEmpty() || values.size() > 25 || values.stream().anyMatch(value -> value == null || value.isBlank()))
      {
         throw (new QBadRequestException("Choose 1 to 25 recipients"));
      }
      return (values);
   }
}
