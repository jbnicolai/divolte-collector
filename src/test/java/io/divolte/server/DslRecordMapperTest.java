/*
 * Copyright 2014 GoDataDriven B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.divolte.server;

import static io.divolte.server.BaseEventHandler.*;
import static org.junit.Assert.*;
import static org.mockito.Matchers.*;
import static org.mockito.Mockito.*;
import io.divolte.server.ServerTestUtils.EventPayload;
import io.divolte.server.ServerTestUtils.TestServer;
import io.divolte.server.ip2geo.LookupService;
import io.divolte.server.ip2geo.LookupService.ClosedServiceException;
import io.divolte.server.recordmapping.DslRecordMapper;
import io.divolte.server.recordmapping.SchemaMappingException;
import io.undertow.server.HttpServerExchange;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.util.Utf8;
import org.junit.After;
import org.junit.Test;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.InjectableValues;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;
import com.google.common.io.Resources;
import com.maxmind.geoip2.model.CityResponse;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

public class DslRecordMapperTest {
    private static final String DIVOLTE_URL_STRING = "http://localhost:%d/csc-event";
    private static final String DIVOLTE_URL_QUERY_STRING = "?"
            + "p=0%3Ai0rjfnxc%3AJLOvH9Nda2c1uV8M~vmdhPGFEC3WxVNq&"
            + "s=0%3Ai0rjfnxc%3AFPpXFMdcEORvvaP_HbpDgABG3Iu5__4d&"
            + "v=0%3AOxVC1WJ4PZNEGIUuzdXPsy_bztnKMuoH&"
            + "e=0%3AOxVC1WJ4PZNEGIUuzdXPsy_bztnKMuoH0&"
            + "c=i0rjfnxd&"
            + "n=t&"
            + "f=t&"
            + "i=sg&"
            + "j=sg&"
            + "k=2&"
            + "w=sa&"
            + "h=sa&"
            + "t=pageView";

    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_10_1) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/38.0.2125.122 Safari/537.36";

    public TestServer server;
    private File mappingFile;
    private File avroFile;

    @Test
    public void shouldPopulateFlatFields() throws InterruptedException, IOException {
        setupServer("flat-mapping.groovy");

        EventPayload event = request("https://example.com/", "http://example.com/");
        final GenericRecord record = event.record;
        final HttpServerExchange exchange = event.exchange;

        assertEquals(true, record.get("sessionStart"));
        assertEquals(true, record.get("unreliable"));
        assertEquals(false, record.get("dupe"));
        assertEquals(exchange.getAttachment(REQUEST_START_TIME_KEY), record.get("ts"));
        assertEquals("https://example.com/", record.get("location"));
        assertEquals("http://example.com/", record.get("referer"));

        assertEquals(USER_AGENT, record.get("userAgentString"));
        assertEquals("Chrome", record.get("userAgentName"));
        assertEquals("Chrome", record.get("userAgentFamily"));
        assertEquals("Google Inc.", record.get("userAgentVendor"));
        assertEquals("Browser", record.get("userAgentType"));
        assertEquals("38.0.2125.122", record.get("userAgentVersion"));
        assertEquals("Personal computer", record.get("userAgentDeviceCategory"));
        assertEquals("OS X", record.get("userAgentOsFamily"));
        assertEquals("10.10.1", record.get("userAgentOsVersion"));
        assertEquals("Apple Computer, Inc.", record.get("userAgentOsVendor"));

        assertEquals(exchange.getAttachment(PARTY_COOKIE_KEY).value, record.get("client"));
        assertEquals(exchange.getAttachment(SESSION_COOKIE_KEY).value, record.get("session"));
        assertEquals(exchange.getAttachment(PAGE_VIEW_ID_KEY), record.get("pageview"));
        assertEquals(exchange.getAttachment(EVENT_ID_KEY), record.get("event"));
        assertEquals(1018, record.get("viewportWidth"));
        assertEquals(1018, record.get("viewportHeight"));
        assertEquals(1024, record.get("screenWidth"));
        assertEquals(1024, record.get("screenHeight"));
        assertEquals(2, record.get("pixelRatio"));
        assertEquals("pageView", record.get("eventType"));

        Stream.of(
                "sessionStart",
                "unreliable",
                "dupe",
                "ts",
                "location",
                "referer",
                "userAgentString",
                "userAgentName",
                "userAgentFamily",
                "userAgentVendor",
                "userAgentType",
                "userAgentVersion",
                "userAgentDeviceCategory",
                "userAgentOsFamily",
                "userAgentOsVersion",
                "userAgentOsVendor",
                "client",
                "session",
                "pageview",
                "event",
                "viewportWidth",
                "viewportHeight",
                "screenWidth",
                "screenHeight",
                "pixelRatio",
                "eventType")
              .forEach((v) -> assertNotNull(record.get(v)));
    }

    @Test(expected=SchemaMappingException.class)
    public void shouldFailOnStartupIfMappingMissingField() throws IOException {
        setupServer("missing-field-mapping.groovy");
    }

    @Test
    public void shouldSetCustomCookieValue() throws InterruptedException, IOException {
        setupServer("custom-cookie-mapping.groovy");
        EventPayload event = request("http://example.com");
        assertEquals("custom_cookie_value", event.record.get("customCookie"));
    }

    @Test
    public void shouldApplyActionsInClosureWhenEqualToConditionHolds() throws IOException, InterruptedException {
        setupServer("when-mapping.groovy");
        EventPayload event = request("http://www.example.com/", "http://www.example.com/somepage.html");

        assertEquals("locationmatch", event.record.get("eventType"));
        assertEquals("referermatch", event.record.get("client"));
        assertEquals(new Utf8("not set"), event.record.get("queryparam"));

        assertEquals("absent", event.record.get("event"));
        assertEquals("present", event.record.get("pageview"));
    }

    @Test
    public void shouldChainValueProducersWithIntermediateNull() throws IOException, InterruptedException {
        setupServer("chained-na-mapping.groovy");
        EventPayload event = request("http://www.exmaple.com/");
        assertEquals(new Utf8("not set"), event.record.get("queryparam"));
    }

    @Test
    public void shouldMatchRegexAndExtractGroups() throws IOException, InterruptedException {
        setupServer("regex-mapping.groovy");
        EventPayload event = request("http://www.example.com/path/with/42/about.html", "http://www.example.com/path/with/13/contact.html");
        assertEquals(true, event.record.get("pathBoolean"));
        assertEquals("42", event.record.get("client"));
        assertEquals("about", event.record.get("pageview"));
    }

    @Test
    public void shouldParseUriComponents() throws IOException, InterruptedException {
        setupServer("uri-mapping.groovy");
        EventPayload event = request(
                "https://www.example.com:8080/path/to/resource/page.html?q=multiple+words+%24%23%25%26&p=10&p=20",
                "http://example.com/path/to/resource/page.html?q=divolte&p=42#/client/side/path?x=value&y=42");

        assertEquals("https", event.record.get("uriScheme"));
        assertEquals("/path/to/resource/page.html", event.record.get("uriPath"));
        assertEquals("www.example.com", event.record.get("uriHost"));
        assertEquals(8080, event.record.get("uriPort"));

        assertEquals("/client/side/path?x=value&y=42", event.record.get("uriFragment"));

        assertEquals("q=multiple+words+$#%&&p=10&p=20", event.record.get("uriQueryString"));
        assertEquals("multiple words $#%&", event.record.get("uriQueryStringValue"));
        assertEquals(Arrays.asList("10", "20"), event.record.get("uriQueryStringValues"));
        assertEquals(
                ImmutableMap.of("p", Arrays.asList("10","20"), "q", Arrays.asList("multiple words $#%&")),
                event.record.get("uriQuery"));
    }

    @Test
    public void shouldParseUriComponentsRaw() throws IOException, InterruptedException {
        setupServer("uri-mapping-raw.groovy");
        EventPayload event = request(
                "http://example.com/path/to/resource%20and%20such/page.html?q=multiple+words+%24%23%25%26&p=42#/client/side/path?x=value&y=42&q=multiple+words+%24%23%25%26");
        assertEquals("/path/to/resource%20and%20such/page.html", event.record.get("uriPath"));
        assertEquals("q=multiple+words+%24%23%25%26&p=42", event.record.get("uriQueryString"));
        assertEquals("/client/side/path?x=value&y=42&q=multiple+words+%24%23%25%26", event.record.get("uriFragment"));
    }

    @Test
    public void shouldParseMinimalUri() throws IOException, InterruptedException {
        /*
         * Test that URI parsing works on URIs that consist of only a path and possibly a query string.
         * This is typical for Angular style applications, where the fragment component of the location
         * is the internal location used within the angular app. In the mapping it should be possible
         * to parse the fragment of the location to a URI again and do path matching and such against
         * it.
         */
        setupServer("uri-mapping-fragment.groovy");
        EventPayload event = request(
                "http://example.com/path/?q=divolte#/client/side/path?x=value&y=42&q=multiple+words+%24%23%25%26");
        assertEquals("/client/side/path", event.record.get("uriPath"));
        assertEquals("x=value&y=42&q=multiple+words+%24%23%25%26", event.record.get("uriQueryString"));
        assertEquals("multiple words $#%&", event.record.get("uriQueryStringValue"));
    }

    @Test
    public void shouldNotFailOnBrokenQueryString() throws IOException, InterruptedException {
        setupServer("funky-querystring-mapping.groovy");
        EventPayload event = request("http://example.com/path/?a=value&=42&b=&d=word&c&=bla");

        /*
         * Query string parsing semantics:
         * ?q=          =>   q == ""
         * ?q=foo       =>   q == "foo"
         * ?q&a=bar     =>   q == "" && a == "bar"
         * ?=42&q=foo   =>   q == "foo"
         */
        assertEquals("value", event.record.get("uriQueryStringValue"));
        assertEquals("", event.record.get("queryparam"));
        assertEquals("", event.record.get("client"));
        assertEquals("word", event.record.get("pageview"));
    }

    @Test
    public void shouldSetCustomHeaders() throws IOException, InterruptedException {
        setupServer("header-mapping.groovy");
        EventPayload event = request("http://www.example.com/");
        assertEquals(Arrays.asList("first", "second", "last"), event.record.get("headerList"));
        assertEquals("first", event.record.get("header"));
        assertEquals("first,second,last", event.record.get("headers"));
    }

    @Test
    public void shouldMapAllGeoIpFields() throws IOException, InterruptedException, ClosedServiceException {
        /*
         * Have to work around not being able to create a HttpServerExchange a bit.
         * We setup a actual server just to do a request and capture the HttpServerExchange
         * instance. Then we setup a DslRecordMapper instance with a mock ip2geo lookup service,
         * we then use the previously captured exchange object against our locally created mapper
         * instance to test the ip2geo mapping (using the a mock lookup service).
         */
        setupServer("minimal-mapping.groovy");
        EventPayload event = request("http://www.example.com");

        final File geoMappingFile = File.createTempFile("geo-mapping", ".groovy");
        Files.write(Resources.toByteArray(Resources.getResource("geo-mapping.groovy")), geoMappingFile);

        final ImmutableMap<String, Object> mappingConfig = ImmutableMap.of(
                "divolte.tracking.schema_mapping.mapping_script_file", geoMappingFile.getAbsolutePath(),
                "divolte.tracking.schema_file", avroFile.getAbsolutePath()
                );

        final Config geoConfig = ConfigFactory.parseMap(mappingConfig)
            .withFallback(ConfigFactory.parseResources("dsl-mapping-test.conf"))
            .withFallback(ConfigFactory.parseResources("reference-test.conf"));

        final CityResponse mockResponseWithEverything = loadFromClassPath("/city-response-with-everything.json", new TypeReference<CityResponse>(){});
        final Map<String,Object> expectedMapping = loadFromClassPath("/city-response-expected-mapping.json", new TypeReference<Map<String,Object>>(){});

        final LookupService mockLookupService = mock(LookupService.class);
        when(mockLookupService.lookup(any())).thenReturn(Optional.of(mockResponseWithEverything));

        final DslRecordMapper mapper = new DslRecordMapper(
                geoConfig,
                new Schema.Parser().parse(Resources.toString(Resources.getResource("TestRecord.avsc"), StandardCharsets.UTF_8)),
                Optional.of(mockLookupService));

        final GenericRecord record = mapper.newRecordFromExchange(event.exchange);

        // Validate the results.
        verify(mockLookupService).lookup(any());
        verifyNoMoreInteractions(mockLookupService);
        expectedMapping.forEach((k, v) -> {
            final Object recordValue = record.get(k);
            assertEquals("Property " + k + " not mapped correctly.", v, recordValue);
        });

        java.nio.file.Files.delete(geoMappingFile.toPath());
    }

    @Test(expected=SchemaMappingException.class)
    public void shouldFailOnIncompatibleTypesWithLiteral() throws IOException, InterruptedException {
        setupServer("wrong-types-literal.groovy");
        request("http://www.example.com/wrong");
    }

    @Test(expected=SchemaMappingException.class)
    public void shouldFailOnIncompatibleTypesWithValueProducer() throws IOException, InterruptedException {
        setupServer("wrong-types-producer.groovy");
        request("http://www.example.com/wrong");
    }

    @Test
    public void shouldMapLiteralsOntoCorrectTypes() throws IOException, InterruptedException {
        setupServer("correct-types-literal.groovy");
        EventPayload event = request("http://www.example.com/correct");
        assertEquals("string value", event.record.get("queryparam"));
        assertEquals(true, event.record.get("queryparamBoolean"));
        assertEquals(42L, event.record.get("queryparamLong"));
        assertEquals(42, event.record.get("pathInteger"));
        assertEquals(42.0, event.record.get("queryparamDouble"));
    }

    @Test
    public void shouldStopWhenToldTo() throws IOException, InterruptedException {
        setupServer("basic-stop.groovy");
        EventPayload event = request("http://www.example.com");
        assertEquals("happened", event.record.get("client"));
        assertNull(event.record.get("session"));
    }

    @Test
    public void shouldStopOnNestedStop() throws IOException, InterruptedException {
        setupServer("nested-conditional-stop.groovy");
        EventPayload event = request("http://www.example.com");
        assertEquals("happened", event.record.get("client"));
        assertNull(event.record.get("session"));
    }

    @Test
    public void shouldStopOnCondition() throws IOException, InterruptedException {
        setupServer("shorthand-conditional-stop.groovy");
        EventPayload event = request("http://www.example.com");
        assertEquals("happened", event.record.get("client"));
        assertNull(event.record.get("session"));
    }

    @Test
    public void shouldStopOnConditionClosureSyntax() throws IOException, InterruptedException {
        setupServer("shorthand-conditional-stop-closure.groovy");
        EventPayload event = request("http://www.example.com");
        assertEquals("happened", event.record.get("client"));
        assertNull(event.record.get("session"));
    }

    @Test
    public void shouldStopOnTopLevelExit() throws IOException, InterruptedException {
        setupServer("basic-toplevel-exit.groovy");
        EventPayload event = request("http://www.example.com");
        assertEquals("happened", event.record.get("client"));
        assertNull(event.record.get("session"));
    }

    @Test
    public void shouldExitFromSectionOnCondition() throws IOException, InterruptedException {
        setupServer("nested-conditional-exit.groovy");
        EventPayload event = request("http://www.example.com");
        assertEquals("happened", event.record.get("client"));
        assertEquals("happened", event.record.get("pageview"));
        assertEquals("happened", event.record.get("event"));
        assertEquals("happened", event.record.get("customCookie"));
        assertNull(event.record.get("session"));
    }

    @Test
    public void sholdExitFromSectionOnConditionClosureSyntax() throws IOException, InterruptedException {
        setupServer("nested-conditional-exit-closure.groovy");
        EventPayload event = request("http://www.example.com");
        assertEquals("happened", event.record.get("client"));
        assertEquals("happened", event.record.get("pageview"));
        assertEquals("happened", event.record.get("event"));
        assertEquals("happened", event.record.get("customCookie"));
        assertNull(event.record.get("session"));
    }

    @Test
    public void shouldApplyBooleanLogic() throws IOException, InterruptedException {
        setupServer("boolean-logic.groovy");
        EventPayload event = request("http://www.example.com/");
        assertTrue((Boolean) event.record.get("unreliable"));
        assertFalse((Boolean) event.record.get("dupe"));
        assertTrue((Boolean) event.record.get("queryparamBoolean"));
        assertTrue((Boolean) event.record.get("pathBoolean"));
    }

    private static final ObjectMapper MAPPER =
            new ObjectMapper()
                    .configure(JsonParser.Feature.ALLOW_COMMENTS, true)
                    .setInjectableValues(new InjectableValues.Std().addValue("locales", ImmutableList.of("en")));

    private <T> T loadFromClassPath(final String resource, final TypeReference<?> typeReference) throws IOException {
        try (final InputStream resourceStream = this.getClass().getResourceAsStream(resource)) {
            return MAPPER.readValue(resourceStream, typeReference);
        }
    }

    private EventPayload request(String location) throws IOException, InterruptedException {
        return request(location, null);
    }

    private EventPayload request(String location, String referer) throws IOException, InterruptedException {
        final URL url = referer == null ?
                new URL(
                        String.format(DIVOLTE_URL_STRING, server.port) +
                        DIVOLTE_URL_QUERY_STRING +
                        "&l=" + URLEncoder.encode(location, StandardCharsets.UTF_8.name()))
                :
                new URL(
                        String.format(DIVOLTE_URL_STRING, server.port) +
                        DIVOLTE_URL_QUERY_STRING +
                        "&l=" + URLEncoder.encode(location, StandardCharsets.UTF_8.name()) +
                        "&r=" + URLEncoder.encode(referer, StandardCharsets.UTF_8.name()));

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.addRequestProperty("User-Agent", USER_AGENT);
        conn.addRequestProperty("Cookie", "custom_cookie=custom_cookie_value;");
        conn.addRequestProperty("X-Divolte-Test", "first");
        conn.addRequestProperty("X-Divolte-Test", "second");
        conn.addRequestProperty("X-Divolte-Test", "last");
        conn.setRequestMethod("GET");

        assertEquals(200, conn.getResponseCode());

        return server.waitForEvent();
    }

    private void setupServer(final String mapping) throws IOException {
        mappingFile = File.createTempFile("test-mapping", ".groovy");
        Files.write(Resources.toByteArray(Resources.getResource(mapping)), mappingFile);

        avroFile = File.createTempFile("TestSchema-", ".avsc");
        Files.write(Resources.toByteArray(Resources.getResource("TestRecord.avsc")), avroFile);

        ImmutableMap<String, Object> mappingConfig = ImmutableMap.of(
                "divolte.tracking.schema_mapping.mapping_script_file", mappingFile.getAbsolutePath(),
                "divolte.tracking.schema_file", avroFile.getAbsolutePath()
                );

        server = new TestServer("dsl-mapping-test.conf", mappingConfig);
        server.server.run();
    }

    @After
    public void shutdown() throws IOException {
        if (server != null) server.server.shutdown();
        if (mappingFile != null) java.nio.file.Files.delete(mappingFile.toPath());
        if (avroFile != null) java.nio.file.Files.delete(avroFile.toPath());
    }
}
