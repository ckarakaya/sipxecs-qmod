/*
 *
 *
 * Copyright (C) 2007 Pingtel Corp., certain elements licensed under a Contributor Agreement.
 * Contributors retain copyright to elements licensed under a Contributor Agreement.
 * Licensed to the User under the LGPL license.
 *
 *
 */
package org.sipfoundry.sipxconfig.vm;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.apache.commons.httpclient.Credentials;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.auth.AuthPolicy;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.PutMethod;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.SuffixFileFilter;
import org.sipfoundry.sipxconfig.admin.commserver.Location;
import org.sipfoundry.sipxconfig.admin.commserver.LocationsManager;
import org.sipfoundry.sipxconfig.common.CoreContext;
import org.sipfoundry.sipxconfig.common.User;
import org.sipfoundry.sipxconfig.common.UserException;
import org.sipfoundry.sipxconfig.common.event.UserDeleteListener;
import org.sipfoundry.sipxconfig.domain.Domain;
import org.sipfoundry.sipxconfig.domain.DomainManager;
import org.sipfoundry.sipxconfig.permission.PermissionName;
import org.sipfoundry.sipxconfig.service.SipxIvrService;
import org.sipfoundry.sipxconfig.service.SipxServiceManager;
import org.sipfoundry.sipxconfig.vm.MailboxPreferences.VoicemailTuiType;
import org.sipfoundry.sipxconfig.vm.attendant.PersonalAttendant;
import org.sipfoundry.sipxconfig.vm.attendant.PersonalAttendantWriter;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.orm.hibernate3.support.HibernateDaoSupport;

import static org.springframework.dao.support.DataAccessUtils.singleResult;

public class MailboxManagerImpl extends HibernateDaoSupport implements MailboxManager {
    private static final String MESSAGE_SUFFIX = "-00.xml";
    private static final FilenameFilter MESSAGE_FILES = new SuffixFileFilter(MESSAGE_SUFFIX);
    private static final String PATH_MAILBOX = "/mailbox/";
    private static final String PATH_MESSAGE = "/message/";
    private File m_mailstoreDirectory;
    private File m_stdpromptDirectory;
    private DistributionListsReader m_distributionListsReader;
    private DistributionListsWriter m_distributionListsWriter;
    private PersonalAttendantWriter m_personalAttendantWriter;
    private MailboxPreferencesWriter m_mailboxPreferencesWriter;
    private CoreContext m_coreContext;
    private SipxServiceManager m_sipxServiceManager;
    private LocationsManager m_locationsManager;
    private DomainManager m_domainManager;

    public boolean isEnabled() {
        return m_mailstoreDirectory != null && m_mailstoreDirectory.exists();
    }

    public boolean isSystemCpui() {
        SipxIvrService ivrService = (SipxIvrService) m_sipxServiceManager.getServiceByBeanId(SipxIvrService.BEAN_ID);
        return ivrService.getDefaultTui().equals(VoicemailTuiType.CALLPILOT.getValue());
    }

    public DistributionList[] loadDistributionLists(Mailbox mailbox) {
        File file = mailbox.getDistributionListsFile();
        DistributionList[] lists = m_distributionListsReader.readObject(file);
        if (lists == null) {
            lists = DistributionList.createBlankList();
        }
        return lists;
    }

    public void saveDistributionLists(Mailbox mailbox, DistributionList[] lists) {
        Collection<String> aliases = DistributionList.getUniqueExtensions(lists);
        m_coreContext.checkForValidExtensions(aliases, PermissionName.VOICEMAIL);
        File file = mailbox.getDistributionListsFile();
        m_distributionListsWriter.writeObject(lists, file);
    }

    public List<Voicemail> getVoicemail(Mailbox mbox, String folder) {
        checkMailstoreDirectory();
        File vmdir = new File(mbox.getUserDirectory(), folder);
        String[] wavs = vmdir.list(MESSAGE_FILES);
        if (wavs == null) {
            return Collections.emptyList();
        }
        Arrays.sort(wavs);
        List<Voicemail> vms = new ArrayList(wavs.length);
        for (String wav : wavs) {
            String basename = basename(wav);
            vms.add(new Voicemail(m_mailstoreDirectory, mbox.getUserId(), folder, basename));
        }
        return vms;
    }

    private String getMailboxServerUrl(String fqdn, int port) {
        return String.format("https://%s:%d", fqdn, port);
    }

