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
package org.webpki.json;

import java.io.IOException;

import java.security.GeneralSecurityException;
import java.security.PublicKey;

import java.security.interfaces.ECPublicKey;

import java.util.Vector;

import org.webpki.crypto.AlgorithmPreferences;
import org.webpki.crypto.DecryptionKeyHolder;


////////////////////////////////////////////////////////////////////////////////
// This is effectively a "remake" of a subset of JWE.  Why a remake?          //
// Because the encryption system (naturally) borrows heavily from JCS         //
// including public key structures and property naming conventions.           //
//                                                                            //
// The supported algorithms are though JOSE compatible including their names. //
////////////////////////////////////////////////////////////////////////////////

public class JSONEncryption {

    public static final String JOSE_RSA_OAEP_256_ALG_ID   = "RSA-OAEP-256";
    public static final String JOSE_ECDH_ES_ALG_ID        = "ECDH-ES";
    public static final String JOSE_A128CBC_HS256_ALG_ID  = "A128CBC-HS256";

    public static final String ENCRYPTED_DATA_JSON  = "encryptedData";
    public static final String ENCRYPTED_KEY_JSON   = "encryptedKey";
    public static final String STATIC_KEY_JSON      = "staticKey";
    public static final String EPHEMERAL_KEY_JSON   = "ephemeralKey";
    public static final String IV_JSON              = "iv";
    public static final String TAG_JSON             = "tag";
    public static final String CIPHER_TEXT_JSON     = "cipherText";

    private PublicKey publicKey;

    private ECPublicKey ephemeralPublicKey;  // For ECHD only

    private String dataEncryptionAlgorithm;

    private byte[] iv;

    private byte[] tag;

    private String keyEncryptionAlgorithm;

    private byte[] encryptedKeyData;  // For RSA only

    private byte[] encryptedData;
    
    private byte[] authenticatedData;  // This implementation uses "encryptedKey" which is similar to JWE's protected header
    
    static boolean isRsaKey(String keyEncryptionAlgorithm) {
        return keyEncryptionAlgorithm.contains("RSA");
    }
    
    public static JSONEncryption parse(JSONObjectReader rd, boolean sharedSecret) throws IOException {
        return new JSONEncryption(rd, sharedSecret);
    }

    private JSONEncryption(JSONObjectReader encryptionObject, boolean sharedSecret) throws IOException {
        JSONObjectReader rd = encryptionObject.getObject(ENCRYPTED_DATA_JSON);
        dataEncryptionAlgorithm = rd.getString(JSONSignatureDecoder.ALGORITHM_JSON);
        iv = rd.getBinary(IV_JSON);
        tag = rd.getBinary(TAG_JSON);
        if (sharedSecret) {
            authenticatedData = dataEncryptionAlgorithm.getBytes("UTF-8");
        } else {
            JSONObjectReader encryptedKey = rd.getObject(ENCRYPTED_KEY_JSON);
            authenticatedData = encryptedKey.serializeJSONObject(JSONOutputFormats.NORMALIZED);
            keyEncryptionAlgorithm = encryptedKey.getString(JSONSignatureDecoder.ALGORITHM_JSON);
            if (isRsaKey(keyEncryptionAlgorithm)) {
                publicKey = encryptedKey.getPublicKey(AlgorithmPreferences.JOSE);
                encryptedKeyData = encryptedKey.getBinary(CIPHER_TEXT_JSON);
            } else {
                publicKey = encryptedKey.getObject(STATIC_KEY_JSON).getPublicKey(AlgorithmPreferences.JOSE);
                ephemeralPublicKey = 
                    (ECPublicKey) encryptedKey.getObject(EPHEMERAL_KEY_JSON).getPublicKey(AlgorithmPreferences.JOSE);
            }
        }
        encryptedData = rd.getBinary(CIPHER_TEXT_JSON);
    }

    public JSONObjectReader getDecryptedData(byte[] dataDecryptionKey) throws IOException, GeneralSecurityException {
        return JSONParser.parse(EncryptionCore.contentDecryption(dataEncryptionAlgorithm,
                                                                 dataDecryptionKey,
                                                                 encryptedData,
                                                                 iv,
                                                                 authenticatedData,
                                                                 tag));
    }

