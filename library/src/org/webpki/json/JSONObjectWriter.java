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
package org.webpki.json;

import java.io.IOException;
import java.io.Serializable;

import java.math.BigDecimal;
import java.math.BigInteger;

import java.security.GeneralSecurityException;
import java.security.PublicKey;

import java.security.cert.X509Certificate;

import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;

import java.security.spec.ECPoint;

import java.util.GregorianCalendar;
import java.util.Vector;

import java.util.regex.Pattern;

import org.webpki.crypto.AlgorithmPreferences;
import org.webpki.crypto.KeyAlgorithms;

import org.webpki.util.ArrayUtil;
import org.webpki.util.Base64URL;
import org.webpki.util.ISODateTime;

/**
 * Creates JSON objects and performs serialization according to ES6.
 * <p>
 * Also provides built-in support for encoding
 <a href="https://cyberphone.github.io/doc/security/jcs.html" target="_blank"><b>JCS (JSON Cleartext Signature)</b></a>, 
<a href="https://cyberphone.github.io/doc/security/jef.html" target="_blank"><b>JEF (JSON Encryption Format)</b></a>
and
<a href="https://tools.ietf.org/html/rfc7517" target="_blank"><b>JWK</b></a>
 objects.</p>
 */
public class JSONObjectWriter implements Serializable {

    private static final long serialVersionUID = 1L;

    static final int STANDARD_INDENT = 2;

    /**
     * Integers outside of this range are not natively supported by JSON.
     */
    public static final long MAX_SAFE_INTEGER = 9007199254740991L; // 2^53 - 1 ("53-bit precision")

    static final Pattern JS_ID_PATTERN    = Pattern.compile("[a-zA-Z$_]+[a-zA-Z$_0-9]*");
    static final Pattern JS_INDEX_PATTERN = Pattern.compile("[0-9]+");

    JSONObject root;

    StringBuffer buffer;

    int indent;

    boolean prettyPrint;

    boolean javaScriptMode;

    boolean htmlMode;

    int indentFactor;

    static String htmlVariableColor = "#008000";
    static String htmlStringColor   = "#0000C0";
    static String htmlPropertyColor = "#C00000";
    static String htmlKeywordColor  = "#606060";

    static int htmlIndent = 4;

    /**
     * Support interface for dynamic JSON generation.
     */
    public interface Dynamic {

        public JSONObjectWriter set(JSONObjectWriter wr) throws IOException;

    }

    /**
     * For updating already read JSON objects.
     *
     * @param objectReader Existing object reader
     * @throws IOException For any kind of underlying error...
     */
    public JSONObjectWriter(JSONObjectReader objectReader) throws IOException {
        this(objectReader.root);
        if (objectReader.root.properties.containsKey(null)) {
            throw new IOException("You cannot update array objects");
        }
    }

    /**
     * Creates a fresh JSON object and associated writer.
     */
    public JSONObjectWriter() {
        this(new JSONObject());
    }

    JSONObjectWriter(JSONObject root) {
        this.root = root;
    }

    JSONObjectWriter setProperty(String name, JSONValue value) throws IOException {
        root.setProperty(name, value);
        return this;
    }

    /**
     * Prepares the current object writer for a <i>rewrite</i> of a property.
     * @param name Name of property to be rewritten
     */
    public void setupForRewrite(String name) {
        root.properties.put(name, null);
    }

    /**
     * Set a <code>"string"</code> property.<p>
     * Sample:
     * <pre>
     *    "statement": "Life is good!"
     * </pre>
     * @param name Property
     * @param value Value
     * @return Current instance of {@link org.webpki.json.JSONObjectWriter}
     * @throws IOException &nbsp;
     */
    public JSONObjectWriter setString(String name, String value) throws IOException {
        return setProperty(name, new JSONValue(JSONTypes.STRING, value));
    }

    /**
     * Formats a number according to ES6.<p>
     * This code is emulating 7.1.12.1 of the EcmaScript V6 specification.</p>
     * @param value Value to be formatted
     * @return String representation
     * @throws IOException &nbsp;
     */
    public static String es6JsonNumberSerialization(double value) throws IOException {
        // 1. Check for JSON compatibility.
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IOException("NaN/Infinity are not permitted in JSON");
        }

        // 2.Deal with zero separately.  Note that this test takes "-0.0" as well
        if (value == 0.0) {
            return "0";
        }

