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
package org.webpki.keygen2;

import java.io.IOException;

import java.util.Date;

import org.webpki.sks.SecureKeyStore;

import org.webpki.util.Base64URL;

import org.webpki.crypto.KeyContainerTypes;

import org.webpki.json.JSONObjectWriter;

import org.webpki.keygen2.ServerState.ProtocolPhase;

import static org.webpki.keygen2.KeyGen2Constants.*;

public class InvocationRequestEncoder extends ServerEncoder
  {
    private static final long serialVersionUID = 1L;

    Action action = Action.MANAGE;

    String server_session_id;

    String submit_url;
    
    String abort_url; // Optional

    private ServerState server_state;

    // Constructors

    public InvocationRequestEncoder (ServerState server_state,
                                     String submit_url,
                                     String server_session_id) throws IOException
      {
        server_state.checkState (true, ProtocolPhase.INVOCATION);
        this.server_state = server_state;
        this.submit_url = submit_url;
        if (server_session_id == null)
          {
            server_session_id = Long.toHexString (new Date().getTime());
            server_session_id += Base64URL.generateURLFriendlyRandom (SecureKeyStore.MAX_LENGTH_ID_TYPE - server_session_id.length ());
          }
        this.server_session_id = server_state.server_session_id = server_session_id;
      }
    
    public void setAction (Action action)
      {
        this.action = action;
      }

    public void setAbortUrl (String abort_url)
      {
        this.abort_url = abort_url;
      }


    @Override
    void writeServerRequest (JSONObjectWriter wr) throws IOException
      {
        //////////////////////////////////////////////////////////////////////////
        // Session properties
        //////////////////////////////////////////////////////////////////////////
        wr.setString (SERVER_SESSION_ID_JSON, server_session_id);

        wr.setString (SUBMIT_URL_JSON, submit_url);
        
        wr.setString (ACTION_JSON, action.getJSONName ());

        setOptionalString (wr, ABORT_URL_JSON, abort_url);
        
        if (server_state.privacy_enabled_set)
          {
            wr.setBoolean (PRIVACY_ENABLED_JSON, server_state.privacy_enabled);
          }

        setOptionalStringArray (wr, PREFERREDD_LANGUAGES_JSON, server_state.language_list);

        setOptionalStringArray (wr, KeyContainerTypes.KCT_TARGET_KEY_CONTAINERS, server_state.key_container_list);
        
        setOptionalStringArray (wr,
                                CLIENT_CAPABILITY_QUERY_JSON,
                                server_state.queried_capabilities.isEmpty () 
                                         ?
                                null 
                                         :
                                server_state.queried_capabilities.keySet ().toArray (new String[0]));
      }

    @Override
    public String getQualifier ()
      {
        return KeyGen2Messages.INVOCATION_REQUEST.getName ();
      }
  }
