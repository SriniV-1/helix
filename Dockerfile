# Helix demo server — the real Java matching engine behind an HTTP/JSON API.
# Deploy to any container host that runs a persistent process (Fly.io, Render,
# Railway, Cloud Run). It listens on $PORT (default 8080).

FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN ./gradlew --no-daemon installDist

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/install/helix/lib ./lib
COPY --from=build /app/docs ./docs
ENV PORT=8080
EXPOSE 8080
CMD ["sh", "-c", "java -cp 'lib/*' dev.srini.helix.engine.DemoServer ${PORT}"]
