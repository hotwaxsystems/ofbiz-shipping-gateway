/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package co.hotwax.shippinggateway;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.ThreadContext;
import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.GeneralException;
import org.apache.ofbiz.base.util.UtilGenerics;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

public final class ShipmentGatewayRequestServices {

    private static final String MODULE = ShipmentGatewayRequestServices.class.getName();

    static final String ANSWERED = "ANSWERED";
    static final String TIMED_OUT = "TIMED_OUT";
    static final String NOT_REACHED = "NOT_REACHED";
    static final String OAUTH_CLIENT = "SGA_OAUTH_CLIENT";
    static final String FORM_CONTENT_TYPE = "application/x-www-form-urlencoded";

    private static final String BASIC = "SGA_BASIC";
    private static final String API_KEY_HEADER = "SGA_API_KEY_HEADER";
    private static final String JSON_CONTENT_TYPE = "application/json";
    private static final String LOG_FILE_KEY = "ofbiz.logFile";
    private static final String REMOVED = "[removed]";
    private static final int UNAUTHORIZED = 401;
    private static final Set<String> HTTP_METHODS = Set.of("GET", "POST", "PUT", "DELETE");

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    private ShipmentGatewayRequestServices() { }

    record CarrierCall(String shipmentGatewayConfigId, String operation, String userLoginId, Map<String, Object> referenceIds,
                       Set<String> blankFields, HttpRequest request, Object loggedBody) { }

    record CarrierReply(Integer statusCode, String text, Object json, String callOutcome) {
        boolean isSuccessful() {
            return ANSWERED.equals(callOutcome) && statusCode >= 200 && statusCode < 300;
        }
    }

    private record LoginHeader(String name, String value) { }

    private record RequestBody(HttpRequest.BodyPublisher publisher, String contentType, Map<String, Object> fields) { }

