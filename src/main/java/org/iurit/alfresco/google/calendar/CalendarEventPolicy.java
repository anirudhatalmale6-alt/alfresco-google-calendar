package org.iurit.alfresco.google.calendar;

import org.alfresco.repo.node.NodeServicePolicies;
import org.alfresco.repo.policy.Behaviour;
import org.alfresco.repo.policy.JavaBehaviour;
import org.alfresco.repo.policy.PolicyComponent;
import org.alfresco.service.cmr.repository.ChildAssociationRef;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.namespace.QName;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.Serializable;
import java.util.Date;
import java.util.Map;

public class CalendarEventPolicy implements
        NodeServicePolicies.OnCreateNodePolicy,
        NodeServicePolicies.OnUpdatePropertiesPolicy,
        NodeServicePolicies.BeforeDeleteNodePolicy {

    private static final Log logger = LogFactory.getLog(CalendarEventPolicy.class);

    private PolicyComponent policyComponent;
    private NodeService nodeService;
    private GoogleCalendarService googleCalendarService;

    private static final ThreadLocal<Boolean> IN_SYNC = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return Boolean.FALSE;
        }
    };

    public void setPolicyComponent(PolicyComponent policyComponent) {
        this.policyComponent = policyComponent;
    }

    public void setNodeService(NodeService nodeService) {
        this.nodeService = nodeService;
    }

    public void setGoogleCalendarService(GoogleCalendarService googleCalendarService) {
        this.googleCalendarService = googleCalendarService;
    }

    public void init() {
        policyComponent.bindClassBehaviour(
                NodeServicePolicies.OnCreateNodePolicy.QNAME,
                GoogleCalendarService.TYPE_CALENDAR_EVENT,
                new JavaBehaviour(this, "onCreateNode", Behaviour.NotificationFrequency.TRANSACTION_COMMIT));

        policyComponent.bindClassBehaviour(
                NodeServicePolicies.OnUpdatePropertiesPolicy.QNAME,
                GoogleCalendarService.TYPE_CALENDAR_EVENT,
                new JavaBehaviour(this, "onUpdateProperties", Behaviour.NotificationFrequency.TRANSACTION_COMMIT));

        policyComponent.bindClassBehaviour(
                NodeServicePolicies.BeforeDeleteNodePolicy.QNAME,
                GoogleCalendarService.TYPE_CALENDAR_EVENT,
                new JavaBehaviour(this, "beforeDeleteNode", Behaviour.NotificationFrequency.FIRST_EVENT));

        logger.info("Google Calendar sync policies registered for ia:calendarEvent");
    }

    @Override
    public void onCreateNode(ChildAssociationRef childAssocRef) {
        if (IN_SYNC.get()) return;

        NodeRef eventNode = childAssocRef.getChildRef();
        if (!nodeService.exists(eventNode)) return;
        if (GoogleCalendarService.wasRecentlySyncedFromGoogle(eventNode)) return;
        if (nodeService.hasAspect(eventNode, GoogleCalendarService.ASPECT_SYNCED)) return;
        if (isEventPast(eventNode)) return;

        try {
            IN_SYNC.set(Boolean.TRUE);
            googleCalendarService.createEvent(eventNode);
        } catch (Exception e) {
            logger.error("Failed to sync new calendar event to Google Calendar: " + eventNode, e);
        } finally {
            IN_SYNC.set(Boolean.FALSE);
        }
    }

    @Override
    public void onUpdateProperties(NodeRef nodeRef, Map<QName, Serializable> before, Map<QName, Serializable> after) {
        if (IN_SYNC.get()) return;
        if (!nodeService.exists(nodeRef)) return;
        if (GoogleCalendarService.wasRecentlySyncedFromGoogle(nodeRef)) return;
        if (isEventPast(nodeRef)) return;

        boolean relevant = hasChanged(before, after, GoogleCalendarService.PROP_WHAT_EVENT)
                || hasChanged(before, after, GoogleCalendarService.PROP_FROM_DATE)
                || hasChanged(before, after, GoogleCalendarService.PROP_TO_DATE)
                || hasChanged(before, after, GoogleCalendarService.PROP_WHERE_EVENT)
                || hasChanged(before, after, GoogleCalendarService.PROP_DESCRIPTION_EVENT);

        if (!relevant) return;

        try {
            IN_SYNC.set(Boolean.TRUE);
            googleCalendarService.updateEvent(nodeRef);
        } catch (Exception e) {
            logger.error("Failed to sync updated calendar event to Google Calendar: " + nodeRef, e);
        } finally {
            IN_SYNC.set(Boolean.FALSE);
        }
    }

    @Override
    public void beforeDeleteNode(NodeRef nodeRef) {
        if (IN_SYNC.get()) return;
        if (!nodeService.exists(nodeRef)) return;
        if (GoogleCalendarService.wasRecentlySyncedFromGoogle(nodeRef)) return;

        if (!nodeService.hasAspect(nodeRef, GoogleCalendarService.ASPECT_SYNCED)) return;

        String googleEventId = (String) nodeService.getProperty(nodeRef, GoogleCalendarService.PROP_GOOGLE_EVENT_ID);
        String calendarId = (String) nodeService.getProperty(nodeRef, GoogleCalendarService.PROP_GOOGLE_CALENDAR_ID);

        try {
            IN_SYNC.set(Boolean.TRUE);
            org.alfresco.service.cmr.repository.Path path = nodeService.getPath(nodeRef);
            String siteId = null;
            for (int i = 0; i < path.size(); i++) {
                org.alfresco.service.cmr.repository.Path.Element element = path.get(i);
                if (element instanceof org.alfresco.service.cmr.repository.Path.ChildAssocElement) {
                    org.alfresco.service.cmr.repository.ChildAssociationRef ref =
                            ((org.alfresco.service.cmr.repository.Path.ChildAssocElement) element).getRef();
                    NodeRef parent = ref.getChildRef();
                    if (nodeService.getType(parent).equals(
                            QName.createQName("http://www.alfresco.org/model/site/1.0", "site"))) {
                        siteId = (String) nodeService.getProperty(parent, org.alfresco.model.ContentModel.PROP_NAME);
                        break;
                    }
                }
            }

            if (siteId != null) {
                googleCalendarService.deleteEvent(siteId, googleEventId, calendarId);
            }
        } catch (Exception e) {
            logger.error("Failed to delete Google Calendar event: " + googleEventId, e);
        } finally {
            IN_SYNC.set(Boolean.FALSE);
        }
    }

    private boolean isEventPast(NodeRef eventNode) {
        Date toDate = (Date) nodeService.getProperty(eventNode, GoogleCalendarService.PROP_TO_DATE);
        if (toDate == null) {
            toDate = (Date) nodeService.getProperty(eventNode, GoogleCalendarService.PROP_FROM_DATE);
        }
        return toDate != null && toDate.before(new Date());
    }

    private boolean hasChanged(Map<QName, Serializable> before, Map<QName, Serializable> after, QName prop) {
        Serializable oldVal = before.get(prop);
        Serializable newVal = after.get(prop);
        if (oldVal == null && newVal == null) return false;
        if (oldVal == null || newVal == null) return true;
        return !oldVal.equals(newVal);
    }
}
