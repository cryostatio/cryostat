# Cryostat-Web

![Build Status](https://github.com/cryostatio/cryostat-web/actions/workflows/ci.yaml/badge.svg)
[![Google Group : Cryostat Development](https://img.shields.io/badge/Google%20Group-Cryostat%20Development-blue.svg)](https://groups.google.com/g/cryostat-development)

Web front-end for [Cryostat](https://github.com/cryostatio/cryostat), providing a graphical user interface for managing JFR on remote JVMs.

Based on [Patternfly React Seed](https://github.com/patternfly/patternfly-react-seed).

## SEE ALSO

* [cryostat-core](https://github.com/cryostatio/cryostat-core) for
the core library providing a convenience wrapper and headless stubs for use of
JFR using JDK Mission Control internals.

* [cryostat-operator](https://github.com/cryostatio/cryostat-operator)
for an OpenShift Operator facilitating easy setup of Cryostat in your OpenShift
cluster as well as exposing the Cryostat API as Kubernetes Custom Resources.

* [cryostat](https://github.com/cryostatio/cryostat-web) for the JFR management service


## REQUIREMENTS
Build:
- NPM
- Yarn

## BUILD

### Setup dependencies

```
npm install --save-dev yarn # or install using your package manager
npm run yarn:frzinstall # or just yarn install, if installed globally
```

### Run a production build

```
npm run build
```


## DEVELOPMENT SERVER

To run a hot-reloading instance of cryostat-web, first run a `cryostat` instance with WebSocket communication and CORS enabled. Use `CRYOSTAT_CORS_ORIGIN` to target `http://localhost:9000`

For example:
`cd /path/to/cryostat && CRYOSTAT_DISABLE_SSL=true CRYOSTAT_CORS_ORIGIN=http://localhost:9000 sh run.sh`

Then run `npm run start:dev`. This will target the `cryostat` instance started above by default. This can be customized by editing the `.env` file, for example if you have another service already listening on the default port `8181` and your Cryostat is listening elsewhere.

## TEST


### Run the test suite
```
npm run test
```

### Run the linter
```
npm run lint
```

### Run the code formatter
```
npm run format
```

### Inspect the bundle size
```
npm run bundle-profile:analyze
```

### Start the express server (run a production build first)
```
npm run start
```

