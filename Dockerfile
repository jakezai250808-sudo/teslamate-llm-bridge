FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace
COPY bridge/pom.xml bridge/pom.xml
COPY plays plays
COPY bridge/src bridge/src
RUN apt-get update && apt-get install -y --no-install-recommends maven && \
    mvn -f bridge/pom.xml -DskipTests package -q && \
    mv bridge/target/teslamate-llm-bridge-*.jar /workspace/app.jar

FROM eclipse-temurin:21-jre-jammy
# CJK fonts required by Batik SVG -> PNG card rendering
RUN apt-get update && apt-get install -y --no-install-recommends fonts-noto-cjk && \
    apt-get clean && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
EXPOSE 8770
ENTRYPOINT ["java", "-jar", "app.jar"]
