<#if error??>
{"error": "${error}"}
<#elseif success??>
{"success": ${success?string}, "message": "${message!""}"}
<#else>
{}
</#if>
