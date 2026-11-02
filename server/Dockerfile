# Build:  docker build -f server/Dockerfile -t flos-server .   (run from the repository root)
FROM gradle:8.14.3-jdk17 AS build
WORKDIR /src
COPY . .
# The Android module is not needed for the server image.
RUN sed -i 's/^include(":app")$//' settings.gradle.kts && \
    sed -i '/android.application\|kotlin.android\|kotlin.compose\|libs.plugins.ksp/d' build.gradle.kts && \
    gradle :server:installDist --no-daemon -q

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/server/build/install/server /app
ENV PORT=8080
EXPOSE 8080
CMD ["/app/bin/server"]
