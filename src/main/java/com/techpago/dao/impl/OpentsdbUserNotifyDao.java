package com.techpago.dao.impl;

import com.stumbleupon.async.Callback;
import com.stumbleupon.async.Deferred;
import com.sun.xml.internal.ws.api.config.management.policy.ManagementAssertion;
import com.techpago.config.Settings;
import com.techpago.dao.IUserNotifyDao;
import com.techpago.model.UserNotify;
import com.techpago.utility.Util;
import com.techpago.validator.IValidator;
import jdk.nashorn.internal.ir.ObjectNode;
import net.opentsdb.core.TSDB;
import net.opentsdb.tools.OpenTSDBMain;

import net.opentsdb.uid.NoSuchUniqueName;
import net.opentsdb.utils.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.opentsdb.uid.UniqueId.UniqueIdType;

@Component
public class OpentsdbUserNotifyDao implements IUserNotifyDao {
    @Autowired
    private IValidator<UserNotify> validator;

    private final TSDB tsdb;

    public OpentsdbUserNotifyDao(){
        TSDB tsdbWrite = createTsdb();
        TSDB tsdbRead = createTsdb();
    }
    @PostConstruct
    void init() throws IOException{

    }
    @Override
    public void insert(UserNotify userNotify) throws Exception {
        // Write a number of data points at 30 second intervals. Each write will
        // return a deferred (similar to a Java Future or JS Promise) that will
        // be called on completion with either a "null" value on success or an
        // exception.
        String metricName="";
        Long value = null;
        Map<String,String> tags = new HashMap<>();
        tags.put( "user_id", userNotify.getUserID());
        tags.put("notify_id", userNotify.getNotifyID());

        Deferred<Object> deferred = tsdb.addPoint(metricName, userNotify.getTimestamp(),value, tags);
        deferred.addErrback(new OpentsdbUserNotifyDao().new errBack());
//deferred.addCallbacks(new OpentsdbUserNotifyDao().new succBack());
        tsdb.shutdown().join();

    }

//    private void createDB(TSDB tsdb) throws IOException{
//        // Declare new metric
//        String metricName = "my.tsdb.test.metric";
//        // First check to see it doesn't already exist
//        byte[] byteMetricUID; // we don't actually need this for the first
//        // .addPoint() call below.
//        // TODO: Ideally we could just call a not-yet-implemented tsdb.uIdExists()
//        // function.
//        // Note, however, that this is optional. If auto metric is enabled
//        // (tsd.core.auto_create_metrics), the UID will be assigned in call to
//        // addPoint().
//        try {
//            byteMetricUID = tsdb.getUID(UniqueIdType.METRIC, metricName);
//        } catch (IllegalArgumentException iae) {
//            System.out.println("Metric name not valid.");
//            iae.printStackTrace();
//            System.exit(1);
//        } catch (NoSuchUniqueName nsune) {
//            // If not, great. Create it.
//            byteMetricUID = tsdb.assignUid("metric", metricName);
//        }
//
//        // Make a single datum
//        long timestamp = System.currentTimeMillis() / 1000;
//        long value = 314159;
//        // Make key-val
//        Map<String, String> tags = new HashMap<String, String>(1);
//        tags.put("script", "example1");
//    }
    @Override
    public CompletableFuture<Object> insertAsync(UserNotify userNotify) throws Exception {
//        CompletableFuture<Object> future = new CompletableFuture<>();
//
//
//// and when the value is ready, call
//
//        String metricName="";
//        Long value = null;
//        Map<String,String> tags = new HashMap<>();
//        tags.put( "user_id", userNotify.getUserID());
//        tags.put("notify_id", userNotify.getNotifyID());
//
//        Deferred<Object> deferred = tsdb.addPoint(metricName, userNotify.getTimestamp(),value, tags);
//        deferred.addErrback(new OpentsdbUserNotifyDao().new errBack());
////        deferred.addCallbacks(new OpentsdbUserNotifyDao().new succBack());
//        tsdb.shutdown().join();

    }



    @Override
    public List<UserNotify> fetchDesc(String userID, Long fromTime) throws Exception {
        return null;
    }

    @Override
    public List<UserNotify> fetchAsc(String userID, Long fromTime) throws Exception {
        return null;
    }

    @Override
    public void flushDB() throws Exception {

    }

    private TSDB createTsdb(){
        Settings setting = Settings.getInstance();
        Config config = new Config();

        config.overrideConfig("tsd.network.port", "4242"); //The TCP port to use for accepting connections
        config.overrideConfig("tsd.http.staticroot", "/usr/share/opentsdb/static"); //Location of a directory where static files
        config.overrideConfig("tsd.http.cachedir", "/tmp/opentsdb"); //The full path to a location where temporary files can be written
        config.overrideConfig("tsd.core.auto_create_metrics", "true"); //Create new metrics or throw exception if it not exist.
        config.overrideConfig("tsd.core.meta.enable_tsuid_incrementing", "true");
        config.overrideConfig("tsd.storage.hbase.data_table", "tsdb");//Name of the HBase table where data points are stored
        config.overrideConfig("tsd.storage.hbase.uid_table", "tsdb-uid");//Name of the HBase table where UID information is stored
        config.overrideConfig("tsd.storage.hbase.zk_quorum",  String.join(",", setting.HBASE_IP)); //List of Zookeeper hosts that manage the HBase cluster
        config.overrideConfig("tsd.storage.fix_duplicates", "true");

        TSDB tsdb = new TSDB(config);
        return  tsdb;
    }

    // This is an optional errorback to handle when there is a failure.
    private class errBack implements Callback<String, Exception> {
        public String call(final Exception e) throws Exception {
            String message = ">>>>>>>>>>>Failure!>>>>>>>>>>>";
            System.err.println(message + " " + e.getMessage());
            e.printStackTrace();
            return message;
        }
    };

    class succBack implements Callback<Object, ArrayList<Object>> {
        public Object call(final ArrayList<Object> results) {
            System.out.println("Successfully wrote " + results.size() + " data points");
            return null;
        }
    };
}
