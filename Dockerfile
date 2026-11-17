FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src src
RUN ./mvnw -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/mecanica-execution-service-0.0.1-SNAPSHOT.jar app.jar
USER 10001:10001
ENV SERVER_ADDRESS=0.0.0.0
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
