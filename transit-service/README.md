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
location details and takes the current delay stage from the
`package-status-topic` subscription (see MQ above), then widens the base
24-48h window by 6 hours per delay stage. `404` for an unknown hub, `502`
when hub-service cannot be reached. A hub the topic has not carried an
update for yet is treated as stage 0 (no delay).

The subscription replaces the direct `GET :7052/delay-stage/{hubId}` call
transit-service used to make per request.

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
    │   ├── DelayStageCache.java
    │   ├── PackageStatusSubscriber.java
    │   ├── EtaCalculator.java
    │   └── mq/
    │       └── MqConfig.java
    └── test/java/co/wethinkcode/logisticsconnect/
        ├── EtaCalculatorTest.java
        ├── DelayStageCacheTest.java
        └── PackageStatusSubscriberTest.java
```

## Build

```
mvn package
```

## Run

```
java -jar target/transit-service.jar
```

Listens on port `7053`. hub-service and the ActiveMQ broker (`../common`) need
to be running for `/eta` to answer.

## Test

Unit tests cover the ETA window and delay stage cache/subscription handling:

```
mvn test
```

Manually verify it's up:

```
curl http://localhost:7053/health   # -> OK
curl http://localhost:7053/eta/H-501
```
