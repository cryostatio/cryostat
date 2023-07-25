# Cryostat3

This project uses Quarkus, the Supersonic Subatomic Java Framework.

If you want to learn more about Quarkus, please visit its website: https://quarkus.io/ .

## Container Engines

Development on this project is primarily done using `podman`, though things *should* generally work when using `docker`
as well. For ease and convenience it is suggested to use `podman` with the following configurations:

```bash
$ systemctl --user enable --now podman.socket
```

`~/.bashrc` (or equivalent shell configuration)
```bash
$ export DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock
$ export TESTCONTAINERS_RYUK_DISABLED=true
```

```bash
$ sudo dnf install podman-docker
```

## Prerequisites

```bash
$ git submodule init && git submodule update
$ cd src/main/webui
$ yarn install && yarn yarn:frzinstall
$ cd -
```

Installing quarkus:
```bash
$ curl -Ls https://sh.jbang.dev | bash -s - trust add https://repo1.maven.org/maven2/io/quarkus/quarkus-cli/
$ curl -Ls https://sh.jbang.dev | bash -s - app install --fresh --force quarkus@quarkusio
```

Installing docker-compose:
```bash
$ sudo dnf install docker-compose
```

Initial build:
```bash
$ sh db/build.sh
```

## Running the application in dev mode

You can run your application in dev mode that enables live coding using:
```bash
$ ./mvnw compile quarkus:dev
```

or

```bash
$ quarkus dev
```

> **_NOTE:_**  Quarkus now ships with a Dev UI, which is available in dev mode only at http://localhost:8181/q/dev/.

## Packaging and running the application

The application can be packaged using:
```bash
$ ./mvnw package
```

or

```bash
$ quarkus build
```

It produces the `quarkus-run.jar` file in the `target/quarkus-app/` directory.
Be aware that it’s not an _über-jar_ as the dependencies are copied into the `target/quarkus-app/lib/` directory.

The application is now runnable using `java -jar target/quarkus-app/quarkus-run.jar`.

If you want to build an _über-jar_, execute the following command:
```bash
$ ./mvnw package -Dquarkus.package.type=uber-jar
```

The application, packaged as an _über-jar_, is now runnable using `java -jar target/*-runner.jar`.

## Creating a native executable

You can create a native executable using: 
```bash
$ ./mvnw package -Pnative
```

or

```bash
$ quarkus build --native
```

Or, if you don't have GraalVM installed, you can run the native executable build in a container using: 
```bash
$ ./mvnw package -Pnative -Dquarkus.native.container-build=true
```

You can then execute your native executable with: `./target/cryostat3-3.0.0-SNAPSHOT-runner`

If you want to learn more about building native executables, please consult https://quarkus.io/guides/maven-tooling.

## Manual Smoketesting

The shortest feedback loop during active development is to use Quarkus dev mode, ie `quarkus dev` from earlier.
This will run Cryostat as a local JVM process hooked up to its frontend, and required companion services in containers.
Any changes made to the backend or frontend sources, `application.properties`, `pom.xml`, etc. will trigger
automatic rebuilds and live-coding.

The next testing step is to build and package Cryostat into a container and run it as a container.

```bash
$ quarkus build ; podman image prune -f
$ sh smoketest.sh
```

This will build the container image, then spin it up along with required services within a Podman pod.
Data is persisted between runs in Podman volumes.

To make containers' names DNS-resolvable from the host machine, do:
```bash
$ git clone https://github.com/figiel/hosts libuserhosts
$ cd libuserhosts
$ make
$ mkdir -p ~/bin
$ cp libuserhosts.so ~/bin
$ echo 'export LD_PRELOAD=$HOME/bin/libuserhosts.so' >> ~/.bashrc
$ export LD_PRELOAD=$HOME/bin/libuserhosts.so
```

You can verify that this setup works by running `smoketest.sh`, and then in another terminal:
```bash
$ LD_PRELOAD=$HOME/bin/libuserhosts.so ping cryostat
$ LD_PRELOAD=$HOME/bin/libuserhosts.so curl http://cryostat:8181
$ LD_PRELOAD=$HOME/bin/libuserhosts.so firefox http://cryostat:8181
```

## Smoketesting in K8s

The next testing step is to run this same container setup in k8s, which will require the installation of yq, Kompose and the multiforward plugin with Krew.

Installing yq:
```bash
$ curl -L https://github.com/mikefarah/yq/releases/latest/download/yq_linux_amd64 -o yq
$ chmod +x yq
$ sudo mv ./yq /usr/local/bin/yq
```

Installing Kompose:
```bash
$ curl -L https://github.com/kubernetes/kompose/releases/download/v1.30.0/kompose-linux-amd64 -o kompose
$ chmod +x kompose
$ sudo mv ./kompose /usr/local/bin/kompose
```

Installing Krew:
```bash
$ set -x; cd "$(mktemp -d)"
$ OS="$(uname | tr '[:upper:]' '[:lower:]')"
$ ARCH="$(uname -m | sed -e 's/x86_64/amd64/' -e 's/\(arm\)\(64\)\?.*/\1\2/' -e 's/aarch64$/arm64/')"
$ KREW="krew-${OS}_${ARCH}"
$ curl -fsSLO "https://github.com/kubernetes-sigs/krew/releases/latest/download/${KREW}.tar.gz"
$ tar zxvf "${KREW}.tar.gz"
$ ./"${KREW}" install krew
```

`~/.bashrc` (or equivalent shell configuration)
```bash
$ export PATH="${KREW_ROOT:-$HOME/.krew}/bin:$PATH"
```

Installing multiforward:
```bash
$ kubectl krew install multiforward
```

The steps for testing:

```bash
$ cd smoketest/k8s
$ sh smoketest.sh kind # if you use `kind` and want to spin up a cluster, otherwise skip this if you have another cluster accessible via `kubectl`
```
If you get an error during the 'ensuring node image' step while creating cluster "kind", manually pull the podman image by running the command `podman pull docker.io/kindest/node@IMAGE_DIGEST` where IMAGE_DIGEST is the sha256 of the image. Then rerun `sh smoketest.sh kind`.

```bash
$ sh smoketest.sh generate apply
$ sh smoketest.sh forward # if you need to use port-forwarding to get access to the cluster's services
```
