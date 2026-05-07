package org.iurit.alfresco.google.calendar;

import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

public class GoogleCalendarSyncJob implements Job {

    private static final Log logger = LogFactory.getLog(GoogleCalendarSyncJob.class);

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        JobDataMap jobData = context.getJobDetail().getJobDataMap();
        final GoogleCalendarService service = (GoogleCalendarService) jobData.get("googleCalendarService");

        try {
            AuthenticationUtil.setFullyAuthenticatedUser(AuthenticationUtil.getAdminUserName());
            service.syncAllSitesFromGoogle();
        } catch (Exception e) {
            logger.error("Google Calendar reverse sync failed", e);
        } finally {
            try {
                AuthenticationUtil.clearCurrentSecurityContext();
            } catch (Exception ignore) {
            }
        }
    }
}