    public static Map<String, Object> sendShipmentGatewayRequest(DispatchContext dctx, Map<String, ? extends Object> context) {
        String shipmentGatewayConfigId = (String) context.get("shipmentGatewayConfigId");
        String httpMethod = (String) context.get("httpMethod");
        String path = (String) context.get("path");
        Map<String, Object> requestBody = UtilGenerics.cast(context.get("requestBody"));
        Map<String, Object> formBody = UtilGenerics.cast(context.get("formBody"));
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        if (!HTTP_METHODS.contains(httpMethod)) {
            return ServiceUtil.returnError("HTTP method " + httpMethod + " is not one of " + HTTP_METHODS + ".");
        }
        if (requestBody != null && formBody != null) {
            return ServiceUtil.returnError("Send either requestBody or formBody, not both.");
        }

        try {
            Map<String, Object> connection = getConnection(dctx, shipmentGatewayConfigId, userLogin);
            String authTypeEnumId = (String) connection.get("authTypeEnumId");
            URI uri = resolve((String) connection.get("baseUrl"), path);
            RequestBody body = requestBody(requestBody, formBody);
            Duration timeout = Duration.ofSeconds((Integer) context.get("timeoutSeconds"));
            Map<String, Object> requestHeaders = UtilGenerics.cast(context.get("requestHeaders"));
            List<String> logBlankFields = UtilGenerics.cast(context.get("logBlankFields"));
            Map<String, Object> referenceIds = UtilGenerics.cast(context.get("referenceIds"));
            Set<String> blankFields = logBlankFields != null ? new HashSet<>(logBlankFields) : Set.of();

            LoginHeader loginHeader = loginHeader(dctx, shipmentGatewayConfigId, authTypeEnumId, userLogin);
            HttpRequest request = buildRequest(uri, httpMethod, timeout, requestHeaders, body, loginHeader);
            CarrierCall call = new CarrierCall(shipmentGatewayConfigId, (String) context.get("operation"),
                    userLogin.getString("userLoginId"), referenceIds, blankFields, request, body.fields());
            CarrierReply reply = exchange(call);

            if (OAUTH_CLIENT.equals(authTypeEnumId) && ANSWERED.equals(reply.callOutcome()) && reply.statusCode() == UNAUTHORIZED) {
                ShipmentGatewayTokenServices.forgetToken(shipmentGatewayConfigId);
                LoginHeader newLoginHeader = loginHeader(dctx, shipmentGatewayConfigId, authTypeEnumId, userLogin);
                HttpRequest repeatedRequest = buildRequest(uri, httpMethod, timeout, requestHeaders, body, newLoginHeader);
                reply = exchange(new CarrierCall(call.shipmentGatewayConfigId(), call.operation(), call.userLoginId(),
                        referenceIds, blankFields, repeatedRequest, body.fields()));
            }
            return replyResult(reply);
        } catch (GeneralException | IllegalArgumentException e) {
            return ServiceUtil.returnError(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ServiceUtil.returnError("The call to carrier account " + shipmentGatewayConfigId + " was interrupted.");
        }
    }

    static Map<String, Object> getConnection(DispatchContext dctx, String shipmentGatewayConfigId, GenericValue userLogin)
            throws GeneralException {
        Map<String, Object> connection = dctx.getDispatcher().runSync("getShipmentGatewayConnection",
                UtilMisc.toMap("shipmentGatewayConfigId", shipmentGatewayConfigId, "userLogin", userLogin));
        if (ServiceUtil.isError(connection)) {
            throw new GeneralException(ServiceUtil.getErrorMessage(connection));
        }
        return connection;
    }

    static GenericValue getLogin(Delegator delegator, String shipmentGatewayConfigId) throws GeneralException {
        return EntityQuery.use(delegator).from("ShipmentGatewayAuth").where("shipmentGatewayConfigId", shipmentGatewayConfigId).queryOne();
    }

    static URI resolve(String baseUrl, String path) throws GeneralException {
        if (!path.startsWith("/") || path.startsWith("//")) {
            throw new GeneralException("Path " + path + " must start with a single / and stay on the carrier's address.");
        }
        String address = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(address + path);
    }

    static String formEncoded(Map<String, Object> fields) {
        return fields.entrySet().stream()
                .filter(field -> field.getValue() != null)
                .map(field -> URLEncoder.encode(field.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(String.valueOf(field.getValue()), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    static String basicAuthorization(String user, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    static CarrierReply exchange(CarrierCall call) throws InterruptedException {
        ThreadContext.put(LOG_FILE_KEY, "shippinggateway/" + call.shipmentGatewayConfigId() + "/"
                + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE));
        try {
            HttpRequest request = call.request();
            Debug.logInfo("Carrier request: operation=%s userLoginId=%s referenceIds=%s method=%s url=%s body=%s", MODULE,
                    call.operation(), call.userLoginId(), toLogText(call.referenceIds()), request.method(), request.uri(),
                    toLogText(blanked(call.loggedBody(), call.blankFields())));
            CarrierReply reply = send(request);
            Object loggedReply = reply.json() != null ? toLogText(blanked(reply.json(), call.blankFields())) : reply.text();
            Debug.logInfo("Carrier reply: operation=%s status=%s outcome=%s body=%s", MODULE,
                    call.operation(), reply.statusCode(), reply.callOutcome(), loggedReply);
            return reply;
        } finally {
            ThreadContext.remove(LOG_FILE_KEY);
        }
    }

    private static CarrierReply send(HttpRequest request) throws InterruptedException {
        try {
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            Object json = contentType.contains("json") ? parseJson(response.body()) : null;
            return new CarrierReply(response.statusCode(), response.body(), json, ANSWERED);
        } catch (HttpConnectTimeoutException | ConnectException e) {
            return new CarrierReply(null, e.getMessage(), null, NOT_REACHED);
        } catch (IOException e) {
            return new CarrierReply(null, e.getMessage(), null, TIMED_OUT);
        }
    }

    private static Object parseJson(String text) {
        try {
            return UtilValidate.isEmpty(text) ? null : MAPPER.readValue(text, Object.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static Map<String, Object> replyResult(CarrierReply reply) {
        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("callOutcome", reply.callOutcome());
        if (!ANSWERED.equals(reply.callOutcome())) {
            return result;
        }
        result.put("statusCode", reply.statusCode());
        if (reply.json() instanceof Map) {
            result.put("responseBody", reply.json());
        } else if (UtilValidate.isNotEmpty(reply.text())) {
            result.put("responseText", reply.text());
        }
        return result;
    }

    private static LoginHeader loginHeader(DispatchContext dctx, String shipmentGatewayConfigId, String authTypeEnumId,
                                           GenericValue userLogin) throws GeneralException {
        if (OAUTH_CLIENT.equals(authTypeEnumId)) {
            Map<String, Object> token = dctx.getDispatcher().runSync("getShipmentGatewayToken",
                    UtilMisc.toMap("shipmentGatewayConfigId", shipmentGatewayConfigId, "userLogin", userLogin));
            if (ServiceUtil.isError(token)) {
                throw new GeneralException(ServiceUtil.getErrorMessage(token));
            }
            return new LoginHeader("Authorization", "Bearer " + token.get("accessToken"));
        }
        GenericValue login = getLogin(dctx.getDelegator(), shipmentGatewayConfigId);
        if (BASIC.equals(authTypeEnumId)) {
            String username = login.getString("username");
            String password = login.getString("password");
            if (UtilValidate.isEmpty(username) || UtilValidate.isEmpty(password)) {
                throw new GeneralException("Carrier account " + shipmentGatewayConfigId + " has no user name or password.");
            }
            return new LoginHeader("Authorization", basicAuthorization(username, password));
        }
        if (API_KEY_HEADER.equals(authTypeEnumId)) {
            String headerName = login.getString("authHeaderName");
            String key = ShipmentGatewayCredentials.decryptKey(dctx.getDelegator(), login.getString("publicKey"));
            if (UtilValidate.isEmpty(headerName) || UtilValidate.isEmpty(key)) {
                throw new GeneralException("Carrier account " + shipmentGatewayConfigId + " has no key header name or key.");
            }
            return new LoginHeader(headerName, key);
        }
        throw new GeneralException("Carrier account " + shipmentGatewayConfigId + " has login type " + authTypeEnumId
                + ", which the request helper cannot send.");
    }

    private static HttpRequest buildRequest(URI uri, String httpMethod, Duration timeout, Map<String, Object> requestHeaders,
                                            RequestBody body, LoginHeader loginHeader) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout).method(httpMethod, body.publisher());
        if (body.contentType() != null) {
            builder.setHeader("Content-Type", body.contentType());
        }
        if (requestHeaders != null) {
            requestHeaders.forEach((name, value) -> builder.setHeader(name, String.valueOf(value)));
        }
        builder.setHeader(loginHeader.name(), loginHeader.value());
        return builder.build();
    }

    private static RequestBody requestBody(Map<String, Object> jsonFields, Map<String, Object> formFields) throws GeneralException {
        if (jsonFields != null) {
            return jsonBody(jsonFields);
        }
        if (formFields != null) {
            return formBody(formFields);
        }
        return new RequestBody(HttpRequest.BodyPublishers.noBody(), null, null);
    }

    private static RequestBody jsonBody(Map<String, Object> fields) throws GeneralException {
        try {
            return new RequestBody(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(fields)), JSON_CONTENT_TYPE, fields);
        } catch (JsonProcessingException e) {
            throw new GeneralException("The request body cannot be written as JSON: " + e.getOriginalMessage());
        }
    }

    private static RequestBody formBody(Map<String, Object> fields) {
        return new RequestBody(HttpRequest.BodyPublishers.ofString(formEncoded(fields)), FORM_CONTENT_TYPE, fields);
    }

    private static Object blanked(Object value, Set<String> blankFields) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, fieldValue) -> copy.put(key, blankFields.contains(String.valueOf(key)) ? REMOVED : blanked(fieldValue, blankFields)));
            return copy;
        }
        if (value instanceof Collection<?> items) {
            return items.stream().map(item -> blanked(item, blankFields)).toList();
        }
        return value;
    }

    private static String toLogText(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "[not written: " + e.getOriginalMessage() + "]";
        }
    }
}
