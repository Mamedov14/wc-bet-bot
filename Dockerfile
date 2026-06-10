FROM gradle:8.7-jdk17 AS build
WORKDIR /app
COPY settings.gradle.kts build.gradle.kts ./
COPY src ./src
RUN gradle distTar --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/distributions/application.tar ./
RUN tar -xf application.tar --strip-components=1 && rm application.tar
ENV JAVA_OPTS="-Xmx256m"
ENTRYPOINT ["./bin/wc-bet-bot"]
