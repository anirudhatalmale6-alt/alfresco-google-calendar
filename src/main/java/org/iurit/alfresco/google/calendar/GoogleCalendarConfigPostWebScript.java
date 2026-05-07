package org.iurit.alfresco.google.calendar;

import org.alfresco.service.cmr.site.SiteService;
import org.json.JSONObject;
import org.springframework.extensions.webscripts.Cache;
import org.springframework.extensions.webscripts.DeclarativeWebScript;
import org.springframework.extensions.webscripts.Status;
import org.springframework.extensions.webscripts.WebScriptRequest;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.BufferedReader;
import java.util.HashMap;
import java.util.Map;

/**
 * Web Script to update Google Calendar configuration for a site.
 * POST /alfresco/s/api/google-calendar/config
 * Body: {"siteId": "...", "calendarId": "...", "action": "set-calendar|disconnect"}
 */
public class GoogleCalendarConfigPostWebScript extends DeclarativeWebScript {

    private static final Log logger = LogFactory.getLog(GoogleCalendarConfigPostWebScript.class);

    private GoogleCalendarService googleCalendarService;
    private SiteService siteService;

    public void setGoogleCalendarService(GoogleCalendarService googleCalendarService) {
        this.googleCalendarService = googleCalendarService;
    }

    public void setSiteService(SiteService siteService) {
        this.siteService = siteService;
    }

    @Override
    protected Map<String, Object> executeImpl(WebScriptRequest req, Status status, Cache cache) {
        Map<String, Object> model = new HashMap<String, Object>();

        try {
            StringBuilder body = new StringBuilder();
            BufferedReader reader = new BufferedReader(req.getContent().getReader());
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }

            JSONObject input = new JSONObject(body.toString());
            String siteId = input.getString("siteId");
            String action = input.getString("action");

            if (!isSiteManager(siteId)) {
                status.setCode(403);
                model.put("error", "Only Site Managers can configure Google Calendar");
                return model;
            }

            if ("set-calendar".equals(action)) {
                String calendarId = input.getString("calendarId");
                googleCalendarService.setTargetCalendar(siteId, calendarId);
                model.put("success", true);
                model.put("message", "Target calendar set: " + calendarId);
            } else if ("disconnect".equals(action)) {
                googleCalendarService.disconnect(siteId);
                model.put("success", true);
                model.put("message", "Google Calendar disconnected");
            } else {
                status.setCode(400);
                model.put("error", "Unknown action: " + action);
            }
        } catch (Exception e) {
            logger.error("Error processing Google Calendar config", e);
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
