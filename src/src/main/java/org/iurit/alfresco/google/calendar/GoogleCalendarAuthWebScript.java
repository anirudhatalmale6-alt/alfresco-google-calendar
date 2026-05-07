package org.iurit.alfresco.google.calendar;

import org.alfresco.service.cmr.site.SiteInfo;
import org.alfresco.service.cmr.site.SiteService;
import org.json.JSONObject;
import org.springframework.extensions.webscripts.AbstractWebScript;
import org.springframework.extensions.webscripts.WebScriptRequest;
import org.springframework.extensions.webscripts.WebScriptResponse;
import org.springframework.extensions.webscripts.Status;
import org.springframework.extensions.webscripts.Cache;
import org.springframework.extensions.webscripts.DeclarativeWebScript;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Web Script to initiate Google Calendar OAuth flow for a site.
 * GET /alfresco/s/api/google-calendar/auth?siteId={siteId}
 * Returns JSON with the authorization URL.
 */
public class GoogleCalendarAuthWebScript extends DeclarativeWebScript {

    private static final Log logger = LogFactory.getLog(GoogleCalendarAuthWebScript.class);

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

        String siteId = req.getParameter("siteId");

        if (siteId == null || siteId.isEmpty()) {
            status.setCode(400);
            model.put("error", "siteId parameter is required");
            return model;
        }

        SiteInfo site = siteService.getSite(siteId);
        if (site == null) {
            status.setCode(404);
            model.put("error", "Site not found: " + siteId);
            return model;
        }

        String role = siteService.getMembersRole(siteId,
                org.alfresco.repo.security.authentication.AuthenticationUtil.getFullyAuthenticatedUser());
        if (!"SiteManager".equals(role)) {
            status.setCode(403);
            model.put("error", "Only Site Managers can configure Google Calendar");
            return model;
        }

        try {
            String authUrl = googleCalendarService.getAuthorizationUrl(siteId);
            model.put("authorizationUrl", authUrl);
            model.put("siteId", siteId);
        } catch (Exception e) {
            logger.error("Error generating Google Calendar auth URL", e);
            status.setCode(500);
            model.put("error", e.getMessage());
        }

        return model;
    }
}
