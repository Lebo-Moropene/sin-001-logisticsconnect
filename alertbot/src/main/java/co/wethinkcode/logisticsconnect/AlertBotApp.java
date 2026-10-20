package co.wethinkcode.logisticsconnect;

import io.javalin.Javalin;

public class AlertBotApp {

    private static final DelayStageCache DELAY_STAGE_CACHE =
            new DelayStageCache();

    private static final AlertPolicy ALERT_POLICY =
            new AlertPolicy();

    private static final SocialAlertFeed ALERT_FEED =
            new SocialAlertFeed();

    public static void main(String[] args) throws Exception {
        PackageStatusSubscriber subscriber =
                new PackageStatusSubscriber(
                        DELAY_STAGE_CACHE, ALERT_POLICY, ALERT_FEED);

        // The broker is a hard dependency: without it AlertBot has
        // nothing to react to.
        subscriber.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                subscriber.close();
            } catch (Exception e) {
                System.err.println(
                        "Error closing ActiveMQ resources: "
                                + e.getMessage());
            }
        }));

        Javalin app = Javalin.create().start(7054);

        app.get("/health", ctx -> ctx.result("OK"));

        // Everything the simulated account has posted so far.
        app.get("/alerts", ctx ->
                ctx.json(ALERT_FEED.recent()));

        System.out.println(
                "AlertBot running on port 7054, posting as "
                        + SocialAlertFeed.ACCOUNT);
    }
}
