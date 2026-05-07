package org.iurit.alfresco.google.calendar;

import com.google.api.services.calendar.model.CalendarListEntry;
import org.alfresco.service.cmr.site.SiteInfo;
import org.alfresco.service.cmr.site.SiteService;
import org.alfresco.service.cmr.repository.NodeService;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.extensions.webscripts.Cache;
import org.springframework.extensions.webscripts.DeclarativeWebScript;
import org.springframework.extensions.webscripts.Status;
import org.springframework.extensions.webscripts.WebScriptRequest;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Web Script to get Google Calendar configuration for a site.
 * GET /alfresco/s/api/google-calendar/config?siteId={siteId}
 */
public class GoogleCalendarConfigWebScript extends DeclarativeWebScript {

    private static final Log logger = LogFactory.getLog(GoogleCalendarConfigWebScript.class);

    private GoogleCalendarService googleCalendarService;
    private SiteService siteService;
    private NodeService nodeService;

    public void setGoogleCalendarService(GoogleCalendarService googleCalendarService) {
        this.googleCalendarService = googleCalendarService;
    }

    public void setSiteService(SiteService siteService) {
        this.siteService = siteService;
    }

    public void setNodeService(NodeService nodeService) {
        this.nodeService = nodeService;
    }

    @Override
    protected Map<String, Object> executeImpl(WebScriptRequest req, Status status, Cache cache) {
        Map<String, Object> model = new HashMap<String, Object>();

        String siteId = req.getParameter("siteId");
        if (siteId == null) {
            status.setCode(400);
            model.put("error", "siteId required");
            return model;
        }

        if (!isSiteManager(siteId)) {
            status.setCode(403);
            model.put("error", "Only Site Managers can view Google Calendar config");
            return model;
        }

        try {
            model.put("siteId", siteId);

            SiteInfo site = siteService.getSite(siteId);
            if (site != null && nodeService.hasAspect(site.getNodeRef(), GoogleCalendarService.ASPECT_SITE_CONFIG)) {
                Boolean enabled = (Boolean) nodeService.getProperty(
                        site.getNodeRef(), GoogleCalendarService.PROP_ENABLED);
                String targetCalId = (String) nodeService.getProperty(
                        site.getNodeRef(), GoogleCalendarService.PROP_TARGET_CALENDAR_ID);
                String accessToken = (String) nodeService.getProperty(
                        site.getNodeRef(), GoogleCalendarService.PROP_ACCESS_TOKEN);

                model.put("connected", accessToken != null);
                model.put("enabled", Boolean.TRUE.equals(enabled));
                model.put("targetCalendarId", targetCalId != null ? targetCalId : "");

                if (accessToken != null) {
                    List<CalendarListEntry> calendars = googleCalendarService.listCalendars(siteId);
                    JSONArray calArray = new JSONArray();
                    for (CalendarListEntry cal : calendars) {
                        JSONObject calObj = new JSONObject();
                        calObj.put("id", cal.getId());
                        calObj.put("summary", cal.getSummary());
                        calObj.put("primary", Boolean.TRUE.equals(cal.getPrimary()));
                        calArray.put(calObj);
                    }
                    model.put("calendars", calArray.toString());
                }
            } else {
                model.put("connected", false);
                model.put("enabled", false);
            }
        } catch (Exception e) {
            logger.error("Error getting Google Calendar config for site: " + siteId, e);
            status.setCode(500);
            model.put("error", e.getMessage());
        }

        return model;
    }

    private boolean isSiteManager(String siteId) {
        String user = org.alfresco.repo.security.authentication.AuthenticationUtil.getFullyAuthenticatedUser();
        String role = siteService.getMembersRole(siteId, user);
        return "SiteManager".equals(role);
    }
}
