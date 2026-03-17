# ===============================
# 🏗️ STAGE 1: Build (Maven)
# ===============================
# Χρησιμοποιούμε εικόνα που έχει Maven ΚΑΙ Java 21
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# Αντιγράφουμε το pom.xml και τον κώδικα
COPY pom.xml .
COPY src ./src

# Χτίζουμε το JAR (παραλείπουμε τα tests για ταχύτητα στο build)
RUN mvn clean package -DskipTests

# ===============================
# 🚀 STAGE 2: Run (Java Runtime)
# ===============================
# Εδώ χρησιμοποιούμε την εικόνα που είχες κι εσύ (Runtime only)
FROM eclipse-temurin:21-jdk-jammy

LABEL authors="DaBram"

WORKDIR /app

# Μαγεία: Παίρνουμε το JAR από το Stage 1 και το μετονομάζουμε σε app.jar
# Έτσι δεν σε νοιάζει αν αλλάξει το version στο pom.xml (0.0.1 -> 0.0.2)
COPY --from=build /app/target/*.jar app.jar

# Τρέχουμε το app
ENTRYPOINT ["java", "-jar", "app.jar"]