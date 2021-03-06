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
import java.io.IOException;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.Vector;

import org.webpki.crypto.AlgorithmPreferences;
import org.webpki.crypto.CertificateUtil;
import org.webpki.crypto.CustomCryptoProvider;
import org.webpki.crypto.KeyStoreVerifier;
import org.webpki.crypto.MACAlgorithms;
import org.webpki.json.JSONAsymKeySigner;
import org.webpki.json.JSONAsymKeyVerifier;
import org.webpki.json.JSONObjectReader;
import org.webpki.json.JSONObjectWriter;
import org.webpki.json.JSONParser;
import org.webpki.json.JSONRemoteKeys;
import org.webpki.json.JSONSignatureDecoder;
import org.webpki.json.JSONSigner;
import org.webpki.json.JSONSymKeySigner;
import org.webpki.json.JSONSymKeyVerifier;
import org.webpki.json.JSONX509Signer;
import org.webpki.json.JSONX509Verifier;
import org.webpki.net.HTTPSWrapper;
import org.webpki.util.ArrayUtil;
import org.webpki.util.Base64;

/*
 * Create JCS test vectors
 */
public class Signatures {
    static String baseKey;
    static String baseSignatures;
    static SymmetricKeys symmetricKeys;
    static JSONX509Verifier x509Verifier;
    static String keyId;
   
    static final String P256CERTPATH = "https://cyberphone.github.io/doc/openkeystore/p256certpath.pem";
    static final String R2048KEY     = "https://cyberphone.github.io/doc/openkeystore/r2048.jwk";
    
    public static class WebKey implements JSONRemoteKeys.Reader {
        
        Vector<byte[]> getBinaryContentFromPem(byte[] pemBinary, String label, boolean multiple) throws IOException {
            String pem = new String(pemBinary, "UTF-8");
            Vector<byte[]> result = new Vector<byte[]>();
            while (true) {
                int start = pem.indexOf("-----BEGIN " + label + "-----");
                int end = pem.indexOf("-----END " + label + "-----");
                if (start >= 0 && end > 0 && end > start) {
                    byte[] blob = new Base64().getBinaryFromBase64String(pem.substring(start + label.length() + 16, end));
                    result.add(blob);
                    pem = pem.substring(end + label.length() + 14);
                } else {
                    if (result.isEmpty()) {
                        throw new IOException("No \"" + label + "\" found");
                    }
                    if (!multiple && result.size() > 1) {
                        throw new IOException("Multiple \"" + label + "\" found");
                    }
                    return result;
                }
            }
        }
     
        byte[] shoot(String uri) throws IOException {
            HTTPSWrapper wrapper = new HTTPSWrapper();
            wrapper.makeGetRequest(uri);
            return wrapper.getData();
        }

        @Override
        public PublicKey readPublicKey(String uri, JSONRemoteKeys format) throws IOException {
            byte[] data = shoot(uri);
            if (format == JSONRemoteKeys.JWK_PUB_KEY) {
                return JSONParser.parse(data).getCorePublicKey(AlgorithmPreferences.JOSE_ACCEPT_PREFER);
            }
            throw new IOException("Not implemented");
        }

        @Override
        public X509Certificate[] readCertificatePath(String uri, JSONRemoteKeys format) throws IOException {
            byte[] data = shoot(uri);
            if (format == JSONRemoteKeys.PEM_CERT_PATH) {
                return CertificateUtil.getSortedPathFromBlobs(getBinaryContentFromPem(data, "CERTIFICATE", true));
            }
            throw new IOException("Not implemented");
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new Exception("Wrong number of arguments");
        }
        CustomCryptoProvider.forcedLoad(true);
        baseKey = args[0] + File.separator;
        baseSignatures = args[1] + File.separator;
        symmetricKeys = new SymmetricKeys(baseKey);
        
        X509Certificate rootca = JSONParser.parse(ArrayUtil.readFile(baseKey + "rootca.jcer"))
                .getJSONArrayReader().getCertificatePath()[0];
        KeyStore keyStore = KeyStore.getInstance("JKS");
        keyStore.load (null, null);
        keyStore.setCertificateEntry ("mykey", rootca);        
        x509Verifier = new JSONX509Verifier(new KeyStoreVerifier(keyStore));

       
        asymSign("p256");
        asymSign("p384");
        asymSign("p521");
        asymSign("r2048");

        asymSignNoPublicKeyInfo("p256", true);
        asymSignNoPublicKeyInfo("p521", false);

        certSign("p256");
        certSign("p384");
        certSign("p521");
        certSign("r2048");
        
        symmSign(256, MACAlgorithms.HMAC_SHA256);
        symmSign(384, MACAlgorithms.HMAC_SHA384);
        symmSign(512, MACAlgorithms.HMAC_SHA512);
        
        multipleSign("p256", "r2048");
        
        KeyPair localKey = readJwk("r2048");
        JSONAsymKeySigner remoteKeySigner =
                new JSONAsymKeySigner(localKey.getPrivate(),
                                      localKey.getPublic(),
                                      null)
                    .setRemoteKey(R2048KEY, JSONRemoteKeys.JWK_PUB_KEY);
        byte[] remoteSig = createSignature(remoteKeySigner);
        ArrayUtil.writeFile(baseSignatures + "r2048remotekeysigned.json", remoteSig);
        JSONParser.parse(remoteSig).getSignature(new JSONSignatureDecoder.Options()
            .setRemoteKeyReader(new WebKey()));

