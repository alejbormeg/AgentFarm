FROM ubuntu:24.04
RUN apt-get update -qq && apt-get install -y -qq --no-install-recommends ca-certificates curl git gnupg jq openssl python3 tar unzip xz-utils zip >/dev/null
RUN install -m 0755 -d /etc/apt/keyrings \
 && curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc \
 && echo "deb [signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu noble stable" > /etc/apt/sources.list.d/docker.list \
 && curl -fsSL https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb -o /tmp/chrome.deb \
 && apt-get update -qq && apt-get install -y -qq --no-install-recommends docker-ce-cli docker-compose-plugin docker-buildx-plugin /tmp/chrome.deb >/dev/null \
 && rm /tmp/chrome.deb
RUN useradd --create-home --uid 1001 runner
WORKDIR /home/runner/actions-runner
RUN curl -fsSL -o r.tgz https://github.com/actions/runner/releases/download/v2.338.2/actions-runner-linux-x64-2.338.2.tar.gz \
 && echo "baebf204307bac6193480852732f0aba2ae97b6c5597c244f4d5558390147c40  r.tgz" | sha256sum -c - \
 && tar xzf r.tgz && rm r.tgz && ./bin/installdependencies.sh >/dev/null && chown -R runner: /home/runner
COPY entry.sh /entry.sh
USER runner
ENTRYPOINT ["bash", "/entry.sh"]
