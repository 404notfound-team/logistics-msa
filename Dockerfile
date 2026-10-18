FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace
COPY . .
RUN chmod +x gradlew && ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre-jammy AS runtime-base
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system app && useradd --system --gid app app
WORKDIR /app
USER app
ENV JAVA_TOOL_OPTIONS="-Xms64m -Xmx256m -Duser.timezone=Asia/Seoul"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM runtime-base AS ci-extract
USER root
ARG MODULE
COPY --from=jars /${MODULE}/app.jar /app/app.jar
RUN java -Djarmode=tools -jar /app/app.jar extract --destination /app/extracted

FROM runtime-base AS ci
COPY --from=ci-extract --chown=app:app /app/extracted/ /app/

FROM runtime-base AS runtime-extract
USER root
ARG MODULE
COPY --from=build /workspace/${MODULE}/build/libs/app.jar /app/app.jar
RUN java -Djarmode=tools -jar /app/app.jar extract --destination /app/extracted

FROM runtime-base AS runtime
COPY --from=runtime-extract --chown=app:app /app/extracted/ /app/
