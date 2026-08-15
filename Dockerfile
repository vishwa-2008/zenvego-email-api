FROM maven:3.9.16-eclipse-temurin-25 AS build

WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:25-jre

WORKDIR /app
COPY --from=build /app/target/classes ./classes
COPY --from=build /app/target/lib ./lib

CMD ["sh", "-c", "java -cp 'classes:lib/*' com.emailbot.OTPServer"]