    /**
     * Mark voicemail as read
     */
    public void markRead(Mailbox mailbox, Voicemail voicemail) {
        StringBuilder sb = new StringBuilder(PATH_MAILBOX).append(mailbox.getUserDirectory().getName()).append(
                PATH_MESSAGE).append(voicemail.getMessageId()).append("/heard");
        invokeWebservice(sb.toString(), mailbox.getUserId());
    }

    public void move(Mailbox mailbox, Voicemail voicemail, String destinationFolderId) {
        File destination = new File(mailbox.getUserDirectory(), destinationFolderId);
        // make sure destination directory exists
        if (!destination.isDirectory()) {
            destination.mkdir();
        }
        for (File f : voicemail.getAllFiles()) {
            f.renameTo(new File(destination, f.getName()));
        }

        // ask voicemail to update the MWI ..
        StringBuilder sb = new StringBuilder(PATH_MAILBOX).append(mailbox.getUserDirectory().getName()).append(
                "/mwi");
        invokeWebservice(sb.toString(), mailbox.getUserId());

    }

    public void delete(Mailbox mailbox, Voicemail voicemail) {
        StringBuilder sb = new StringBuilder(PATH_MAILBOX).append(mailbox.getUserDirectory().getName()).append(
                PATH_MESSAGE).append(voicemail.getMessageId()).append("/delete");
        invokeWebservice(sb.toString(), mailbox.getUserId());
    }

    private void invokeWebservice(String uri, String username) {
        PutMethod httpPut = null;
        String host = null;
        String port = null;
        SipxIvrService ivrService = (SipxIvrService) m_sipxServiceManager.getServiceByBeanId(SipxIvrService.BEAN_ID);

        Location ivrLocation = (Location) singleResult(m_locationsManager.getLocationsForService(ivrService));
        if (ivrLocation == null) {
            throw new UserException("&voicemail.install.error");
        }
        try {
            HttpClient client = new HttpClient();
            host = ivrLocation.getFqdn();
            port = ivrService.getHttpsPort();
            List<String> authPrefs = new ArrayList<String>(1);
            // call ivr API with digest auth policy
            authPrefs.add(AuthPolicy.DIGEST);
            client.getParams().setParameter(AuthPolicy.AUTH_SCHEME_PRIORITY, authPrefs);
            // authenticate using shared secret
            Domain domain = m_domainManager.getDomain();
            Credentials credentials = new UsernamePasswordCredentials(username, domain.getSharedSecret());
            client.getState().setCredentials(new AuthScope(host, Integer.parseInt(port), AuthScope.ANY_REALM),
                    credentials);
            httpPut = new PutMethod(getMailboxServerUrl(host, Integer.parseInt(port)) + uri);
            int statusCode = client.executeMethod(httpPut);
            if (statusCode != 200) {
                throw new UserException("&error.https.server.status.code", host, String.valueOf(statusCode));
            }
        } catch (HttpException ex) {
            throw new UserException("&error.https.server", host, ex.getMessage());
        } catch (IOException ex) {
            throw new UserException("&error.io.exception", host, ex.getMessage());
        } finally {
            if (httpPut != null) {
                httpPut.releaseConnection();
            }
        }
    }

    /**
     * Because in HA systems, admin may change mailstore directory, validate it
     */
    void checkMailstoreDirectory() {
        if (m_mailstoreDirectory == null) {
            throw new MailstoreMisconfigured(null);
        }
        if (!m_mailstoreDirectory.exists()) {
            throw new MailstoreMisconfigured(m_mailstoreDirectory.getAbsolutePath());
        }
    }

    static class MailstoreMisconfigured extends UserException {
        MailstoreMisconfigured() {
            super("Mailstore directory configuration setting is missing.");
        }

        MailstoreMisconfigured(String dir) {
            super(String.format("Mailstore directory does not exist '%s'", dir));
        }

        MailstoreMisconfigured(String message, IOException cause) {
            super(message, cause);
        }
    }

    /**
     * extract file name w/o ext.
     */
    static String basename(String filename) {
        int suffix = filename.lastIndexOf(MESSAGE_SUFFIX);
        return suffix >= 0 ? filename.substring(0, suffix) : filename;
    }

    public String getMailstoreDirectory() {
        return m_mailstoreDirectory.getPath();
    }

    public void setMailstoreDirectory(String mailstoreDirectory) {
        m_mailstoreDirectory = new File(mailstoreDirectory);
    }

    public void setDomainManager(DomainManager manager) {
        m_domainManager = manager;
    }

    public String getStdpromptDirectory() {
        if (m_stdpromptDirectory != null) {
            return m_stdpromptDirectory.getPath();
        }
        return null;
    }