    public JSONObjectReader getDecryptedData(Vector<DecryptionKeyHolder> decryptionKeys)
    throws IOException, GeneralSecurityException {
        boolean notFound = true;
        for (DecryptionKeyHolder decryptionKey : decryptionKeys) {
            if (decryptionKey.getPublicKey().equals(publicKey)) {
                notFound = false;
                if (decryptionKey.getKeyEncryptionAlgorithm().equals(keyEncryptionAlgorithm)) {
                    return getDecryptedData(isRsaKey(keyEncryptionAlgorithm) ?
                         EncryptionCore.rsaDecryptKey(keyEncryptionAlgorithm,
                                                      encryptedKeyData,
                                                      decryptionKey.getPrivateKey())
                                             :
                         EncryptionCore.receiverKeyAgreement(keyEncryptionAlgorithm,
                                                             dataEncryptionAlgorithm,
                                                             ephemeralPublicKey,
                                                             decryptionKey.getPrivateKey()));
                }
            }
        }
        throw new IOException(notFound ? "No matching key found" : "No matching key+algorithm found");
    }

    public static JSONObjectWriter encode(JSONObjectWriter unencryptedData,
                                          String dataEncryptionAlgorithm,
                                          PublicKey keyEncryptionKey,
                                          String keyEncryptionAlgorithm)
    throws IOException, GeneralSecurityException {
        JSONObjectWriter encryptedKey = new JSONObjectWriter()
            .setString(JSONSignatureDecoder.ALGORITHM_JSON, keyEncryptionAlgorithm);
        byte[] dataEncryptionKey = null;
        if (JSONEncryption.isRsaKey(keyEncryptionAlgorithm)) {
            encryptedKey.setPublicKey(keyEncryptionKey, AlgorithmPreferences.JOSE);
            dataEncryptionKey = EncryptionCore.generateDataEncryptionKey(dataEncryptionAlgorithm);
            encryptedKey.setBinary(CIPHER_TEXT_JSON,
            EncryptionCore.rsaEncryptKey(keyEncryptionAlgorithm,
                                         dataEncryptionKey,
                                         keyEncryptionKey));
        } else {
            EncryptionCore.EcdhSenderResult result =
                EncryptionCore.senderKeyAgreement(keyEncryptionAlgorithm,
                                                  dataEncryptionAlgorithm,
                                                  keyEncryptionKey);
            dataEncryptionKey = result.getSharedSecret();
            encryptedKey.setObject(STATIC_KEY_JSON)
                .setPublicKey(keyEncryptionKey, AlgorithmPreferences.JOSE);
            encryptedKey.setObject(EPHEMERAL_KEY_JSON)
                .setPublicKey(result.getEphemeralKey(), AlgorithmPreferences.JOSE);
        }
        return encode(unencryptedData,
                      dataEncryptionAlgorithm,
                      dataEncryptionKey,
                      encryptedKey);
    }

    public static JSONObjectWriter encode(JSONObjectWriter unencryptedData,
                                          String dataEncryptionAlgorithm,
                                          byte[] dataEncryptionKey)
    throws IOException, GeneralSecurityException {
        return encode(unencryptedData, dataEncryptionAlgorithm, dataEncryptionKey, null);
    }
    

    private static JSONObjectWriter encode(JSONObjectWriter unencryptedData,
                                           String dataEncryptionAlgorithm,
                                           byte[] dataEncryptionKey,
                                           JSONObjectWriter encryptedKey)
    throws IOException, GeneralSecurityException {
        JSONObjectWriter encryptionObject = new JSONObjectWriter();
        JSONObjectWriter encryptedData = encryptionObject.setObject(ENCRYPTED_DATA_JSON);
        byte[] authenticatedData = null;
        if (encryptedKey == null) {
            authenticatedData = dataEncryptionAlgorithm.getBytes("UTF-8");
        } else {
            encryptedData.setObject(ENCRYPTED_KEY_JSON, encryptedKey);
            authenticatedData = encryptedKey.serializeJSONObject(JSONOutputFormats.NORMALIZED);
        }
        EncryptionCore.AuthEncResult result =
            EncryptionCore.contentEncryption(dataEncryptionAlgorithm,
                                             dataEncryptionKey,
                                             unencryptedData.serializeJSONObject(JSONOutputFormats.NORMALIZED),
                                             authenticatedData);
        encryptedData.setString(JSONSignatureDecoder.ALGORITHM_JSON, dataEncryptionAlgorithm)
            .setBinary(IV_JSON, result.getIv())
            .setBinary(TAG_JSON, result.getTag())
            .setBinary(CIPHER_TEXT_JSON, result.getCipherText());
        return encryptionObject;
    }
}
