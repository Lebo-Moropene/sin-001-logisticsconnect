# TransitServiceApp

## Overview

Calculates estimated arrival windows based on hub and delay stage.

Part of the [LogisticsConnect](../README.md) project. Independent Maven module, no
parent pom.

MQ: this service subscribes to the ActiveMQ topic `package-status-topic` — see [`../common/`](../common). Broker URL and topic name come from the common `co.wethinkcode.logisticsconnect.mq.MqConfig` class alongside it in this module.

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/health` | Smoke check |
| GET | `/eta/{hubId}` | Estimated arrival window for one hub |

`GET /eta/{hubId}` calls hub-service (`GET :7051/hubs/{hubId}`) for the hub's
location details and delay-stage-service (`GET :7052/delay-stage/{hubId}`) for
its current stage, then widens the base 24-48h window by 6 hours per delay
stage. `404` for an unknown hub, `502` when an upstream service cannot be
reached.

```json
{
  "hubId": "H-501",
  "province": "Western Cape",
  "sortingCenter": "Cape Town Port",
  "active": true,
  "delayStage": 3,
  "eta": {
    "earliestHours": 42,
    "latestHours": 66,
    "earliest": "2026-10-20T15:00:00Z",
    "latest": "2026-10-21T09:00:00Z",
    "delayed": true
  }
}
```

## Project structure

```
transit-service/
├── pom.xml
└── src/
    ├── main/java/co/wethinkcode/logisticsconnect/
    │   ├── TransitServiceApp.java
    │   ├── HubServiceClient.java
    │   ├── DelayStageClient.java
    │   ├── EtaCalculator.java
    │   └── mq/
    │       └── MqConfig.java
    └── test/java/co/wethinkcode/logisticsconnect/
        └── EtaCalculatorTest.java
```

## Build

```
mvn package
```

## Run

```
java -jar target/transit-service.jar
```

Listens on port `7053`. hub-service and delay-stage-service need to be running
for `/eta` to answer.

## Test

Unit tests cover the ETA window calculation:

```
mvn test
```

Manually verify it's up:

```
curl http://localhost:7053/health   # -> OK
curl http://localhost:7053/eta/H-501
```
