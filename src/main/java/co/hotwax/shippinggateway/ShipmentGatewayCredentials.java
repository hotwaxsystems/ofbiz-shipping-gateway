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

import java.util.Objects;

import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.EntityCryptoException;
import org.apache.ofbiz.entity.model.ModelField;

public final class ShipmentGatewayCredentials {

    private static final String ENTITY_NAME = "ShipmentGatewayAuth";
    private static final int MAX_KEY_LENGTH = 150;

    private ShipmentGatewayCredentials() { }

    public static String encryptKey(Delegator delegator, String newKey, String savedEncryptedKey) throws EntityCryptoException {
        if (newKey.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("The key is longer than " + MAX_KEY_LENGTH + " characters.");
        }
        if (Objects.equals(newKey, decryptKey(delegator, savedEncryptedKey))) {
            return savedEncryptedKey;
        }
        return (String) delegator.encryptFieldValue(ENTITY_NAME, ModelField.EncryptMethod.TRUE, newKey);
    }

    public static String decryptKey(Delegator delegator, String encryptedKey) throws EntityCryptoException {
        return (String) delegator.decryptFieldValue(ENTITY_NAME, ModelField.EncryptMethod.TRUE, encryptedKey);
    }
}
