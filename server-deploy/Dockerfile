# Build ONLY the context emitted by deploy/prepare-context.mjs, not the workspace.
# Official image digests and their primary sources are recorded in deploy/README.md.
# This deployment target is linux/amd64. No Android SDK, licence acceptance, npm
# installation or unspecified prebuilt FeedMe builder is needed for these tasks.
FROM --platform=linux/amd64 node:22.23.2-bookworm-slim@sha256:83f487e0a63425e5b4d146fb5e5be574bcbe1b7b843d3ebafdd95eaf7767a7e5 AS node

FROM --platform=linux/amd64 eclipse-temurin:17.0.20_8-jdk-jammy@sha256:e573c097106f35634857604fdfbe70a2a2bbcaa52574bca3d2025703d0df994d AS build
USER 0:0
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/LICENSE /usr/local/share/licenses/node/LICENSE
ENV GRADLE_USER_HOME=/tmp/feedme-container-gradle
WORKDIR /workspace/feedme
COPY feedme/ /workspace/feedme/
COPY outputs/ /workspace/outputs/
RUN test "$(javac -version 2>&1)" = 'javac 17.0.20' \
    && test "$(node --version)" = 'v22.23.2'
# Exact project tasks and dependencies. Do not replace shared code, disable checks,
# write locks, run Android packaging/signing, or invoke the local native transform.
RUN sh ./gradlew :verifyReleaseScope :server:installDist \
    --no-daemon --no-parallel --max-workers=2 \
    --no-build-cache --no-configuration-cache --console=plain \
    && test -x server/build/install/server/bin/server \
    && test -f server/build/install/server/lib/server.jar

FROM --platform=linux/amd64 eclipse-temurin:17.0.20_8-jre-jammy@sha256:24cd8eed18b5976441d27b45823490eb5e8efff4b3ecdc632e442717ea66f160 AS runtime
USER 0:0
WORKDIR /opt/feedme
COPY --from=build --chown=0:0 /workspace/feedme/server/build/install/server/ /opt/feedme/
COPY --chown=0:0 feedme/deploy/trust/prod-ca-2021.crt /opt/feedme/trust/prod-ca-2021.crt
RUN java -version 2>&1 | grep 'version "17\.' \
    && chmod -R a=rX /opt/feedme \
    && chmod 0555 /opt/feedme/bin/server \
    && chmod 0444 /opt/feedme/trust/prod-ca-2021.crt
USER 10001:10001
# Verify the real runtime identity can read the public CA but cannot alter or
# replace it. This is image-build evidence, not a database connectivity check.
RUN test "$(id -u)" = 10001 && test "$(id -g)" = 10001 \
    && test -r /opt/feedme/trust/prod-ca-2021.crt \
    && test ! -w /opt/feedme/trust/prod-ca-2021.crt \
    && test ! -w /opt/feedme/trust \
    && test ! -w /opt/feedme
STOPSIGNAL SIGTERM
ENTRYPOINT ["/opt/feedme/bin/server"]
