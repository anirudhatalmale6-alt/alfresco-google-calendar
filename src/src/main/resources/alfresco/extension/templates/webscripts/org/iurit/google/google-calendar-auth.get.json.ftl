<#if error??>
{"error": "${error}"}
<#elseif authorizationUrl??>
{"authorizationUrl": "${authorizationUrl}", "siteId": "${siteId}"}
<#else>
{"error": "Google Calendar not configured. Set google.calendar.clientId and google.calendar.clientSecret in alfresco-global.properties"}
</#if>
