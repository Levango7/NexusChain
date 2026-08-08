@echo off
set JAVA_HOME=F:\Program Files (x86)\Amazon Corretto\jdk17.0.20_8
set PATH=%JAVA_HOME%\bin;%PATH%
cd /d F:\Nexus\NexusChain
call gradlew.bat test --continue -x jacocoTestReport --no-daemon --console=plain > F:\Nexus\NexusChain\tmp\test-bat.log 2>&1
echo EXITCODE=%errorlevel% >> F:\Nexus\NexusChain\tmp\test-bat.log