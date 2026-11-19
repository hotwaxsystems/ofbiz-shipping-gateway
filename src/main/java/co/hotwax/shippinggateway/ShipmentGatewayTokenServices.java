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

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.ofbiz.base.util.GeneralException;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.base.util.cache.UtilCache;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityUtilProperties;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

import co.hotwax.shippinggateway.ShipmentGatewayRequestServices.CarrierCall;
import co.hotwax.shippinggateway.ShipmentGatewayRequestServices.CarrierReply;

public final class ShipmentGatewayTokenServices {

    private static final String PROPERTY_RESOURCE = "shippinggateway";
    private static final String OPERATION = "getShipmentGatewayToken";
    private static final long EARLY_EXPIRY_SECONDS = 300;
    private static final int TIMEOUT_SECONDS = 30;
    private static final Set<String> TOKEN_LOG_BLANK_FIELDS = Set.of("client_id", "client_secret", "access_token");
    private static final UtilCache<String, String> TOKENS = UtilCache.getOrCreateUtilCache("shippinggateway.token", 0, 0, 0, false);

    private ShipmentGatewayTokenServices() { }

    public static Map<String, Object> getShipmentGatewayToken(DispatchContext dctx, Map<String, ? extends Object> context) {
        String shipmentGatewayConfigId = (String) context.get("shipmentGatewayConfigId");
        String cachedToken = TOKENS.get(shipmentGatewayConfigId);
        if (cachedToken != null) {
            return tokenResult(cachedToken);
        }
        try {
            return tokenResult(buyToken(dctx, shipmentGatewayConfigId, (GenericValue) context.get("userLogin")));
        } catch (GeneralException e) {
            return ServiceUtil.returnError(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ServiceUtil.returnError("The login call for carrier account " + shipmentGatewayConfigId + " was interrupted.");
        }
    }

    public static Map<String, Object> clearShipmentGatewayToken(DispatchContext dctx, Map<String, ? extends Object> context) {
        forgetToken((String) context.get("shipmentGatewayConfigId"));
        return ServiceUtil.returnSuccess();
    }

    static void forgetToken(String shipmentGatewayConfigId) {
        TOKENS.remove(shipmentGatewayConfigId);
    }

    private static String buyToken(DispatchContext dctx, String shipmentGatewayConfigId, GenericValue userLogin)
            throws GeneralException, InterruptedException {
        Delegator delegator = dctx.getDelegator();
        Map<String, Object> connection = ShipmentGatewayRequestServices.getConnection(dctx, shipmentGatewayConfigId, userLogin);
        if (!ShipmentGatewayRequestServices.OAUTH_CLIENT.equals(connection.get("authTypeEnumId"))) {
            throw new GeneralException("Carrier account " + shipmentGatewayConfigId + " does not log in with a token.");
        }
        GenericValue login = ShipmentGatewayRequestServices.getLogin(delegator, shipmentGatewayConfigId);
        String clientId = ShipmentGatewayCredentials.decryptKey(delegator, login.getString("publicKey"));
        String clientSecret = login.getString("password");
        if (UtilValidate.isEmpty(clientId) || UtilValidate.isEmpty(clientSecret)) {
            throw new GeneralException("Carrier account " + shipmentGatewayConfigId + " has no key or secret.");
        }

        String carrierType = (String) connection.get("shipmentGatewayConfTypeId");
        String tokenPath = requiredProperty(delegator, carrierType + ".token.path");
        String clientAuth = requiredProperty(delegator, carrierType + ".token.clientAuth");

        Map<String, Object> form = new LinkedHashMap<>();
        form.put("grant_type", "client_credentials");
        HttpRequest.Builder builder = HttpRequest.newBuilder(ShipmentGatewayRequestServices.resolve((String) connection.get("baseUrl"), tokenPath))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .setHeader("Content-Type", ShipmentGatewayRequestServices.FORM_CONTENT_TYPE);
        switch (clientAuth) {
        case "form":
            form.put("client_id", clientId);
            form.put("client_secret", clientSecret);
            break;
        case "basic":
            builder.setHeader("Authorization", ShipmentGatewayRequestServices.basicAuthorization(clientId, clientSecret));
            break;
        default:
            throw new GeneralException("SystemProperty " + carrierType + ".token.clientAuth is " + clientAuth + "; it must be form or basic.");
        }
        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(ShipmentGatewayRequestServices.formEncoded(form))).build();

        CarrierReply reply = ShipmentGatewayRequestServices.exchange(new CarrierCall(shipmentGatewayConfigId, OPERATION,
                userLogin.getString("userLoginId"), Map.of(), TOKEN_LOG_BLANK_FIELDS, request, form));
        Map<?, ?> tokenReply = reply.isSuccessful() && reply.json() instanceof Map<?, ?> replyMap ? replyMap : Map.of();
        if (!(tokenReply.get("access_token") instanceof String accessToken) || accessToken.isEmpty()) {
            throw new GeneralException("Carrier account " + shipmentGatewayConfigId + " got no login token: outcome "
                    + reply.callOutcome() + ", status " + reply.statusCode() + ".");
        }
        long cacheSeconds = lifetimeSeconds(tokenReply.get("expires_in")) - EARLY_EXPIRY_SECONDS;
        if (cacheSeconds > 0) {
            TOKENS.put(shipmentGatewayConfigId, accessToken, TimeUnit.SECONDS.toMillis(cacheSeconds));
        }
        return accessToken;
    }

    private static String requiredProperty(Delegator delegator, String propertyName) throws GeneralException {
        String value = EntityUtilProperties.getPropertyValue(PROPERTY_RESOURCE, propertyName, delegator);
        if (UtilValidate.isEmpty(value)) {
            throw new GeneralException("SystemProperty " + propertyName + " of resource " + PROPERTY_RESOURCE + " is not set.");
        }
        return value;
    }

    private static long lifetimeSeconds(Object expiresIn) {
        if (expiresIn instanceof Number seconds) {
            return seconds.longValue();
        }
        if (expiresIn instanceof String seconds && seconds.matches("\\d{1,9}")) {
            return Long.parseLong(seconds);
        }
        return 0;
    }

    private static Map<String, Object> tokenResult(String accessToken) {
        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("accessToken", accessToken);
        return result;
    }
}
