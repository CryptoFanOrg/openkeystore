/*
 *  Copyright 2006-2016 WebPKI.org (http://webpki.org).
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
package org.webpki.kg2xml;

import static org.webpki.kg2xml.KeyGen2Constants.*;

import java.io.IOException;
import java.io.Serializable;

import java.security.GeneralSecurityException;
import java.security.PublicKey;

import java.security.cert.X509Certificate;

import java.security.interfaces.ECPublicKey;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.Vector;

import org.webpki.crypto.DeviceID;
import org.webpki.crypto.HashAlgorithms;
import org.webpki.crypto.KeyAlgorithms;
import org.webpki.crypto.MACAlgorithms;
import org.webpki.crypto.SymKeyVerifierInterface;

import org.webpki.sks.AppUsage;
import org.webpki.sks.BiometricProtection;
import org.webpki.sks.DeleteProtection;
import org.webpki.sks.ExportProtection;
import org.webpki.sks.InputMethod;
import org.webpki.sks.Grouping;
import org.webpki.sks.PassphraseFormat;
import org.webpki.sks.PatternRestriction;
import org.webpki.sks.SecureKeyStore;

import org.webpki.util.ArrayUtil;
import org.webpki.util.MimeTypedObject;

import org.webpki.xml.DOMWriterHelper;

import org.webpki.xmldsig.XMLSymKeyVerifier;

public class ServerState implements Serializable
  {
    private static final long serialVersionUID = 1L;
    
    public enum ProtocolPhase {PLATFORM_NEGOTIATION,
                               PROVISIONING_INITIALIZATION,
                               CREDENTIAL_DISCOVERY,
                               KEY_CREATION,
                               PROVISIONING_FINALIZATION,
                               DONE};

    enum PostOperation
      {
        DELETE_KEY            (SecureKeyStore.METHOD_POST_DELETE_KEY,           DELETE_KEY_ELEM), 
        UNLOCK_KEY            (SecureKeyStore.METHOD_POST_UNLOCK_KEY,           UNLOCK_KEY_ELEM), 
        UPDATE_KEY            (SecureKeyStore.METHOD_POST_UPDATE_KEY,           UPDATE_KEY_ELEM), 
        CLONE_KEY_PROTECTION  (SecureKeyStore.METHOD_POST_CLONE_KEY_PROTECTION, CLONE_KEY_PROTECTION_ELEM);
        
        private byte[] method;
        
        private String xml_elem;
        
        PostOperation (byte[] method, String xml_elem)
          {
            this.method = method;
            this.xml_elem = xml_elem;
          }

        byte[] getMethod ()
          {
            return method;
          }
        
        String getXMLElem ()
          {
            return xml_elem;
          }
      }
    
    class PostProvisioningTargetKey implements Serializable
      {
        private static final long serialVersionUID = 1L;
        
        String client_session_id;
        
        String server_session_id;
        
        PublicKey key_management_key;
      
        byte[] certificate_data;
        
        PostOperation post_operation;
        
        PostProvisioningTargetKey (String client_session_id,
                                   String server_session_id,
                                   byte[] certificate_data,
                                   PublicKey key_management_key,
                                   PostOperation post_operation)
          {
            this.client_session_id = client_session_id;
            this.server_session_id = server_session_id;
            this.certificate_data = certificate_data;
            this.key_management_key = key_management_key;
            this.post_operation = post_operation;
          }
  
        public boolean equals (Object o)
          {
            return o instanceof PostProvisioningTargetKey && 
                   client_session_id.equals(((PostProvisioningTargetKey)o).client_session_id) &&
                   server_session_id.equals (((PostProvisioningTargetKey)o).server_session_id) &&
                   ArrayUtil.compare (certificate_data, ((PostProvisioningTargetKey)o).certificate_data);
          }
      }
  
    Vector<PostProvisioningTargetKey> post_operations = new Vector<PostProvisioningTargetKey> ();
  
    public abstract class ExtensionInterface implements Serializable
      {
        private static final long serialVersionUID = 1L;

        String type;
        
        public String getType ()
          {
            return type;
          }
        
        public abstract byte getSubType ();
        
        public String getQualifier () throws IOException
          {
            return "";
          }
        
        public abstract byte[] getExtensionData () throws IOException;
        
        abstract void writeExtension (DOMWriterHelper wr, byte[] mac_data) throws IOException;
        
        void writeCore (DOMWriterHelper wr, byte[] mac_data) throws IOException
          {
            wr.setBinaryAttribute (MAC_ATTR, mac_data);
            wr.setStringAttribute (TYPE_ATTR, type);
          }
        
        ExtensionInterface (String type)
          {
            this.type = type;
          }
      }

    public class Extension extends ExtensionInterface implements Serializable
      {
        private static final long serialVersionUID = 1L;

        byte[] data;

        Extension (String type, byte[] data)
          {
            super (type);
            this.data = data;
          }

        public byte getSubType ()
          {
            return SecureKeyStore.SUB_TYPE_EXTENSION;
          }

        public byte[] getExtensionData () throws IOException
          {
            return data;
          }

        void writeExtension (DOMWriterHelper wr, byte[] mac_data) throws IOException
          {
            wr.addBinary (EXTENSION_ELEM, data);
            writeCore (wr, mac_data);
          }
      }

    public class EncryptedExtension extends ExtensionInterface implements Serializable
      {
        private static final long serialVersionUID = 1L;

        byte[] encrypted_data;

        EncryptedExtension (String type, byte[] encrypted_data)
          {
            super (type);
            this.encrypted_data = encrypted_data;
          }

        public byte getSubType ()
          {
            return SecureKeyStore.SUB_TYPE_ENCRYPTED_EXTENSION;
          }

        public byte[] getExtensionData () throws IOException
          {
            return encrypted_data;
          }

        void writeExtension (DOMWriterHelper wr, byte[] mac_data) throws IOException
          {
            wr.addBinary (ENCRYPTED_EXTENSION_ELEM, encrypted_data);
            writeCore (wr, mac_data);
          }
      }

    public class Logotype extends ExtensionInterface implements Serializable
      {
        private static final long serialVersionUID = 1L;

        MimeTypedObject logotype;

        Logotype (String type, MimeTypedObject logotype)
          {
            super (type);
            this.logotype = logotype;
          }

        public byte getSubType ()
          {
            return SecureKeyStore.SUB_TYPE_LOGOTYPE;
          }

        public byte[] getExtensionData () throws IOException
          {
            return logotype.getData ();
          }

        public String getQualifier () throws IOException
          {
            return logotype.getMimeType ();
          }

        void writeExtension (DOMWriterHelper wr, byte[] mac_data) throws IOException
          {
            wr.addBinary (LOGOTYPE_ELEM, logotype.getData ());
            writeCore (wr, mac_data);
            wr.setStringAttribute (MIME_TYPE_ATTR, logotype.getMimeType ());
          }
      }

    public class Property implements Serializable
      {
        private static final long serialVersionUID = 1L;

        String name;

        String value;

        boolean writable;
        
        private Property () {}
        
        public String getName ()
          {
            return name;
          }
        
        public String getValue ()
          {
            return value;
          }
        
        public boolean isWritable ()
          {
            return writable;
          }
      }

    public class PropertyBag extends ExtensionInterface implements Serializable
      {
        private static final long serialVersionUID = 1L;

        LinkedHashMap<String,Property> properties = new LinkedHashMap<String,Property> ();

        public PropertyBag addProperty (String name, String value, boolean writable) throws IOException
          {
            Property property = new Property ();
            property.name = name;
            property.value = value;
            property.writable = writable;
            if (properties.put (name, property) != null)
              {
                throw new IOException ("Duplicate property name \"" + name + "\" not allowed");
              }
            return this;
          }

        PropertyBag (String type)
          {
            super (type);
          }
        
        public byte getSubType ()
          {
            return SecureKeyStore.SUB_TYPE_PROPERTY_BAG;
          }
        
        public byte[] getExtensionData () throws IOException
          {
            MacGenerator convert = new MacGenerator ();
            for (Property prop : properties.values ())
              {
                convert.addString (prop.name);
                convert.addBool (prop.writable);
                convert.addString (prop.value);
               }
            return convert.getResult ();
          }
        
        public Property[] getProperties ()
          {
            return properties.values ().toArray (new Property[0]);
          }

        void writeExtension (DOMWriterHelper wr, byte[] mac_data) throws IOException
          {
            if (properties.isEmpty ())
              {
                throw new IOException ("Empty " + PROPERTY_BAG_ELEM + ": " + type);
              }
            wr.addChildElement (PROPERTY_BAG_ELEM);
            writeCore (wr, mac_data);
            for (Property property : properties.values ())
              {
                wr.addChildElement (PROPERTY_ELEM);
                wr.setStringAttribute (NAME_ATTR, property.name);
                wr.setStringAttribute (VALUE_ATTR, property.value);
                if (property.writable)
                  {
                    wr.setBooleanAttribute (WRITABLE_ATTR, property.writable);
                  }
                wr.getParent ();
              }
            wr.getParent ();
          }
      }


    public class PUKPolicy implements Serializable
      {
        private static final long serialVersionUID = 1L;

        String id;
        
        byte[] encrypted_value;

        public String getID ()
          {
            return id;
          }

        PassphraseFormat format;

        int retry_limit;

        PUKPolicy (byte[] encrypted_value, PassphraseFormat format, int retry_limit) throws IOException
          {
            this.encrypted_value = encrypted_value;
            this.id = puk_prefix + ++next_puk_id_suffix;
            this.format = format;
            this.retry_limit = retry_limit;
          }

        void writePolicy (DOMWriterHelper wr) throws IOException
          {
            wr.addChildElement (PUK_POLICY_SPECIFIER_ELEM);

            wr.setStringAttribute (ID_ATTR, id);
            wr.setIntAttribute (RETRY_LIMIT_ATTR, retry_limit);
            wr.setStringAttribute (FORMAT_ATTR, format.getProtocolName ());
            wr.setBinaryAttribute (ENCRYPTED_PUK_ATTR, encrypted_value);

            MacGenerator puk_policy_mac = new MacGenerator ();
            puk_policy_mac.addString (id);
            puk_policy_mac.addArray (encrypted_value);
            puk_policy_mac.addByte (format.getSksValue ());
            puk_policy_mac.addShort (retry_limit);
            wr.setBinaryAttribute (MAC_ATTR, mac (puk_policy_mac.getResult (), SecureKeyStore.METHOD_CREATE_PUK_POLICY));
          }
      }


    public class PINPolicy implements Serializable
      {
        private static final long serialVersionUID = 1L;

        boolean written;

        boolean not_first;

        byte[] preset_test;

        // Actual data


        PUKPolicy puk_policy; // Optional
        
        public PUKPolicy getPUKPolicy ()
          {
            return puk_policy;
          }


        boolean user_modifiable = true;
        
        boolean user_modifiable_set;
        
        public boolean getUserModifiable ()
          {
            return user_modifiable;
          }

        public PINPolicy setUserModifiable (boolean flag)
          {
            user_modifiable = flag;
            user_modifiable_set = true;
            return this;
          }
        
        boolean user_defined = true;
        
        public boolean getUserDefinedFlag ()
          {
            return user_defined;
          }


        PassphraseFormat format;

        int min_length;

        int max_length;

        int retry_limit;

        Grouping grouping; // Optional

        Set<PatternRestriction> pattern_restrictions = EnumSet.noneOf (PatternRestriction.class);

        InputMethod input_method; // Optional


        String id;
        
        public String getID ()
          {
            return id;
          }


        private PINPolicy ()
          {
            this.id = pin_prefix + ++next_pin_id_suffix;
          }

        void writePolicy (DOMWriterHelper wr) throws IOException
          {
            wr.addChildElement (PIN_POLICY_SPECIFIER_ELEM);
            wr.setStringAttribute (ID_ATTR, id);
            wr.setIntAttribute (MAX_LENGTH_ATTR, max_length);
            wr.setIntAttribute (MIN_LENGTH_ATTR, min_length);
            wr.setIntAttribute (RETRY_LIMIT_ATTR, retry_limit);
            if (user_modifiable_set)
              {
                wr.setBooleanAttribute (USER_MODIFIABLE_ATTR, user_modifiable);
              }
            if (grouping != null)
              {
                wr.setStringAttribute (GROUPING_ATTR, grouping.getProtocolName ());
              }
            wr.setStringAttribute (FORMAT_ATTR, format.getProtocolName ());
            if (!pattern_restrictions.isEmpty ())
              {
                Vector<String> prs = new Vector<String> ();
                for (PatternRestriction pr : pattern_restrictions)
                  {
                    prs.add (pr.getProtocolName ());
                  }
                wr.setListAttribute (PATTERN_RESTRICTIONS_ATTR, prs.toArray (new String[0]));
              }
            if (input_method != null)
              {
                wr.setStringAttribute (INPUT_METHOD_ATTR, input_method.getProtocolName ());
              }

            MacGenerator pin_policy_mac = new MacGenerator ();
            pin_policy_mac.addString (id);
            pin_policy_mac.addString (puk_policy == null ? SecureKeyStore.CRYPTO_STRING_NOT_AVAILABLE : puk_policy.id);
            pin_policy_mac.addBool (user_defined);
            pin_policy_mac.addBool (user_modifiable);
            pin_policy_mac.addByte (format.getSksValue ());
            pin_policy_mac.addShort (retry_limit);
            pin_policy_mac.addByte (grouping == null ? Grouping.NONE.getSksValue () : grouping.getSksValue ());
            pin_policy_mac.addByte (PatternRestriction.getSksValue (pattern_restrictions));
            pin_policy_mac.addShort (min_length);
            pin_policy_mac.addShort (max_length);
            pin_policy_mac.addByte (input_method == null ? InputMethod.ANY.getSksValue () : input_method.getSksValue ());
            wr.setBinaryAttribute (MAC_ATTR, mac (pin_policy_mac.getResult (), SecureKeyStore.METHOD_CREATE_PIN_POLICY));
          }

        public PINPolicy setInputMethod (InputMethod input_method)
          {
            this.input_method = input_method;
            return this;
          }

        public PINPolicy setGrouping (Grouping grouping)
          {
            this.grouping = grouping;
            return this;
          }

        public PINPolicy addPatternRestriction (PatternRestriction pattern)
          {
            this.pattern_restrictions.add (pattern);
            return this;
          }
      }


    public class Key implements Serializable
      {
        private static final long serialVersionUID = 1L;

        LinkedHashMap<String,ExtensionInterface> extensions = new LinkedHashMap<String,ExtensionInterface> ();
        
        PostProvisioningTargetKey clone_or_update_operation;
        
        boolean key_init_done;
        
        byte[] expected_attest_mac_count;  // Two bytes
        
        private void addExtension (ExtensionInterface ei) throws IOException
          {
            if (extensions.put (ei.type, ei) != null)
              {
                bad ("Duplicate extension:" + ei.type);
              }
          }
        
        public PropertyBag[] getPropertyBags ()
          {
            Vector<PropertyBag> prop_bags = new Vector<PropertyBag> ();
            for (ExtensionInterface ei : extensions.values ())
              {
                if (ei instanceof PropertyBag)
                  {
                    prop_bags.add ((PropertyBag) ei);
                  }
              }
            return prop_bags.toArray (new PropertyBag[0]);
          }
        
        public PropertyBag addPropertyBag (String type) throws IOException
          {
            PropertyBag pb = new PropertyBag (type);
            addExtension (pb);
            return pb;
          }


        Object object;
        
        public Key setUserObject (Object object)
          {
            this.object = object;
            return this;
          }
        
        public Object getUserObject ()
          {
            return object;
          }


        public Key addExtension (String type, byte[] data) throws IOException
          {
            addExtension (new Extension (type, data));
            return this;
          }

        public Key addEncryptedExtension (String type, byte[] data) throws IOException
          {
            addExtension (new EncryptedExtension (type, encrypt (data)));
            return this;
          }

        public Key addLogotype (String type, MimeTypedObject logotype) throws IOException
          {
            addExtension (new Logotype (type, logotype));
            return this;
          }


        X509Certificate[] certificate_path;
        
        public Key setCertificatePath (X509Certificate[] certificate_path)
          {
            this.certificate_path = certificate_path;
            return this;
          }
        
        public X509Certificate[] getCertificatePath ()
          {
            return certificate_path;
          }


        byte[] encrypted_symmetric_key;
        
        public Key setSymmetricKey (byte[] symmetric_key) throws IOException
          {
            this.encrypted_symmetric_key = encrypt (symmetric_key);
            return this;
          }
        

        String[] endorsed_algorithms;

        public Key setEndorsedAlgorithms (String[] endorsed_algorithms) throws IOException
          {
            this.endorsed_algorithms = BasicCapabilities.getSortedAlgorithms (endorsed_algorithms);
            return this;
          }


        public byte[] getEncryptedSymmetricKey ()
          {
            return encrypted_symmetric_key;
          }


        byte[] encrypted_private_key;
        
        public Key setPrivateKey (byte[] private_key) throws IOException
          {
            this.encrypted_private_key = encrypt (private_key);
            return this;
          }

        public byte[] getEncryptedPrivateKey ()
          {
            return encrypted_private_key;
          }

        
        String friendly_name;

        public Key setFriendlyName (String friendly_name)
          {
            this.friendly_name = friendly_name;
            return this;
          }

        public String getFriendlyName ()
          {
            return friendly_name;
          }

        
        PublicKey public_key;   // Filled in by KeyCreationRequestDecoder

        public PublicKey getPublicKey ()
          {
            return public_key;
          }


        byte[] attestation;   // Filled in by KeyCreationRequestDecoder
        
        public byte[] getAttestation ()
          {
            return attestation;
          }


        ExportProtection export_protection;
        
        public Key setExportProtection (ExportProtection export_protection)
          {
            this.export_protection = export_protection;
            return this;
          }

        public ExportProtection getExportPolicy ()
          {
            return export_protection;
          }
        
        
        byte[] server_seed;
        
        public Key setServerSeed (byte[] server_seed) throws IOException
          {
            if (server_seed != null && server_seed.length > SecureKeyStore.MAX_LENGTH_SERVER_SEED)
              {
                bad ("Server seed > " + SecureKeyStore.MAX_LENGTH_SERVER_SEED + " bytes");
              }
            this.server_seed = server_seed;
            return this;
          }
        

        boolean enable_pin_caching;
        boolean enable_pin_caching_set;
        
        public Key setEnablePINCaching (boolean flag)
          {
            enable_pin_caching = flag;
            enable_pin_caching_set = true;
            return this;
          }
        
        public boolean getEnablePINCachingFlag ()
          {
            return enable_pin_caching;
          }


        boolean trust_anchor;
        boolean trust_anchor_set;
        
        public Key setTrustAnchor (boolean flag)
          {
            trust_anchor = flag;
            trust_anchor_set = true;
            return this;
          }
        
        public boolean getTrustAnchorFlag ()
          {
            return trust_anchor;
          }

        
        BiometricProtection biometric_protection;
        
        public Key setBiometricProtection (BiometricProtection biometric_protection) throws IOException
          {
            // TODO there must be some PIN-related tests here...
            this.biometric_protection = biometric_protection;
            return this;
          }

        public BiometricProtection getBiometricProtection ()
          {
            return biometric_protection;
          }


        DeleteProtection delete_protection;
        
        public Key setDeleteProtection (DeleteProtection delete_protection) throws IOException
          {
            // TODO there must be some PIN-related tests here...
            this.delete_protection = delete_protection;
            return this;
          }

        public DeleteProtection getDeletePolicy ()
          {
            return delete_protection;
          }


        String id;

        public String getID ()
          {
            return id;
          }
        

        AppUsage app_usage;

        public AppUsage getAppUsage ()
          {
            return app_usage;
          }

        KeySpecifier key_specifier;

        PINPolicy pin_policy;
        
        public PINPolicy getPINPolicy ()
          {
            return pin_policy;
          }

        
        byte[] preset_pin;
        
        public byte[] getEncryptedPIN ()
          {
            return preset_pin;
          }
        

        boolean device_pin_protection;

        public boolean getDevicePINProtection ()
          {
            return device_pin_protection;
          }
        
        
        void setPostOp (PostProvisioningTargetKey op) throws IOException
          {
            if (clone_or_update_operation != null)
              {
                bad ("Clone or Update already set for this key");
              }
            if (pin_policy != null || device_pin_protection)
              {
                bad ("Clone/Update keys cannot be PIN protected");
              }
            clone_or_update_operation = op;
          }
        
     
        public Key setClonedKeyProtection (String old_client_session_id, 
                                                     String old_server_session_id,
                                                     X509Certificate old_key,
                                                     PublicKey key_management_key) throws IOException
          {
            PostProvisioningTargetKey op = addPostOperation (old_client_session_id,
                                                             old_server_session_id,
                                                             old_key,
                                                             PostOperation.CLONE_KEY_PROTECTION,
                                                             key_management_key);
            setPostOp (op);
            return this;
          }

        public Key setUpdatedKey (String old_client_session_id, 
                                            String old_server_session_id,
                                            X509Certificate old_key,
                                            PublicKey key_management_key) throws IOException
          { 
            PostProvisioningTargetKey op = addPostOperation (old_client_session_id,
                                                             old_server_session_id,
                                                             old_key,
                                                             PostOperation.UPDATE_KEY,
                                                             key_management_key);
            setPostOp (op);
            return this;
          }

        Key (AppUsage app_usage, KeySpecifier key_specifier, PINPolicy pin_policy, byte[] preset_pin, boolean device_pin_protection) throws IOException
          {
            this.id = key_prefix + ++next_key_id_suffix;
            this.app_usage = app_usage;
            this.key_specifier = key_specifier;
            this.pin_policy = pin_policy;
            this.preset_pin = preset_pin;
            this.device_pin_protection = device_pin_protection;
            if (pin_policy != null)
              {
                if (pin_policy.not_first)
                  {
                    if (pin_policy.grouping == Grouping.SHARED && ((pin_policy.preset_test == null && preset_pin != null) || (pin_policy.preset_test != null && preset_pin == null)))
                      {
                        bad ("\"shared\" PIN keys must either have no \"preset_pin\" " + "value or all be preset");
                      }
                  }
                else
                  {
                    pin_policy.not_first = true;
                    pin_policy.preset_test = preset_pin;
                  }
              }
          }

        void writeRequest (DOMWriterHelper wr) throws IOException, GeneralSecurityException
          {
            key_init_done = true;
            MacGenerator key_pair_mac = new MacGenerator ();
            key_pair_mac.addString (id);
            key_pair_mac.addString (key_attestation_algorithm);
            key_pair_mac.addArray (server_seed == null ? SecureKeyStore.ZERO_LENGTH_ARRAY : server_seed);
            key_pair_mac.addString (pin_policy == null ? 
                                      SecureKeyStore.CRYPTO_STRING_NOT_AVAILABLE 
                                                       :
                                      pin_policy.id);
            if (getEncryptedPIN () == null)
              {
                key_pair_mac.addString (SecureKeyStore.CRYPTO_STRING_NOT_AVAILABLE);
              }
            else
              {
                key_pair_mac.addArray (getEncryptedPIN ());
              }
            key_pair_mac.addBool (device_pin_protection);
            key_pair_mac.addBool (enable_pin_caching);
            key_pair_mac.addByte (biometric_protection == null ?
                       BiometricProtection.NONE.getSksValue () : biometric_protection.getSksValue ());
            key_pair_mac.addByte (export_protection == null ?
                ExportProtection.NON_EXPORTABLE.getSksValue () : export_protection.getSksValue ());
            key_pair_mac.addByte (delete_protection == null ?
                       DeleteProtection.NONE.getSksValue () : delete_protection.getSksValue ());
            key_pair_mac.addByte (app_usage.getSksValue ());
            key_pair_mac.addString (friendly_name == null ? "" : friendly_name);
            key_pair_mac.addString (key_specifier.getKeyAlgorithm ().getURI ());
            key_pair_mac.addArray (key_specifier.getParameters () == null ? SecureKeyStore.ZERO_LENGTH_ARRAY : key_specifier.getParameters ());
            if (endorsed_algorithms != null) for (String algorithm : endorsed_algorithms)
              {
                key_pair_mac.addString (algorithm);
              }

            wr.addChildElement (KEY_ENTRY_SPECIFIER_ELEM);

            wr.setStringAttribute (ID_ATTR, id);

            if (server_seed != null)
              {
                wr.setBinaryAttribute (SERVER_SEED_ATTR, server_seed);
              }

            if (device_pin_protection)
              {
                wr.setBooleanAttribute (DEVICE_PIN_PROTECTION_ATTR, true);
              }

            if (preset_pin != null)
              {
                wr.setBinaryAttribute (ENCRYPTED_PRESET_PIN_ATTR, preset_pin);
              }

            if (enable_pin_caching_set)
              {
                if (enable_pin_caching && (pin_policy == null || pin_policy.input_method != InputMethod.TRUSTED_GUI))
                  {
                    bad ("\"" + ENABLE_PIN_CACHING_ATTR +"\" must be combined with " + InputMethod.TRUSTED_GUI.toString ());
                  }
                wr.setBooleanAttribute (ENABLE_PIN_CACHING_ATTR, enable_pin_caching);
              }

            if (biometric_protection != null)
              {
                wr.setStringAttribute (BIOMETRIC_PROTECTION_ATTR, biometric_protection.getProtocolName ());
              }

            if (export_protection != null)
              {
                wr.setStringAttribute (EXPORT_PROTECTION_ATTR, export_protection.getProtocolName ());
              }

            if (delete_protection != null)
              {
                wr.setStringAttribute (DELETE_PROTECTION_ATTR, delete_protection.getProtocolName ());
              }

            if (friendly_name != null)
              {
                wr.setStringAttribute (FRIENDLY_NAME_ATTR, friendly_name);
              }

            wr.setStringAttribute (APP_USAGE_ATTR, app_usage.getProtocolName ());

            wr.setStringAttribute (KEY_ALGORITHM_ATTR, key_specifier.getKeyAlgorithm ().getURI ());
            if (key_specifier.getParameters () != null)
              {
                wr.setBinaryAttribute (KEY_PARAMETERS_ATTR, key_specifier.getParameters ());
              }

            if (endorsed_algorithms != null)
              {
                wr.setListAttribute (ENDORSED_ALGORITHMS_ATTR, endorsed_algorithms);
              }

            wr.setBinaryAttribute (MAC_ATTR, mac (key_pair_mac.getResult (), SecureKeyStore.METHOD_CREATE_KEY_ENTRY));
            
            expected_attest_mac_count = getMacSequenceCounterAndUpdate ();
            
            wr.getParent ();
          }
      }

    public Key[] getKeys ()
      {
        return requested_keys.values ().toArray (new Key[0]);
      }

    public ProtocolPhase getProtocolPhase ()
      {
        return current_phase;
      }

    ServerCryptoInterface server_crypto_interface;

    BasicCapabilities basic_capabilities = new BasicCapabilities (false);
    
    HashMap<String,HashSet<String>> client_attribute_values;

    ProtocolPhase current_phase = ProtocolPhase.PLATFORM_NEGOTIATION;
    
    boolean request_phase = true;
    
    int next_personal_code = 1;

    String key_prefix = "Key.";

    int next_key_id_suffix = 0;

    String pin_prefix = "PIN.";

    int next_pin_id_suffix = 0;

    String puk_prefix = "PUK.";

    int next_puk_id_suffix = 0;

    short mac_sequence_counter;

    LinkedHashMap<String,Key> requested_keys = new LinkedHashMap<String,Key> ();

    Vector<ImagePreference> image_preferences; 

    String server_session_id;

    String client_session_id;
    
    String issuer_uri;

    int session_life_time;

    short session_key_limit;
    
    String provisioning_session_algorithm = SecureKeyStore.ALGORITHM_SESSION_ATTEST_1;
    
    String key_attestation_algorithm;
    
    ECPublicKey server_ephemeral_key;
    
    ECPublicKey client_ephemeral_key;
    
    PublicKey key_management_key;
    
    byte[] saved_close_nonce;
    
    byte[] vm_nonce;
    
    X509Certificate device_certificate;
    
    PostProvisioningTargetKey addPostOperation (String old_client_session_id,
                                                String old_server_session_id,
                                                X509Certificate old_key,
                                                PostOperation operation,
                                                PublicKey key_management_key) throws IOException
      {
        try
          {
            PostProvisioningTargetKey new_post_op = new PostProvisioningTargetKey (old_client_session_id,
                                                                                   old_server_session_id,
                                                                                   old_key.getEncoded (),
                                                                                   key_management_key,
                                                                                   operation);
            for (PostProvisioningTargetKey post_op : post_operations)
              {
                if (post_op.equals (new_post_op))
                  {
                    if (post_op.post_operation == PostOperation.DELETE_KEY || new_post_op.post_operation == PostOperation.DELETE_KEY)
                      {
                        bad ("DeleteKey cannot be combined with other management operations");
                      }
                    if (post_op.post_operation == PostOperation.UPDATE_KEY || new_post_op.post_operation == PostOperation.UPDATE_KEY)
                      {
                        bad ("UpdateKey can only be performed once per key");
                      }
                  }
              }
            post_operations.add (new_post_op);
            return new_post_op;
          }
        catch (GeneralSecurityException e)
          {
            throw new IOException (e);
          }
      }
    
    void checkSession (String client_session_id, String server_session_id) throws IOException
      {
        if (!this.client_session_id.equals (client_session_id) || !this.server_session_id.equals (server_session_id))
          {
            bad ("Session ID mismatch");
          }
      }
    
    private byte[] getMacSequenceCounterAndUpdate ()
      {
        int q = mac_sequence_counter++;
        return  new byte[]{(byte)(q >>> 8), (byte)(q &0xFF)};
      }

    byte[] mac (byte[] data, byte[] method) throws IOException
      {
        return server_crypto_interface.mac (data, ArrayUtil.add (method, getMacSequenceCounterAndUpdate ()));
      }
    
    byte[] attest (byte[] data, byte[] mac_counter) throws IOException, GeneralSecurityException
      {
        return server_crypto_interface.mac (data, ArrayUtil.add (SecureKeyStore.KDF_DEVICE_ATTESTATION, mac_counter)); 
      }
    
    byte[] encrypt (byte[] data) throws IOException
      {
        return server_crypto_interface.encrypt (data);
      }
    
    void checkFinalResult (byte[] close_session_attestation) throws IOException, GeneralSecurityException
      {
        MacGenerator check = new MacGenerator ();
        check.addArray (saved_close_nonce);
        check.addString (SecureKeyStore.ALGORITHM_SESSION_ATTEST_1);
        if (!ArrayUtil.compare (attest (check.getResult (),
                                        getMacSequenceCounterAndUpdate ()),
                                close_session_attestation))
          {
            bad ("Final attestation failed!");
          }
      }
  
    static void bad (String error_msg) throws IOException
      {
        throw new IOException (error_msg);
      }
    
    boolean privacy_enabled;
    boolean privacy_enabled_set;
    
    public void setPrivacyEnabled (boolean flag) throws IOException
      {
        if (!request_phase || current_phase != ProtocolPhase.PLATFORM_NEGOTIATION)
          {
            throw new IOException ("Must be specified before any requests");
          }
        privacy_enabled_set = true;
        privacy_enabled = flag;
      }

    KeyAlgorithms ephemeral_key_algorithm = KeyAlgorithms.NIST_P_256;
    
    public void setEphemeralKeyAlgorithm (KeyAlgorithms ephemeral_key_algorithm)
      {
        this.ephemeral_key_algorithm = ephemeral_key_algorithm;
      }

 
    // Constructor
    public ServerState (ServerCryptoInterface server_crypto_interface)
      {
        this.server_crypto_interface = server_crypto_interface;
      }

    
    void checkState (boolean request, ProtocolPhase expected) throws IOException
      {
        if (request ^ request_phase)
          {
            throw new IOException ("Wrong order of request versus response");
          }
        request_phase = !request_phase;
        if (current_phase != expected)
          {
            throw new IOException ("Incorrect object, expected: " + expected + " got: " + current_phase);
          }
      }


    public void update (PlatformNegotiationResponseDecoder platform_response) throws IOException
      {
        checkState (false, ProtocolPhase.PLATFORM_NEGOTIATION);
        current_phase = ProtocolPhase.PROVISIONING_INITIALIZATION;
        basic_capabilities.checkCapabilities (platform_response.basic_capabilities);
        basic_capabilities = platform_response.basic_capabilities;
        image_preferences = platform_response.image_preferences;
        vm_nonce = platform_response.nonce;
      }


    public void update (ProvisioningInitializationResponseDecoder prov_init_response, X509Certificate server_certificate) throws IOException
      {
        try
          {
            checkState (false, ProtocolPhase.PROVISIONING_INITIALIZATION);
            client_session_id = prov_init_response.client_session_id;
            device_certificate = prov_init_response.device_certificate_path == null ? null : prov_init_response.device_certificate_path[0];
            client_ephemeral_key = prov_init_response.client_ephemeral_key;
            client_attribute_values = prov_init_response.client_attribute_values;

            MacGenerator kdf = new MacGenerator ();
            kdf.addString (client_session_id);
            kdf.addString (server_session_id);
            kdf.addString (issuer_uri);
            kdf.addArray (getDeviceID ());

            MacGenerator attestation_arguments = new MacGenerator ();
            attestation_arguments.addString (client_session_id);
            attestation_arguments.addString (server_session_id);
            attestation_arguments.addString (issuer_uri);
            attestation_arguments.addArray (getDeviceID ());
            attestation_arguments.addString (provisioning_session_algorithm);
            attestation_arguments.addBool (device_certificate == null);
            attestation_arguments.addArray (server_ephemeral_key.getEncoded ());
            attestation_arguments.addArray (client_ephemeral_key.getEncoded ());
            attestation_arguments.addArray (key_management_key == null ? new byte[0] : key_management_key.getEncoded ());
            attestation_arguments.addInt ((int) (prov_init_response.client_time.getTime () / 1000));
            attestation_arguments.addInt (session_life_time);
            attestation_arguments.addShort (session_key_limit);

            server_crypto_interface.generateAndVerifySessionKey (client_ephemeral_key,
                                                                 kdf.getResult (),
                                                                 attestation_arguments.getResult (),
                                                                 device_certificate == null ? null : device_certificate,
                                                                 prov_init_response.attestation);
            if (((server_certificate == null ^ prov_init_response.server_certificate_fingerprint == null)) ||
                (server_certificate != null && !ArrayUtil.compare (prov_init_response.server_certificate_fingerprint, 
                                                                   HashAlgorithms.SHA256.digest (server_certificate.getEncoded ()))))
              {
                throw new IOException ("Attribute '" + SERVER_CERT_FP_ATTR + "' is missing or is invalid");
              }
            new XMLSymKeyVerifier (new SymKeyVerifierInterface()
              {
                @Override
                public boolean verifyData (byte[] data, byte[] digest, MACAlgorithms algorithm, String key_id) throws IOException
                  {
                    return ArrayUtil.compare (server_crypto_interface.mac (data, SecureKeyStore.KDF_EXTERNAL_SIGNATURE), digest);
                  }
              }).validateEnvelopedSignature (prov_init_response, null, prov_init_response.signature, client_session_id);
          }
        catch (GeneralSecurityException e)
          {
            throw new IOException (e);
          }
        current_phase = ProtocolPhase.CREDENTIAL_DISCOVERY;
      }


    byte[] getDeviceID () throws GeneralSecurityException
      {
        return device_certificate == null ? SecureKeyStore.KDF_ANONYMOUS : device_certificate.getEncoded ();
      }

    public void update (CredentialDiscoveryResponseDecoder credential_discovery_response) throws IOException
      {
        checkState (false, ProtocolPhase.CREDENTIAL_DISCOVERY);
        checkSession (credential_discovery_response.client_session_id, credential_discovery_response.server_session_id);
        current_phase = ProtocolPhase.KEY_CREATION;
      }


    public void update (KeyCreationResponseDecoder key_create_response) throws IOException
      {
        checkState (false, ProtocolPhase.KEY_CREATION);
        checkSession (key_create_response.client_session_id, key_create_response.server_session_id);
        if (key_create_response.generated_keys.size () != requested_keys.size ())
          {
            ServerState.bad ("Different number of requested and received keys");
          }
        try
          {
            for (KeyCreationResponseDecoder.GeneratedPublicKey gpk : key_create_response.generated_keys.values ())
              {
                ServerState.Key kp = requested_keys.get (gpk.id);
                if (kp == null)
                  {
                    ServerState.bad ("Missing key id:" + gpk.id);
                  }
                if (kp.key_specifier.key_algorithm != KeyAlgorithms.getKeyAlgorithm (kp.public_key = gpk.public_key, kp.key_specifier.parameters != null))
                  {
                    ServerState.bad ("Wrong key type returned for key id:" + gpk.id);
                  }
                MacGenerator attestation = new MacGenerator ();
                // Write key attestation data
                attestation.addString (gpk.id);
                attestation.addArray (gpk.public_key.getEncoded ());
                 if (!ArrayUtil.compare (attest (attestation.getResult (), kp.expected_attest_mac_count),
                                         kp.attestation = gpk.attestation))
                  {
                    ServerState.bad ("Attestation failed for key id:" + gpk.id);
                  }
              }
          }
        catch (GeneralSecurityException e)
          {
            throw new IOException (e);
          }
        current_phase = ProtocolPhase.PROVISIONING_FINALIZATION;
      }

    
    public void update (ProvisioningFinalizationResponseDecoder prov_final_response) throws IOException
      {
        checkState (false, ProtocolPhase.PROVISIONING_FINALIZATION);
        checkSession (prov_final_response.client_session_id, prov_final_response.server_session_id);
        try
          {
            checkFinalResult (prov_final_response.attestation);
          }
        catch (GeneralSecurityException e)
          {
            throw new IOException (e);
          }
        current_phase = ProtocolPhase.DONE;
      }

    
    public String getDeviceID (boolean long_version)
      {
        return DeviceID.getDeviceID (device_certificate, long_version);
      }


    public X509Certificate getDeviceCertificate ()
      {
        return device_certificate;
      }


    public BasicCapabilities getBasicCapabilities ()
      {
        return basic_capabilities;
      }


    public HashMap<String,HashSet<String>> getClientAttributeValues ()
      {
        return client_attribute_values;
      }


    public ImagePreference[] getImagePreferences ()
      {
        return image_preferences.toArray (new ImagePreference[0]);
      }

    
    public ImagePreference[] getImagePreferences (String type)
      {
        Vector<ImagePreference> matching = new Vector<ImagePreference> ();
        for (ImagePreference impref : image_preferences)
          {
            if (impref.type.equals (type))
              {
                matching.add (impref);
              }
          }
        return matching.toArray (new ImagePreference[0]);
      }

    
    public void addPostDeleteKey (String old_client_session_id,
                                  String old_server_session_id,
                                  X509Certificate old_key,
                                  PublicKey key_management_key) throws IOException
      {
        addPostOperation (old_client_session_id, 
                          old_server_session_id,
                          old_key, 
                          PostOperation.DELETE_KEY,
                          key_management_key);
      }

  
    public void addPostUnlockKey (String old_client_session_id,
                                  String old_server_session_id,
                                  X509Certificate old_key,
                                  PublicKey key_management_key) throws IOException
      {
        addPostOperation (old_client_session_id, 
        old_server_session_id,
        old_key, 
        PostOperation.UNLOCK_KEY,
        key_management_key);
      }

    
    public String getClientSessionId ()
      {
        return client_session_id;
      }

    public String getServerSessionId ()
      {
        return server_session_id;
      }

    
    public PINPolicy createPINPolicy (PassphraseFormat format, int min_length, int max_length, int retry_limit, PUKPolicy puk_policy) throws IOException
      {
        PINPolicy pin_policy = new PINPolicy ();
        pin_policy.format = format;
        pin_policy.min_length = min_length;
        pin_policy.max_length = max_length;
        pin_policy.retry_limit = retry_limit;
        pin_policy.puk_policy = puk_policy;
        if (format == null)
          {
            bad ("PassphraseFormat must not be null");
          }
        if (min_length > max_length)
          {
            bad ("min_length > max_length");
          }
        return pin_policy;
      }


    public PUKPolicy createPUKPolicy (byte[] puk, PassphraseFormat format, int retry_limit) throws IOException
      {
        return new PUKPolicy (encrypt (puk), format, retry_limit);
      }


    private Key addKeyProperties (AppUsage app_usage, KeySpecifier key_specifier, PINPolicy pin_policy, byte[] preset_pin, boolean device_pin_protection) throws IOException
      {
        Key key = new Key (app_usage, key_specifier, pin_policy, preset_pin, device_pin_protection);
        requested_keys.put (key.getID (), key);
        return key;
      }


    public Key createKeyWithPresetPIN (AppUsage app_usage, KeySpecifier key_specifier, PINPolicy pin_policy, byte[] pin) throws IOException
      {
        if (pin_policy == null)
          {
            bad ("PresetPIN without PINPolicy is not allowed");
          }
        pin_policy.user_defined = false;
        return addKeyProperties (app_usage, key_specifier, pin_policy, encrypt (pin), false);
      }


    public Key createKey (AppUsage app_usage, KeySpecifier key_specifier, PINPolicy pin_policy) throws IOException
      {
        return addKeyProperties (app_usage, key_specifier, pin_policy, null, false);
      }


    public Key createDevicePINProtectedKey (AppUsage app_usage, KeySpecifier key_specifier) throws IOException
      {
        return addKeyProperties (app_usage, key_specifier, null, null, true);
      }


    private LinkedHashMap<String,Object> service_specific_objects = new LinkedHashMap<String,Object> ();
    
    public void setServiceSpecificObject (String name, Object value)
      {
        service_specific_objects.put (name, value);
      }


    public Object getServiceSpecificObject (String name)
      {
        return service_specific_objects.get (name);
      }

    public ECPublicKey generateEphemeralKey () throws IOException
      {
        return server_crypto_interface.generateEphemeralKey (ephemeral_key_algorithm);
      }
  }
