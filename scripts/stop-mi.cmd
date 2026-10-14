@echo off
rem Stops WSO2 Micro Integrator (finds its java.exe by install folder)
powershell -NoProfile -Command "$p = Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -like '*wso2mi*' }; if ($p) { $p | ForEach-Object { Stop-Process -Id $_.ProcessId -Force; Write-Host ('Stopped MI (PID ' + $_.ProcessId + ')') } } else { Write-Host 'MI is not running' }"
