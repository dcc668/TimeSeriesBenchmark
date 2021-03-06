package io.dogy.dao.impl;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.QueryApi;
import com.influxdb.client.WriteApi;
import com.influxdb.client.domain.Bucket;
import com.influxdb.client.domain.BucketRetentionRules;
import com.influxdb.client.domain.Organization;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.influxdb.client.write.events.WriteErrorEvent;
import com.influxdb.client.write.events.WriteSuccessEvent;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import com.influxdb.query.dsl.Flux;
import com.influxdb.query.dsl.functions.restriction.Restrictions;
import io.dogy.config.Settings;
import io.dogy.dao.IUserNotifyDao;
import io.dogy.model.UserNotify;
import io.dogy.utility.Util;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class InfluxDbUserNotifyDao implements IUserNotifyDao {

    private final Settings setting = Settings.getInstance();
    private final InfluxDBClient influxDBClient;

    public InfluxDbUserNotifyDao() {
        this.influxDBClient = InfluxDBClientFactory.create(
                String.format("http://%s:%s", setting.INFLUXDB_IP, setting.INFLUXDB_PORT),
                setting.INFLUXDB_TOKEN.toCharArray(), setting.INFLUXDB_ORG, setting.INFLUXDB_BUCKET
        );
        this.influxDBClient.enableGzip();
        Runtime.getRuntime().addShutdownHook(new Thread(this.influxDBClient::close));
    }

    @Override
    public void flushDB() {
        String orgId = null;
        // Create bucket if not exists with retention policy
        Bucket bucket = influxDBClient.getBucketsApi().findBucketByName(setting.INFLUXDB_BUCKET);
        if (bucket != null) {
            orgId = bucket.getOrgID();
            influxDBClient.getBucketsApi().deleteBucket(bucket);
        } else {
            for (Organization organization : influxDBClient.getOrganizationsApi().findOrganizations()) {
                if (organization.getName().equals(setting.INFLUXDB_ORG)) {
                    orgId = organization.getId();
                    break;
                }
            }
            if (orgId == null) {
                throw new RuntimeException("Not found org: " + setting.INFLUXDB_ORG);
            }
        }

        BucketRetentionRules retention = new BucketRetentionRules();
        retention.setEverySeconds(setting.TTL_IN_SECONDS);

        influxDBClient.getBucketsApi().createBucket(setting.INFLUXDB_BUCKET, retention, orgId);
    }

    @Override
    public void insert(UserNotify userNotify) throws Exception {
        CompletableFuture<String> future = new CompletableFuture<>();
        try (WriteApi writeApi = influxDBClient.getWriteApi()) {
            writeApi.listenEvents(WriteSuccessEvent.class, event -> future.complete(event.getLineProtocol()));
            writeApi.listenEvents(WriteErrorEvent.class, event -> future.completeExceptionally(event.getThrowable()));

            Point point = Point.measurement(setting.INFLUXDB_MEASUREMENT)
                    .addTag("user_id", userNotify.getUserID())
                    .addTag("notify_id", userNotify.getNotifyID())
                    .addField("data", Util.OBJECT_MAPPER.writeValueAsString(userNotify.getData()))
                    .time(userNotify.getTimestamp(), WritePrecision.MS);

            writeApi.writePoint(point);
        }
        future.get();
    }

    @Override
    public CompletableFuture<Object> insertAsync(UserNotify userNotify) throws Exception {
        CompletableFuture<Object> future = new CompletableFuture<>();
        try (WriteApi writeApi = influxDBClient.getWriteApi()) {
            writeApi.listenEvents(WriteSuccessEvent.class, event -> future.complete(event.getLineProtocol()));
            writeApi.listenEvents(WriteErrorEvent.class, event -> future.completeExceptionally(event.getThrowable()));

            Point point = Point.measurement(setting.INFLUXDB_MEASUREMENT)
                    .addTag("user_id", userNotify.getUserID())
                    .addTag("notify_id", userNotify.getNotifyID())
                    .addField("data", Util.OBJECT_MAPPER.writeValueAsString(userNotify.getData()))
                    .time(userNotify.getTimestamp(), WritePrecision.MS);

            writeApi.writePoint(point);
        }
        return future;
    }

    @Override
    public List<UserNotify> fetchDesc(String userID, Long fromTime) throws Exception {
        QueryApi queryApi = influxDBClient.getQueryApi();

        if (fromTime == null) {
            fromTime = System.currentTimeMillis();
        }
        Flux flux = Flux.from(setting.INFLUXDB_BUCKET)
                .range(Instant.now().minusSeconds(setting.TTL_IN_SECONDS), Instant.ofEpochMilli(fromTime))
                .filter(Restrictions.and(
                        Restrictions.measurement().equal(setting.INFLUXDB_MEASUREMENT),
                        Restrictions.tag("user_id").equal(userID)
                ));

        List<UserNotify> results = new ArrayList<>();
        Map<String, UserNotify> mapResult = new HashMap<>();

        // Query data
        List<FluxTable> tables = queryApi.query(flux.toString());
        for (FluxTable fluxTable : tables) {
            List<FluxRecord> records = fluxTable.getRecords();
            for (FluxRecord fluxRecord : records) {
                String notifyId = fluxRecord.getValueByKey("notify_id").toString();

                UserNotify userNotify = mapResult.get(notifyId);
                if (userNotify == null) {
                    userNotify = new UserNotify();
                    userNotify.setNotifyID(notifyId);
                    userNotify.setUserID(userID);
                    userNotify.setTimestamp(fluxRecord.getTime().toEpochMilli());

                    mapResult.put(notifyId, userNotify);
                    results.add(userNotify);
                }
                if (Objects.equals(fluxRecord.getField(), "data")) {
                    userNotify.setData(Util.OBJECT_MAPPER.readValue(fluxRecord.getValue().toString(), ObjectNode.class));
                }
            }
        }

        return results.stream().sorted((o1, o2) -> Long.compare(o2.getTimestamp(), o1.getTimestamp())).limit(20).collect(Collectors.toList());
    }

    @Override
    public List<UserNotify> fetchAsc(String userID, Long fromTime) throws Exception {
        QueryApi queryApi = influxDBClient.getQueryApi();

        if (fromTime == null) {
            fromTime = System.currentTimeMillis();
        }
        Flux flux = Flux.from(setting.INFLUXDB_BUCKET)
                .range(Instant.now().minusSeconds(setting.TTL_IN_SECONDS), Instant.ofEpochMilli(fromTime))
                .filter(Restrictions.and(
                        Restrictions.measurement().equal(setting.INFLUXDB_MEASUREMENT),
                        Restrictions.tag("user_id").equal(userID)
                ));

        List<UserNotify> results = new ArrayList<>();
        Map<String, UserNotify> mapResult = new HashMap<>();

        // Query data
        List<FluxTable> tables = queryApi.query(flux.toString());
        for (FluxTable fluxTable : tables) {
            List<FluxRecord> records = fluxTable.getRecords();
            for (FluxRecord fluxRecord : records) {
                String notifyId = fluxRecord.getValueByKey("notify_id").toString();

                UserNotify userNotify = mapResult.get(notifyId);
                if (userNotify == null) {
                    userNotify = new UserNotify();
                    userNotify.setNotifyID(notifyId);
                    userNotify.setUserID(userID);
                    userNotify.setTimestamp(fluxRecord.getTime().toEpochMilli());

                    mapResult.put(notifyId, userNotify);
                    results.add(userNotify);
                }
                if (Objects.equals(fluxRecord.getField(), "data")) {
                    userNotify.setData(Util.OBJECT_MAPPER.readValue(fluxRecord.getValue().toString(), ObjectNode.class));
                }
            }
        }

        return results.stream().sorted(Comparator.comparingLong(UserNotify::getTimestamp)).limit(20).collect(Collectors.toList());
    }

}
