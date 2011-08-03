package brooklyn.event.adapter

import groovy.json.JsonSlurper

import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import brooklyn.entity.basic.EntityLocal

import com.google.common.base.Preconditions
import com.google.common.io.ByteStreams
import com.google.common.io.CharStreams

/**
 * This class adapts HTTP {@link URL}s to {@link Sensor} data for a particular {@link Entity}, updating the
 * {@link Activity} as required.
 *
 *  The adapter normally polls the HTTP server every second to update sensors, which could involve aggregation of data
 *  or simply reading values and setting them in the attribute map of the activity model.
 */
public class HttpSensorAdapter {
    static final Logger log = LoggerFactory.getLogger(HttpSensorAdapter.class);

    final EntityLocal entity

    public HttpSensorAdapter(EntityLocal entity, long timeout = -1) {
        this.entity = entity
    }

    public ValueProvider<Boolean> newDataValueProvider(String url, String regexp) {
        return new HttpDataValueProvider(new URL(url), regexp, this)
    }

    public ValueProvider<Integer> newStatusValueProvider(String url) {
        return new HttpStatusValueProvider(new URL(url), this)
    }

    public ValueProvider<String> newHeaderValueProvider(String url, String headerName) {
        return new HttpHeaderValueProvider(new URL(url), headerName, this)
    }
    
    public ValueProvider<Integer> newJsonIntegerProvider(String url, String key) {
        return new HttpJsonIntegerValueProvider(new URL(url), key, this)
    }

    /**
     * Returns true if the HTTP data from the URL matches the regexp.
     */
    private Boolean checkHttpData(URL url, String regexp) {
        HttpURLConnection connection = url.openConnection()
        List<String> lines = CharStreams.readLines(connection.inputStream)
        return lines.any { it =~ regexp }
    }

    /**
     * Returns the HTTP status code when retrieving the URL.
     */
    private Integer getHttpStatus(URL url) {
        HttpURLConnection connection = url.openConnection()
        connection.connect()
        return connection.getResponseCode()
    }

    /**
     * Returns data matching the regexp from the given HTTP URL.
     */
    private String getHttpHeader(URL url, String headerName) {
        HttpURLConnection connection = url.openConnection()
        connection.connect()
        return connection.getHeaderField(headerName)
    }

    /**
     * Returns a byte array of the content returned from a connection to url.
     */
    public byte[] getContents(URL url) {
        HttpURLConnection connection = url.openConnection()
        connection.connect()
        InputStream is = connection.getInputStream()
        byte[] bytes = ByteStreams.toByteArray(is)
        is.close()
        return bytes
    }

    /**
     * Returns the value mapped to by the given key in JSON from the given URL.
     */
    public String getJson(URL url, String key) {
        String jsonOut = new String(getContents(url))
        def slurper = new JsonSlurper()
        def parsed = slurper.parseText(jsonOut)
        return parsed[key]
    }
    
}

/**
 * Provides integer values to a sensor via JSON+HTTP.
 */
public class HttpJsonIntegerValueProvider implements ValueProvider<Integer> {
   private final URL url
   private final String jsonKey
   private final HttpSensorAdapter adapter

   public HttpJsonIntegerValueProvider(URL url, String jsonKey, HttpSensorAdapter adapter) {
       this.url = Preconditions.checkNotNull(url, "url")
       this.jsonKey = Preconditions.checkNotNull(jsonKey, "jsonKey")
       this.adapter = Preconditions.checkNotNull(adapter, "adapter")
   }

   @Override
   public Integer compute() {
       String out = adapter.getJson(url, jsonKey)
       assert out != null
       return Integer.valueOf(out)
   }
}

/**
 * Provides values to a sensor via HTTP.
 */
public class HttpDataValueProvider implements ValueProvider<Boolean> {
    private final URL url
    private final String regexp
    private final HttpSensorAdapter adapter

    public HttpDataValueProvider(URL url, String regexp, HttpSensorAdapter adapter) {
        this.url = Preconditions.checkNotNull(url, "url")
        this.regexp = Preconditions.checkNotNull(regexp, "regexp")
        this.adapter = Preconditions.checkNotNull(adapter, "adapter")
    }

    public Boolean compute() {
        return adapter.checkHttpData(url, regexp)
    }
}

/**
 * Provides HTTP status values to a sensor.
 */
public class HttpStatusValueProvider implements ValueProvider<Integer> {
    private final URL url
    private final HttpSensorAdapter adapter

    public HttpStatusValueProvider(URL url, HttpSensorAdapter adapter) {
        this.url = Preconditions.checkNotNull(url, "url")
        this.adapter = Preconditions.checkNotNull(adapter, "adapter")
    }

    public Integer compute() {
        return adapter.getHttpStatus(url)
    }
}

/**
 * Provides HTTP header values to a sensor.
 */
public class HttpHeaderValueProvider implements ValueProvider<String> {
    private final URL url
    private final String headerName
    private final HttpSensorAdapter adapter

    public HttpHeaderValueProvider(URL url, String headerName, HttpSensorAdapter adapter) {
        this.url = Preconditions.checkNotNull(url, "url")
        this.headerName = Preconditions.checkNotNull(headerName, "header name")
        this.adapter = Preconditions.checkNotNull(adapter, "adapter")
    }

    public String compute() {
        return adapter.getHttpHeader(url, headerName)
    }
}
