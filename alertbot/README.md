# AlertBotApp

## Overview

Posts proactive delay notifications to public transit social media pages (simulated).

Part of the [LogisticsConnect](../README.md) project — its alerting service.
Independent Maven module, no parent pom.

Mechanism: Outbound webhook, simulated social post

MQ (stretch goal): this service subscribes to the ActiveMQ topic
`package-status-topic` — see [`../common/`](../common). Broker URL and topic name
come from the common `co.wethinkcode.logisticsconnect.mq.MqConfig` class alongside
it in this module. Use the stage in each message to decide when to raise an alert
(e.g. above a threshold you choose).

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/health` | Smoke check |
| GET | `/alerts` | Every alert the account has posted so far |

## Alerting behaviour

AlertBot subscribes to `package-status-topic` and posts a **simulated** social
message as `@LogisticsConnectAlerts` whenever a hub's delay stage *crosses* the
alert threshold (`AlertPolicy.DEFAULT_THRESHOLD` = stage 5).

- Crossing = the new stage is >= the threshold and the hub's previous stage was
  below it (or the hub had no recorded stage yet).
- A hub that is already above the threshold does **not** re-alert on every
  update — no spam.
- If a hub drops below the threshold and later crosses it again, that is a new
  alert.

The "social post" is simulated: it is logged and recorded in an in-memory feed
served by `GET /alerts` — no real social media API is called.

```json
[
  {
    "platform": "simulated-social",
    "account": "@LogisticsConnectAlerts",
    "hubId": "H-501",
    "stage": 5,
    "message": "Delay stage 5 at hub H-501 — deliveries through this hub are expected to be delayed. Check before you ship.",
    "postedAt": "2026-10-19T11:20:00Z"
  }
]
```

## Project structure

```
alertbot/
├── pom.xml
└── src/
    ├── main/java/co/wethinkcode/logisticsconnect/
    │   ├── AlertBotApp.java
    │   ├── AlertPolicy.java
    │   ├── DelayStageCache.java
    │   ├── PackageStatusSubscriber.java
    │   ├── SocialAlertFeed.java
    │   └── mq/
    │       └── MqConfig.java
    └── test/java/co/wethinkcode/logisticsconnect/
        ├── AlertPolicyTest.java
        └── PackageStatusSubscriberTest.java
```

## Build

```
mvn package
```

## Run

```
java -jar target/alertbot.jar
```

Listens on port `7054`. The ActiveMQ broker (`../common`) needs to be running.

## Test

Unit tests cover the alert threshold policy and message handling:

```
mvn test
```

Manually verify it's up:

```
curl http://localhost:7054/health     # -> OK
curl http://localhost:7054/alerts     # -> [] until a threshold is crossed
```
