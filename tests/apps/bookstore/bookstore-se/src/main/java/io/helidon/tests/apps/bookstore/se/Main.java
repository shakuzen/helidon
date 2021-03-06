/*
 * Copyright (c) 2019 Oracle and/or its affiliates. All rights reserved.
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

package io.helidon.tests.apps.bookstore.se;

import java.io.IOException;
import java.util.logging.LogManager;

import io.helidon.common.configurable.Resource;
import io.helidon.common.pki.KeyConfig;
import io.helidon.config.Config;
import io.helidon.health.HealthSupport;
import io.helidon.health.checks.HealthChecks;
import io.helidon.media.jackson.server.JacksonSupport;
import io.helidon.media.jsonb.server.JsonBindingSupport;
import io.helidon.media.jsonp.server.JsonSupport;
import io.helidon.metrics.MetricsSupport;
import io.helidon.webserver.ExperimentalConfiguration;
import io.helidon.webserver.Http2Configuration;
import io.helidon.webserver.Routing;
import io.helidon.webserver.SSLContextBuilder;
import io.helidon.webserver.ServerConfiguration;
import io.helidon.webserver.WebServer;

/**
 * Simple Hello World rest application.
 */
public final class Main {

    private static final String SERVICE_PATH = "/books";

    enum JsonLibrary {
        JSONP,
        JSONB,
        JACKSON
    }

    /**
     * Cannot be instantiated.
     */
    private Main() {
    }

    /**
     * Application main entry point.
     *
     * @param args command line arguments.
     * @throws IOException if there are problems reading logging properties
     */
    public static void main(final String[] args) throws IOException {
        startServer();
    }

    /**
     * Start the server.
     *
     * @return the created {@link WebServer} instance
     * @throws IOException if there are problems reading logging properties
     */
    static WebServer startServer() throws IOException {
        return startServer(false, false);
    }

    /**
     * Start the server.
     *
     * @param ssl Enable ssl support.
     * @param http2 Enable http2 support.
     * @return the created {@link WebServer} instance
     * @throws IOException if there are problems reading logging properties
     */
    static WebServer startServer(boolean ssl, boolean http2) throws IOException {
        // load logging configuration
        LogManager.getLogManager().readConfiguration(
                Main.class.getResourceAsStream("/logging.properties"));

        // By default this will pick up application.yaml from the classpath
        Config config = Config.create();

        // Build server config based on params
        ServerConfiguration.Builder configBuilder = ServerConfiguration.builder(config.get("server"));
        if (ssl) {
            configBuilder.ssl(
                    SSLContextBuilder.create(
                            KeyConfig.keystoreBuilder()
                                    .keystore(Resource.create("certificate.p12"))
                                    .keystorePassphrase("helidon".toCharArray())
                                    .build())
                            .build());
        }
        if (http2) {
            configBuilder.experimental(
                    ExperimentalConfiguration.builder()
                            .http2(Http2Configuration.builder().enable(true).build()).build());
        }

        WebServer server = WebServer.create(configBuilder.build(), createRouting(config));

        // Start the server and print some info.
        server.start().thenAccept(ws -> {
            String url = (ssl ? "https" : "http") + "://localhost:" + ws.port() + SERVICE_PATH;
            System.out.println("WEB server is up! " + url + " [ssl=" + ssl + ", http2=" + http2 + "]");
        });

        // Server threads are not daemon. NO need to block. Just react.
        server.whenShutdown().thenRun(()
                -> System.out.println("WEB server is DOWN. Good bye!"));

        return server;
    }

    /**
     * Creates new {@link Routing}.
     *
     * @param config configuration of this server
     * @return routing configured with JSON support, a health check, and a service
     */
    private static Routing createRouting(Config config) {
        HealthSupport health = HealthSupport.builder()
                .add(HealthChecks.healthChecks())   // Adds a convenient set of checks
                .build();

        JsonLibrary jsonLibrary = getJsonLibrary(config);

        Routing.Builder builder = Routing.builder();
        switch (jsonLibrary) {
            case JSONP:
                builder.register(JsonSupport.create());
                break;
            case JSONB:
                builder.register(JsonBindingSupport.create());
                break;
            case JACKSON:
                builder.register(JacksonSupport.create());
                break;
            default:
                throw new RuntimeException("Unknown JSON library " + jsonLibrary);
        }

        return builder.register(health)                   // Health at "/health"
                .register(MetricsSupport.create())  // Metrics at "/metrics"
                .register(SERVICE_PATH, new BookService(config))
                .build();
    }

    static JsonLibrary getJsonLibrary(Config config) {
        Config jl = config.get("app").get("json-library");
        return !jl.exists() ? JsonLibrary.JSONP
                : JsonLibrary.valueOf(jl.asString().get().toUpperCase());
    }
}
