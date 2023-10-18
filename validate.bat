@echo off

where /q bb
if ERRORLEVEL 0 (
 set RUNTIME="bb"
) else (
  set RUNTIME="clojure -M"
)
%RUNTIME% -m nl.jomco.eduhub-validator.main %*