        // 3. Call the DtoA algorithm crunchers
        // V8 FastDtoa can't convert all numbers, so try it first but
        // fall back to old DToA in case it fails
        String result = NumberFastDtoa.numberToString(value);
        if (result != null) {
            return result;
        }
        StringBuilder buffer = new StringBuilder();
        NumberDToA.JS_dtostr(buffer, NumberDToA.DTOSTR_STANDARD, 0, value);
        return buffer.toString();
    }

    static String es6JsonLongSerialization(long value) throws IOException {
        return Long.toString(JSONObjectReader.int53Check(value));
    }

    /**
     * Set an <code>int</code> property.<p>
     * Sample:
     * <pre>
     *    "headCount": 300
     * </pre>
     * @param name Property
     * @param value Value
     * @return Current instance of {@link org.webpki.json.JSONObjectWriter}
     * @throws IOException &nbsp;
     */
    public JSONObjectWriter setInt(String name, int value) throws IOException {
        return setInt53(name, value);
    }

    /**
     * Set a <code>long</code> property.<p>
     * Sample:</p>
     * <p><code>&nbsp;&nbsp;&nbsp;&nbsp;"quiteNegative": -800719925474099</code>
     * </p> Note that <code>long</code> data is limited to 53 bits of precision ({@value #MAX_SAFE_INTEGER}),
     * exceeding this limit throws an exception.
     * If you need higher precision use {@link JSONObjectWriter#setLong(String, long)}.
     * @param name Property
     * @param value Value
     * @return Current instance of {@link org.webpki.json.JSONObjectWriter}
     * @throws IOException &nbsp;
     * @see #MAX_SAFE_INTEGER
     */
    public JSONObjectWriter setInt53(String name, long value) throws IOException {
        return setProperty(name, new JSONValue(JSONTypes.NUMBER, es6JsonLongSerialization(value)));
    }

    /**
     * Set a <code>long</code> property.<p>
     * Sample:</p>
     * <p><code>&nbsp;&nbsp;&nbsp;&nbsp;"quiteLong": "89007199254740991"</code>
     * </p>Note: This method puts the value within quotes to provide full 64-bit precision
     * which does not have a native counterpart in JavaScript.
     * @param name Property
     * @param value Value
     * @return Current instance of {@link org.webpki.json.JSONObjectWriter}
     * @throws IOException &nbsp;
     * @see #setInt53(String, long)
     * @see #setBigInteger(String, BigInteger)
     */
    public JSONObjectWriter setLong(String name, long value) throws IOException {
        return setBigInteger(name, BigInteger.valueOf(value));
    }

    /**
     * Set a <code>double</code> property.<p>
     * Sample:
     * <pre>
     *    "Planck's Constant": 6.62607004e-34
     * </pre>
     * @param name Property
     * @param value Value
     * @return Current instance of {@link org.webpki.json.JSONObjectWriter}
     * @throws IOException &nbsp;
     */
    public JSONObjectWriter setDouble(String name, double value) throws IOException {
        return setProperty(name, new JSONValue(JSONTypes.NUMBER, es6JsonNumberSerialization(value)));
    }

    /**
     * Set a <code>BigInteger</code> property.<p>
     * Note: this is a <i>mapped</i> type since there is no <code>BigInteger</code> type in JSON.</p><p>
     * Sample:
     * <pre>
     *    "aPrettyHugeNumber": "94673335822222222222222222222222222222222222222222222"
     * </pre>
     * @param name Property
     * @param value Value
     * @return Current instance of {@link org.webpki.json.JSONObjectWriter}
     * @throws IOException &nbsp;
     */
    public JSONObjectWriter setBigInteger(String name, BigInteger value) throws IOException {
        return setString(name, value.toString());
    }

    static String bigDecimalToString(BigDecimal value, Integer decimals) {
        return (decimals == null ? value : value.setScale(decimals)).toPlainString();
    }

    /**
     * Set a <code>BigDecimal</code> property.<p>
     * Note: this is a <i>mapped</i> type since there is no <code>BigDecimal</code> type in JSON.</p><p>
     * Sample:
     * <pre>
     *    "amount": "568790.25"
     * </pre>
     * @param name Property
     * @param value Value
     * @return Current instance of {@link org.webpki.json.JSONObjectWriter}
     * @throws IOException &nbsp;
     * @see #setBigDecimal(String, BigDecimal, Integer)
     */
    public JSONObjectWriter setBigDecimal(String name, BigDecimal value) throws IOException {
        return setString(name, bigDecimalToString(value, null));
    }

    /**
     * Set a <code>BigDecimal</code> property.<p>
     * Note: this is a <i>mapped</i> type since there is no <code>BigDecimal</code> type in JSON.</p>
     * @param name Property
     * @param value Value
     * @param decimals Number of fractional digits
     * @return Current instance of {@link org.webpki.json.JSONObjectWriter}
     * @throws IOException &nbsp;
     * @see #setBigDecimal(String, BigDecimal)
     */
    public JSONObjectWriter setBigDecimal(String name, BigDecimal value, Integer decimals) throws IOException {
        return setString(name, bigDecimalToString(value, decimals));
    }

    /**
     * Set a <code>boolean</code> property.<p>
     * Sample:
     * <pre>
     *    "theEarthIsFlat": false
     * </pre>
     * @param name Property
     * @param value Value
     * @return Current instance of {@link org.webpki.json.JSONObjectWriter}
     * @throws IOException &nbsp;
     */
    public JSONObjectWriter setBoolean(String name, boolean value) throws IOException {
        return setProperty(name, new JSONValue(JSONTypes.BOOLEAN, Boolean.toString(value)));
    }

    /**
     * Set a <b>null</b> property.<p>
     * Sample:
     * <pre>
     *    "myKnowledgeOfTheLispProgrammingLanguage": null
     * </pre>
     * @param name Property
     * @return Current instance of {@link org.webpki.json.JSONObjectWriter}
     * @throws IOException &nbsp;
     * @see JSONObjectReader#getIfNULL(String)
     */
    public JSONObjectWriter setNULL(String name) throws IOException {
        return setProperty(name, new JSONValue(JSONTypes.NULL, "null"));
    }

    /**
     * Set an ISO formatted <code>dateTime</code> property.<p>
     * Note: this is a <i>mapped</i> type since there is no <code>dateTime</code> type in JSON.</p><p>
     * Sample:
     * <pre>
     *    "received": "2016-11-12T09:22:36Z"
     * </pre>
     * @param name Property
     * @param dateTime Date/time value
     * @param forceUtc <code>true</code> for UTC, <code>false</code> for local time
     * @return Current instance of {@link org.webpki.json.JSONObjectWriter}
     * @throws IOException &nbsp;
     * @see org.webpki.util.ISODateTime#formatDateTime(GregorianCalendar, boolean)
     */
    public JSONObjectWriter setDateTime(String name, GregorianCalendar dateTime, boolean forceUtc) throws IOException {
        return setString(name, ISODateTime.formatDateTime(dateTime, forceUtc));
    }

    /**
     * Set a <code>byte[]</code> property.<p>
     * This method utilizes Base64Url encoding.</p><p>
     * Sample:
     * <pre>
     *    "nonce": "lNxNvAUEE8t7DSQBft93LVSXxKCiVjhbWWfyg023FCk"
     * </pre>
     * @param name Property
     * @param value Array of bytes
     * @return Current instance of {@link org.webpki.json.JSONObjectWriter}
     * @throws IOException &nbsp;
     * @see Base64URL#encode(byte[])
     */
    public JSONObjectWriter setBinary(String name, byte[] value) throws IOException {
        return setString(name, Base64URL.encode(value));
    }

    /**
     * Set a JSON object.<p>
     * This method assigns a property name to an already existing object reader
     * which is useful for wrapping JSON objects.</p>
     * @param name Property
     * @param objectReader Object reader
     * @return Current instance of {@link org.webpki.json.JSONObjectWriter}
     * @throws IOException &nbsp;
     */
    public JSONObjectWriter setObject(String name, JSONObjectReader objectReader) throws IOException {
        setProperty(name, new JSONValue(JSONTypes.OBJECT, objectReader.root));
        return this;
    }

    /**
     * Set a JSON object.<p>
     * This method assigns a property name to an already created object writer
     * which is useful for nested JSON objects.</p>
     * @param name Property
     * @param objectWriter Object writer
     * @return Current instance of {@link org.webpki.json.JSONObjectWriter}
     * @throws IOException &nbsp;
     */
    public JSONObjectWriter setObject(String name, JSONObjectWriter objectWriter) throws IOException {
        setProperty(name, new JSONValue(JSONTypes.OBJECT, objectWriter.root));
        return this;
    }

    /**
     * Set (create) a JSON object.<p>
     * This method creates an empty JSON object and links it to the current object through a property.</p> 
     * @param name Property
     * @return New instance of {@link org.webpki.json.JSONObjectWriter}
     * @throws IOException &nbsp;
     */
    public JSONObjectWriter setObject(String name) throws IOException {
        JSONObjectWriter writer = new JSONObjectWriter();
        setProperty(name, new JSONValue(JSONTypes.OBJECT, writer.root));
        return writer;
    }

    /**
     * Set (create) a JSON array.<p>
     * This method creates an empty JSON array and links it to the current object through a property.</p> 
     * @param name Property
     * @return New instance of {@link org.webpki.json.JSONArrayWriter}
     * @throws IOException &nbsp;
     */
    public JSONArrayWriter setArray(String name) throws IOException {
        JSONArrayWriter array = new JSONArrayWriter();
        setProperty(name, new JSONValue(JSONTypes.ARRAY, array.array));
        return array;
    }

    /**
     * Set a JSON array.<p>
     * This method assigns a property name to an already created array writer
     * which is useful for nested JSON objects.</p>
     * @param name Property
     * @param arrayWriter Array writer
     * @return Current instance of {@link org.webpki.json.JSONObjectWriter}
     * @throws IOException &nbsp;
     */
    public JSONObjectWriter setArray(String name, JSONArrayWriter arrayWriter) throws IOException {
        setProperty(name, new JSONValue(JSONTypes.ARRAY, arrayWriter.array));
        return this;
    }

    JSONObjectWriter setStringArray(String name, String[] values, JSONTypes jsonType) throws IOException {
        Vector<JSONValue> array = new Vector<JSONValue>();
        for (String value : values) {
            array.add(new JSONValue(jsonType, value));
        }
        return setProperty(name, new JSONValue(JSONTypes.ARRAY, array));
    }

    /**
     * Set an array of <code>byte[]</code> property.<p>
     * This method puts each byte array (after Base64Url encoding) into a single JSON array.</p><p>
     * Sample:
     * <pre>
     *    "blobs": ["lNxNvAUEE8t7DSQBft93LVSXxKCiVjhbWWfyg023FCk","LmTlQxXB3LgZrNLmhOfMaCnDizczC_RfQ6Kx8iNwfFA"]
     * </pre>
     * @param name Property
     * @param values Vector holding arrays of bytes
     * @return Current instance of {@link org.webpki.json.JSONObjectWriter}
     * @throws IOException &nbsp;
     * @see Base64URL#encode(byte[])
     */
    public JSONObjectWriter setBinaryArray(String name, Vector<byte[]> values) throws IOException {
        Vector<String> array = new Vector<String>();
        for (byte[] value : values) {
            array.add(Base64URL.encode(value));
        }
        return setStringArray(name, array.toArray(new String[0]));
    }

    /**
     * Set a <code>String[]</code> property.<p>
     * This method puts each <code>String</code> into a single JSON array.</p><p>
     * Sample:
     * <pre>
     *    "usPresidents": ["Clinton","Bush","Obama","Trump"]
     * </pre>
     * @param name Property
     * @param values Array of <code>String</code>
     * @return Current instance of {@link org.webpki.json.JSONObjectWriter}
     * @throws IOException &nbsp;
     */
    public JSONObjectWriter setStringArray(String name, String[] values) throws IOException {
        return setStringArray(name, values, JSONTypes.STRING);
    }

    /**
     * Set JSON data using an external (dynamic) interface.<p>
     * Sample using a construct suitable for chained writing:
     * <pre>
     *    setDynamic((wr) -&gt; optionalString == null ? wr : wr.setString("opt", optionalString)); 
     * </pre>
     * @param jsonSetDynamic Interface (usually Lambda)
     * @return An instance of {@link org.webpki.json.JSONObjectWriter}
     * @throws IOException &nbsp;
     */
    public JSONObjectWriter setDynamic(Dynamic jsonSetDynamic) throws IOException {
        return jsonSetDynamic.set(this);
    }

    void setCurvePoint(BigInteger value, String name, KeyAlgorithms ec) throws IOException {
        byte[] curvePoint = value.toByteArray();
        if (curvePoint.length > (ec.getPublicKeySizeInBits() + 7) / 8) {
            if (curvePoint[0] != 0) {
                throw new IOException("Unexpected EC \"" + name + "\" value");
            }
            setCryptoBinary(value, name);
        } else {
            while (curvePoint.length < (ec.getPublicKeySizeInBits() + 7) / 8) {
                curvePoint = ArrayUtil.add(new byte[]{0}, curvePoint);
            }
            setBinary(name, curvePoint);
        }
    }

    void setCryptoBinary(BigInteger value, String name) throws IOException {
        byte[] cryptoBinary = value.toByteArray();
        if (cryptoBinary[0] == 0x00) {
            byte[] woZero = new byte[cryptoBinary.length - 1];
            System.arraycopy(cryptoBinary, 1, woZero, 0, woZero.length);
            cryptoBinary = woZero;
        }
        setBinary(name, cryptoBinary);
    }

    private void coreSign(JSONSigner signer, JSONObjectWriter signatureWriter) throws IOException {
        signatureWriter.setString(JSONCryptoDecoder.ALG_JSON,
                signer.getAlgorithm().getAlgorithmId(signer.algorithmPreferences));

        // "kid" is always optional
        if (signer.keyId != null) {
            signatureWriter.setString(JSONCryptoDecoder.KID_JSON, signer.keyId);
        }

        // "jku"/"x5u" and "jwk"/"x5c" are mutually exclusive
        if (signer.remoteUrl == null) {
            if (signer.outputPublicKeyInfo) {
                signer.writeKeyData(signatureWriter);
            }
        } else {
            signatureWriter.setString(signer.remoteKeyFormat.jsonName, signer.remoteUrl);
        }

        // Optional extensions
        if (signer.extensions != null) {
            JSONArrayWriter extensions = signatureWriter.setArray(JSONCryptoDecoder.CRIT_JSON);
            for (String property : signer.extensions.getProperties()) {
                extensions.setString(property);
                signatureWriter.setProperty(property, signer.extensions.getProperty(property));
            }
        }

        // Optional excluded properties
        JSONObjectWriter signedObject = this;
        if (signer.excluded != null) {
            JSONObjectReader rd = new JSONObjectReader(this).clone();
            for (String property : signer.excluded) {
                if (!rd.hasProperty(property)) {
                    throw new IOException("Missing \"" + JSONCryptoDecoder.EXCL_JSON + "\" property: " + property);
                }
                rd.removeProperty(property);
            }
            signedObject = new JSONObjectWriter(rd);
            signatureWriter.setStringArray(JSONCryptoDecoder.EXCL_JSON, signer.excluded);
        }

        // Finally, the signature itself
        signatureWriter.setBinary(JSONCryptoDecoder.VAL_JSON,
                                  signer.signData(signer.normalizedData = 
                                          signedObject.serializeToBytes(JSONOutputFormats.NORMALIZED)));
    }

    /**
     * Set a <a href="https://cyberphone.github.io/doc/security/jcs.html" target="_blank"><b>JCS</b></a>
     * <code>"signature"</code>object.<p>
     * This method performs all the processing needed for adding a JCS signature to the current object.</p>
     * @param signer The interface to the signing key and type
     * @return Current instance of {@link org.webpki.json.JSONObjectWriter}
     * @throws IOException In case there a problem with keys etc.
     * <br>&nbsp;<br><b>Sample Code:</b>
     <pre>
import java.io.IOException;

import java.security.PrivateKey;
import java.security.PublicKey;

import org.webpki.crypto.AsymKeySignerInterface;
import org.webpki.crypto.AsymSignatureAlgorithms;
import org.webpki.crypto.SignatureWrapper;

import org.webpki.json.JSONAsymKeySigner;
import org.webpki.json.JSONAsymKeyVerifier;
import org.webpki.json.JSONObjectReader;
import org.webpki.json.JSONObjectWriter;
import org.webpki.json.JSONParser;
import org.webpki.json.JSONSignatureDecoder;
           .
           .
           .
    public void signAndVerifyJCS(PrivateKey privateKey, PublicKey publicKey) throws IOException {
    
        // Create an empty JSON document
        JSONObjectWriter writer = new JSONObjectWriter();
    
        // Fill it with some data
        writer.setString("myProperty", "Some data");
    
        // Sign document
        writer.setSignature(new JSONAsymKeySigner(privateKey, publicKey, null));

        // Serialize document
        String json = writer.toString();
    
        // Print signed document on the console
        System.out.println(json);
</pre>
<div id="verify" style="display:inline-block;background:#F8F8F8;border-width:1px;border-style:solid;border-color:grey;padding:0pt 10pt 0pt 10pt;box-shadow:3pt 3pt 3pt #D0D0D0"><pre>{
  "<span style="color:#C00000">myProperty</span>": "<span style="color:#0000C0">Some data</span>",
  "<span style="color:#C00000">signature</span>": {
    "<span style="color:#C00000">alg</span>": "<span style="color:#0000C0">ES256</span>",
    "<span style="color:#C00000">jwk</span>": {
      "<span style="color:#C00000">kty</span>": "<span style="color:#0000C0">EC</span>",
      "<span style="color:#C00000">crv</span>": "<span style="color:#0000C0">P-256</span>",
      "<span style="color:#C00000">x</span>": "<span style="color:#0000C0">vlYxD4dtFJOp1_8_QUcieWCW-4KrLMmFL2rpkY1bQDs</span>",
      "<span style="color:#C00000">y</span>": "<span style="color:#0000C0">fxEF70yJenP3SPHM9hv-EnvhG6nXr3_S-fDqoj-F6yM</span>"
    },
    "<span style="color:#C00000">val</span>": "<span style="color:#0000C0">23NSrdC9ol5N3-wYPxdV4w8Ylm_mhUNijbCuJ3G_DqWGiN5j8X5qZxyBo2yy8kGou4yBh74egauup7u2KYytLQ</span>"
  }
}
</pre></div>    
<pre>
        // Parse document
        JSONObjectReader reader = JSONParser.parse(json);
    
        // Get and verify signature
        JSONSignatureDecoder signature = reader.getSignature(new JSONCryptoDecoder.Options());
        signature.verify(new JSONAsymKeyVerifier(publicKey));
    
        // Print document payload on the console
        System.out.println("Returned data: " + reader.getString("myProperty"));
    }
</pre>
    */
    public JSONObjectWriter setSignature(JSONSigner signer) throws IOException {
        return setSignature(JSONCryptoDecoder.SIGNATURE_JSON, signer);
    }
    
    public JSONObjectWriter setSignature(String signatureLabel, JSONSigner signer) throws IOException {
        coreSign(signer, setObject(signatureLabel));
        return this;
    }
    /**
     * Set a <a href="https://cyberphone.github.io/doc/security/jcs.html" target="_blank"><b>JCS</b></a>
     * <code>"signatures"</code> [] object.<p>
     * This method performs all the processing needed for adding multiple JCS signatures to the current object.</p>
     * @param signers List with signature interfaces
     * @return Current instance of {@link org.webpki.json.JSONObjectWriter}
     * @throws IOException In case there a problem with keys etc.
     */
    @SuppressWarnings("unchecked") 
    public JSONObjectWriter setSignatures(Vector<JSONSigner> signers) throws IOException {
        if (signers.isEmpty()) {
            throw new IOException("Empty signer list");
        }
        setArray(JSONCryptoDecoder.SIGNATURES_JSON);
        Vector<JSONObject> signatures = new Vector<JSONObject>();
        for (JSONSigner signer : signers) {
            setupForRewrite(JSONCryptoDecoder.SIGNATURES_JSON);
            coreSign(signer, setArray(JSONCryptoDecoder.SIGNATURES_JSON).setObject());
            signatures.addAll((Vector<JSONObject>) root.properties.get(JSONCryptoDecoder.SIGNATURES_JSON).value);
        }
        root.properties.put(JSONCryptoDecoder.SIGNATURES_JSON, new JSONValue(JSONTypes.ARRAY, signatures));
        return this;
    }

    /**
     * Create a <a href="https://cyberphone.github.io/doc/security/jcs.html" target="_blank">JCS</a>
     * (<a href="https://tools.ietf.org/html/rfc7517" target="_blank"><b>JWK</b></a>) formatted public key.<p>
     * Typical use:
     *<pre>
    setObject("myPublicKey", JSONObjectWriter.setCorePublicKey(myPublicKey, AlgorithmPreferences.JOSE);
</pre>
     * Resulting JSON:
     * <pre>
    "myPublicKey": {
         .
      <i>depends on the actual public key type and value</i>   
         .
    }
</pre>
     * @param publicKey Public key value
     * @param algorithmPreferences JOSE or SKS algorithm notation
     * @return New instance of {@link org.webpki.json.JSONObjectWriter}
     * @throws IOException &nbsp;
     */
    public static JSONObjectWriter createCorePublicKey(PublicKey publicKey, AlgorithmPreferences algorithmPreferences) throws IOException {
        JSONObjectWriter corePublicKey = new JSONObjectWriter();
        KeyAlgorithms keyAlg = KeyAlgorithms.getKeyAlgorithm(publicKey);
        if (keyAlg.isRSAKey()) {
            corePublicKey.setString(JSONCryptoDecoder.KTY_JSON, JSONCryptoDecoder.RSA_PUBLIC_KEY);
            RSAPublicKey rsaPublicKey = (RSAPublicKey) publicKey;
            corePublicKey.setCryptoBinary(rsaPublicKey.getModulus(), JSONCryptoDecoder.N_JSON);
            corePublicKey.setCryptoBinary(rsaPublicKey.getPublicExponent(), JSONCryptoDecoder.E_JSON);
        } else {
            corePublicKey.setString(JSONCryptoDecoder.KTY_JSON, JSONCryptoDecoder.EC_PUBLIC_KEY);
            corePublicKey.setString(JSONCryptoDecoder.CRV_JSON, keyAlg.getAlgorithmId(algorithmPreferences));
            ECPoint ecPoint = ((ECPublicKey) publicKey).getW();
            corePublicKey.setCurvePoint(ecPoint.getAffineX(), JSONCryptoDecoder.X_JSON, keyAlg);
            corePublicKey.setCurvePoint(ecPoint.getAffineY(), JSONCryptoDecoder.Y_JSON, keyAlg);
        }
        return corePublicKey;
    }

    /**
     * Set a <a href="https://cyberphone.github.io/doc/security/jcs.html" target="_blank">JCS</a>
     * (<a href="https://tools.ietf.org/html/rfc7517" target="_blank"><b>JWK</b></a>) formatted public key.<p>
     * Resulting JSON:
     * <pre>
    "publicKey": {
         .
      <i>depends on the actual public key type and value</i>   
         .
    }
</pre>
     * @param publicKey Public key value
     * @param algorithmPreferences JOSE or SKS algorithm notation
     * @return Current instance of {@link org.webpki.json.JSONObjectWriter}
     * @throws IOException &nbsp;
     */
    public JSONObjectWriter setPublicKey(PublicKey publicKey, AlgorithmPreferences algorithmPreferences) throws IOException {
        return setObject(JSONCryptoDecoder.JWK_JSON, createCorePublicKey(publicKey, algorithmPreferences));
    }

    /**
     * Set a <a href="https://cyberphone.github.io/doc/security/jcs.html" target="_blank">JCS</a>
     * (<a href="https://tools.ietf.org/html/rfc7517" target="_blank"><b>JWK</b></a>) formatted public key.<p>
     * This method is equivalent to {@link #setPublicKey(PublicKey, AlgorithmPreferences)}
     * using {@link AlgorithmPreferences#JOSE} as second argument.</p>
     * @param publicKey Public key value
     * @return Current instance of {@link org.webpki.json.JSONObjectWriter}
     * @throws IOException &nbsp;
     */
    public JSONObjectWriter setPublicKey(PublicKey publicKey) throws IOException {
        return setPublicKey(publicKey, AlgorithmPreferences.JOSE);
    }

    /**
     * Set a <a href="https://cyberphone.github.io/doc/security/jcs.html" target="_blank"><b>JCS</b></a>
     * certificate path property.
     * <p>Each path element (certificate) is base64url encoded and the path must be
     * <i>sorted</i> where certificate[i] is signed by certificate[i + 1].</p><p>
     * Resulting JSON:
     * <pre>
    "certificatePath": ["MIIETTCCAjWgAwIBAgIGAUoqo74...gfdd" {,...}]
</pre>
     * @param certificatePath Sorted certificate path array
     * @return Current instance of {@link org.webpki.json.JSONObjectWriter}
     * @throws IOException &nbsp;
     */
    public JSONObjectWriter setCertificatePath(X509Certificate[] certificatePath) throws IOException {
        return setArray(JSONCryptoDecoder.X5C_JSON, 
                        JSONArrayWriter.createCoreCertificatePath(certificatePath));
    }

    /**
     * Create a <a href="https://cyberphone.github.io/doc/security/jef.html" target="_blank"><b>JEF</b></a>
     * encrypted object.
     * @param unencryptedData Data to be encrypted
     * @param contentEncryptionAlgorithm Content encryption algorithm
     * @param encrypter Holds keys etc.
     * @return New instance of {@link org.webpki.json.JSONObjectWriter}
     * @throws IOException &nbsp;
     * @throws GeneralSecurityException &nbsp;
     */
    public static JSONObjectWriter createEncryptionObject(byte[] unencryptedData,
                                                          ContentEncryptionAlgorithms contentEncryptionAlgorithm,
                                                          JSONEncrypter encrypter)
    throws IOException, GeneralSecurityException {
        JSONEncrypter.EncryptionHeader encryptionHeader = 
                new JSONEncrypter.EncryptionHeader(contentEncryptionAlgorithm, encrypter);
        JSONObjectWriter recipient = new JSONObjectWriter();
        encryptionHeader.createRecipient(encrypter, recipient);
        encryptionHeader.cleanRecipient(recipient);
        for (String property : recipient.root.properties.keySet()) {
            encryptionHeader.encryptionWriter.setProperty(property, recipient.root.properties.get(property));
        }
        return encryptionHeader.finalizeEncryption(unencryptedData);
    }

    /**
     * Create a <a href="https://cyberphone.github.io/doc/security/jef.html" target="_blank"><b>JEF</b></a>
     * encrypted object fo multiple recipients.
     * @param unencryptedData Data to be encrypted
     * @param contentEncryptionAlgorithm Content encryption algorithm
     * @param encrypters Holds keys etc.
     * @return New instance of {@link org.webpki.json.JSONObjectWriter}
     * @throws IOException &nbsp;
     * @throws GeneralSecurityException &nbsp;
     */
    public static JSONObjectWriter createEncryptionObject(byte[] unencryptedData,
                                                          ContentEncryptionAlgorithms contentEncryptionAlgorithm,
                                                          Vector<JSONEncrypter> encrypters)
    throws IOException, GeneralSecurityException {
        if (encrypters.isEmpty()) {
            throw new IOException("Empty encrypter list");
        }
        JSONEncrypter.EncryptionHeader encryptionHeader = 
                new JSONEncrypter.EncryptionHeader(contentEncryptionAlgorithm, encrypters.firstElement());
        Vector<JSONObjectWriter> recipents = new Vector<JSONObjectWriter>();
        for (JSONEncrypter encrypter : encrypters) {
            if (encrypter.keyEncryptionAlgorithm == null || !encrypter.keyEncryptionAlgorithm.keyWrap) {
                throw new IOException("Multiple encryptions only permitted for key wrapping schemes");
            }
            JSONObjectWriter currentRecipient = new JSONObjectWriter();
            encryptionHeader.createRecipient(encrypter, currentRecipient);
            recipents.add(currentRecipient);
        }
        JSONArrayWriter recipientList = 
                encryptionHeader.encryptionWriter.setArray(JSONCryptoDecoder.RECIPIENTS_JSON);
        for (JSONObjectWriter recipient : recipents) {
            encryptionHeader.cleanRecipient(recipient);
            recipientList.setObject(recipient);
        }
        return encryptionHeader.finalizeEncryption(unencryptedData);
    }    


    ////////////////////////////////////////////////////////////////////////
    
    void newLine() {
        if (prettyPrint) {
            buffer.append(htmlMode ? "<br>" : "\n");
        }
    }

    void indentLine() {
        indent += indentFactor;
    }

    void undentLine() {
        indent -= indentFactor;
    }

    @SuppressWarnings("unchecked")
    void printOneElement(JSONValue jsonValue) throws IOException {
        switch (jsonValue.type) {
            case ARRAY:
                printArray((Vector<JSONValue>) jsonValue.value);
                break;

            case OBJECT:
                printObject((JSONObject) jsonValue.value);
                break;

            default:
                printSimpleValue(jsonValue, false);
        }
    }

    void newUndentSpace() {
        newLine();
        undentLine();
        spaceOut();
    }

    void newIndentSpace() {
        newLine();
        indentLine();
        spaceOut();
    }

    void printObject(JSONObject object) throws IOException {
        buffer.append('{');
        indentLine();
        boolean next = false;
        long lastIndex = Long.MIN_VALUE;
        for (String property : object.properties.keySet()) {
            long currentIndex = JS_INDEX_PATTERN.matcher(property).matches() ?
                                                      Long.valueOf(property) : Long.MAX_VALUE;
            if (currentIndex < lastIndex) {
                throw new IOException("For strict ES6+ compatibility, this JSON implementation requires properties " +
                                      "with numeric names (like \"2\":true) to be created first, and in ascending order");
            }
            lastIndex = currentIndex;
            JSONValue jsonValue = object.properties.get(property);
            if (next) {
                buffer.append(',');
            }
            newLine();
            next = true;
            printProperty(property);
            printOneElement(jsonValue);
        }
        newUndentSpace();
        buffer.append('}');
    }

    @SuppressWarnings("unchecked")
    void printArray(Vector<JSONValue> array) throws IOException {
        buffer.append('[');
        if (!array.isEmpty()) {
            boolean mixed = false;
            JSONTypes firstType = array.firstElement().type;
            for (JSONValue jsonValue : array) {
                if (firstType.complex != jsonValue.type.complex ||
                        (firstType.complex && firstType != jsonValue.type))

                {
                    mixed = true;
                    break;
                }
            }
            if (mixed || (array.size() == 1 && firstType == JSONTypes.OBJECT)) {
                boolean next = false;
                for (JSONValue value : array) {
                    if (next) {
                        buffer.append(',');
                    } else {
                        next = true;
                    }
                    printOneElement(value);
                }
            } else if (firstType == JSONTypes.OBJECT) {
                printArrayObjects(array);
            } else if (firstType == JSONTypes.ARRAY) {
                newIndentSpace();
                boolean next = false;
                for (JSONValue value : array) {
                    Vector<JSONValue> subArray = (Vector<JSONValue>) value.value;
                    if (next) {
                        buffer.append(',');
                    } else {
                        next = true;
                    }
                    printArray(subArray);
                }
                newUndentSpace();
            } else {
                printArraySimple(array);
            }
        }
        buffer.append(']');
    }

    void printArraySimple(Vector<JSONValue> array) {
        int i = 0;
        for (JSONValue value : array) {
            i += ((String) value.value).length();
        }
        boolean brokenLines = i > 100;
        boolean next = false;
        if (brokenLines) {
            indentLine();
            newLine();
        }
        for (JSONValue value : array) {
            if (next) {
                buffer.append(',');
                if (brokenLines) {
                    newLine();
                }
            }
            if (brokenLines) {
                spaceOut();
            }
            printSimpleValue(value, false);
            next = true;
        }
        if (brokenLines) {
            newUndentSpace();
        }
    }

    void printArrayObjects(Vector<JSONValue> array) throws IOException {
        boolean next = false;
        for (JSONValue value : array) {
            if (next) {
                buffer.append(',');
            }
            printObject((JSONObject) value.value);
            next = true;
        }
    }

    @SuppressWarnings("fallthrough")
    void printSimpleValue(JSONValue value, boolean property) {
        String string = (String) value.value;
        if (value.type != JSONTypes.STRING) {
            if (htmlMode) {
                buffer.append("<span style=\"color:")
                        .append(htmlVariableColor)
                        .append("\">");
            }
            buffer.append(string);
            if (htmlMode) {
                buffer.append("</span>");
            }
            return;
        }
        boolean quoted = !property || !javaScriptMode || !JS_ID_PATTERN.matcher(string).matches();
        if (htmlMode) {
            buffer.append("&quot;<span style=\"color:")
                    .append(property ? string.startsWith("@") ? htmlKeywordColor : htmlPropertyColor : htmlStringColor)
                    .append("\">");
        } else if (quoted) {
            buffer.append('"');
        }
        for (char c : string.toCharArray()) {
            if (htmlMode) {
                switch (c) {
/* 
      HTML needs specific escapes...
*/
                    case '<':
                        buffer.append("&lt;");
                        continue;

                    case '>':
                        buffer.append("&gt;");
                        continue;

                    case '&':
                        buffer.append("&amp;");
                        continue;

                    case '"':
                        buffer.append("\\&quot;");
                        continue;
                }
            }

            switch (c) {
                case '\\':
                case '"':
                    escapeCharacter(c);
                    break;

                case '\b':
                    escapeCharacter('b');
                    break;

                case '\f':
                    escapeCharacter('f');
                    break;

                case '\n':
                    escapeCharacter('n');
                    break;

                case '\r':
                    escapeCharacter('r');
                    break;

                case '\t':
                    escapeCharacter('t');
                    break;

                case '&':
                    if (javaScriptMode) {
                        buffer.append("\\u0026");
                        break;
                    }

                case '>':
                    if (javaScriptMode) {
                        buffer.append("\\u003e");
                        break;
                    }

                case '<':
                    if (javaScriptMode) {
                        buffer.append("\\u003c");
                        break;
                    }

                default:
                    if (c < 0x20) {
                        escapeCharacter('u');
                        for (int i = 0; i < 4; i++) {
                            int hex = c >>> 12;
                            buffer.append((char) (hex > 9 ? hex + 'a' - 10 : hex + '0'));
                            c <<= 4;
                        }
                        break;
                    }
                    buffer.append(c);
            }
        }
        if (htmlMode) {
            buffer.append("</span>&quot;");
        } else if (quoted) {
            buffer.append('"');
        }
    }

    void escapeCharacter(char c) {
        buffer.append('\\').append(c);
    }

    void singleSpace() {
        if (prettyPrint) {
            if (htmlMode) {
                buffer.append("&nbsp;");
            } else {
                buffer.append(' ');
            }
        }
    }

    void printProperty(String name) {
        spaceOut();
        printSimpleValue(new JSONValue(JSONTypes.STRING, name), true);
        buffer.append(':');
        singleSpace();
    }

    void spaceOut() {
        for (int i = 0; i < indent; i++) {
            singleSpace();
        }
    }

    /**
     * Serialize current object writer to a Java <code>String</code>.
     * @param outputFormat Any JSONOutputFormats
     * @return JSON string data
     * @throws IOException &nbsp;
     */
    @SuppressWarnings("unchecked")
    public String serializeToString(JSONOutputFormats outputFormat) throws IOException {
        buffer = new StringBuffer();
        indentFactor = outputFormat == JSONOutputFormats.PRETTY_HTML ? htmlIndent : STANDARD_INDENT;
        prettyPrint = outputFormat.pretty;
        javaScriptMode = outputFormat.javascript;
        htmlMode = outputFormat.html;
        if (root.properties.containsKey(null)) {
            printArray((Vector<JSONValue>) root.properties.get(null).value);
        } else {
            printObject(root);
        }
        if (!javaScriptMode) {
            newLine();
        }
        return buffer.toString();
    }

    /**
     * Serialize current object writer to a Java <code>byte[]</code>.
     * @param outputFormat Any JSONOutputFormats
     * @return JSON UTF-8 data
     * @throws IOException &nbsp;
     */
    public byte[] serializeToBytes(JSONOutputFormats outputFormat) throws IOException {
        return serializeToString(outputFormat).getBytes("UTF-8");
    }

    /**
     * Pretty print JSON of current object writer. 
     */
    @Override
    public String toString() {
        try {
            return serializeToString(JSONOutputFormats.PRETTY_PRINT);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