    public void setStdpromptDirectory(String stdpromptDirectory) {
        m_stdpromptDirectory = new File(stdpromptDirectory);
    }

    public Mailbox getMailbox(String userId) {
        return new Mailbox(m_mailstoreDirectory, userId);
    }

    public void deleteMailbox(String userId) {
        Mailbox mailbox = getMailbox(userId);
        mailbox.deleteUserDirectory();
    }

    public void renameMailbox(String oldUserId, String newUserId) {
        Mailbox oldMailbox = getMailbox(oldUserId);
        Mailbox newMailbox = getMailbox(newUserId);
        File oldUserDir = oldMailbox.getUserDirectory();
        File newUserDir = newMailbox.getUserDirectory();
        newMailbox.deleteUserDirectory();
        if (oldUserDir.exists()) {
            try {
                FileUtils.moveDirectory(oldUserDir, newUserDir);
            } catch (IOException e) {
                throw new MailstoreMisconfigured("Cannot rename mailbox directory " + oldUserDir.getAbsolutePath(),
                        e);
            }
        }
    }

    public static class YesNo {
        public String encode(Object o) {
            return Boolean.TRUE.equals(o) ? "yes" : "no";
        }
    }

    public void setDistributionListsReader(DistributionListsReader distributionListsReader) {
        m_distributionListsReader = distributionListsReader;
    }

    public void setDistributionListsWriter(DistributionListsWriter distributionListsWriter) {
        m_distributionListsWriter = distributionListsWriter;
    }

    public void setCoreContext(CoreContext coreContext) {
        m_coreContext = coreContext;
    }

    public void setSipxServiceManager(SipxServiceManager sipxServiceManager) {
        m_sipxServiceManager = sipxServiceManager;
    }

    public void setLocationsManager(LocationsManager locationsManager) {
        m_locationsManager = locationsManager;
    }

    public void setPersonalAttendantWriter(PersonalAttendantWriter personalAttendantWriter) {
        m_personalAttendantWriter = personalAttendantWriter;
    }

    public PersonalAttendant loadPersonalAttendantForUser(User user) {
        PersonalAttendant pa = findPersonalAttendant(user);
        if (pa == null) {
            pa = new PersonalAttendant();
            pa.setUser(user);
            getHibernateTemplate().save(pa);
        }
        return pa;
    }

    public void removePersonalAttendantForUser(User user) {
        PersonalAttendant pa = findPersonalAttendant(user);
        if (pa != null) {
            getHibernateTemplate().delete(pa);
        }
    }

    public void storePersonalAttendant(PersonalAttendant pa) {
        getHibernateTemplate().saveOrUpdate(pa);
        writePersonalAttendant(pa);
    }

    public void clearPersonalAttendants() {
        List<PersonalAttendant> allPersonalAttendants = getHibernateTemplate().loadAll(PersonalAttendant.class);
        getHibernateTemplate().deleteAll(allPersonalAttendants);
    }

    public void writeAllPersonalAttendants() {
        List<PersonalAttendant> all = getHibernateTemplate().loadAll(PersonalAttendant.class);
        for (PersonalAttendant pa : all) {
            writePersonalAttendant(pa);
        }
    }

    private void writePersonalAttendant(PersonalAttendant pa) {
        Mailbox mailbox = getMailbox(pa.getUser().getUserName());
        m_personalAttendantWriter.write(mailbox, pa);
    }

    private PersonalAttendant findPersonalAttendant(User user) {
        Collection pas = getHibernateTemplate().findByNamedQueryAndNamedParam("personalAttendantForUser", "user",
                user);
        return (PersonalAttendant) DataAccessUtils.singleResult(pas);
    }

    public UserDeleteListener createUserDeleteListener() {
        return new OnUserDelete();
    }

    private class OnUserDelete extends UserDeleteListener {
        @Override
        protected void onUserDelete(User user) {
            removePersonalAttendantForUser(user);
            deleteMailbox(user.getUserName());
        }
    }

    @Override
    public void updatePersonalAttendantForUser(User user, String operatorValue) {
        PersonalAttendant pa = loadPersonalAttendantForUser(user);
        pa.setOperator(operatorValue);
        storePersonalAttendant(pa);
    }

    public void writePreferencesFile(User user) {
        Mailbox mailbox = getMailbox(user.getUserName());
        File file = mailbox.getVoicemailPreferencesFile();
        m_mailboxPreferencesWriter.writeObject(new MailboxPreferences(user), file);
    }

    public void setMailboxPreferencesWriter(MailboxPreferencesWriter mailboxWriter) {
        m_mailboxPreferencesWriter = mailboxWriter;
    }

}
