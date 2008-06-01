/*
 * 
 * 
 * Copyright (C) 2007 Pingtel Corp., certain elements licensed under a Contributor Agreement.  
 * Contributors retain copyright to elements licensed under a Contributor Agreement.
 * Licensed to the User under the LGPL license.
 * 
 * $
 */
package org.sipfoundry.sipxconfig.sip;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class SipServiceImpl extends SipStackBean implements SipService  {

    static final Log LOG = LogFactory.getLog(SipServiceImpl.class);
    
    private String m_fromServerName;

    public void sendCheckSync(String addrSpec) {
        AbstractMessage message = new NotifyMessage(this, addrSpec, "check-sync");
        message.createAndSend();
    }

    public void sendNotify(String addrSpec, String eventType, String contentType, byte[] payload) {
        AbstractMessage message = new NotifyMessage(this, addrSpec, eventType, contentType, payload);
        message.createAndSend();
    }

    public void sendRefer(String sourceAddrSpec, String destinationAddSpec) {
        // TODO: implementation using JAIN SIP stack needed
    }
    
    public void setFromServerName(String fromServerName) {
        this.m_fromServerName = fromServerName;
    }
}
