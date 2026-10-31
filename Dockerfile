FROM maven:3.9.9-eclipse-temurin-17

WORKDIR /app
COPY backend/pom.xml backend/pom.xml
COPY backend/src backend/src
COPY frontend frontend

WORKDIR /app/backend
RUN mvn -B -DskipTests compile

EXPOSE 8080
CMD ["mvn", "-B", "exec:java"]
