# Stage 1: Build the application
FROM maven:3.8.5-openjdk-17-slim AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B --no-transfer-progress -Dmaven.wagon.http.retryHandler.count=5 -Dmaven.wagon.http.connectionTimeout=120000 -Dmaven.wagon.http.readTimeout=120000
COPY src ./src
RUN mvn clean package -DskipTests --no-transfer-progress -Dmaven.wagon.http.retryHandler.count=5 -Dmaven.wagon.http.connectionTimeout=120000 -Dmaven.wagon.http.readTimeout=120000

# Stage 2: Run the application
FROM eclipse-temurin:17-jdk
RUN apt-get update && apt-get install -y postgresql-client && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
