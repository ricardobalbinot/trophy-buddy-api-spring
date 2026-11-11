FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY --chmod=0755 mvnw ./
COPY pom.xml ./
COPY config config
COPY src src
RUN ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /workspace/target/trophy-buddy-api-spring-0.0.1-SNAPSHOT.jar /app/trophy-buddy-api.jar
COPY data/public-games-pilot.json /app/data/public-games-pilot.json
ENV SERVER_ADDRESS=0.0.0.0
USER 10001:10001
CMD ["java", "-jar", "/app/trophy-buddy-api.jar"]
