package org.iurit.alfresco.google.calendar;

import com.google.api.client.auth.oauth2.BearerToken;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeTokenRequest;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.CalendarScopes;
import com.google.api.services.calendar.model.CalendarList;
import com.google.api.services.calendar.model.CalendarListEntry;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import com.google.api.services.calendar.model.Events;

import org.alfresco.model.ContentModel;
import org.alfresco.service.cmr.repository.ChildAssociationRef;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.cmr.site.SiteInfo;
import org.alfresco.service.cmr.site.SiteService;
import org.alfresco.service.namespace.QName;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class GoogleCalendarService {

    private static final Log logger = LogFactory.getLog(GoogleCalendarService.class);

    private static final NetHttpTransport HTTP_TRANSPORT = new NetHttpTransport();
    private static final JacksonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
    private static final String APPLICATION_NAME = "Alfresco-GoogleCalendar-Sync/1.0";

    private static final String GCAL_NS = "http://www.iurit.org/model/google-calendar/1.0";

    public static final QName ASPECT_SYNCED = QName.createQName(GCAL_NS, "synced");
    public static final QName PROP_GOOGLE_EVENT_ID = QName.createQName(GCAL_NS, "googleEventId");
    public static final QName PROP_GOOGLE_CALENDAR_ID = QName.createQName(GCAL_NS, "googleCalendarId");
    public static final QName PROP_LAST_SYNC_TIME = QName.createQName(GCAL_NS, "lastSyncTime");

    public static final QName ASPECT_SITE_CONFIG = QName.createQName(GCAL_NS, "siteConfig");
    public static final QName PROP_ENABLED = QName.createQName(GCAL_NS, "enabled");
    public static final QName PROP_TARGET_CALENDAR_ID = QName.createQName(GCAL_NS, "targetCalendarId");
    public static final QName PROP_ACCESS_TOKEN = QName.createQName(GCAL_NS, "accessToken");
    public static final QName PROP_REFRESH_TOKEN = QName.createQName(GCAL_NS, "refreshToken");
    public static final QName PROP_TOKEN_EXPIRY = QName.createQName(GCAL_NS, "tokenExpiry");
    public static final QName PROP_SYNC_TOKEN = QName.createQName(GCAL_NS, "syncToken");

    private static final String IA_NS = "http://www.alfresco.org/model/calendar";
    public static final QName TYPE_CALENDAR_EVENT = QName.createQName(IA_NS, "calendarEvent");
    public static final QName PROP_WHAT_EVENT = QName.createQName(IA_NS, "whatEvent");
    public static final QName PROP_FROM_DATE = QName.createQName(IA_NS, "fromDate");
    public static final QName PROP_TO_DATE = QName.createQName(IA_NS, "toDate");
    public static final QName PROP_WHERE_EVENT = QName.createQName(IA_NS, "whereEvent");
    public static final QName PROP_DESCRIPTION_EVENT = QName.createQName(IA_NS, "descriptionEvent");

    private static final Set<NodeRef> RECENTLY_SYNCED_FROM_GOOGLE =
            Collections.newSetFromMap(new ConcurrentHashMap<NodeRef, Boolean>());

    public static boolean wasRecentlySyncedFromGoogle(NodeRef nodeRef) {
        return RECENTLY_SYNCED_FROM_GOOGLE.remove(nodeRef);
    }

    private NodeService nodeService;
    private SiteService siteService;
    private String clientId;
    private String clientSecret;
    private String redirectUri;

    public void setNodeService(NodeService nodeService) {
        this.nodeService = nodeService;
    }

    public void setSiteService(SiteService siteService) {
        this.siteService = siteService;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public void setRedirectUri(String redirectUri) {
        this.redirectUri = redirectUri;
    }

    public void init() {
        logger.info("GoogleCalendarService initialized:");
        logger.info("  clientId = [" + (clientId != null && clientId.length() > 5 ? clientId.substring(0, 5) + "..." : "null/empty") + "]");
        logger.info("  clientSecret = [" + (clientSecret != null && !clientSecret.isEmpty() ? "SET" : "null/empty") + "]");
        logger.info("  redirectUri = [" + redirectUri + "]");
    }

    // ---- OAuth ----

    public String getAuthorizationUrl(String siteId) {
        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                HTTP_TRANSPORT, JSON_FACTORY, clientId, clientSecret,
                Collections.singleton(CalendarScopes.CALENDAR))
                .setAccessType("offline")
                .setApprovalPrompt("force")
                .build();

        return flow.newAuthorizationUrl()
                .setRedirectUri(redirectUri)
                .setState(siteId)
                .build();
    }

    public void handleAuthorizationCode(String siteId, String code) throws IOException {
        GoogleTokenResponse tokenResponse = new GoogleAuthorizationCodeTokenRequest(
                HTTP_TRANSPORT, JSON_FACTORY, clientId, clientSecret, code, redirectUri)
                .execute();

        SiteInfo site = siteService.getSite(siteId);
        if (site == null) {
            throw new IllegalArgumentException("Site not found: " + siteId);
        }

        NodeRef siteNode = site.getNodeRef();

        if (!nodeService.hasAspect(siteNode, ASPECT_SITE_CONFIG)) {
            nodeService.addAspect(siteNode, ASPECT_SITE_CONFIG, null);
        }

        nodeService.setProperty(siteNode, PROP_ACCESS_TOKEN, tokenResponse.getAccessToken());
        nodeService.setProperty(siteNode, PROP_REFRESH_TOKEN, tokenResponse.getRefreshToken());
        nodeService.setProperty(siteNode, PROP_TOKEN_EXPIRY,
                System.currentTimeMillis() + (tokenResponse.getExpiresInSeconds() * 1000));
        nodeService.setProperty(siteNode, PROP_ENABLED, true);

        logger.info("Google Calendar OAuth tokens stored for site: " + siteId);
    }

    // ---- Forward sync: Alfresco -> Google ----

    public List<CalendarListEntry> listCalendars(String siteId) throws IOException {
        Calendar service = getCalendarService(siteId);
        if (service == null) return Collections.emptyList();

        CalendarList calendarList = service.calendarList().list().execute();
        return calendarList.getItems() != null ? calendarList.getItems() : Collections.<CalendarListEntry>emptyList();
    }

    public void setTargetCalendar(String siteId, String calendarId) {
        SiteInfo site = siteService.getSite(siteId);
        if (site == null) return;

        nodeService.setProperty(site.getNodeRef(), PROP_TARGET_CALENDAR_ID, calendarId);
        logger.info("Target Google Calendar set for site " + siteId + ": " + calendarId);
    }

    public String createEvent(NodeRef eventNode) throws IOException {
        String siteId = getSiteIdForNode(eventNode);
        if (siteId == null) return null;
        if (!isSyncEnabled(siteId)) return null;

        Calendar service = getCalendarService(siteId);
        if (service == null) return null;

        String calendarId = getTargetCalendarId(siteId);
        if (calendarId == null) return null;

        Event googleEvent = buildGoogleEvent(eventNode);
        Event created = service.events().insert(calendarId, googleEvent).execute();

        if (!nodeService.hasAspect(eventNode, ASPECT_SYNCED)) {
            nodeService.addAspect(eventNode, ASPECT_SYNCED, null);
        }
        nodeService.setProperty(eventNode, PROP_GOOGLE_EVENT_ID, created.getId());
        nodeService.setProperty(eventNode, PROP_GOOGLE_CALENDAR_ID, calendarId);
        nodeService.setProperty(eventNode, PROP_LAST_SYNC_TIME, new Date());

        logger.info("Created Google Calendar event: " + created.getId() + " for Alfresco event: " + eventNode);
        return created.getId();
    }

    public void updateEvent(NodeRef eventNode) throws IOException {
        if (!nodeService.hasAspect(eventNode, ASPECT_SYNCED)) {
            createEvent(eventNode);
            return;
        }

        String googleEventId = (String) nodeService.getProperty(eventNode, PROP_GOOGLE_EVENT_ID);
        String calendarId = (String) nodeService.getProperty(eventNode, PROP_GOOGLE_CALENDAR_ID);
        if (googleEventId == null || calendarId == null) return;

        String siteId = getSiteIdForNode(eventNode);
        if (siteId == null || !isSyncEnabled(siteId)) return;

        Calendar service = getCalendarService(siteId);
        if (service == null) return;

        Event googleEvent = buildGoogleEvent(eventNode);
        service.events().update(calendarId, googleEventId, googleEvent).execute();
        nodeService.setProperty(eventNode, PROP_LAST_SYNC_TIME, new Date());

        logger.info("Updated Google Calendar event: " + googleEventId);
    }

    public void deleteEvent(String siteId, String googleEventId, String calendarId) throws IOException {
        if (googleEventId == null || calendarId == null || siteId == null) return;
        if (!isSyncEnabled(siteId)) return;

        Calendar service = getCalendarService(siteId);
        if (service == null) return;

        try {
            service.events().delete(calendarId, googleEventId).execute();
            logger.info("Deleted Google Calendar event: " + googleEventId);
        } catch (GoogleJsonResponseException e) {
            if (e.getStatusCode() == 404 || e.getStatusCode() == 410) {
                logger.debug("Google event already deleted: " + googleEventId);
            } else {
                throw e;
            }
        }
    }

    public void disconnect(String siteId) {
        SiteInfo site = siteService.getSite(siteId);
        if (site == null) return;

        NodeRef siteNode = site.getNodeRef();
        if (nodeService.hasAspect(siteNode, ASPECT_SITE_CONFIG)) {
            nodeService.setProperty(siteNode, PROP_ENABLED, false);
            nodeService.setProperty(siteNode, PROP_ACCESS_TOKEN, null);
            nodeService.setProperty(siteNode, PROP_REFRESH_TOKEN, null);
            nodeService.setProperty(siteNode, PROP_TOKEN_EXPIRY, null);
            nodeService.setProperty(siteNode, PROP_TARGET_CALENDAR_ID, null);
            nodeService.setProperty(siteNode, PROP_SYNC_TOKEN, null);
        }
        logger.info("Google Calendar disconnected for site: " + siteId);
    }

    // ---- Reverse sync: Google -> Alfresco ----

    public void syncAllSitesFromGoogle() {
        List<SiteInfo> sites = siteService.listSites(null, null);
        int synced = 0;
        for (SiteInfo site : sites) {
            String siteId = site.getShortName();
            if (isSyncEnabled(siteId) && getTargetCalendarId(siteId) != null) {
                try {
                    syncFromGoogle(siteId);
                    synced++;
                } catch (Exception e) {
                    logger.error("Reverse sync failed for site: " + siteId, e);
                }
            }
        }
        if (synced > 0) {
            logger.debug("Reverse sync completed for " + synced + " site(s)");
        }
    }

    public void syncFromGoogle(String siteId) throws IOException {
        Calendar service = getCalendarService(siteId);
        if (service == null) return;

        String calendarId = getTargetCalendarId(siteId);
        if (calendarId == null) return;

        NodeRef calendarContainer = siteService.getContainer(siteId, "calendar");
        if (calendarContainer == null) {
            calendarContainer = siteService.createContainer(siteId, "calendar", ContentModel.TYPE_FOLDER, null);
        }

        Map<String, NodeRef> googleIdToNode = buildGoogleIdMap(calendarContainer);
        String syncToken = getSyncToken(siteId);

        try {
            String pageToken = null;
            do {
                Calendar.Events.List request = service.events().list(calendarId);
                if (syncToken != null) {
                    request.setSyncToken(syncToken);
                } else {
                    request.setTimeMin(new com.google.api.client.util.DateTime(
                            System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000));
                }
                if (pageToken != null) {
                    request.setPageToken(pageToken);
                }
                request.setMaxResults(100);

                Events eventsResponse = request.execute();
                List<Event> items = eventsResponse.getItems();
                if (items != null) {
                    for (Event event : items) {
                        try {
                            processGoogleEvent(siteId, calendarContainer, googleIdToNode, calendarId, event);
                        } catch (Exception e) {
                            logger.warn("Failed to process Google event " + event.getId() + ": " + e.getMessage());
                        }
                    }
                }

                pageToken = eventsResponse.getNextPageToken();
                if (eventsResponse.getNextSyncToken() != null) {
                    storeSyncToken(siteId, eventsResponse.getNextSyncToken());
                }
            } while (pageToken != null);

        } catch (GoogleJsonResponseException e) {
            if (e.getStatusCode() == 410) {
                storeSyncToken(siteId, null);
                logger.info("Sync token expired for site " + siteId + ", will do full sync next cycle");
            } else {
                throw e;
            }
        }
    }

    private void processGoogleEvent(String siteId, NodeRef calendarContainer,
            Map<String, NodeRef> googleIdToNode, String calendarId, Event googleEvent) {

        String googleEventId = googleEvent.getId();
        NodeRef existingNode = googleIdToNode.get(googleEventId);

        if (existingNode != null && !nodeService.exists(existingNode)) {
            existingNode = null;
        }

        if (existingNode == null) {
            existingNode = findEventByGoogleId(calendarContainer, googleEventId);
            if (existingNode != null) {
                googleIdToNode.put(googleEventId, existingNode);
            }
        }

        if ("cancelled".equals(googleEvent.getStatus())) {
            if (existingNode != null) {
                RECENTLY_SYNCED_FROM_GOOGLE.add(existingNode);
                nodeService.deleteNode(existingNode);
                logger.info("Deleted Alfresco event (Google deleted): " + googleEventId);
            }
            return;
        }

        if (existingNode != null) {
            updateAlfrescoEventFromGoogle(existingNode, googleEvent);
        } else {
            NodeRef created = createAlfrescoEventFromGoogle(calendarContainer, calendarId, googleEvent);
            if (created != null) {
                googleIdToNode.put(googleEventId, created);
            }
        }
    }

    private NodeRef createAlfrescoEventFromGoogle(NodeRef calendarContainer, String calendarId, Event googleEvent) {
        NodeRef existing = findEventByGoogleId(calendarContainer, googleEvent.getId());
        if (existing != null) {
            updateAlfrescoEventFromGoogle(existing, googleEvent);
            return existing;
        }

        Map<QName, Serializable> props = new HashMap<QName, Serializable>();
        props.put(PROP_WHAT_EVENT, googleEvent.getSummary() != null ? googleEvent.getSummary() : "Untitled Event");
        props.put(ContentModel.PROP_NAME, "gcal-" + googleEvent.getId());

        if (googleEvent.getDescription() != null) {
            props.put(PROP_DESCRIPTION_EVENT, googleEvent.getDescription());
        }
        if (googleEvent.getLocation() != null) {
            props.put(PROP_WHERE_EVENT, googleEvent.getLocation());
        }

        Date startDate = getDateFromEventDateTime(googleEvent.getStart());
        Date endDate = getDateFromEventDateTime(googleEvent.getEnd());
        if (startDate != null) props.put(PROP_FROM_DATE, startDate);
        if (endDate != null) props.put(PROP_TO_DATE, endDate);

        ChildAssociationRef childRef = nodeService.createNode(
                calendarContainer,
                ContentModel.ASSOC_CONTAINS,
                QName.createQName("http://www.alfresco.org/model/content/1.0",
                        "gcal-" + googleEvent.getId()),
                TYPE_CALENDAR_EVENT,
                props);

        NodeRef eventNode = childRef.getChildRef();
        RECENTLY_SYNCED_FROM_GOOGLE.add(eventNode);

        Map<QName, Serializable> aspectProps = new HashMap<QName, Serializable>();
        aspectProps.put(PROP_GOOGLE_EVENT_ID, googleEvent.getId());
        aspectProps.put(PROP_GOOGLE_CALENDAR_ID, calendarId);
        aspectProps.put(PROP_LAST_SYNC_TIME, new Date());
        nodeService.addAspect(eventNode, ASPECT_SYNCED, aspectProps);

        logger.info("Created Alfresco event from Google: " + googleEvent.getId()
                + " (" + googleEvent.getSummary() + ")");
        return eventNode;
    }

    private void updateAlfrescoEventFromGoogle(NodeRef eventNode, Event googleEvent) {
        RECENTLY_SYNCED_FROM_GOOGLE.add(eventNode);

        if (googleEvent.getSummary() != null) {
            nodeService.setProperty(eventNode, PROP_WHAT_EVENT, googleEvent.getSummary());
        }
        nodeService.setProperty(eventNode, PROP_DESCRIPTION_EVENT, googleEvent.getDescription());
        nodeService.setProperty(eventNode, PROP_WHERE_EVENT, googleEvent.getLocation());

        Date startDate = getDateFromEventDateTime(googleEvent.getStart());
        Date endDate = getDateFromEventDateTime(googleEvent.getEnd());
        if (startDate != null) nodeService.setProperty(eventNode, PROP_FROM_DATE, startDate);
        if (endDate != null) nodeService.setProperty(eventNode, PROP_TO_DATE, endDate);

        nodeService.setProperty(eventNode, PROP_LAST_SYNC_TIME, new Date());
    }

    // ---- Helpers ----

    private Date getDateFromEventDateTime(EventDateTime edt) {
        if (edt == null) return null;
        if (edt.getDateTime() != null) {
            return new Date(edt.getDateTime().getValue());
        }
        if (edt.getDate() != null) {
            return new Date(edt.getDate().getValue());
        }
        return null;
    }

    private Map<String, NodeRef> buildGoogleIdMap(NodeRef calendarContainer) {
        Map<String, NodeRef> map = new HashMap<String, NodeRef>();
        List<ChildAssociationRef> children = nodeService.getChildAssocs(calendarContainer);
        for (ChildAssociationRef child : children) {
            NodeRef eventNode = child.getChildRef();
            if (nodeService.hasAspect(eventNode, ASPECT_SYNCED)) {
                String gEventId = (String) nodeService.getProperty(eventNode, PROP_GOOGLE_EVENT_ID);
                if (gEventId != null) {
                    map.put(gEventId, eventNode);
                }
            } else {
                String name = (String) nodeService.getProperty(eventNode, ContentModel.PROP_NAME);
                if (name != null && name.startsWith("gcal-")) {
                    map.put(name.substring(5), eventNode);
                }
            }
        }
        return map;
    }

    private NodeRef findEventByGoogleId(NodeRef calendarContainer, String googleEventId) {
        List<ChildAssociationRef> children = nodeService.getChildAssocs(calendarContainer);
        for (ChildAssociationRef child : children) {
            NodeRef eventNode = child.getChildRef();
            if (nodeService.hasAspect(eventNode, ASPECT_SYNCED)) {
                String gEventId = (String) nodeService.getProperty(eventNode, PROP_GOOGLE_EVENT_ID);
                if (googleEventId.equals(gEventId)) {
                    return eventNode;
                }
            }
            String name = (String) nodeService.getProperty(eventNode, ContentModel.PROP_NAME);
            if (("gcal-" + googleEventId).equals(name)) {
                return eventNode;
            }
        }
        return null;
    }

    private String getSyncToken(String siteId) {
        SiteInfo site = siteService.getSite(siteId);
        if (site == null) return null;
        return (String) nodeService.getProperty(site.getNodeRef(), PROP_SYNC_TOKEN);
    }

    private void storeSyncToken(String siteId, String syncToken) {
        SiteInfo site = siteService.getSite(siteId);
        if (site == null) return;
        nodeService.setProperty(site.getNodeRef(), PROP_SYNC_TOKEN, syncToken);
    }

    private Calendar getCalendarService(String siteId) throws IOException {
        SiteInfo site = siteService.getSite(siteId);
        if (site == null) return null;

        NodeRef siteNode = site.getNodeRef();
        if (!nodeService.hasAspect(siteNode, ASPECT_SITE_CONFIG)) return null;

        String accessToken = (String) nodeService.getProperty(siteNode, PROP_ACCESS_TOKEN);
        String refreshToken = (String) nodeService.getProperty(siteNode, PROP_REFRESH_TOKEN);
        Long expiry = (Long) nodeService.getProperty(siteNode, PROP_TOKEN_EXPIRY);

        if (accessToken == null || refreshToken == null) return null;

        if (expiry != null && System.currentTimeMillis() > expiry - 60000) {
            accessToken = refreshAccessToken(siteNode, refreshToken);
        }

        Credential credential = new Credential(BearerToken.authorizationHeaderAccessMethod())
                .setAccessToken(accessToken);

        return new Calendar.Builder(HTTP_TRANSPORT, JSON_FACTORY, credential)
                .setApplicationName(APPLICATION_NAME)
                .build();
    }

    private String refreshAccessToken(NodeRef siteNode, String refreshToken) throws IOException {
        Credential credential = new Credential.Builder(BearerToken.authorizationHeaderAccessMethod())
                .setTransport(HTTP_TRANSPORT)
                .setJsonFactory(JSON_FACTORY)
                .setTokenServerUrl(new GenericUrl("https://oauth2.googleapis.com/token"))
                .setClientAuthentication(new com.google.api.client.auth.oauth2.ClientParametersAuthentication(
                        clientId, clientSecret))
                .build();
        credential.setRefreshToken(refreshToken);
        credential.refreshToken();

        String newAccessToken = credential.getAccessToken();
        Long newExpiry = credential.getExpirationTimeMilliseconds();

        nodeService.setProperty(siteNode, PROP_ACCESS_TOKEN, newAccessToken);
        if (newExpiry != null) {
            nodeService.setProperty(siteNode, PROP_TOKEN_EXPIRY, newExpiry);
        }

        return newAccessToken;
    }

    private Event buildGoogleEvent(NodeRef eventNode) {
        Map<QName, Serializable> props = nodeService.getProperties(eventNode);

        String title = (String) props.get(PROP_WHAT_EVENT);
        String description = (String) props.get(PROP_DESCRIPTION_EVENT);
        String location = (String) props.get(PROP_WHERE_EVENT);
        Date fromDate = (Date) props.get(PROP_FROM_DATE);
        Date toDate = (Date) props.get(PROP_TO_DATE);

        Event event = new Event();
        event.setSummary(title != null ? title : "Untitled Event");
        if (description != null) event.setDescription(description);
        if (location != null) event.setLocation(location);

        if (fromDate != null) {
            event.setStart(new EventDateTime()
                    .setDateTime(new com.google.api.client.util.DateTime(fromDate)));
        }
        if (toDate != null) {
            event.setEnd(new EventDateTime()
                    .setDateTime(new com.google.api.client.util.DateTime(toDate)));
        } else if (fromDate != null) {
            event.setEnd(new EventDateTime()
                    .setDateTime(new com.google.api.client.util.DateTime(
                            new Date(fromDate.getTime() + 3600000))));
        }

        return event;
    }

    private String getSiteIdForNode(NodeRef nodeRef) {
        SiteInfo site = siteService.getSite(nodeRef);
        return site != null ? site.getShortName() : null;
    }

    private boolean isSyncEnabled(String siteId) {
        SiteInfo site = siteService.getSite(siteId);
        if (site == null) return false;

        NodeRef siteNode = site.getNodeRef();
        if (!nodeService.hasAspect(siteNode, ASPECT_SITE_CONFIG)) return false;

        Boolean enabled = (Boolean) nodeService.getProperty(siteNode, PROP_ENABLED);
        return Boolean.TRUE.equals(enabled);
    }

    private String getTargetCalendarId(String siteId) {
        SiteInfo site = siteService.getSite(siteId);
        if (site == null) return null;
        return (String) nodeService.getProperty(site.getNodeRef(), PROP_TARGET_CALENDAR_ID);
    }
}
