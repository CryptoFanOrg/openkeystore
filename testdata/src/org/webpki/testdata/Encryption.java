/*
 *  Copyright 2006-2017 WebPKI.org (http://webpki.org).
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
package org.webpki.testdata;

import java.io.File;

import java.security.KeyPair;

import java.security.interfaces.ECPublicKey;

import org.webpki.crypto.CustomCryptoProvider;

import org.webpki.json.JSONObjectReader;
import org.webpki.json.JSONObjectWriter;
import org.webpki.json.JSONOutputFormats;
import org.webpki.json.JSONParser;
import org.webpki.json.JSONSigner;

import org.webpki.json.encryption.DataEncryptionAlgorithms;
import org.webpki.json.encryption.KeyEncryptionAlgorithms;

import org.webpki.util.ArrayUtil;

/*
 * Create JEF test vectors
 */
public class Encryption {
    static String baseKey;
    static String baseEncryption;
    static SymmetricKeys symmetricKeys;
    static String keyId;
    static byte[] dataToBeEncrypted;
   
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new Exception("Wrong number of arguments");
        }
        CustomCryptoProvider.forcedLoad(true);
        baseKey = args[0] + File.separator;
        baseEncryption = args[1] + File.separator;
        symmetricKeys = new SymmetricKeys(baseKey);
        dataToBeEncrypted = ArrayUtil.readFile(baseEncryption + "datatobeencrypted.txt");

        asymEnc("p256", DataEncryptionAlgorithms.JOSE_A128CBC_HS256_ALG_ID);
        asymEnc("p384", DataEncryptionAlgorithms.JOSE_A256CBC_HS512_ALG_ID);
        asymEnc("p521", DataEncryptionAlgorithms.JOSE_A128GCM_ALG_ID);
        asymEnc("r2048", DataEncryptionAlgorithms.JOSE_A256GCM_ALG_ID);

        asymEncNoPublicKeyInfo("p256", DataEncryptionAlgorithms.JOSE_A128CBC_HS256_ALG_ID, true);
        asymEncNoPublicKeyInfo("p256", DataEncryptionAlgorithms.JOSE_A128GCM_ALG_ID, true);
        asymEncNoPublicKeyInfo("r2048", DataEncryptionAlgorithms.JOSE_A256GCM_ALG_ID, false);
        asymEncNoPublicKeyInfo("r2048", DataEncryptionAlgorithms.JOSE_A128GCM_ALG_ID, false);
      
        symmEnc(256, DataEncryptionAlgorithms.JOSE_A128CBC_HS256_ALG_ID);
        symmEnc(512, DataEncryptionAlgorithms.JOSE_A256CBC_HS512_ALG_ID);
        symmEnc(128, DataEncryptionAlgorithms.JOSE_A128GCM_ALG_ID);
        symmEnc(256, DataEncryptionAlgorithms.JOSE_A256GCM_ALG_ID);

        coreSymmEnc(256, ".implicitkey.json", DataEncryptionAlgorithms.JOSE_A256GCM_ALG_ID, false);
    }
    
    static void coreSymmEnc(int keyBits, String fileSuffix, DataEncryptionAlgorithms dataEncryptionAlgorithm, boolean wantKeyId) throws Exception {
        byte[] key = symmetricKeys.getValue(keyBits);
        String keyName = symmetricKeys.getName(keyBits);
        byte[] encryptedData = 
                JSONObjectWriter.createEncryptionObject(dataToBeEncrypted, 
                                                        dataEncryptionAlgorithm, 
                                                        wantKeyId ? keyName : null, 
                                                        key).serializeToBytes(JSONOutputFormats.PRETTY_PRINT);
        ArrayUtil.writeFile(baseEncryption + dataEncryptionAlgorithm.toString().toLowerCase() + fileSuffix, encryptedData);
        if (!ArrayUtil.compare(dataToBeEncrypted,
                       JSONParser.parse(encryptedData).getEncryptionObject().getDecryptedData(key))) {
            throw new Exception("Encryption fail");
        }
    }

    static void symmEnc(int keyBits, DataEncryptionAlgorithms dataEncryptionAlgorithm) throws Exception {
        coreSymmEnc(keyBits, ".encrypted.json", dataEncryptionAlgorithm, true);
    }

    static String getDataToSign() throws Exception {
        return new String(ArrayUtil.readFile(baseEncryption + "datatobesigned.json"), 
                          "UTF-8").replace("\r", "");
    }
    
    static JSONObjectWriter parseDataToSign() throws Exception {
        return new JSONObjectWriter(JSONParser.parse(getDataToSign()));
    }

    static byte[] createSignature(JSONSigner signer) throws Exception {
        String signed = parseDataToSign().setSignature(signer).toString();
        int i = signed.indexOf(",\n  \"signature\":");
        String unsigned = getDataToSign();
        int j = unsigned.lastIndexOf("\n}");
        return (unsigned.substring(0,j) + signed.substring(i)).getBytes("UTF-8");
    }
    
    static KeyPair readJwk(String keyType) throws Exception {
        JSONObjectReader jwkPlus = JSONParser.parse(ArrayUtil.readFile(baseKey + keyType + "privatekey.jwk"));
        // Note: The built-in JWK decoder does not accept "kid" since it doesn't have a meaning in JCS or JEF. 
        if ((keyId = jwkPlus.getStringConditional("kid")) != null) {
            jwkPlus.removeProperty("kid");
        }
        return jwkPlus.getKeyPair();
    }

    static void coreAsymEnc(String keyType, String fileSuffix, DataEncryptionAlgorithms dataEncryptionAlgorithm, String wantKeyId) throws Exception {
        KeyPair keyPair = readJwk(keyType);
        if (wantKeyId != null && wantKeyId.equals("yes")) {
            wantKeyId = keyId;
        }
        KeyEncryptionAlgorithms keyEncryptionAlgorithm = KeyEncryptionAlgorithms.JOSE_RSA_OAEP_256_ALG_ID;
        if (keyPair.getPublic() instanceof ECPublicKey) {
            switch (dataEncryptionAlgorithm.getKeyLength()) {
            case 16: 
                keyEncryptionAlgorithm = KeyEncryptionAlgorithms.JOSE_ECDH_ES_A128KW_ALG_ID;
                break;
            case 32: 
                keyEncryptionAlgorithm = KeyEncryptionAlgorithms.JOSE_ECDH_ES_A256KW_ALG_ID;
                break;
            default: 
                keyEncryptionAlgorithm = KeyEncryptionAlgorithms.JOSE_ECDH_ES_ALG_ID;
                break;
            }
        }
        if (keyEncryptionAlgorithm == KeyEncryptionAlgorithms.JOSE_RSA_OAEP_256_ALG_ID &&
            dataEncryptionAlgorithm == DataEncryptionAlgorithms.JOSE_A128GCM_ALG_ID) {
            keyEncryptionAlgorithm = KeyEncryptionAlgorithms.JOSE_RSA_OAEP_ALG_ID;
        }
        byte[] encryptedData =
               JSONObjectWriter.createEncryptionObject(dataToBeEncrypted, 
                                                       dataEncryptionAlgorithm, 
                                                       keyPair.getPublic(),
                                                       wantKeyId,
                                                       keyEncryptionAlgorithm).serializeToBytes(JSONOutputFormats.PRETTY_PRINT);
        ArrayUtil.writeFile(baseEncryption + keyType + keyEncryptionAlgorithm.toString().toLowerCase() + fileSuffix, encryptedData);
        if (!ArrayUtil.compare(JSONParser.parse(encryptedData)
                 .getEncryptionObject().getDecryptedData(keyPair.getPrivate()),
                               dataToBeEncrypted)) {
            throw new Exception("Dec err");
        }
     }

    static void asymEnc(String keyType, DataEncryptionAlgorithms dataEncryptionAlgorithm) throws Exception {
        coreAsymEnc(keyType, ".encrypted.json", dataEncryptionAlgorithm, null);
    }

    static void asymEncNoPublicKeyInfo(String keyType, DataEncryptionAlgorithms dataEncryptionAlgorithm, boolean wantKeyId) throws Exception {
        coreAsymEnc(keyType, ".implicitkey.json", dataEncryptionAlgorithm, wantKeyId ? "yes" : "");
    }
}