package io.dogy.dao.impl;

import com.beust.jcommander.internal.Lists;
import com.stumbleupon.async.Callback;
import com.stumbleupon.async.Deferred;
import io.dogy.config.Settings;
import io.dogy.dao.IUserNotifyDao;
import io.dogy.model.UserNotify;
import io.dogy.utility.Util;
import io.dogy.validator.IValidator;
import net.opentsdb.core.*;
import net.opentsdb.query.filter.TagVFilter;
import net.opentsdb.utils.Config;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class OpentsdbUserNotifyDao implements IUserNotifyDao {

    private static final Logger logger = LoggerFactory.getLogger(OpentsdbUserNotifyDao.class);
    private final TSDB tsdb;
    private final Settings setting = Settings.getInstance();

    @Autowired
    private IValidator<UserNotify> validator;

    public OpentsdbUserNotifyDao() throws Exception {
        Config config = new Config(true);
        config.overrideConfig("tsd.storage.hbase.zk_quorum", setting.TSDB_HBASE_HOST + ":" + setting.TSDB_HBASE_PORT);
        config.overrideConfig("tsd.storage.hbase.zk_basedir", setting.TSDB_LOCATION);
        config.overrideConfig("tsd.network.port", setting.TSDB_TCP_PORT); //The TCP port to use for accepting connections
        config.overrideConfig("tsd.http.staticroot", "/usr/share/opentsdb/static"); //Location of a directory where static files
        config.overrideConfig("tsd.http.cachedir", "/tmp/opentsdb"); //The full path to a location where temporary files can be written
        config.overrideConfig("tsd.core.auto_create_metrics", "true"); //Create new metrics or throw exception if it not exist.
        config.overrideConfig("tsd.core.meta.enable_tsuid_incrementing", "true");
        config.overrideConfig("tsd.storage.fix_duplicates", "true");
        config.overrideConfig("tsd.query.skip_unresolved_tagvs", "true");

        config.overrideConfig("tsd.storage.hbase.data_table", setting.TSDB_HBASE_DATA_TABLE);//Name of the HBase table where data points are stored
        config.overrideConfig("tsd.storage.hbase.uid_table", setting.TSDB_HBASE_UID_TABLE);//Name of the HBase table where UID information is stored
        config.overrideConfig("tsd.storage.hbase.tree_table", setting.TSDB_HBASE_TREE_TABLE);//Name of the HBase table where tree data are stored
        config.overrideConfig("tsd.storage.hbase.meta_table", setting.TSDB_HBASE_META_TABLE);//Name of the HBase table where UID information is stored

        this.tsdb = new TSDB(config);
    }

    @Override
    public void insert(UserNotify userNotify) throws Exception {
        CompletableFuture<Object> future = insertAsync(userNotify);
        future.get();
    }

    @Override
    public CompletableFuture<Object> insertAsync(UserNotify userNotify) throws Exception {
        validator.validate(userNotify);
        CompletableFuture<Object> future = new CompletableFuture<>();
        // Write a number of data points at 30 second intervals. Each write will
        // return a deferred (similar to a Java Future or JS Promise) that will
        // be called on completion with either a "null" value on success or an
        // exception.

        String metricName = Settings.getInstance().TSDB_METRIC;

        long value = userNotify.getTimestamp();
        Map<String, String> tags = new HashMap<>();
        tags.put("user_id", userNotify.getUserID());
        tags.put("notify_id", userNotify.getNotifyID());

        Deferred<Object> deferred = tsdb.addPoint(metricName, userNotify.getTimestamp(), value, tags);
        deferred.addBoth(new BothCallBack(future));
        return future;
    }

    @Override
    public List<UserNotify> fetchDesc(String userID, Long fromTime) throws Exception {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
        //main query
        final TSQuery query = new TSQuery();
        if (fromTime == null) {
            fromTime = System.currentTimeMillis();
        }
        query.setStart(dateFormat.format(new Date(0L)));
        query.setEnd(dateFormat.format(new Date(fromTime)));
        // at least one sub query required. This is where you specify the metric and
        // tags
        final TSSubQuery subQuery = new TSSubQuery();
        subQuery.setMetric(Settings.getInstance().TSDB_METRIC);//required

        // add filters
        final List<TagVFilter> filters = new ArrayList<>(1);//optional
        TagVFilter.Builder builder = new TagVFilter.Builder();
        builder.setType("literal_or") // In SQL, literal_ is similar to the IN or = predicates.
                .setFilter(userID)
                .setTagk("user_id")
                .setGroupBy(true);
        filters.add(builder.build());
        subQuery.setFilters(filters);

        //Aggregation functions are means of merging two or more data points for a single time stamp into a single value
        subQuery.setAggregator("count");//required. 'count' --> The number of raw data points in the set
        // IMPORTANT: don't forget to add the subQuery
        final ArrayList<TSSubQuery> subQueries = new ArrayList<>(1);
        subQueries.add(subQuery);
        query.setQueries(subQueries);
        query.setMsResolution(true); // otherwise we aggregate on the second.
        // make sure the query is valid.
        query.validateAndSetQuery();
        // compile the queries into TsdbQuery objects behind the scenes
        Query[] tsdbqueries = query.buildQueries(tsdb);
        Deferred<DataPoints[]> deferred = tsdbqueries[0].runAsync();
        CompletableFuture<DataPoints[]> future = new CompletableFuture<>();
        deferred.addBoth(new QueryCallBack(future));

        List<UserNotify> results = new ArrayList<>();
//        Map<String, UserNotify> mapResult = new HashMap<>();

        DataPoints[] dataResults = future.get();
        for (DataPoints data : dataResults) {
            for (DataPoint dp : data) {
                UserNotify userNotify = new UserNotify();
                Map<String, String> tags = data.getTags();
                for (final Map.Entry<String, String> pair : tags.entrySet()) {
                    switch (pair.getKey()) {
                        case "user_id":
                            userNotify.setUserID(pair.getValue());
                            break;
                        case "notify_id":
                            userNotify.setNotifyID(pair.getValue());
                            break;
                    }
                }
                userNotify.setTimestamp(dp.timestamp());
                userNotify.setData(null);
                results.add(userNotify);
            }
        }
        return results;
    }

    @Override
    public List<UserNotify> fetchAsc(String userID, Long fromTime) throws Exception {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
        //main query
        final TSQuery query = new TSQuery();

        if (fromTime == null) {
            fromTime = 0L;
        }
        query.setStart(dateFormat.format(new Date(fromTime)));
        query.setEnd(dateFormat.format(new Date()));

        final List<TagVFilter> filters = new ArrayList<>(1);//optional
        TagVFilter.Builder builder = new TagVFilter.Builder();
        builder.setType("literal_or") // In SQL, literal_ is similar to the IN or = predicates.
                .setFilter(userID)
                .setTagk("user_id")
                .setGroupBy(true);
        filters.add(builder.build());

        // at least one sub query required. This is where you specify the metric and
        // tags
        final TSSubQuery subQuery = new TSSubQuery();

        subQuery.setMetric(Settings.getInstance().TSDB_METRIC);//required

        // add filters
        subQuery.setFilters(filters);
        //Aggregation functions are means of merging two or more data points for a single time stamp into a single value
        subQuery.setAggregator("count");//required. 'count' --> The number of raw data points in the set

        query.setQueries(Lists.newArrayList(subQuery));
        query.setMsResolution(true); // otherwise we aggregate on the second.
        // make sure the query is valid.
        query.validateAndSetQuery();
        // compile the queries into TsdbQuery objects behind the scenes
        Query[] tsdbqueries = query.buildQueries(tsdb);
        Deferred<DataPoints[]> deferred = tsdbqueries[0].runAsync();

        CompletableFuture<DataPoints[]> future = new CompletableFuture<>();
        deferred.addBoth(new QueryCallBack(future));

        List<UserNotify> results = new ArrayList<>();
//        Map<String, UserNotify> mapResult = new HashMap<>();

        DataPoints[] dataResults = future.get();
        for (DataPoints data : dataResults) {
            for (DataPoint dp : data) {
                UserNotify userNotify = new UserNotify();
                Map<String, String> tags = data.getTags();
                for (final Map.Entry<String, String> pair : tags.entrySet()) {
                    switch (pair.getKey()) {
                        case "user_id":
                            userNotify.setUserID(pair.getValue());
                            break;
                        case "notify_id":
                            userNotify.setNotifyID(pair.getValue());
                            break;
                    }
                }
                userNotify.setTimestamp(dp.timestamp());
                userNotify.setData(null);
                results.add(userNotify);
            }
        }
        return results;
    }

    @Override
    public void flushDB() throws Exception {
        Configuration config = HBaseConfiguration.create();
        config.set("hbase.zookeeper.quorum", this.setting.TSDB_HBASE_HOST);
        config.setInt("hbase.zookeeper.property.clientPort", Integer.parseInt(setting.TSDB_HBASE_PORT));
        config.set("zookeeper.znode.parent", this.setting.TSDB_LOCATION);
        config.set("hbase.rpc.timeout", "10000");
        HBaseAdmin.checkHBaseAvailable(config);

        // Add custom config parameters here
        Connection connection = ConnectionFactory.createConnection(config);

        ArrayList<String> tableNames = new ArrayList<>();
        tableNames.add(this.setting.TSDB_HBASE_DATA_TABLE);
        tableNames.add(this.setting.TSDB_HBASE_UID_TABLE);
        tableNames.add(this.setting.TSDB_HBASE_META_TABLE);
        tableNames.add(this.setting.TSDB_HBASE_TREE_TABLE);

        try {
            Admin admin = connection.getAdmin();

            for (String tableName : tableNames) {
                logger.info("Truncate table " + tableName);
                try {
                    if (admin.tableExists(TableName.valueOf(tableName))) {
                        if (!admin.isTableDisabled(TableName.valueOf(tableName))) {
                            admin.disableTable(TableName.valueOf(tableName));
                        }
                        admin.truncateTable(TableName.valueOf(tableName), true);
                    }
                } catch (IOException e) {
                    logger.error("Failed to truncate table " + tableName + "\nError Msg: ", e);
                }
            }
        } catch (Exception e) {
            logger.error("Could not connect to HBase Admin. Error Msg: ", e);
        }
        connection.close();
        tsdb.dropCaches();
    }

    private static class BothCallBack implements Callback<Object, Object> {
        private final CompletableFuture<Object> future;

        public BothCallBack(CompletableFuture<Object> future) {
            this.future = future;
        }

        @Override
        public Object call(Object arg) {
            if (arg instanceof Exception) {
                Exception e = (Exception) arg;
                future.completeExceptionally(e);
                return e.getMessage();
            }
            future.complete(arg);
            return arg;
        }
    }

    private static class QueryCallBack implements Callback<Object, DataPoints[]> {
        private final CompletableFuture<DataPoints[]> future;

        public QueryCallBack(CompletableFuture<DataPoints[]> future) {
            this.future = future;
        }

        @Override
        public Object call(DataPoints[] queryResult) {
            for (DataPoints result : queryResult) {
                if (result instanceof Exception) {
                    Exception e = (Exception) result;
                    future.completeExceptionally(e);
                    return e.getMessage();
                }
            }
            future.complete(queryResult);
            return queryResult;
        }

//        public static void processArgs(final String[] args) {
//            // Set these as arguments so you don't have to keep path information in
//            // source files
//            if (args != null && args.length > 0) {
//                pathToConfigFile = args[0];
//            }
//        }
    }

    public static void main(String[] args) {
        try {
            OpentsdbUserNotifyDao testDao = new OpentsdbUserNotifyDao();

            for (int i = 0; i < 10; i++) {
                UserNotify userNotify = new UserNotify();
                userNotify.setNotifyID(String.valueOf(i));
                userNotify.setUserID(String.valueOf(i % 5));
                userNotify.setTimestamp(System.currentTimeMillis());
                System.out.println(Util.OBJECT_MAPPER.writeValueAsString(userNotify));
                testDao.insert(userNotify);
            }
            for (UserNotify result : testDao.fetchAsc("6", null)) {
                System.out.println();
                System.out.println(result);
            }
            testDao.flushDB();

            System.out.println(testDao.tsdb.dataTable().length);
            int i = 0;
            for (UserNotify result : testDao.fetchAsc("6", null)) {
                System.out.println(i++);
                System.out.println(result);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
