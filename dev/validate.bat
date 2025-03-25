@echo off

rem SPDX-FileCopyrightText: 2024, 2025 SURF B.V.
rem SPDX-License-Identifier: EPL-2.0 WITH Classpath-exception-2.0
rem SPDX-FileContributor: Joost Diepenmaat

where /q bb
if ERRORLEVEL 0 (
 set RUNTIME="bb"
) else (
  set RUNTIME="clojure -M"
)
%RUNTIME% -m nl.jomco.apie.main %*