        localKey = readJwk("p256");
        remoteKeySigner =
                new JSONAsymKeySigner(localKey.getPrivate(),
                                      localKey.getPublic(),
                                      null)
                    .setRemoteKey(P256CERTPATH, JSONRemoteKeys.PEM_CERT_PATH);
        remoteSig = createSignature(remoteKeySigner);
        ArrayUtil.writeFile(baseSignatures + "p256remotecertsigned.json", remoteSig);
        JSONParser.parse(remoteSig).getSignature(new JSONSignatureDecoder.Options()
            .setRemoteKeyReader(new WebKey()));
    }
    
    static void symmSign(int keyBits, MACAlgorithms algorithm) throws Exception {
        byte[] key = symmetricKeys.getValue(keyBits);
        String keyName = symmetricKeys.getName(keyBits);
        byte[] signedData = createSignature(new JSONSymKeySigner(key, algorithm).setKeyId(keyName));
        ArrayUtil.writeFile(baseSignatures + "hs" + (key.length * 8) + "signed.json", signedData);
        JSONParser.parse(signedData).getSignature(new JSONSignatureDecoder.Options()
                .setRequirePublicKeyInfo(false)
                .setKeyIdOption(JSONSignatureDecoder.KEY_ID_OPTIONS.REQUIRED)).verify(new JSONSymKeyVerifier(key));
    }

    static String getDataToSign() throws Exception {
        return new String(ArrayUtil.readFile(baseSignatures + "datatobesigned.json"), 
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
    
    static byte[] createSignatures(Vector<JSONSigner> signers) throws Exception {
        String signed = parseDataToSign().setSignatures(signers).toString();
        int i = signed.indexOf(",\n  \"signatures\":");
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

    static void asymSign(String keyType) throws Exception {
        KeyPair keyPair = readJwk(keyType);
        byte[] signedData = createSignature(new JSONAsymKeySigner(keyPair.getPrivate(), keyPair.getPublic(), null));
        ArrayUtil.writeFile(baseSignatures + keyType + "keysigned.json", signedData);
        JSONParser.parse(signedData).getSignature(new JSONSignatureDecoder.Options()).verify(new JSONAsymKeyVerifier(keyPair.getPublic()));;
     }

    static void multipleSign(String keyType1, String KeyType2) throws Exception {
        KeyPair keyPair1 = readJwk(keyType1);
        KeyPair keyPair2 = readJwk(KeyType2);
        Vector<JSONSigner> signers = new Vector<JSONSigner>();
        signers.add(new JSONAsymKeySigner(keyPair1.getPrivate(), keyPair1.getPublic(), null));
        signers.add(new JSONAsymKeySigner(keyPair2.getPrivate(), keyPair2.getPublic(), null));
        byte[] signedData = createSignatures(signers);
        ArrayUtil.writeFile(baseSignatures + keyType1 + "+" + KeyType2 + "keysigned.json", signedData);
        Vector<JSONSignatureDecoder> signatures = 
                JSONParser.parse(signedData).getSignatures(new JSONSignatureDecoder.Options());
        signatures.get(0).verify(new JSONAsymKeyVerifier(keyPair1.getPublic()));
        signatures.get(1).verify(new JSONAsymKeyVerifier(keyPair2.getPublic()));
        if (signatures.size() != 2) {
            throw new Exception("Wrong multi");
        }
     }

    static void asymSignNoPublicKeyInfo(String keyType, boolean wantKeyId) throws Exception {
        KeyPair keyPair = readJwk(keyType);
        JSONSigner signer = 
                new JSONAsymKeySigner(keyPair.getPrivate(), keyPair.getPublic(), null)
            .setKeyId(wantKeyId ? keyId : "");
        byte[] signedData = createSignature(signer);
        ArrayUtil.writeFile(baseSignatures + keyType + "implicitkeysigned.json", signedData);
        JSONParser.parse(signedData).getSignature(
            new JSONSignatureDecoder.Options()
                .setRequirePublicKeyInfo(false)
                .setKeyIdOption(wantKeyId ? 
     JSONSignatureDecoder.KEY_ID_OPTIONS.REQUIRED : JSONSignatureDecoder.KEY_ID_OPTIONS.FORBIDDEN))
                    .verify(new JSONAsymKeyVerifier(keyPair.getPublic()));
     }

    static void certSign(String keyType) throws Exception {
        KeyPair keyPair = readJwk(keyType);
        X509Certificate[] certPath = JSONParser.parse(ArrayUtil.readFile(baseKey + keyType + "certificate.jcer"))
                .getJSONArrayReader().getCertificatePath();
        byte[] signedData = 
            createSignature(new JSONX509Signer(keyPair.getPrivate(), certPath, null)
                                .setSignatureCertificateAttributes(true));
        ArrayUtil.writeFile(baseSignatures + keyType + "certsigned.json", signedData);
        JSONParser.parse(signedData).getSignature(new JSONSignatureDecoder.Options()).verify(x509Verifier);
    }
}