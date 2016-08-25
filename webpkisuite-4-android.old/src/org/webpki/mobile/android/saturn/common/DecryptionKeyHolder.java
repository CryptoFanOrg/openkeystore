/*
 *  Copyright 2006-2016 WebPKI.org (http://webpki.org).
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.webpki.mobile.android.saturn.common;

import java.security.PrivateKey;
import java.security.PublicKey;

public class DecryptionKeyHolder {

    PublicKey publicKey;
    
    PrivateKey privateKey;
    
    String keyEncryptionAlgorithm;

    public PublicKey getPublicKey() {
        return publicKey;
    }

    public String getKeyEncryptionAlgorithm() {
        return keyEncryptionAlgorithm;
    }

    public DecryptionKeyHolder(PublicKey publicKey, PrivateKey privateKey, String keyEncryptionAlgorithm) {
        this.publicKey = publicKey;
        this.privateKey = privateKey;
        this.keyEncryptionAlgorithm = keyEncryptionAlgorithm;
    }
}