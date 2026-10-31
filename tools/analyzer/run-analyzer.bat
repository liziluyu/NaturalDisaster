@echo off
chcp 65001 >nul
setlocal EnableDelayedExpansion
cd /d "%~dp0..\.."

set "CP_FILE=build\moddev\clientLegacyClasspath.txt"
set "OUT=build\tmp\analyzer-web"
set "JAVAC_ARGS=build\tmp\analyzer-javac.args"
set "JAVA_ARGS=build\tmp\analyzer-java.args"
set "PORT=8765"

if not exist "%CP_FILE%" (
  echo [error] Missing %CP_FILE%
  echo Run "gradlew compileJava" or "gradlew build" first.
  pause
  exit /b 1
)

if not exist "%OUT%" mkdir "%OUT%" >nul 2>&1

rem Join the newline-separated classpath and use forward slashes for Java argfiles.
set "CP="
for /f "usebackq delims=" %%L in ("%CP_FILE%") do (
  if defined CP (set "CP=!CP!;%%L") else set "CP=%%L"
)
set "CP=!CP:\=/!"
set "OUT_ARG=!OUT:\=/!"

rem javac argfile: keeps the very long classpath off the cmd command line.
> "%JAVAC_ARGS%" echo -proc:none
>>"%JAVAC_ARGS%" echo -encoding UTF-8
>>"%JAVAC_ARGS%" echo -d "!OUT_ARG!"
>>"%JAVAC_ARGS%" echo -cp "!CP!"
for %%F in (src\main\java\com\fst\naturaldisaster\field\*.java tools\analyzer\*.java) do (
  set "SRC=%%F"
  set "SRC=!SRC:\=/!"
  >>"%JAVAC_ARGS%" echo "!SRC!"
)

echo Compiling field sources and analyzer...
javac @"%JAVAC_ARGS%"
if errorlevel 1 (
  echo [error] Compilation failed.
  pause
  exit /b 1
)

rem java argfile: same classpath, plus the analyzer server options.
> "%JAVA_ARGS%" echo -Dstdout.encoding=UTF-8
>>"%JAVA_ARGS%" echo -Dstderr.encoding=UTF-8
>>"%JAVA_ARGS%" echo -cp "!OUT_ARG!;!CP!"
>>"%JAVA_ARGS%" echo AnalyzerServer
>>"%JAVA_ARGS%" echo %PORT%
>>"%JAVA_ARGS%" echo tools/analyzer/index.html
if /i "%~1"=="--no-open" >>"%JAVA_ARGS%" echo --no-open

echo Starting local analyzer: http://127.0.0.1:%PORT%/
echo Close this window or press Ctrl+C to stop the server.
java @"%JAVA_ARGS%"
if errorlevel 1 pause
endlocal
