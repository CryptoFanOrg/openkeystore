/*
 *  Copyright 2006-2018 WebPKI.org (http://webpki.org).
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.webpki.json.encryption;

/**
 * Return object for symmetric key encryptions.
 */
public class SymmetricEncryptionResult {
    private byte[] iv;
    byte[] tag;
    byte[] cipherText;

    SymmetricEncryptionResult(byte[] iv, byte[] tag, byte[] cipherText) {
        this.iv = iv;
        this.tag = tag;
        this.cipherText = cipherText;
    }

    public byte[] getTag() {
        return tag;
    }

    public byte[] getIv() {
        return iv;
    }

    public byte[] getCipherText() {
        return cipherText;
    }
}
