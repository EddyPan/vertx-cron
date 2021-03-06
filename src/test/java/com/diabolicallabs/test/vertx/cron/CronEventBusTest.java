package com.diabolicallabs.test.vertx.cron;

import com.diabolicallabs.vertx.cron.CronEventSchedulerVertical;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.RunTestOnContext;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@RunWith(io.vertx.ext.unit.junit.VertxUnitRunner.class)
public class CronEventBusTest {

  private static final String BASE_ADDRESS = "cron.schedule";

  private Supplier<Vertx> supplier = () -> {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Vertx> vertx = new AtomicReference<>();
    Vertx.clusteredVertx(new VertxOptions(), handler -> {
      if (handler.succeeded()) {
        vertx.set(handler.result());
        latch.countDown();
      } else {
        throw new RuntimeException("Unable to create clustered Vertx");
      }
    });
    try {
      latch.await();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
    return vertx.get();
  };

  @Rule
  public RunTestOnContext rule = new RunTestOnContext(supplier);

  @Before
  public void before(TestContext context) {

    rule.vertx().deployVerticle(CronEventSchedulerVertical.class.getName(), context.asyncAssertSuccess(id -> {
      System.out.println("CronEventSchedulerVertical deployment id: " + id);
    }));
  }

  @Test
  public void testSend(TestContext context) {

    Async async = context.async();

    String address = UUID.randomUUID().toString();
    JsonObject event = event().put("address", address);
    AtomicBoolean gotit = new AtomicBoolean(false);

    rule.vertx().eventBus().consumer(address, handler -> {
      gotit.set(true);
    });

    rule.vertx().eventBus().request(BASE_ADDRESS, event, handler -> {
      if (handler.failed()) context.fail(handler.cause());
    });

    rule.vertx().setTimer(1000 * 2, timerHandler -> {
      context.assertTrue(gotit.get());
      async.complete();
    });
  }

  @Test
  public void testSendNoMessage(TestContext context) {

    Async async = context.async();

    String address = UUID.randomUUID().toString();
    JsonObject event = event().put("address", address);
    event.remove("message");
    AtomicBoolean gotit = new AtomicBoolean(false);

    rule.vertx().eventBus().consumer(address, handler -> {
      gotit.set(true);
    });

    rule.vertx().eventBus().request(BASE_ADDRESS, event, handler -> {
      if (handler.failed()) context.fail(handler.cause());
    });

    rule.vertx().setTimer(1000 * 2, timerHandler -> {
      context.assertTrue(gotit.get());
      async.complete();
    });
  }

  final long threeMinutes = 1000 * 60 * 3;
  @Test(timeout = threeMinutes)
  public void testDuplicate30SecondlySendNoMessage(TestContext context) {

    Async async = context.async();

    String address = UUID.randomUUID().toString();
    int testIntervals = 5;

    JsonObject event = event().put("address", address);
    event.remove("message");
    event.put("cron_expression", "*/30 * * * * ?");

    AtomicInteger count = new AtomicInteger(0);
    AtomicBoolean timerStarted = new AtomicBoolean(false);

    rule.vertx().eventBus().consumer(address, handler -> {
      //Start timer when we get pinged the first time
      if (!timerStarted.get()) {
        //wait for testIntervals 30 second intervals plus 200 ms to make sure last is received
        System.out.println("Starting the 30 timer for " + testIntervals + " intervals");
        rule.vertx().setTimer((1000 * 30 * testIntervals) + 200, timerHandler -> {
          System.out.println("Finished waiting for " + testIntervals + " pings from 30 second interval");
          context.assertEquals(testIntervals, count.get());
          async.complete();
        });
        timerStarted.set(true);
      } else {
        System.out.println("30 second ping at " + new Date());
        count.incrementAndGet();
      }
      handler.reply(null);
    });

    DeliveryOptions options = new DeliveryOptions().setSendTimeout(threeMinutes);
    rule.vertx().eventBus().request(BASE_ADDRESS, event, options, handler -> {
      if (handler.failed()) context.fail(handler.cause());
    });

  }

  @Test
  public void testPublish(TestContext context) {

    Async async = context.async();

    String address = UUID.randomUUID().toString();
    JsonObject event = event().put("address", address).put("action", "publish");

    AtomicBoolean got1 = new AtomicBoolean(false);
    AtomicBoolean got2 = new AtomicBoolean(false);

    rule.vertx().eventBus().consumer(address, handler -> {
      got1.set(true);
    });
    rule.vertx().eventBus().consumer(address, handler -> {
      got2.set(true);
    });

    rule.vertx().eventBus().request(BASE_ADDRESS, event, handler -> {
      if (handler.failed()) context.fail(handler.cause());
    });

    rule.vertx().setTimer(1000 * 2, timerHandler -> {
      context.assertTrue(got1.get());
      context.assertTrue(got2.get());
      async.complete();
    });
  }

  @Test
  public void testSendWithReply(TestContext context) {

    Async async = context.async();

    String sendAddress = UUID.randomUUID().toString();
    String replyAddress = UUID.randomUUID().toString();

    JsonObject event = event().put("address", sendAddress).put("result_address", replyAddress);

    rule.vertx().eventBus().consumer(sendAddress, handler -> {
      handler.reply("Squid");
    });

    rule.vertx().eventBus().consumer(replyAddress, handler -> {
      context.assertEquals("Squid", handler.body());
      async.complete();
    });

    rule.vertx().eventBus().request(BASE_ADDRESS, event, handler -> {
      if (handler.failed()) context.fail(handler.cause());
    });
  }

  @Test
  public void testSendWithReplyNoMessage(TestContext context) {

    Async async = context.async();

    String sendAddress = UUID.randomUUID().toString();
    String replyAddress = UUID.randomUUID().toString();

    JsonObject event = event().put("address", sendAddress).put("result_address", replyAddress);
    event.remove("message");

    rule.vertx().eventBus().consumer(sendAddress, handler -> {
      handler.reply("Squid");
    });

    rule.vertx().eventBus().consumer(replyAddress, handler -> {
      context.assertEquals("Squid", handler.body());
      async.complete();
    });

    rule.vertx().eventBus().request(BASE_ADDRESS, event, handler -> {
      if (handler.failed()) context.fail(handler.cause());
    });
  }

  //@Test
  public void testCancel(TestContext context) {

    Async async = context.async();

    String sendAddress = UUID.randomUUID().toString();
    AtomicReference<String> id = new AtomicReference<>("");
    AtomicInteger hits = new AtomicInteger(0);

    JsonObject event = event().put("address", sendAddress);
    event.remove("message");

    rule.vertx().eventBus().consumer(sendAddress, handler -> {
      System.out.println("Got a hit");
      hits.incrementAndGet();
      rule.vertx().eventBus().publish("cron.cancel", id.get());
      System.out.println("Publishing cancel");
      //wait a couple of secs to clear the hits
      rule.vertx().setTimer(4000, firstHandler -> {
        System.out.println("Setting hits to zero");
        hits.set(0);
        //make sure it's still zero
        rule.vertx().setTimer(2000, secondHandler -> {
          System.out.println("Hits: " + hits.get());
          context.assertEquals(0, hits.get());
          async.complete();
        });
      });
    });

    rule.vertx().eventBus().request(BASE_ADDRESS, event, handler -> {
      id.set((String) handler.result().body());
      System.out.println("Id: " + id.get());
      if (handler.failed()) context.fail(handler.cause());
    });
  }

  //@Test
  public void testDoubleCancel(TestContext context) {

    Async async = context.async();

    String sendAddress = UUID.randomUUID().toString();
    AtomicReference<String> id = new AtomicReference<>("");
    AtomicInteger hits = new AtomicInteger(0);

    JsonObject event = event().put("address", sendAddress);
    event.remove("message");

    rule.vertx().eventBus().consumer(sendAddress, handler -> {
      System.out.println("Got a hit");
      hits.incrementAndGet();
      rule.vertx().eventBus().publish("cron.cancel", id.get());
      rule.vertx().eventBus().publish("cron.cancel", id.get());
      System.out.println("Publishing cancel");
      //wait a couple of secs to clear the hits
      rule.vertx().setTimer(4000, firstHandler -> {
        System.out.println("Setting hits to zero");
        hits.set(0);
        //make sure it's still zero
        rule.vertx().setTimer(2000, secondHandler -> {
          System.out.println("Hits: " + hits.get());
          context.assertEquals(0, hits.get());
          async.complete();
        });
      });
    });

    rule.vertx().eventBus().request(BASE_ADDRESS, event, handler -> {
      id.set((String) handler.result().body());
      System.out.println("Id: " + id.get());
      if (handler.failed()) context.fail(handler.cause());
    });
  }

  @Test
  public void testSendWithTimeZone(TestContext context) {

    Async async = context.async();

    String address = UUID.randomUUID().toString();
    JsonObject event = event().put("address", address).put("timezone_name", "Pacific/Fiji");
    AtomicBoolean gotit = new AtomicBoolean(false);

    rule.vertx().eventBus().consumer(address, handler -> {
      gotit.set(true);
    });

    rule.vertx().eventBus().request(BASE_ADDRESS, event, handler -> {
      if (handler.failed()) context.fail(handler.cause());
    });

    rule.vertx().setTimer(1000 * 2, timerHandler -> {
      context.assertTrue(gotit.get());
      async.complete();
    });
  }

  @Test
  public void testMissingCronspec(TestContext context) {

    Async async = context.async();

    JsonObject event = event();
    event.remove("cron_expression");

    rule.vertx().eventBus().request(BASE_ADDRESS, event, handler -> {
      if (handler.succeeded()) context.fail("Should have failed due to bad cronspec");
      if (handler.failed()) {
        context.assertEquals("Message must contain cron_expression", handler.cause().getMessage());
      }
      async.complete();
    });
  }

  @Test
  public void testMissingAddress(TestContext context) {

    Async async = context.async();

    JsonObject event = event();
    event.remove("address");

    rule.vertx().eventBus().request(BASE_ADDRESS, event, handler -> {
      if (handler.succeeded()) context.fail("Should have failed due to bad cronspec");
      if (handler.failed()) {
        context.assertEquals("Message must contain the address to schedule", handler.cause().getMessage());
      }
      async.complete();
    });
  }

  @Test
  public void testBadTimeZone(TestContext context) {

    Async async = context.async();

    JsonObject event = event().put("timezone_name", "USSR/Honolulu");

    rule.vertx().eventBus().request(BASE_ADDRESS, event, handler -> {
      if (handler.succeeded()) context.fail("Should have failed due to bad timezone");
      if (handler.failed()) {
        context.assertEquals("timezone_name USSR/Honolulu is invalid", handler.cause().getMessage());
      }
      async.complete();
    });
  }

  @Test
  public void testBadCronspec(TestContext context) {

    Async async = context.async();

    JsonObject event = event().put("cron_expression", "SQUID");
    rule.vertx().eventBus().request(BASE_ADDRESS, event, handler -> {
      if (handler.succeeded()) context.fail("Should have failed due to bad cronspec");
      if (handler.failed()) {
        context.assertEquals("Invalid cronspec SQUID", handler.cause().getMessage());
      }
      async.complete();
    });
  }

  @Test
  public void testBadAction(TestContext context) {

    Async async = context.async();

    JsonObject event = event().put("action", "SQUID");
    rule.vertx().eventBus().request(BASE_ADDRESS, event, handler -> {
      if (handler.succeeded()) context.fail("Should have failed due to bad action");
      if (handler.failed()) {
        context.assertEquals("action must be 'send' or 'publish'", handler.cause().getMessage());
      }
      async.complete();
    });
  }

  private JsonObject event() {

    JsonObject event = new JsonObject()
      .put("cron_expression", "*/1 * * * * ?")
      .put("address", "scheduled.address")
      .put("message", "squid")
      .put("action", "send")
      .put("result_address", "result.address");

    return event;
  }
}
