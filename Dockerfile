FROM gradle:8.10.2-jdk17

ARG ANDROID_CMDLINE_TOOLS_REVISION=13114758

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    GRADLE_USER_HOME=/home/gradle/.gradle \
    PATH=/opt/android-sdk/cmdline-tools/latest/bin:${PATH}

USER root

RUN apt-get update \
    && apt-get install --yes --no-install-recommends ca-certificates curl unzip \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p "${ANDROID_HOME}/cmdline-tools" \
    && curl --fail --location --silent --show-error \
        "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS_REVISION}_latest.zip" \
        --output /tmp/android-command-line-tools.zip \
    && unzip -q /tmp/android-command-line-tools.zip -d /tmp/android-command-line-tools \
    && mv /tmp/android-command-line-tools/cmdline-tools "${ANDROID_HOME}/cmdline-tools/latest" \
    && rm -rf /tmp/android-command-line-tools /tmp/android-command-line-tools.zip

RUN yes | sdkmanager --sdk_root="${ANDROID_HOME}" --licenses >/dev/null \
    && sdkmanager --sdk_root="${ANDROID_HOME}" --install \
        "platforms;android-36" \
        "build-tools;34.0.0" \
        "build-tools;36.0.0" \
    && chmod -R a+rX "${ANDROID_HOME}" \
    && mkdir -p "${GRADLE_USER_HOME}" \
    && chmod 0777 "${GRADLE_USER_HOME}"

USER gradle
WORKDIR /workspace

ENTRYPOINT ["gradle", "--no-daemon", "--console=plain"]
CMD [":app:assembleDebug"]
