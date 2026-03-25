# ===============================
# 🏗️ STAGE 1: Build (Maven)
# ===============================
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# Αντιγράφουμε το pom.xml και τον κώδικα
COPY pom.xml .
COPY src ./src

# Χτίζουμε το JAR (παραλείπουμε τα tests για ταχύτητα)
RUN mvn clean package -DskipTests

# ===============================
# 🚀 STAGE 2: Run (Java Runtime + Terraform CLI)
# ===============================
FROM eclipse-temurin:21-jdk-jammy

LABEL authors="DaBram"

WORKDIR /app

# --- Εγκατάσταση Terraform CLI ---
# Απαραίτητα εργαλεία για την προσθήκη του HashiCorp Repo
RUN apt-get update && apt-get install -y curl gnupg software-properties-common

# Προσθήκη του GPG key και του επίσημου repository της HashiCorp
RUN curl -fsSL https://apt.releases.hashicorp.com/gpg | apt-key add - && \
    apt-add-repository "deb [arch=amd64] https://apt.releases.hashicorp.com $(lsb_release -cs) main"

# Εγκατάσταση της Terraform
RUN apt-get update && apt-get install -y terraform && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# --- Αντιγραφή του Artifact ---
# Παίρνουμε το JAR από το Stage 1
COPY --from=build /app/target/*.jar app.jar

# Τρέχουμε το app
ENTRYPOINT ["java", "-jar", "app.jar"]