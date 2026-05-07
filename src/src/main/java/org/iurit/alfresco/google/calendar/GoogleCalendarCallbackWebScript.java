package org.iurit.alfresco.google.calendar;

import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.springframework.extensions.webscripts.AbstractWebScript;
import org.springframework.extensions.webscripts.WebScriptRequest;
import org.springframework.extensions.webscripts.WebScriptResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.IOException;

public class GoogleCalendarCallbackWebScript extends AbstractWebScript {

    private static final Log logger = LogFactory.getLog(GoogleCalendarCallbackWebScript.class);

    private GoogleCalendarService googleCalendarService;

    public void setGoogleCalendarService(GoogleCalendarService googleCalendarService) {
        this.googleCalendarService = googleCalendarService;
    }

    @Override
    public void execute(WebScriptRequest req, WebScriptResponse res) throws IOException {
        String code = req.getParameter("code");
        String siteId = req.getParameter("state");
        String error = req.getParameter("error");

        if (error != null) {
            res.setContentType("text/html");
            res.getWriter().write("<html><body><h2>Authorization denied</h2>" +
                    "<p>Error: " + escapeHtml(error) + "</p>" +
                    "<script>setTimeout(function(){window.close();},3000);</script>" +
                    "</body></html>");
            return;
        }

        if (code == null || siteId == null) {
            res.setStatus(400);
            res.setContentType("text/html");
            res.getWriter().write("<html><body><h2>Invalid callback</h2>" +
                    "<p>Missing authorization code or site ID.</p>" +
                    "<script>setTimeout(function(){window.close();},3000);</script>" +
                    "</body></html>");
            return;
        }

        try {
            AuthenticationUtil.setFullyAuthenticatedUser(AuthenticationUtil.getAdminUserName());
            googleCalendarService.handleAuthorizationCode(siteId, code);

            res.setContentType("text/html");
            res.getWriter().write("<html><body>" +
                    "<h2>Google Calendar Connected!</h2>" +
                    "<p>Successfully connected for site: <b>" + escapeHtml(siteId) + "</b></p>" +
                    "<p>This window will close automatically...</p>" +
                    "<script>setTimeout(function(){window.close();},2000);</script>" +
                    "</body></html>");

            logger.info("Google Calendar OAuth completed for site: " + siteId);
        } catch (Exception e) {
            logger.error("Error processing Google Calendar OAuth callback for site: " + siteId, e);
            res.setStatus(500);
            res.setContentType("text/html");
            res.getWriter().write("<html><body><h2>Error</h2>" +
                    "<p>Failed to connect Google Calendar: " + escapeHtml(e.getMessage()) + "</p>" +
                    "<script>setTimeout(function(){window.close();},5000);</script>" +
                    "</body></html>");
        } finally {
            try {
                AuthenticationUtil.clearCurrentSecurityContext();
            } catch (Exception ignore) {
            }
        }
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
