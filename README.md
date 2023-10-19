# Cryostat3

[![CI build and push](https://github.com/cryostatio/cryostat3/actions/workflows/ci.yaml/badge.svg)](https://github.com/cryostatio/cryostat3/actions/workflows/ci.yaml)
[![Google Group : Cryostat Development](https://img.shields.io/badge/Google%20Group-Cryostat%20Development-blue.svg)](https://groups.google.com/g/cryostat-development)

This project uses Quarkus, the Supersonic Subatomic Java Framework.

If you want to learn more about Quarkus, please visit its website: https://quarkus.io/ .

## PREVIEW

This repository is in an active development, early preview state, and will eventually be used for `3.x` release
versions of Cryostat. See [cryostatio/cryostat](https://github.com/cryostatio/cryostat) for the old repository
containing the `< 3.0` codebase.

## CONTRIBUTING

We welcome and appreciate any contributions from our community. Please visit our guide on how you can take part in improving Cryostat3.

[See contribution guide →](./CONTRIBUTING.md)

## REQUIREMENTS

Build Requirements:
- Git
- JDK v17+
- Maven v3+
- [Quarkus CLI](https://quarkus.io/guides/cli-tooling) v3.4.1+ (Recommended)
- [Podman](https://podman.io/docs/installation) 4.7+

Run Requirements:
- [`yq`](https://github.com/mikefarah/yq) v4.35.1+
- [`kompose`](https://kompose.io/installation/) v1.30.0+
- [docker-compose](https://docs.docker.com/compose/install/) v1.29.2
- [podman-docker](https://packages.fedoraproject.org/pkgs/podman/podman-docker/) (Optional)
- [kubectl](https://kubernetes.io/docs/tasks/tools/install-kubectl-linux/) / [oc](https://docs.openshift.com/container-platform/latest/cli_reference/openshift_cli/getting-started-cli.html)
- [kubectl multi-forward](https://github.com/njnygaard/kubectl-multiforward) (requires [Krew](https://krew.sigs.k8s.io/docs/user-guide/setup/install/)) (Optional)
- [kind](https://kind.sigs.k8s.io/docs/user/quick-start) v0.20.0+ (Optional)


## BUILD

### Setup Dependencies

Initialize submodules:

```bash
$ git submodule init && git submodule update
$ cd src/main/webui
$ yarn install && yarn yarn:frzinstall
$ cd -
```

### Build the custom database container image

```bash
$ sh db/build.sh
```

### Build the application container image

The application image can be created using:

```bash
# With Maven
$ ./mvnw package
# Or with Quarkus CLI
$ quarkus build
```

### Build and run the application in dev mode

You can run your application in dev mode that enables live coding using:

```bash
# With Maven
$ ./mvnw compile quarkus:dev
# Or with Quarkus CLI
$ quarkus dev
```

This will run Cryostat as a local JVM process hooked up to its frontend, and required companion services in containers. Any changes made to the backend or frontend sources, `application.properties`, `pom.xml`, etc. will trigger automatic rebuilds and live-coding.

> **_NOTE:_**  Quarkus now ships with a Dev UI, which is available in dev mode only at http://localhost:8181/q/dev/.

## RUN

### Local Smoketesting

Development on this project is primarily done using `podman`, though things should generally work when using `docker` as well. For ease and convenience, it is suggested to use `podman` with the following configurations:

```bash
$ systemctl --user enable --now podman.socket
```

`$HOME/.bashrc` (or equivalent shell configuration)
```bash
export DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock
export TESTCONTAINERS_RYUK_DISABLED=true
```

Build the container image and run smoketests. This will spin up the cryostat container and its required services.

```bash
# build Cryostat container, clean up any dangling references
$ quarkus build ; podman image prune -f
# run a smoketest scenario
$ bash smoketest.bash
```

To make containers' names DNS-resolvable from the host machine, do:
```bash
$ git clone https://github.com/figiel/hosts libuserhosts
$ cd libuserhosts
$ make PREFIX=$HOME/bin all install
$ echo 'export LD_PRELOAD=$HOME/bin/lib/libuserhosts.so' >> ~/.bashrc
$ export LD_PRELOAD=$HOME/bin/lib/libuserhosts.so
```
(this will require a C compiler toolchain present on your development machine)

You can verify that this setup works by running `smoketest.bash`, and then in another terminal:
```bash
$ LD_PRELOAD=$HOME/bin/libuserhosts.so ping cryostat3
$ LD_PRELOAD=$HOME/bin/libuserhosts.so curl http://cryostat3:8181
$ LD_PRELOAD=$HOME/bin/libuserhosts.so firefox http://cryostat3:8181
```

### Smoketesting in K8s

To run similar smoketest scenarios in a Kubernetes/OpenShift cluster, do:

```bash
$ cd smoketest/k8s
$ sh smoketest.sh kind # This launches a kind k8s cluster, otherwise skip this if you have another cluster accessible via kubectl/oc.
```

If you get an error during the 'ensuring node image' step while creating cluster "kind", manually pull the podman image by running the command `podman pull docker.io/kindest/node@IMAGE_DIGEST` where IMAGE_DIGEST is the sha256 of the image. Then rerun `sh smoketest.sh kind`.

Generate k8s yaml configurations and apply them to create k8s objects. You can optionally port-forward to the cluster's services to access the Cryostat application from `localhost`.

```bash
$ sh smoketest.sh generate apply
$ sh smoketest.sh forward # if you need to use port-forwarding to get access to the cluster's services
```
