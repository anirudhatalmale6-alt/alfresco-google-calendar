<#if error??>
{"error": "${error}"}
<#else>
{"siteId": "${siteId!""}", "connected": ${(connected!false)?string}, "enabled": ${(enabled!false)?string}<#if targetCalendarId?? && targetCalendarId != "">, "targetCalendarId": "${targetCalendarId}"</#if><#if calendars??>, "calendars": ${calendars}</#if>}
</#if>
