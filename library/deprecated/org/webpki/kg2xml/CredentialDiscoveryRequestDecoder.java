/*
 *  Copyright 2006-2015 WebPKI.org (http://webpki.org).
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
package org.webpki.kg2xml;

import java.io.IOException;

import java.math.BigInteger;

import java.security.PublicKey;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;

import org.w3c.dom.Element;

import org.webpki.xml.DOMReaderHelper;
import org.webpki.xml.DOMAttributeReaderHelper;

import org.webpki.xmldsig.XMLAsymKeyVerifier;
import org.webpki.xmldsig.XMLSignatureWrapper;
import org.webpki.xmldsig.XMLVerifier;

import org.webpki.crypto.CertificateFilter;
import org.webpki.crypto.HashAlgorithms;
import org.webpki.crypto.VerifierInterface;

import static org.webpki.kg2xml.KeyGen2Constants.*;

public class CredentialDiscoveryRequestDecoder extends CredentialDiscoveryRequest
  {

    public class LookupSpecifier
      {
        String id;
        
        String issuer_reg_ex;
        String subject_reg_ex;
        BigInteger serial_number;
        String email_reg_ex;
        String[] policy_rules;
        GregorianCalendar issued_before;
        GregorianCalendar issued_after;

        byte[] nonce;
        
        XMLSignatureWrapper signature;
        
        Element element;
        
        PublicKey key_management_key;

        LookupSpecifier () { }


        LookupSpecifier (DOMReaderHelper rd) throws IOException
          {
            DOMAttributeReaderHelper ah = rd.getAttributeHelper ();
            element = rd.getNext (LOOKUP_SPECIFIER_ELEM);
            id = ah.getString (ID_ATTR);
            nonce = ah.getBinary (NONCE_ATTR);
            rd.getChild ();
            if (rd.hasNext (SEARCH_FILTER_ELEM))
              {
                rd.getNext ();
                issuer_reg_ex = ah.getStringConditional (CertificateFilter.CF_ISSUER_REG_EX);
                subject_reg_ex = ah.getStringConditional (CertificateFilter.CF_SUBJECT_REG_EX);
                serial_number = ah.getBigIntegerConditional (CertificateFilter.CF_SERIAL_NUMBER);
                email_reg_ex = ah.getStringConditional (CertificateFilter.CF_EMAIL_REG_EX);
                policy_rules = ah.getListConditional (CertificateFilter.CF_POLICY_RULES);
                issued_before = ah.getDateTimeConditional (ISSUED_BEFORE_ATTR);
                issued_after = ah.getDateTimeConditional (ISSUED_AFTER_ATTR);
              }
            signature = (XMLSignatureWrapper)wrap (rd.getNext (XMLSignatureWrapper.SIGNATURE_ELEM));
            rd.getParent ();
          }


        public String getID ()
          {
            return id;
          }
        
        public PublicKey getKeyManagementKey ()
          {
            return key_management_key;
          }
        
        public String getSubjectRegEx ()
          {
            return subject_reg_ex;
          }

        public String getIssuerRegEx ()
          {
            return issuer_reg_ex;
          }
        
        public BigInteger getSerialNumber ()
          {
            return serial_number;
          }
        
        public String getEmailRegEx ()
          {
            return email_reg_ex;
          }
        
        public String[] getPolicyRules ()
          {
            return policy_rules;
          }
        
        public GregorianCalendar getIssuedBefore ()
          {
            return issued_before;
          }

        public GregorianCalendar getIssuedAfter ()
          {
            return issued_after;
          }
      }

    LinkedHashMap<String,LookupSpecifier> lookup_specifiers = new LinkedHashMap<String,LookupSpecifier> ();
    
    String client_session_id;

    String server_session_id;

    private String submit_url;

    private XMLSignatureWrapper signature;                  // Optional


    public String getServerSessionId ()
      {
        return server_session_id;
      }


    public String getClientSessionId ()
      {
        return client_session_id;
      }


    public String getSubmitUrl ()
      {
        return submit_url;
      }


    public LookupSpecifier[] getLookupSpecifiers ()
      {
        return lookup_specifiers.values ().toArray (new LookupSpecifier[0]);
      }
    
    
    public void verifySignature (VerifierInterface verifier) throws IOException
      {
        new XMLVerifier (verifier).validateEnvelopedSignature (this, null, signature, server_session_id);
      }


    public boolean isSigned ()
      {
        return signature != null;
      }


    protected void fromXML (DOMReaderHelper rd) throws IOException
      {
        DOMAttributeReaderHelper ah = rd.getAttributeHelper ();

        /////////////////////////////////////////////////////////////////////////////////////////
        // Read the top level attributes
        /////////////////////////////////////////////////////////////////////////////////////////

        client_session_id = ah.getString (CLIENT_SESSION_ID_ATTR);

        server_session_id = ah.getString (ID_ATTR);

        submit_url = ah.getString (SUBMIT_URL_ATTR);
        
        rd.getChild ();

        /////////////////////////////////////////////////////////////////////////////////////////
        // Get the lookup_specifiers [1..n]
        /////////////////////////////////////////////////////////////////////////////////////////
        do 
          {
            LookupSpecifier o = new LookupSpecifier (rd);
            if (lookup_specifiers.put (o.id, o) != null)
              {
                throw new IOException ("Duplicate id: " + o.id);
              }
            XMLAsymKeyVerifier verifier = new XMLAsymKeyVerifier ();
            verifier.validateEnvelopedSignature (this, o.element, o.signature, o.id);
            if (verifier.getSignatureAlgorithm ().getDigestAlgorithm () != HashAlgorithms.SHA256)
              {
                throw new IOException ("Lookup signature must use SHA256");
              }
            o.key_management_key = verifier.getPublicKey ();
          }
        while (rd.hasNext (LOOKUP_SPECIFIER_ELEM));

        /////////////////////////////////////////////////////////////////////////////////////////
        // Get optional signature
        /////////////////////////////////////////////////////////////////////////////////////////
        if (rd.hasNext ())// Must be a Signature otherwise schema validation has gone wrong...
          {
            signature = (XMLSignatureWrapper)wrap (rd.getNext (XMLSignatureWrapper.SIGNATURE_ELEM));
          }
      }
  }
