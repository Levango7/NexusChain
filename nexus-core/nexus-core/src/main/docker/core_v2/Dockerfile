FROM conpay/server_jre8_with_dependencies
RUN apt update
RUN apt install -y netcat
COPY build/conpay*.jar /app/app.jar
COPY run_cpay_core.sh /run_cpay_core.sh
COPY version.json /version.json
ENV CLASSPATH .:$JAVA_HOME/lib
ENTRYPOINT ["usr/bin/env", "bash", "/run_cpay_core.sh" ]
